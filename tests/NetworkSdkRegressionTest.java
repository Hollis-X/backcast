import com.mkei.backcast.agent.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.json.JSONObject;

/** Real official SDK requests must use the selected network once and preserve private failure evidence. */
public final class NetworkSdkRegressionTest {
    private static int passed;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Route implements NetworkRouting.Route {
        final AtomicInteger configure = new AtomicInteger(), close = new AtomicInteger(), probe = new AtomicInteger();
        boolean closed;
        @Override public void configure(OkHttpClient.Builder builder) {
            configure.incrementAndGet();
            builder.dns(host -> Collections.singletonList(InetAddress.getByName("127.0.0.1")));
        }
        @Override public JSONObject diagnostic() {
            try { return new JSONObject().put("selected", "cellular").put("baidu_reachable", probe.get() > 0); }
            catch (Exception impossible) { throw new AssertionError(impossible); }
        }
        @Override public String failureMessage(LlmClient.RequestValidity valid) {
            probe.incrementAndGet(); return "网络可访问，但 AI 服务器连接失败。";
        }
        @Override public synchronized void close() { if (!closed) { closed = true; close.incrementAndGet(); } }
    }
    private static final class Server implements AutoCloseable {
        final HttpServer http;
        final AtomicInteger posts = new AtomicInteger(), gets = new AtomicInteger();
        Server(int status, String body) throws Exception {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/v1", exchange -> {
                if (exchange.getRequestMethod().equals("POST")) posts.incrementAndGet(); else gets.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = body.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Connection", "close");
                if (status == 503) exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); http.start();
        }
        String url() { return "http://selected-network.invalid:" + http.getAddress().getPort() + "/v1"; }
        LlmClient client() { LlmClient.Config config = new LlmClient.Config(url(), "fixture-key", "fixture"); config.timeoutMs = 1000; config.totalTimeoutMs = 3000; return new LlmClient(config); }
        public void close() { http.stop(0); }
    }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }
    private static void selectedRouteSendsOneRealPost() throws Exception {
        Route route = new Route(); AtomicInteger opens = new AtomicInteger();
        NetworkRouting.install((endpoint, valid) -> { opens.incrementAndGet(); return route; });
        try (Server server = new Server(200, "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}")) {
            LlmClient.Reply reply = server.client().send(Arrays.asList(Message.user("inspect")), null, null);
            check(reply.error == null && reply.content.equals("done") && server.posts.get() == 1 && server.gets.get() == 0
                    && opens.get() == 1 && route.configure.get() == 1 && route.close.get() == 1 && route.probe.get() == 0,
                    "Selected network was not used, request duplicated, or network lease leaked");
        } finally { NetworkRouting.install(null); }
        pass("selectedRouteSendsOneRealPost");
    }
    private static void preflightFailureNeverPostsAndPreservesToastEvidence() throws Exception {
        NetworkRouting.install((endpoint, valid) -> { throw new NetworkRouting.Failure("网络可访问，但 AI 服务器连接失败。",
                new JSONObject().put("baidu_reachable", true).put("server_reachable", false)); });
        try (Server server = new Server(200, "{}")) {
            LlmClient.Reply reply = server.client().send(Arrays.asList(Message.user("private prompt")), null, null);
            check(reply.error != null && reply.userMessage.contains("服务器连接失败") && server.posts.get() == 0
                    && reply.diagnostic.getString("stage").equals("network")
                    && reply.diagnostic.getJSONObject("network").getBoolean("baidu_reachable")
                    && !reply.diagnostic.toString().contains("private prompt"), "Early network failure has no private evidence/Toast or still posted");
        } finally { NetworkRouting.install(null); }
        pass("preflightFailureNeverPostsAndPreservesToastEvidence");
    }
    private static void actualConnectionFailureProbesWithoutResending() throws Exception {
        Route route = new Route(); NetworkRouting.install((endpoint, valid) -> route);
        String url;
        try (java.net.ServerSocket unused = new java.net.ServerSocket(0)) { url = "http://selected-network.invalid:" + unused.getLocalPort() + "/v1"; }
        try {
            LlmClient.Config config = new LlmClient.Config(url, "fixture", "fixture"); config.timeoutMs = 1000; config.totalTimeoutMs = 2500;
            LlmClient.Reply reply = new LlmClient(config).send(Arrays.asList(Message.user("inspect")), null, null);
            check(reply.error != null && reply.userMessage.contains("服务器连接失败") && route.configure.get() == 1
                    && route.probe.get() == 1 && route.close.get() == 1
                    && reply.diagnostic.getJSONObject("network").getBoolean("baidu_reachable"), "Connection failure silently resubmitted or discarded the probe");
        } finally { NetworkRouting.install(null); }
        pass("actualConnectionFailureProbesWithoutResending");
    }
    private static void serverFailureStopsLoopWithoutRetryOrProbe() throws Exception {
        Route route = new Route(); NetworkRouting.install((endpoint, valid) -> route);
        AtomicInteger errors = new AtomicInteger(), retries = new AtomicInteger();
        try (Server server = new Server(503, "{\"error\":{\"message\":\"unavailable\"}}")) {
            AgentLoop loop = new AgentLoop(server.client(), new ToolRegistry(), new AgentLoop.Quiet() {
                @Override public void onError(int gen, String text) { check(text.contains("无法处理"), "Raw HTTP error reached notification"); errors.incrementAndGet(); }
                @Override public void onRetry(int gen) { retries.incrementAndGet(); }
            }); loop.bindSession(7); loop.reset("fixture"); loop.setGoal("inspect"); loop.submit("inspect", 7, loop.generation(), 1);
            check(server.posts.get() == 1 && route.probe.get() == 0 && errors.get() == 1 && retries.get() == 0
                    && !loop.busy() && Goal.ACTIVE.equals(loop.goalStatus()), "503 retried/probed needlessly or falsely completed the goal");
        } finally { NetworkRouting.install(null); }
        pass("serverFailureStopsLoopWithoutRetryOrProbe");
    }
    private static void modelListUsesSelectionAndShortFailure() throws Exception {
        Route route = new Route(); NetworkRouting.install((endpoint, valid) -> route);
        try (Server server = new Server(401, "{\"error\":{\"message\":\"raw provider body\"}}")) {
            LlmClient.ModelsResult result = LlmClient.fetchModels(server.url(), "fixture", "openai");
            check(result.error != null && result.userMessage.contains("密钥") && !result.userMessage.contains("raw provider")
                    && server.gets.get() == 1 && server.posts.get() == 0 && route.probe.get() == 0 && route.close.get() == 1
                    && result.diagnostic.getString("provider").equals("openai"), "Models GET ignored selected network or leaked error notification");
        } finally { NetworkRouting.install(null); }
        pass("modelListUsesSelectionAndShortFailure");
    }
    private static void invalidLocalHeaderDoesNotProbeInternetOrPost() throws Exception {
        Route route = new Route(); NetworkRouting.install((endpoint, valid) -> route);
        try (Server server = new Server(200, "{}")) {
            LlmClient.Config config = new LlmClient.Config(server.url(), "invalid\nheader", "fixture");
            LlmClient.Reply reply = new LlmClient(config).send(Arrays.asList(Message.user("inspect")), null, null);
            check(reply.error != null && server.posts.get() == 0 && route.probe.get() == 0
                    && route.close.get() == 1 && !reply.userMessage.contains("服务器连接失败"),
                    "Local header validation was incorrectly diagnosed as a network failure");
        } finally { NetworkRouting.install(null); }
        pass("invalidLocalHeaderDoesNotProbeInternetOrPost");
    }
    private static void cancellationDuringSelectionDoesNotPost() throws Exception {
        CountDownLatch selecting = new CountDownLatch(1); AtomicInteger opens = new AtomicInteger();
        NetworkRouting.install((endpoint, valid) -> {
            opens.incrementAndGet(); selecting.countDown();
            while (valid.isCurrent()) try { Thread.sleep(10); } catch (InterruptedException e) { break; }
            throw new java.io.IOException("cancelled selection");
        });
        try (Server server = new Server(200, "{}")) {
            LlmClient client = server.client(); LlmClient.Reply[] response = new LlmClient.Reply[1];
            Thread worker = new Thread(() -> response[0] = client.send(Arrays.asList(Message.user("inspect")), null, null));
            worker.start(); check(selecting.await(2, TimeUnit.SECONDS), "No selection start"); client.abort(); worker.join(1000);
            check(!worker.isAlive() && server.posts.get() == 0 && opens.get() == 1 && response[0].error == null
                    && response[0].userMessage == null && response[0].diagnostic == null, "Cancelled selection posted or notified an error");
        } finally { NetworkRouting.install(null); }
        pass("cancellationDuringSelectionDoesNotPost");
    }
    public static void main(String[] args) throws Exception {
        selectedRouteSendsOneRealPost(); preflightFailureNeverPostsAndPreservesToastEvidence(); actualConnectionFailureProbesWithoutResending();
        serverFailureStopsLoopWithoutRetryOrProbe(); modelListUsesSelectionAndShortFailure();
        invalidLocalHeaderDoesNotProbeInternetOrPost(); cancellationDuringSelectionDoesNotPost();
        System.out.println(passed + " network SDK integration tests passed");
    }
}
