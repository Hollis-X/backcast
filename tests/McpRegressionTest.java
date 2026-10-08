import android.os.SystemClock;
import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.ApprovalGate;
import com.mkei.backcast.agent.Compactor;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.Tool;
import com.mkei.backcast.agent.ToolRegistry;
import com.mkei.backcast.agent.ToolOutcome;
import com.mkei.backcast.mcp.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real HTTP/SSE integration; no mocked transport and no production test-only entry points. */
public final class McpRegressionTest {
    private static int passed;
    private static final String TOKEN = "fixture-secret-quote\"backslash\\key";
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }
    private interface Handler { void handle(HttpExchange exchange, JSONObject request) throws Exception; }
    private static final class Fixture implements AutoCloseable {
        final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService executor = Executors.newCachedThreadPool();
        final AtomicInteger initializes = new AtomicInteger(), lists = new AtomicInteger(), calls = new AtomicInteger();
        final AtomicInteger cancelled = new AtomicInteger(), deleted = new AtomicInteger(), posts = new AtomicInteger();
        final List<String> methods = Collections.synchronizedList(new ArrayList<String>());
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Handler custom, initializedNotification;
        String version = "2025-11-25";
        boolean errorResult;
        Fixture() throws Exception {
            http.setExecutor(executor);
            http.createContext("/mcp", exchange -> {
                try {
                    if (exchange.getRequestMethod().equals("DELETE")) { deleted.incrementAndGet(); reply(exchange, 204, null, ""); return; }
                    posts.incrementAndGet();
                    check(exchange.getRequestHeaders().getFirst("Authorization").equals("Bearer " + TOKEN), "Bearer missing/mutated");
                    check(exchange.getRequestHeaders().getFirst("Accept").contains("application/json")
                            && exchange.getRequestHeaders().getFirst("Accept").contains("text/event-stream"), "Accept does not negotiate both formats");
                    JSONObject request = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), "UTF-8"));
                    String method = request.optString("method"); methods.add(method);
                    if (method.equals("initialize")) {
                        initializes.incrementAndGet();
                        check(request.getJSONObject("params").getJSONObject("capabilities").length() == 0, "Unimplemented capabilities advertised");
                        exchange.getResponseHeaders().add("MCP-Session-Id", "session-fixture-secret");
                        send(exchange, request, new JSONObject().put("protocolVersion", version)
                                .put("capabilities", new JSONObject().put("tools", new JSONObject())));
                        return;
                    }
                    check("session-fixture-secret".equals(exchange.getRequestHeaders().getFirst("MCP-Session-Id")), "Session missing after initialization");
                    check(version.equals(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version")), "Negotiated protocol header missing");
                    if (method.equals("notifications/cancelled")) { cancelled.incrementAndGet(); reply(exchange, 202, null, ""); return; }
                    if (method.equals("notifications/initialized")) {
                        check(!request.has("id"), "Initialized notification must not have a request ID");
                        if (initializedNotification != null) initializedNotification.handle(exchange, request);
                        else reply(exchange, 202, null, "");
                        return;
                    }
                    if (method.equals("tools/list")) lists.incrementAndGet();
                    if (method.equals("tools/call")) calls.incrementAndGet();
                    if (custom != null) { custom.handle(exchange, request); return; }
                    if (method.equals("tools/list")) send(exchange, request, new JSONObject().put("tools", new JSONArray().put(tool("echo"))));
                    else send(exchange, request, new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "result")))
                            .put("isError", errorResult));
                } catch (Throwable failure) {
                    error.compareAndSet(null, failure);
                    try { reply(exchange, 500, null, "fixture failed"); } catch (Exception ignored) { }
                }
            });
            http.start();
        }
        McpServer server(String id) { return new McpServer(id, "Fixture", "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp", TOKEN, true, 5); }
        void healthy() { if (error.get() != null) throw new AssertionError("Fixture handler failed", error.get()); }
        public void close() { http.stop(0); executor.shutdownNow(); }
    }
    private static JSONObject tool(String name) throws Exception {
        return new JSONObject().put("name", name).put("description", "Echo text")
                .put("inputSchema", new JSONObject("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}"));
    }
    private static void reply(HttpExchange exchange, int code, String type, String text) throws Exception {
        if (type != null) exchange.getResponseHeaders().add("Content-Type", type);
        exchange.getResponseHeaders().add("Connection", "close");
        byte[] bytes = text.getBytes("UTF-8");
        exchange.sendResponseHeaders(code, code == 204 || code == 202 && bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0 && code != 204) exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    private static void send(HttpExchange exchange, JSONObject request, JSONObject result) throws Exception {
        reply(exchange, 200, "application/json", new JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result).toString());
    }
    private static void rejects(Throwing operation, String text) throws Exception {
        try { operation.run(); throw new AssertionError("Expected failure: " + text); }
        catch (AssertionError failure) { throw failure; }
        catch (Exception expected) {
            check(expected.getMessage().contains(text), "Wrong failure: " + expected);
            check(!expected.toString().contains(TOKEN) && expected.getCause() == null, "Credential/error body leaked");
        }
    }
    private interface Throwing { void run() throws Exception; }
    private static void remove(Path path) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.walk(path)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(file -> { try { Files.delete(file); } catch (Exception failure) { throw new RuntimeException(failure); } });
        }
    }

    private static void initializationPaginationAndSchema() throws Exception {
        try (Fixture f = new Fixture()) {
            f.custom = (exchange, request) -> {
                boolean second = request.getJSONObject("params").has("cursor");
                if (second) check(request.getJSONObject("params").getString("cursor").equals("opaque-page"), "Cursor not forwarded");
                JSONObject result = new JSONObject().put("tools", new JSONArray().put(tool(second ? "other" : "echo")));
                if (!second) result.put("nextCursor", "opaque-page"); send(exchange, request, result);
            };
            McpClient client = new McpClient(f.server("one"));
            try {
                List<McpToolInfo> tools = client.discover();
                check(tools.size() == 2 && tools.get(0).name.equals("echo") && tools.get(1).name.equals("other"), "Pagination lost tools");
                tools.get(0).inputSchema().put("type", "string");
                check(tools.get(0).inputSchema().getString("type").equals("object"), "Schema snapshot mutable");
                check(f.initializes.get() == 1 && f.lists.get() == 2 && f.methods.get(1).equals("notifications/initialized"), "Lifecycle order/request count wrong");
                f.healthy();
            } finally { client.close(); }
        } pass("initializationPaginationAndSchema");
    }
    private static void emptyNotificationAcknowledgmentsCompleteInitialization() throws Exception {
        for (int status : new int[] {200, 202, 204}) {
            try (Fixture f = new Fixture()) {
                // For 200 this sends a chunked empty body, without a Content-Type header.
                f.initializedNotification = (exchange, request) -> reply(exchange, status, null, "");
                McpClient client = new McpClient(f.server("one"));
                try {
                    check(client.discover().size() == 1, "Empty acknowledgment lost tool discovery: " + status);
                    client.call("echo", new JSONObject());
                    check(f.methods.equals(Arrays.asList("initialize", "notifications/initialized", "tools/list", "tools/call")),
                            "Empty acknowledgment reordered/repeated lifecycle: " + status);
                    f.healthy();
                } finally { client.close(); }
            }
        }
        try (Fixture f = new Fixture()) {
            f.initializedNotification = (exchange, request) -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, -1); exchange.close();
            };
            McpClient client = new McpClient(f.server("one"));
            try {
                client.call("echo", new JSONObject());
                check(f.initializes.get() == 1 && f.calls.get() == 1, "Fixed empty JSON acknowledgment rejected/retried");
                f.healthy();
            } finally { client.close(); }
        }
        pass("emptyNotificationAcknowledgmentsCompleteInitialization");
    }
    private static void invalidNotificationAcknowledgmentsStopInitialization() throws Exception {
        String error = new JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                .put("error", new JSONObject().put("code", -32001).put("message", TOKEN)).toString();
        for (int status : new int[] {200, 202}) {
            for (String body : new String[] {error, "broken " + TOKEN, "{}",
                    "{\"jsonrpc\":\"2.0\",\"id\":null,\"result\":{}}", "<html>" + TOKEN + "</html>", " \r\n"}) {
                try (Fixture f = new Fixture()) {
                    f.initializedNotification = (exchange, request) -> reply(exchange, status, "application/json", body);
                    McpClient client = new McpClient(f.server("one"));
                    try {
                        rejects(client::discover, "通知响应必须为空");
                        check(f.posts.get() == 2 && f.lists.get() == 0 && f.calls.get() == 0,
                                "Invalid acknowledgment was retried or allowed a request");
                        rejects(() -> client.call("echo", new JSONObject()), "通知响应必须为空");
                        check(f.initializes.get() == 2 && f.posts.get() == 4 && f.calls.get() == 0,
                                "Invalid acknowledgment incorrectly marked client initialized");
                        f.healthy();
                    } finally { client.close(); }
                }
            }
        }
        try (Fixture f = new Fixture()) {
            f.initializedNotification = (exchange, request) -> reply(exchange, 201, null, "");
            McpClient client = new McpClient(f.server("one"));
            try {
                rejects(() -> client.call("echo", new JSONObject()), "通知未被正确接受");
                check(f.posts.get() == 2 && f.calls.get() == 0, "Unsupported acknowledgment status was accepted/retried");
                f.healthy();
            } finally { client.close(); }
        }
        pass("invalidNotificationAcknowledgmentsStopInitialization");
    }
    private static void sseMultilineAndServerRequests() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger acknowledgments = new AtomicInteger();
            f.custom = (exchange, request) -> {
                if (!request.has("method")) { check(request.has("error"), "Unsupported server method was accepted"); acknowledgments.incrementAndGet(); reply(exchange, 202, null, ""); return; }
                String body = ": comment\r\nid: prime\r\ndata:\r\n\r\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progress\":1}}\r\n\r\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"id\":\"server-request\",\"method\":\"sampling/createMessage\"}\r\n\r\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"id\":" + request.getLong("id") + ",\r\n"
                        + "data: \"result\":{\"content\":[{\"type\":\"text\",\"text\":\"stream result\"}],\"structuredContent\":{\"count\":7}}}\r\n\r\n";
                reply(exchange, 200, "text/event-stream; charset=utf-8", body);
            };
            McpClient client = new McpClient(f.server("one"));
            try {
                JSONObject result = client.call("echo", new JSONObject().put("text", "中文\"\n"));
                check(result.getJSONArray("content").getJSONObject(0).getString("text").equals("stream result")
                        && result.getJSONObject("structuredContent").getInt("count") == 7 && acknowledgments.get() == 1, "SSE/result/server-request handling failed");
                f.healthy();
            } finally { client.close(); }
        } pass("sseMultilineAndServerRequests");
    }
    private static void mappedToolsAndNoUiNetwork() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-test-");
        try (Fixture f = new Fixture()) {
            McpStore store = new McpStore(dir.toFile()); store.save(f.server("one"));
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store);
            check(registry.toSchema().length() == 1 && registry.get("mcp_list_tools") != null && f.posts.get() == 0, "UI/schema read opened network");
            JSONObject catalog = new JSONObject(registry.get("mcp_list_tools").run(new JSONObject()));
            String mapped = catalog.getJSONArray("tools").getJSONObject(0).getString("mapped_name");
            check(mapped.matches("[A-Za-z0-9_-]{1,64}") && registry.toSchema().length() == 2, "Mapped schema absent/invalid model name");
            f.custom = (exchange, request) -> {
                check(request.getJSONObject("params").getString("name").equals("echo"), "Mapped name sent to remote instead of original");
                check(request.getJSONObject("params").getJSONObject("arguments").getString("text").equals("中文\"\n"), "Arguments changed");
                send(exchange, request, new JSONObject().put("content", new JSONArray()).put("structuredContent", new JSONObject().put("done", true)));
            };
            check(new JSONObject(registry.get(mapped).run(new JSONObject().put("text", "中文\"\n"))).getJSONObject("structuredContent").getBoolean("done"), "Mapped call lost structured result");
            registry.cleanupTemporary(true);
            f.healthy();
        } finally { remove(dir); }
        pass("mappedToolsAndNoUiNetwork");
    }
    private static void errorsStopWithoutRetry() throws Exception {
        try (Fixture f = new Fixture()) {
            f.initializedNotification = (exchange, request) -> reply(exchange, 200, null, "");
            f.custom = (exchange, request) -> reply(exchange, 503, "application/json", TOKEN);
            McpClient client = new McpClient(f.server("one"));
            try { rejects(() -> client.call("echo", new JSONObject()), "HTTP 503"); check(f.calls.get() == 1, "HTTP tool failure retried"); f.healthy(); }
            finally { client.close(); }
        } pass("errorsStopWithoutRetry");
    }
    private static void protocolErrorsDoNotLeakBody() throws Exception {
        try (Fixture f = new Fixture()) {
            f.custom = (exchange, request) -> reply(exchange, 200, "application/json", new JSONObject().put("jsonrpc", "2.0").put("id", request.get("id"))
                    .put("error", new JSONObject().put("code", -32001).put("message", TOKEN)).toString());
            McpClient client = new McpClient(f.server("one"));
            try { rejects(() -> client.call("echo", new JSONObject()), "-32001"); check(f.calls.get() == 1, "Protocol failure retried"); f.healthy(); }
            finally { client.close(); }
        } pass("protocolErrorsDoNotLeakBody");
    }
    private static void expiredSessionNeverReplaysCall() throws Exception {
        try (Fixture f = new Fixture()) {
            f.custom = (exchange, request) -> {
                if (f.calls.get() == 1) reply(exchange, 404, "application/json", "expired");
                else send(exchange, request, new JSONObject().put("content", new JSONArray()));
            };
            McpClient client = new McpClient(f.server("one"));
            try {
                rejects(() -> client.call("echo", new JSONObject()), "会话已失效");
                check(f.calls.get() == 1 && f.initializes.get() == 1, "Expired call replayed silently");
                client.call("echo", new JSONObject());
                check(f.calls.get() == 2 && f.initializes.get() == 2, "Explicit next call did not reinitialize"); f.healthy();
            } finally { client.close(); }
        } pass("expiredSessionNeverReplaysCall");
    }
    private static void resultCredentialsRedactedAndToolErrorsRecognized() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-test-");
        try (Fixture f = new Fixture()) {
            McpStore store = new McpStore(dir.toFile()); store.save(f.server("one"));
            McpClient probe = new McpClient(f.server("one"));
            try { store.cacheTools(f.server("one"), probe.discover()); } finally { probe.close(); }
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store);
            Tool remote = registry.all().get(1);
            f.custom = (exchange, request) -> send(exchange, request, new JSONObject().put("isError", true)
                    .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", TOKEN + " session-fixture-secret")))
                    .put("structuredContent", new JSONObject().put(TOKEN, TOKEN)));
            String result = remote.run(new JSONObject());
            check(result.startsWith("错误：") && ToolOutcome.failed(remote.name(), result), "isError hidden from loop/goal failure logic");
            JSONObject parsed = new JSONObject(result.substring(result.indexOf('\n') + 1));
            check(!result.contains(TOKEN) && !result.contains("session-fixture-secret") && parsed.getJSONObject("structuredContent").has("[redacted]"), "Credentials leaked/redaction damaged JSON");
            registry.abort(); f.healthy();
        } finally { remove(dir); }
        pass("resultCredentialsRedactedAndToolErrorsRecognized");
    }
    private static void staleCacheAndRevocation() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-test-");
        try (Fixture f = new Fixture()) {
            McpStore store = new McpStore(dir.toFile()); McpServer original = f.server("one"); store.save(original);
            List<McpToolInfo> tools = Arrays.asList(new McpToolInfo(tool("echo")));
            check(store.cacheTools(original, tools), "Current schema cache rejected");
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store); Tool remote = registry.all().get(1);
            store.save(new McpServer("one", "Fixture", original.endpoint, "different", false, 5));
            check(!store.cacheTools(original, tools) && store.cachedTools("one").isEmpty(), "Stale probe overwrote new credentials/cache");
            rejects(() -> remote.run(new JSONObject()), "禁用"); check(f.posts.get() == 0, "Disabled tool still connected");
            store.remove("one"); check(new McpStore(dir.toFile()).servers().isEmpty(), "Removal not persisted");
            check(!Files.exists(dir.resolve("connections.pending")), "Atomic-write staging file leaked");
        } finally { remove(dir); }
        pass("staleCacheAndRevocation");
    }
    private static void cancellationAndSessionIsolation() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            f.custom = (exchange, request) -> {
                if (request.getJSONObject("params").getString("name").equals("slow")) {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(": waiting\n\n".getBytes("UTF-8")); exchange.getResponseBody().flush();
                    started.countDown(); release.await(8, TimeUnit.SECONDS); exchange.close();
                } else send(exchange, request, new JSONObject().put("content", new JSONArray()));
            };
            McpClient parent = new McpClient(f.server("one")), child = new McpClient(f.server("one"));
            AtomicReference<Throwable> outcome = new AtomicReference<Throwable>();
            Thread worker = new Thread(() -> { try { parent.call("slow", new JSONObject()); outcome.set(new AssertionError("Cancelled request succeeded")); } catch (Exception stopped) { outcome.set(stopped); } });
            worker.start(); check(started.await(2, TimeUnit.SECONDS), "Slow call never started");
            long at = System.nanoTime(); parent.abort(); worker.join(1500);
            check(!worker.isAlive() && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at) < 1500
                    && outcome.get() instanceof Exception && outcome.get().getMessage().contains("取消"), "Cancellation blocked or did not stop stream");
            child.call("fast", new JSONObject());
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (f.cancelled.get() == 0 && System.nanoTime() < until) Thread.yield();
            check(f.cancelled.get() == 1 && f.calls.get() == 2 && f.initializes.get() == 2, "MCP cancellation notification/independent child session wrong");
            release.countDown(); parent.close(); child.close(); f.healthy();
        } pass("cancellationAndSessionIsolation");
    }
    private static void hardDeadlineStopsProgress() throws Exception {
        try (Fixture f = new Fixture()) {
            f.custom = (exchange, request) -> {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                try {
                    for (int i = 0; i < 80; i++) {
                        exchange.getResponseBody().write("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n".getBytes("UTF-8"));
                        exchange.getResponseBody().flush(); Thread.sleep(100);
                    }
                } catch (java.io.IOException disconnected) { } finally { exchange.close(); }
            };
            McpClient client = new McpClient(f.server("one")); long at = System.nanoTime();
            try {
                rejects(() -> client.call("slow", new JSONObject()), "超时");
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at);
                check(elapsed >= 4500 && elapsed < 6500 && f.calls.get() == 1, "Progress extended hard deadline/retried call");
            } finally { client.close(); }
        } pass("hardDeadlineStopsProgress");
    }
    private static void malformedAndWrongIdResponses() throws Exception {
        try (Fixture f = new Fixture()) {
            McpClient client = new McpClient(f.server("one"));
            f.custom = (exchange, request) -> reply(exchange, 200, "application/json", new JSONObject().put("jsonrpc", "2.0").put("id", 999).put("result", new JSONObject()).toString());
            try {
                rejects(() -> client.call("echo", new JSONObject()), "ID 不匹配");
                f.custom = (exchange, request) -> reply(exchange, 200, "application/json", "broken " + TOKEN);
                rejects(() -> client.call("echo", new JSONObject()), "格式无效"); check(f.calls.get() == 2, "Invalid responses retried"); f.healthy();
            } finally { client.close(); }
        } pass("malformedAndWrongIdResponses");
    }
    private static void repeatedCursorAndUnsupportedVersion() throws Exception {
        try (Fixture f = new Fixture()) {
            f.custom = (exchange, request) -> send(exchange, request, new JSONObject().put("tools", new JSONArray()).put("nextCursor", "same"));
            McpClient client = new McpClient(f.server("one"));
            try { rejects(client::discover, "游标重复"); check(f.lists.get() == 2, "Pagination loop did not stop"); }
            finally { client.close(); }
        }
        try (Fixture f = new Fixture()) {
            f.version = "2024-11-05"; McpClient client = new McpClient(f.server("one"));
            try { rejects(client::discover, "版本不支持"); check(f.lists.get() == 0, "Unsupported old SSE version misrepresented as supported"); }
            finally { client.close(); }
        } pass("repeatedCursorAndUnsupportedVersion");
    }
    private static void credentialSafeSchemaAndVisibilityLimit() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-test-");
        try (Fixture f = new Fixture()) {
            f.custom = (exchange, request) -> {
                JSONArray many = new JSONArray();
                for (int i = 0; i < 110; i++) many.put(tool("tool_" + i).put("description", TOKEN + " session-fixture-secret"));
                send(exchange, request, new JSONObject().put("tools", many));
            };
            McpStore store = new McpStore(dir.toFile()); store.save(f.server("one"));
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store);
            String response = registry.get("mcp_list_tools").run(new JSONObject());
            String schema = registry.toSchema().toString();
            check(registry.toSchema().length() == 97 && new JSONObject(response).getJSONArray("tools").length() == 96
                    && store.cachedTools("one").size() == 110, "Visible cap did not keep model tool total safe/full UI cache");
            check(!schema.contains(TOKEN) && !schema.contains("session-fixture-secret") && !response.contains(TOKEN), "Credential leaked into model schemas/catalog");
            registry.cleanupTemporary(true); f.healthy();
        } finally { remove(dir); }
        pass("credentialSafeSchemaAndVisibilityLimit");
    }
    private static void explicitServerRefreshMakesLaterToolsCallable() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-test-");
        try (Fixture f = new Fixture()) {
            McpStore store = new McpStore(dir.toFile());
            McpServer first = f.server("one"), second = f.server("two");
            store.save(first); store.save(second);
            List<McpToolInfo> crowded = new ArrayList<McpToolInfo>();
            for (int i = 0; i < 110; i++) crowded.add(new McpToolInfo(tool("crowded_" + i)));
            store.cacheTools(first, crowded); store.cacheTools(second, Arrays.asList(new McpToolInfo(tool("echo"))));
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store);
            check(registry.all().size() == 97, "Visibility count wrong before selecting server");
            JSONObject found = new JSONObject(registry.get("mcp_list_tools").run(new JSONObject().put("server_id", "two")));
            JSONArray visible = found.getJSONArray("tools"); String mapped = null;
            for (int i = 0; i < visible.length(); i++) {
                JSONObject item = visible.getJSONObject(i);
                check(registry.get(item.getString("mapped_name")) != null, "Catalog advertised unavailable tool");
                if (item.getString("server_id").equals("two")) mapped = item.getString("mapped_name");
            }
            check(mapped != null && found.getBoolean("has_more") && found.getString("priority_server_id").equals("two"), "Later server remains permanently hidden");
            registry.get(mapped).run(new JSONObject());
            check(f.calls.get() == 1, "Priority server mapped tool not callable");
            registry.cleanupTemporary(true); f.healthy();
        } finally { remove(dir); }
        pass("explicitServerRefreshMakesLaterToolsCallable");
    }
    private static void schemaAndCatalogBudgetsKeepActualVisibleNames() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-test-");
        try (Fixture f = new Fixture()) {
            f.custom = (exchange, request) -> {
                JSONArray many = new JSONArray();
                String longDescription = String.join("", Collections.nCopies(12000, "字"));
                for (int i = 0; i < 40; i++) many.put(tool("large_" + i).put("description", longDescription));
                send(exchange, request, new JSONObject().put("tools", many));
            };
            McpStore store = new McpStore(dir.toFile()); store.save(f.server("one"));
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store);
            String catalog = registry.get("mcp_list_tools").run(new JSONObject());
            JSONArray schemas = registry.toSchema(); JSONObject decoded = new JSONObject(catalog);
            check(schemas.toString().getBytes("UTF-8").length <= 128 * 1024 && schemas.length() > 1 && schemas.length() < 41,
                    "Aggregate schema budget not enforced");
            check(catalog.getBytes("UTF-8").length <= 48 * 1024 && decoded.getBoolean("has_more")
                    && decoded.getJSONArray("tools").length() == schemas.length() - 1, "Catalog over budget or misrepresents exposed count");
            for (int i = 0; i < decoded.getJSONArray("tools").length(); i++) {
                JSONObject entry = decoded.getJSONArray("tools").getJSONObject(i);
                check(registry.get(entry.getString("mapped_name")) != null && entry.getBoolean("schema_in_tool_definition"),
                        "Budgeted catalog names not callable/does not explain schema location");
            }
            registry.cleanupTemporary(true); f.healthy();
        } finally { remove(dir); }
        pass("schemaAndCatalogBudgetsKeepActualVisibleNames");
    }
    private static void remoteBinaryAndTextResultsStayBoundedAndExplicit() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-test-");
        try (Fixture f = new Fixture()) {
            McpStore store = new McpStore(dir.toFile()); store.save(f.server("one"));
            store.cacheTools(f.server("one"), Arrays.asList(new McpToolInfo(tool("echo"))));
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store);
            String binary = String.join("", Collections.nCopies(200000, "B"));
            String text = String.join("", Collections.nCopies(20000, "正文😀"));
            f.custom = (exchange, request) -> send(exchange, request, new JSONObject().put("content", new JSONArray()
                    .put(new JSONObject().put("type", "image").put("mimeType", "image/png").put("data", binary))
                    .put(new JSONObject().put("type", "audio").put("mimeType", "audio/wav").put("data", binary))
                    .put(new JSONObject().put("type", "resource").put("resource", new JSONObject().put("uri", "memory://binary").put("mimeType", "application/octet-stream").put("blob", binary)))
                    .put(new JSONObject().put("type", "text").put("text", text)))
                    .put("structuredContent", new JSONObject().put("enormous", text)));
            String result = registry.all().get(1).run(new JSONObject()); JSONObject decoded = new JSONObject(result);
            check(result.getBytes("UTF-8").length <= 48 * 1024 && !result.contains(binary) && !result.contains("\ufffd"),
                    "Binary/full text leaked into model or UTF8 truncation corrupted characters");
            check(decoded.getBoolean("binary_content_omitted") && decoded.getBoolean("truncated")
                    && decoded.getBoolean("structuredContent_omitted"), "Budget omissions silently presented as complete evidence");
            JSONArray content = decoded.getJSONArray("content");
            check(content.getJSONObject(0).getBoolean("data_omitted") && content.getJSONObject(1).getBoolean("data_omitted")
                    && content.getJSONObject(2).getJSONObject("resource").getBoolean("blob_omitted")
                    && content.getJSONObject(3).getBoolean("text_truncated")
                    && content.getJSONObject(0).getString("mimeType").equals("image/png"), "Binary metadata/text truncation flags absent");
            registry.cleanupTemporary(true); f.healthy();
        } finally { remove(dir); }
        pass("remoteBinaryAndTextResultsStayBoundedAndExplicit");
    }
    private static boolean includes(JSONArray schema, String name) throws Exception {
        for (int i = 0; i < schema.length(); i++)
            if (schema.getJSONObject(i).getJSONObject("function").getString("name").equals(name)) return true;
        return false;
    }

    private static void cachedChooserAndStructuredSelectionStayLocal() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-chooser-");
        try (Fixture f = new Fixture()) {
            McpStore store = new McpStore(dir.toFile()); McpServer first = f.server("one"), disabled = f.server("off");
            store.save(first); store.save(new McpServer(disabled.id, disabled.name, disabled.endpoint, TOKEN, false, 5));
            store.cacheTools(first, Arrays.asList(new McpToolInfo(tool("echo"))));
            List<McpCatalog.Server> catalog = McpCatalog.cached(store);
            check(catalog.size() == 1 && catalog.get(0).id.equals("one") && catalog.get(0).tools.size() == 1
                    && f.posts.get() == 0, "Cached chooser connected to a server or included disabled tools");
            McpSelection selected = catalog.get(0).tools.get(0);
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store);
            check(registry.get(selected.mappedName) != null && selected.inputSchema().getJSONArray("required").getString(0).equals("text"),
                    "Chooser did not reuse the callable mapping/full input schema");
            String encoded = selected.toJson().toString();
            check(!encoded.contains(TOKEN) && !encoded.contains(first.endpoint), "Selection persisted credentials or endpoint");
            McpSelection restored = McpSelection.fromJson(new JSONObject(encoded)); store.validateSelection(restored);
            JSONObject changed = new JSONObject(encoded); changed.getJSONObject("inputSchema").put("additionalProperties", false);
            rejects(() -> McpSelection.fromJson(changed), "身份");
            Message user = Message.user("use the chosen tool"); user.mcpSelection = selected;
            check(!user.toJson().has("mcp_selection") && Message.fromCheckpointJson(user.toCheckpointJson()).mcpSelection.mappedName.equals(selected.mappedName),
                    "Local selection leaked into API messages or was lost in checkpoint metadata");
            check(f.posts.get() == 0, "Reading or validating a selection opened network");
        } finally { remove(dir); }
        pass("cachedChooserAndStructuredSelectionStayLocal");
    }

    private static void selectedToolWinsCountAndSchemaBudgetsWithoutExpandingApproval() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-priority-");
        try (Fixture f = new Fixture()) {
            McpStore store = new McpStore(dir.toFile()); McpServer server = f.server("one"); store.save(server);
            List<McpToolInfo> cached = new ArrayList<McpToolInfo>();
            StringBuilder large = new StringBuilder(); for (int i = 0; i < 13000; i++) large.append('x');
            for (int i = 0; i < 100; i++) cached.add(new McpToolInfo(tool("crowded_" + i)));
            JSONObject selectedInfo = tool("selected_last"); selectedInfo.put("description", large.toString());
            cached.add(new McpToolInfo(selectedInfo)); store.cacheTools(server, cached);
            McpSelection selected = McpCatalog.cached(store).get(0).tools.get(100);
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store);
            check(!includes(registry.toSchema(), selected.mappedName), "Fixture selected tool was already exposed");
            registry.validateMcpSelection(selected);
            check(!includes(registry.toSchema(), selected.mappedName), "Preflight validation mutated an active registry");
            registry.selectMcpTool(selected);
            check(includes(registry.toSchema(), selected.mappedName) && registry.toSchema().length() == 97
                    && registry.toSchema().toString().getBytes("UTF-8").length <= 128 * 1024,
                    "Explicit selected tool was excluded by count or schema budget");
            registry.selectMcpTool(null);
            check(!includes(registry.toSchema(), selected.mappedName), "Selection priority leaked into another request");
            cached.clear();
            for (int i = 0; i < 15; i++) { JSONObject info = tool("wide_" + i); info.put("description", large.toString()); cached.add(new McpToolInfo(info)); }
            store.cacheTools(server, cached); selected = McpCatalog.cached(store).get(0).tools.get(14);
            ToolRegistry wide = new ToolRegistry(); McpTools.register(wide, store);
            check(!includes(wide.toSchema(), selected.mappedName), "Schema budget fixture did not exclude the last tool");
            wide.selectMcpTool(selected);
            check(includes(wide.toSchema(), selected.mappedName) && wide.toSchema().toString().getBytes("UTF-8").length <= 128 * 1024,
                    "Selected schema did not take priority over earlier schemas");
            check(f.calls.get() == 0 && f.posts.get() == 0, "Selecting tool executed remote work");
        } finally { remove(dir); }
        pass("selectedToolWinsCountAndSchemaBudgetsWithoutExpandingApproval");
    }

    private static void independentRefreshDoesNotAbortModelAndUsesCacheCas() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-refresh-");
        try (Fixture f = new Fixture()) {
            McpStore store = new McpStore(dir.toFile()); McpServer server = f.server("one"); store.save(server);
            store.cacheTools(server, Arrays.asList(new McpToolInfo(tool("echo"))));
            ToolRegistry model = new ToolRegistry(); McpTools.register(model, store);
            String mapped = McpCatalog.cached(store).get(0).tools.get(0).mappedName;
            model.get(mapped).run(new JSONObject().put("text", "first"));
            check(f.initializes.get() == 1, "Model fixture did not own an MCP session");
            McpCatalog.Refresh refresh = new McpCatalog.Refresh(store, "one"); refresh.run();
            long deleteDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (f.deleted.get() == 0 && System.nanoTime() < deleteDeadline) Thread.yield();
            check(f.initializes.get() == 2 && f.deleted.get() == 1, "Manual refresh reused or leaked the model MCP session");
            model.get(mapped).run(new JSONObject().put("text", "second"));
            check(f.initializes.get() == 2 && f.calls.get() == 2, "Refresh canceled the model's independent client");
            McpCatalog.Refresh stale = new McpCatalog.Refresh(store, "one");
            store.cacheTools(server, Arrays.asList(new McpToolInfo(tool("new_cached"))));
            rejects(() -> stale.run(), "缓存已变化");
            check(store.cachedTools("one").get(0).name.equals("new_cached"), "Old manual refresh overwrote newer cached schemas");
            int posts = f.posts.get(); McpCatalog.Refresh canceled = new McpCatalog.Refresh(store, "one"); canceled.close();
            rejects(() -> canceled.run(), "取消"); check(f.posts.get() == posts, "Canceled refresh still contacted server");
            model.cleanupTemporary(true); f.healthy();
        } finally { remove(dir); }
        pass("independentRefreshDoesNotAbortModelAndUsesCacheCas");
    }

    private static void changedSelectionRejectsBeforeSubmitOrToolExecution() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-selection-");
        try (Fixture f = new Fixture()) {
            McpStore store = new McpStore(dir.toFile()); McpServer server = f.server("one"); store.save(server);
            store.cacheTools(server, Arrays.asList(new McpToolInfo(tool("echo"))));
            McpSelection selection = McpCatalog.cached(store).get(0).tools.get(0);
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store); registry.selectMcpTool(selection);
            JSONObject changed = tool("echo"); changed.getJSONObject("inputSchema").put("additionalProperties", false);
            store.cacheTools(server, Arrays.asList(new McpToolInfo(changed)));
            rejects(() -> registry.validateMcpSelection(selection), "已变化");
            rejects(() -> registry.get(selection.mappedName).run(new JSONObject().put("text", "stale")), "已变化");
            final AtomicInteger modelCalls = new AtomicInteger(), userRecords = new AtomicInteger(), errors = new AtomicInteger();
            AgentLoop loop = new AgentLoop(new LlmClient(new LlmClient.Config("http://fixture", "fixture", "fixture")) {
                @Override public Reply send(List<Message> messages, JSONArray tools, Sink sink) { modelCalls.incrementAndGet(); return new Reply(); }
            }, registry, new AgentLoop.Quiet() { @Override public void onError(int gen, String detail) { errors.incrementAndGet(); } });
            loop.bindSession(1L); loop.reset("system");
            loop.setRecorder(new AgentLoop.Recorder() {
                @Override public void record(long sid, Message message) { if (Message.USER.equals(message.role)) userRecords.incrementAndGet(); }
                @Override public void replace(long sid, List<Message> messages) { }
            });
            rejects(() -> loop.submit("stale selected tool", 1L, loop.generation(), 1, selection), "已变化");
            check(modelCalls.get() == 0 && userRecords.get() == 0 && loop.historySnapshot().size() == 1
                    && errors.get() == 0 && !loop.busy(), "Stale selection mutated or submitted a user request");
            McpSelection fresh = McpCatalog.cached(store).get(0).tools.get(0);
            store.save(new McpServer(server.id, server.name, server.endpoint, "changed-key", true, 5));
            rejects(() -> store.validateSelection(fresh), "已变化");
            check(f.calls.get() == 0 && f.posts.get() == 0, "Rejected stale selection contacted the MCP server");
        } finally { remove(dir); }
        pass("changedSelectionRejectsBeforeSubmitOrToolExecution");
    }

    private static void selectedMetadataSurvivesCompactionAndClearsOnNewUserRequest() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-loop-selection-");
        try (Fixture f = new Fixture()) {
            McpStore store = new McpStore(dir.toFile()); McpServer server = f.server("one"); store.save(server);
            List<McpToolInfo> cached = new ArrayList<McpToolInfo>();
            for (int i = 0; i < 100; i++) cached.add(new McpToolInfo(tool("cached_" + i)));
            store.cacheTools(server, cached); final McpSelection selection = McpCatalog.cached(store).get(0).tools.get(99);
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store);
            final AtomicInteger requests = new AtomicInteger(), compactions = new AtomicInteger();
            final List<Message> checkpoint = new ArrayList<Message>();
            LlmClient client = new LlmClient(new LlmClient.Config("http://fixture", "fixture", "fixture")) {
                @Override public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
                    Reply reply = new Reply();
                    if (tools == null) { compactions.incrementAndGet(); reply.content = "bounded summary"; }
                    else {
                        requests.incrementAndGet();
                        try { check(includes(tools, selection.mappedName), "Selected tool disappeared after compaction/recovery"); }
                        catch (Exception invalid) { throw new IllegalStateException(invalid); }
                        reply.error = "SocketTimeoutException: fixture";
                    }
                    return reply;
                }
            };
            AgentLoop loop = new AgentLoop(client, registry, new AgentLoop.Quiet()); loop.bindSession(1L); loop.reset("system");
            loop.setContextBudget(1000, 0.9f);
            loop.setRecorder(new AgentLoop.Recorder() {
                @Override public void record(long sid, Message message) { }
                @Override public void replace(long sid, List<Message> messages) { checkpoint.clear(); checkpoint.addAll(messages); }
            });
            loop.submit("perform selected tool task", 1L, loop.generation(), 1, selection);
            check(requests.get() == 1 && compactions.get() == 1 && !checkpoint.isEmpty(), "Selected request did not exercise compaction");
            Message summary = checkpoint.get(checkpoint.size() - 1);
            check(Compactor.isSummary(summary) && summary.mcpSelection != null && !summary.toJson().has("mcp_selection"),
                    "Compaction failed to preserve selection as local metadata");
            final AtomicInteger restoredCalls = new AtomicInteger();
            LlmClient restoredClient = new LlmClient(new LlmClient.Config("http://fixture", "fixture", "fixture")) {
                @Override public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
                    restoredCalls.incrementAndGet();
                    try {
                        check(includes(tools, selection.mappedName) == (restoredCalls.get() == 1),
                                "Recovered selection vanished or leaked into a new explicit request");
                    } catch (Exception invalid) { throw new IllegalStateException(invalid); }
                    Reply reply = new Reply(); reply.content = "done"; return reply;
                }
            };
            ToolRegistry restoredRegistry = new ToolRegistry(); McpTools.register(restoredRegistry, store);
            AgentLoop restored = new AgentLoop(restoredClient, restoredRegistry, new AgentLoop.Quiet());
            restored.bindSession(1L); restored.reset("system"); restored.loadHistory("system", checkpoint);
            restored.resume(1L, 1); restored.submit("ordinary new request", 1L, restored.generation(), 2, null);
            check(restoredCalls.get() == 2 && f.posts.get() == 0, "Selected metadata recovery executed tools directly or lost a request");
        } finally { remove(dir); }
        pass("selectedMetadataSurvivesCompactionAndClearsOnNewUserRequest");
    }

    private static void rejectedSelectionCannotFinishTheOwningWorker() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-owner-");
        try (Fixture f = new Fixture()) {
            McpStore store = new McpStore(dir.toFile()); McpServer server = f.server("one"); store.save(server);
            store.cacheTools(server, Arrays.asList(new McpToolInfo(tool("echo"))));
            final McpSelection selection = McpCatalog.cached(store).get(0).tools.get(0);
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store);
            final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            final AtomicInteger errors = new AtomicInteger(), finishes = new AtomicInteger(), diagnostics = new AtomicInteger();
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            LlmClient client = new LlmClient(new LlmClient.Config("http://fixture", "fixture", "fixture")) {
                @Override public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
                    started.countDown();
                    try { check(release.await(5, TimeUnit.SECONDS), "Owning model request remained blocked"); }
                    catch (InterruptedException stopped) { throw new IllegalStateException(stopped); }
                    Reply reply = new Reply(); reply.content = "old owner finished"; sink.onContent(reply.content); return reply;
                }
            };
            final AgentLoop loop = new AgentLoop(client, registry, new AgentLoop.Quiet() {
                @Override public void onError(int gen, String detail) { errors.incrementAndGet(); }
                @Override public void onFinish(int gen) { finishes.incrementAndGet(); }
            });
            loop.bindSession(1L); loop.reset("system");
            loop.setDiagnosticRecorder(null, new AgentLoop.ErrorRecorder() {
                @Override public void recordDiagnostic(long sid, String source, String summary, String detail) {
                    check(source.equals("mcp_selection") && !detail.contains(TOKEN), "Selection rejection diagnosis leaked or used the wrong source");
                    diagnostics.incrementAndGet();
                }
            });
            Thread owner = new Thread(() -> { try { loop.submit("old owner", 1L, loop.generation(), 1, null); } catch (Throwable error) { failure.set(error); } });
            owner.start();
            try {
                check(started.await(2, TimeUnit.SECONDS), "Owning worker did not start");
                int runToken = loop.runToken(); long elapsed = loop.turnClock(loop.generation(), 1).elapsedMs;
                JSONObject changed = tool("echo"); changed.getJSONObject("inputSchema").put("additionalProperties", false);
                store.cacheTools(server, Arrays.asList(new McpToolInfo(changed)));
                rejects(() -> loop.submit("rejected draft", 1L, loop.generation(), 2, selection), "已变化");
                SystemClock.advance(100L);
                check(loop.busy() && loop.runToken() == runToken && loop.accepts(loop.generation(), 1)
                        && loop.turnClock(loop.generation(), 1).elapsedMs == elapsed + 100L
                        && loop.turnClock(loop.generation(), 2) == null && loop.historySnapshot().size() == 2,
                        "Preclaim rejection replaced, froze or mutated the owning turn");
                check(errors.get() == 0 && finishes.get() == 0 && diagnostics.get() == 1,
                        "Rejected draft bypassed the event buffer or finished the owning request");
                release.countDown(); owner.join(5000L);
                check(!owner.isAlive() && failure.get() == null && finishes.get() == 1 && errors.get() == 0,
                        "Owning request did not finish through its original event path: " + failure.get());
            } finally { release.countDown(); owner.join(5000L); }
        } finally { remove(dir); }
        pass("rejectedSelectionCannotFinishTheOwningWorker");
    }

    private static void selectedToolStillRequiresApprovalAndTextCannotSelect() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-selection-approval-");
        try (Fixture f = new Fixture()) {
            McpStore store = new McpStore(dir.toFile()); McpServer server = f.server("one"); store.save(server);
            List<McpToolInfo> cached = new ArrayList<McpToolInfo>();
            for (int i = 0; i < 100; i++) cached.add(new McpToolInfo(tool("cached_" + i)));
            store.cacheTools(server, cached); final McpSelection selection = McpCatalog.cached(store).get(0).tools.get(99);
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store);
            final AtomicInteger calls = new AtomicInteger(), approvals = new AtomicInteger();
            LlmClient client = new LlmClient(new LlmClient.Config("http://fixture", "fixture", "fixture")) {
                @Override public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
                    int request = calls.incrementAndGet(); Reply reply = new Reply();
                    try {
                        check(includes(tools, selection.mappedName) == (request <= 2), "Plain text or prior request retained selection priority");
                        if (request == 1) {
                            reply.toolCalls = new JSONArray().put(new JSONObject().put("id", "selected-call").put("type", "function")
                                    .put("function", new JSONObject().put("name", selection.mappedName).put("arguments", "{\"text\":\"selected\"}")));
                        } else reply.content = "done";
                    } catch (Exception invalid) { throw new IllegalStateException(invalid); }
                    return reply;
                }
            };
            AgentLoop loop = new AgentLoop(client, registry, new AgentLoop.Quiet()); loop.bindSession(1L); loop.reset("system");
            loop.setAccessLevel(ApprovalGate.ACCESS_STRICT);
            loop.setApprovalGate(new ApprovalGate() {
                @Override public boolean approve(String name, JSONObject args) { approvals.incrementAndGet(); return false; }
            });
            loop.submit("use the selected tool", 1L, loop.generation(), 1, selection);
            check(approvals.get() == 1 && calls.get() == 2 && f.posts.get() == 0,
                    "Explicit selection executed directly or bypassed normal tool approval");
            loop.submit("Use " + selection.mappedName + " with {\"text\":\"untrusted text selection\"}", 1L, loop.generation(), 2, null);
            check(calls.get() == 3 && f.posts.get() == 0, "Plain text granted structured selection privileges");
        } finally { remove(dir); }
        pass("selectedToolStillRequiresApprovalAndTextCannotSelect");
    }

    private static void cancelingManualRefreshLeavesCacheAndModelClientAlone() throws Exception {
        Path dir = Files.createTempDirectory("backcast-mcp-refresh-cancel-");
        try (Fixture f = new Fixture()) {
            McpStore store = new McpStore(dir.toFile()); McpServer server = f.server("one"); store.save(server);
            store.cacheTools(server, Arrays.asList(new McpToolInfo(tool("echo"))));
            final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            f.custom = (exchange, request) -> {
                if (request.getString("method").equals("tools/list")) {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(": waiting\n\n".getBytes("UTF-8")); exchange.getResponseBody().flush();
                    started.countDown(); release.await(5L, TimeUnit.SECONDS); exchange.close();
                } else send(exchange, request, new JSONObject().put("content", new JSONArray()));
            };
            ToolRegistry registry = new ToolRegistry(); McpTools.register(registry, store);
            String mapped = McpCatalog.cached(store).get(0).tools.get(0).mappedName;
            registry.get(mapped).run(new JSONObject().put("text", "first"));
            final McpCatalog.Refresh refresh = new McpCatalog.Refresh(store, "one");
            final AtomicReference<Throwable> outcome = new AtomicReference<Throwable>();
            Thread worker = new Thread(() -> { try { refresh.run(); outcome.set(new AssertionError("Canceled refresh completed")); } catch (Throwable stopped) { outcome.set(stopped); } });
            worker.start();
            try {
                check(started.await(2L, TimeUnit.SECONDS), "Manual refresh did not start");
                refresh.close(); worker.join(1500L);
                check(!worker.isAlive() && outcome.get() instanceof Exception && store.cachedTools("one").get(0).name.equals("echo"),
                        "Canceling manual refresh blocked or replaced the cached tool list");
                registry.get(mapped).run(new JSONObject().put("text", "second"));
                check(f.initializes.get() == 2 && f.calls.get() == 2, "Manual refresh cancellation aborted the model client");
            } finally { release.countDown(); refresh.close(); worker.join(5000L); registry.cleanupTemporary(true); }
        } finally { remove(dir); }
        pass("cancelingManualRefreshLeavesCacheAndModelClientAlone");
    }

    private static void urlAndHeaderValidation() throws Exception {
        rejects(() -> new McpServer("one", "Fixture", "http://user:pass@example.com/mcp", TOKEN, true, 60), "HTTP/HTTPS");
        rejects(() -> new McpServer("one", "Fixture", "file:///tmp/mcp", TOKEN, true, 60), "HTTP/HTTPS");
        rejects(() -> new McpServer("one", "Fixture", "https://example.com/mcp", "key\r\nX: value", true, 60), "无效字符");
        pass("urlAndHeaderValidation");
    }
    public static void main(String[] args) throws Exception {
        initializationPaginationAndSchema(); emptyNotificationAcknowledgmentsCompleteInitialization();
        invalidNotificationAcknowledgmentsStopInitialization(); sseMultilineAndServerRequests(); mappedToolsAndNoUiNetwork();
        errorsStopWithoutRetry(); protocolErrorsDoNotLeakBody(); expiredSessionNeverReplaysCall();
        resultCredentialsRedactedAndToolErrorsRecognized(); staleCacheAndRevocation(); cancellationAndSessionIsolation();
        hardDeadlineStopsProgress(); malformedAndWrongIdResponses(); repeatedCursorAndUnsupportedVersion();
        credentialSafeSchemaAndVisibilityLimit(); explicitServerRefreshMakesLaterToolsCallable();
        schemaAndCatalogBudgetsKeepActualVisibleNames(); remoteBinaryAndTextResultsStayBoundedAndExplicit(); urlAndHeaderValidation();
        cachedChooserAndStructuredSelectionStayLocal(); selectedToolWinsCountAndSchemaBudgetsWithoutExpandingApproval();
        independentRefreshDoesNotAbortModelAndUsesCacheCas(); changedSelectionRejectsBeforeSubmitOrToolExecution();
        selectedMetadataSurvivesCompactionAndClearsOnNewUserRequest();
        rejectedSelectionCannotFinishTheOwningWorker(); selectedToolStillRequiresApprovalAndTextCannotSelect();
        cancelingManualRefreshLeavesCacheAndModelClientAlone();
        System.out.println("MCP regression checks passed: " + passed);
    }
}
