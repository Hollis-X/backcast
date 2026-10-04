import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Stream ownership and completion through the official SDK against actual HTTP fixtures. */
public final class LlmStreamLifecycleRegressionTest {
    private static final String SSE = "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n"
            + "data: [DONE]\n\n";
    private static final String TOOL = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"fixture-call\",\"function\":{\"name\":\"fixture_tool\",\"arguments\":\"{\\\"path\\\":\\\"fixture.txt\\\"}\"}}]}}]}\n\n";
    private static final String FINISH = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n";
    private static final String USAGE = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":41,\"completion_tokens\":9}}\n\n";
    private static final String EMPTY_DELTA = "data: {\"choices\":[{\"delta\":{}}]}\n\n";
    private static final String CONTENT_DELTA = "data: {\"choices\":[{\"delta\":{\"content\":\"token\"}}]}\n\n";
    private static final class StreamingServer implements HttpHandler, java.io.Closeable {
        final HttpServer http;
        final ExecutorService executor;
        final CountDownLatch stop = new CountDownLatch(1), started = new CountDownLatch(1);
        final AtomicInteger requests = new AtomicInteger();
        final String first, tail;
        final boolean payloadHeartbeat, silentTail, fragmented, meaningfulHeartbeat;
        volatile boolean extraContent;
        final long headerDelayMs;
        StreamingServer(String first, String tail, boolean payloadHeartbeat) throws IOException {
            this(first, tail, payloadHeartbeat, false);
        }
        StreamingServer(String first, String tail, boolean payloadHeartbeat, boolean silentTail) throws IOException {
            this(first, tail, payloadHeartbeat, silentTail, false);
        }
        StreamingServer(String first, String tail, boolean payloadHeartbeat, boolean silentTail, boolean fragmented) throws IOException {
            this(first, tail, payloadHeartbeat, silentTail, fragmented, fragmented ? 350L : 0L);
        }
        StreamingServer(String first, String tail, boolean payloadHeartbeat, boolean silentTail, boolean fragmented, long headerDelayMs) throws IOException {
            this(first, tail, payloadHeartbeat, silentTail, fragmented, headerDelayMs, false);
        }
        StreamingServer(String first, String tail, boolean payloadHeartbeat, boolean silentTail, boolean fragmented, long headerDelayMs, boolean meaningfulHeartbeat) throws IOException {
            this.first = first; this.tail = tail; this.payloadHeartbeat = payloadHeartbeat; this.silentTail = silentTail; this.fragmented = fragmented;
            this.headerDelayMs = headerDelayMs; this.meaningfulHeartbeat = meaningfulHeartbeat;
            executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "fixture-stream-tail"); thread.setDaemon(true); return thread;
                }
            });
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(executor); http.createContext("/v1/chat/completions", this); http.start();
        }
        LlmClient client() {
            LlmClient.Config config = new LlmClient.Config("http://127.0.0.1:" + http.getAddress().getPort(), "fixture", "fixture");
            config.timeoutMs = 1000; config.totalTimeoutMs = 4000;
            return new LlmClient(config);
        }
        @Override public void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            InputStream request = exchange.getRequestBody();
            try { byte[] bytes = new byte[4096]; while (request.read(bytes) >= 0) { } }
            finally { request.close(); }
            if (headerDelayMs > 0) {
                started.countDown();
                try { if (stop.await(headerDelayMs, TimeUnit.MILLISECONDS)) return; }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); exchange.close(); return; }
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                java.io.OutputStream output = exchange.getResponseBody(); byte[] initial = first.getBytes("UTF-8");
                if (fragmented) {
                    int split = 0; while (split < initial.length && (initial[split] & 128) == 0) split++;
                    check(split < initial.length, "Fragment fixture has no multi-byte character");
                    output.write(initial, 0, split + 1); output.flush(); started.countDown();
                    if (stop.await(300, TimeUnit.MILLISECONDS)) return;
                    output.write(initial, split + 1, 1); output.flush();
                    if (stop.await(300, TimeUnit.MILLISECONDS)) return;
                    output.write(initial, split + 2, initial.length - split - 2); output.flush();
                } else { output.write(initial); output.flush(); started.countDown(); }
                if (tail != null) {
                    if (stop.await(150, TimeUnit.MILLISECONDS)) return;
                    output.write(tail.getBytes("UTF-8")); output.flush();
                }
                if (silentTail) { stop.await(); return; }
                while (!stop.await(75, TimeUnit.MILLISECONDS)) {
                    output.write((meaningfulHeartbeat || extraContent ? CONTENT_DELTA : payloadHeartbeat ? EMPTY_DELTA : ": ping\n\n").getBytes("UTF-8")); output.flush();
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException expectedClientClose) { /* A completed client intentionally closes this open fixture. */ }
            finally { exchange.close(); }
        }
        @Override public void close() throws IOException {
            stop.countDown(); http.stop(0); executor.shutdownNow();
            try { check(executor.awaitTermination(2, TimeUnit.SECONDS), "HTTP fixture worker leaked"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
        }
    }
    private static final class ErrorBodyServer implements HttpHandler, java.io.Closeable {
        final HttpServer http;
        final ExecutorService executor;
        final CountDownLatch stop = new CountDownLatch(1);
        final AtomicInteger requests = new AtomicInteger();
        final int status;
        final AtomicInteger responded = new AtomicInteger();
        final boolean silentBody;
        final String retryAfter;
        final boolean trickle;
        final String detail;
        ErrorBodyServer(int status) throws IOException {
            this(status, true);
        }
        ErrorBodyServer(int status, boolean silentBody) throws IOException {
            this(status, silentBody, "0");
        }
        ErrorBodyServer(int status, boolean silentBody, String retryAfter) throws IOException {
            this(status, silentBody, retryAfter, false);
        }
        ErrorBodyServer(int status, boolean silentBody, String retryAfter, boolean trickle) throws IOException {
            this(status, silentBody, retryAfter, trickle, "incomplete provider detail");
        }
        ErrorBodyServer(int status, boolean silentBody, String retryAfter, boolean trickle, String detail) throws IOException {
            this.status = status; this.silentBody = silentBody; this.retryAfter = retryAfter; this.trickle = trickle; this.detail = detail;
            executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "fixture-error-body"); thread.setDaemon(true); return thread;
                }
            });
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(executor); http.createContext("/v1/chat/completions", this);
            http.createContext("/v1/models", this); http.start();
        }
        @Override public void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            InputStream input = exchange.getRequestBody();
            try { byte[] bytes = new byte[4096]; while (input.read(bytes) >= 0) { } }
            finally { input.close(); }
            if (status == 503) exchange.getResponseHeaders().set("Retry-After", retryAfter);
            if (status == 302) exchange.getResponseHeaders().set("Location", "/v1/chat/completions");
            exchange.getResponseHeaders().set("x-request-id", "fixture-private-request-id");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] detail = this.detail.getBytes("UTF-8");
            exchange.sendResponseHeaders(status, silentBody ? 1024 : detail.length);
            try {
                exchange.getResponseBody().write(detail);
                exchange.getResponseBody().flush();
                responded.incrementAndGet();
                if (trickle) {
                    while (!stop.await(50L, TimeUnit.MILLISECONDS)) {
                        exchange.getResponseBody().write('.'); exchange.getResponseBody().flush();
                    }
                } else if (silentBody) stop.await();
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            catch (IOException expectedClose) { /* Deadline-bounded client intentionally closes the response. */ }
            finally { exchange.close(); }
        }
        @Override public void close() throws IOException {
            stop.countDown(); http.stop(0); executor.shutdownNow();
            try { check(executor.awaitTermination(2, TimeUnit.SECONDS), "Error-body fixture worker leaked"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
        }
    }
    private static final class FiniteServer implements HttpHandler, java.io.Closeable {
        final HttpServer http;
        final byte[] body;
        final boolean json, truncated;
        final AtomicInteger requests = new AtomicInteger();
        FiniteServer(String body, boolean json, boolean truncated) throws Exception {
            this.body = body.getBytes("UTF-8"); this.json = json; this.truncated = truncated;
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/v1/chat/completions", this); http.start();
        }
        @Override public void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            InputStream input = exchange.getRequestBody();
            try { byte[] bytes = new byte[4096]; while (input.read(bytes) >= 0) { } }
            finally { input.close(); }
            exchange.getResponseHeaders().set("Content-Type", json ? "application/json" : "text/event-stream");
            exchange.sendResponseHeaders(200, body.length + (truncated ? 64 : 0));
            try { exchange.getResponseBody().write(body); exchange.getResponseBody().flush(); }
            finally { exchange.close(); }
        }
        LlmClient client() {
            LlmClient.Config config = new LlmClient.Config("http://127.0.0.1:" + http.getAddress().getPort(), "fixture", "fixture");
            config.timeoutMs = 1000; config.totalTimeoutMs = 4000; return new LlmClient(config);
        }
        @Override public void close() { http.stop(0); }
    }
    private static LlmClient.Reply finite(String body, boolean json, boolean truncated) throws Exception {
        try (FiniteServer server = new FiniteServer(body, json, truncated)) {
            LlmClient client = server.client();
            LlmClient.Reply reply = client.send(Arrays.asList(Message.user("fixture")), null, null);
            check(server.requests.get() == 1 && client.requestActivity() == null, "Finite SDK request leaked or silently repeated");
            return reply;
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void sseDoneClosesWithoutDrainingOpenStream() throws Exception {
        try (StreamingServer server = new StreamingServer(SSE, null, false, true)) {
            long start = System.nanoTime(); LlmClient.Reply reply = send(server, server.client(), null);
            check(reply.error == null && "answer".equals(reply.content)
                    && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000L, "SDK DONE waited for an open transport EOF");
        }
    }
    private static void firstDoneDoesNotWaitForAnotherNetworkRead() throws Exception {
        try (StreamingServer server = new StreamingServer("data: [DONE]\n\n", null, false, true)) {
            long start = System.nanoTime(); LlmClient.Reply reply = send(server, server.client(), null);
            check(reply.error == null && reply.content.length() == 0
                    && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500L, "First SDK DONE kept reading the connection");
        }
    }
    private static void malformedSseStillClosesStream() throws Exception {
        check(finite("data: invalid-json\n\n", false, false).error != null, "Malformed SDK SSE was accepted");
    }
    private static void networkReadFailureStillClosesStream() throws Exception {
        check(finite("", false, true).error != null, "Truncated HTTP body was accepted");
    }
    private static void ordinaryBodyReadsToEofAndCloses() throws Exception {
        LlmClient.Reply reply = finite("{\"choices\":[{\"message\":{\"content\":\"response\"}}]}", true, false);
        check(reply.error == null && "response".equals(reply.content), "SDK JSON response compatibility was lost");
    }
    private static void ordinaryBodyFailureClosesAndPreservesCause() throws Exception {
        check(finite("{\"choices\":[", true, true).error != null, "Truncated JSON response was accepted");
    }
    private static void closeFailureDoesNotDiscardCompleteReply() throws Exception {
        LlmClient.Reply reply = finite(SSE, false, true);
        check(reply.error == null && "answer".equals(reply.content), "Transport truncation after SDK DONE discarded a complete reply");
    }
    private static void cancelledAttemptClosesWithoutReading() throws Exception {
        try (FiniteServer server = new FiniteServer(SSE, false, false)) {
            LlmClient client = server.client();
            LlmClient.Reply reply = client.sendIfCurrent(Arrays.asList(Message.user("fixture")), null, null,
                    new LlmClient.RequestValidity() { @Override public boolean isCurrent() { return false; } });
            check(reply.content.length() == 0 && server.requests.get() == 0 && client.requestActivity() == null,
                    "Invalid attempt started an SDK HTTP request");
        }
    }
    private static LlmClient.Reply send(StreamingServer server, LlmClient client, LlmClient.Sink sink) {
        return client.send(Arrays.asList(Message.system("fixture"), Message.user("fixture")), null, sink);
    }
    private static void finishedToolCallDoesNotWaitForDoneOrConnectionClose() throws Exception {
        for (boolean fragmented : new boolean[]{false, true}) {
        try (StreamingServer server = new StreamingServer((fragmented ? TOOL.replace("fixture.txt", "fixture你好.txt") : TOOL) + FINISH,
                null, false, true, fragmented)) {
            final AtomicInteger previews = new AtomicInteger();
            long start = System.nanoTime();
            LlmClient.Reply reply = send(server, server.client(), new LlmClient.Sink() {
                @Override public void onReasoning(String delta) { }
                @Override public void onContent(String delta) { }
                @Override public void onToolCall(int index, String id, String name, String arguments) { previews.incrementAndGet(); }
            });
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(reply.error == null && reply.hasToolCalls(), "Explicit tool finish lost completed call: " + reply.error);
            check(reply.toolCalls.getJSONObject(0).getJSONObject("function").getString("arguments").contains(fragmented ? "fixture你好.txt" : "fixture.txt"), "Fragmented UTF-8 tool arguments changed");
            check(elapsed < (fragmented ? 3500 : 2500) && previews.get() == 1 && server.requests.get() == 1, "Completed tool call waited for idle/retried: " + elapsed);
        }
        }
    }
    private static void delayedUsageTailIsRetainedWithoutDone() throws Exception {
        try (StreamingServer server = new StreamingServer(TOOL + FINISH, USAGE, true)) {
            LlmClient.Reply reply = send(server, server.client(), null);
            check(reply.error == null && reply.hasToolCalls() && reply.promptTokens == 41 && reply.completionTokens == 9,
                    "Completion discarded its delayed choices[] usage tail: " + reply.error);
            check(server.requests.get() == 1, "Usage tail caused a second HTTP request");
        }
        for (String tail : new String[]{"data: invalid-json\n\n", "data: {\"error\":{\"message\":\"late optional tail\"}}\n\n"}) {
            try (StreamingServer server = new StreamingServer(TOOL + FINISH, tail, true)) {
                LlmClient.Reply reply = send(server, server.client(), null);
                check(reply.error == null && reply.hasToolCalls(), "Malformed optional usage discarded a completed SDK call: " + reply.error);
            }
        }
    }
    private static void emptyPayloadsDoNotKeepUnfinishedGenerationAlive() throws Exception {
        try (StreamingServer server = new StreamingServer(TOOL, null, true)) {
            long start = System.nanoTime(); LlmClient.Reply reply = send(server, server.client(), null);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(reply.error != null && !reply.hasToolCalls() && elapsed < 2500,
                    "Empty deltas renewed meaningful idle or unfinished call escaped: " + elapsed + " " + reply.error);
        }
    }
    private static void partialToolArgumentsNeverEscapeAtDoneFinishOrEof() throws Exception {
        String partial = TOOL.replace("fixture.txt\\\"}", "fixture");
        for (String ending : new String[]{"", "data: [DONE]\n", FINISH}) {
            LlmClient.Reply reply = finite(partial + ending, false, false);
            check(reply.error != null && !reply.hasToolCalls(), "Truncated SDK args accepted with ending " + ending);
        }
        check(!finite(TOOL.replace("fixture.txt\\\"}", "fixture.txt\\\"}garbage") + "data: [DONE]\n\n", false, false).hasToolCalls(),
                "JSON arguments with trailing data were accepted");
    }
    private static void completeEofCallsRemainCompatibleButTruncatedFinishCannotExecute() throws Exception {
        check(finite(TOOL, false, false).hasToolCalls(), "Provider closing complete SDK calls without DONE was rejected");
        LlmClient.Reply failedTail = finite(TOOL + FINISH, false, true);
        check(failedTail.error == null && failedTail.hasToolCalls(), "Network failure in usage-only tail discarded a completed SDK call");
        for (String finish : new String[]{"length", "content_filter"}) {
            LlmClient.Reply reply = finite(TOOL + FINISH.replace("tool_calls", finish) + "data: [DONE]\n\n", false, false);
            check(reply.error != null && !reply.hasToolCalls(), "Truncated/filtered generation returned executable calls");
        }
    }
    private static void abortDuringCompletionTailDiscardsAllOutput() throws Exception {
        try (final StreamingServer server = new StreamingServer(TOOL + FINISH, null, false)) {
            final LlmClient client = server.client();
            final java.util.concurrent.atomic.AtomicReference<LlmClient.Reply> result = new java.util.concurrent.atomic.AtomicReference<LlmClient.Reply>();
            Thread worker = new Thread(new Runnable() { @Override public void run() { result.set(send(server, client, null)); } });
            worker.setDaemon(true); worker.start();
            try {
                check(server.started.await(2, TimeUnit.SECONDS), "Completion fixture never started"); client.abort(); worker.join(2500);
                check(!worker.isAlive() && result.get() != null && !result.get().hasToolCalls() && result.get().content.length() == 0,
                        "Cancellation leaked completed tool calls from usage grace window");
            } finally { client.abort(); worker.join(2500); }
        }
    }
    private static LlmClient headerClient(StreamingServer server, int idle, int total) {
        LlmClient.Config config = new LlmClient.Config("http://127.0.0.1:" + server.http.getAddress().getPort(), "fixture", "fixture");
        config.timeoutMs = idle; config.totalTimeoutMs = total;
        return new LlmClient(config);
    }
    private static void responseHeadersCanArriveAfterTheTenSecondStreamSlice() throws Exception {
        try (StreamingServer server = new StreamingServer(SSE, null, false, false, false, 11000L)) {
            long start = System.nanoTime(); LlmClient.Reply reply = send(server, headerClient(server, 20000, 20000), null);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(reply.error == null && "answer".equals(reply.content) && server.requests.get() == 1,
                    "Headers inside configured budget failed/retried at the stream slice: " + reply.error);
            check(elapsed >= 10000 && elapsed < 18000, "Delayed header fixture did not cross the original ten-second timeout: " + elapsed);
        }
    }
    private static void headerTimeoutUsesIdleAndRemainingTotalBudgetAndReportsItsStage() throws Exception {
        for (int total : new int[]{4000, 500}) {
            try (StreamingServer server = new StreamingServer(SSE, null, false, false, false, 1500L)) {
                long start = System.nanoTime(); LlmClient.Reply reply = send(server, headerClient(server, 1000, total), null);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                check(reply.error != null && reply.error.startsWith("响应头等待超时：")
                                && reply.error.contains("timeout")
                                && !reply.hasToolCalls() && reply.content.length() == 0,
                        "Header timeout lost its safe fixed stage category: " + reply.error);
                check(elapsed < (total == 500 ? 1300 : 1800) && server.requests.get() == 1,
                        "Header deadline ignored idle or remaining total budget: " + elapsed);
            }
        }
    }
    private static void failedErrorBodyReadPreservesTheReceivedHttpStatus() throws Exception {
        for (int status : new int[]{401, 403, 503}) {
            try (ErrorBodyServer server = new ErrorBodyServer(status)) {
                LlmClient.Config config = new LlmClient.Config("http://127.0.0.1:" + server.http.getAddress().getPort(), "fixture", "fixture");
                config.timeoutMs = 1000; config.totalTimeoutMs = 4000;
                long start = System.nanoTime();
                LlmClient.Reply reply = new LlmClient(config).send(Arrays.asList(Message.user("fixture")), null, null);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                check(reply.error != null && reply.error.startsWith("HTTP " + status + ":"),
                        "Error-body timeout discarded HTTP status: " + status + " requests=" + server.requests.get()
                                + " responded=" + server.responded.get() + " elapsed=" + elapsed + " " + reply.error);
                check(!reply.hasToolCalls() && reply.content.length() == 0 && server.requests.get() == 1,
                        "Failed error body produced model output or an optional-parameter retry");
                check(elapsed >= 700 && elapsed < 2500,
                        "Error-body fixture did not fail within its configured read timeout: " + status + " " + elapsed);
            }
        }
        try (ErrorBodyServer server = new ErrorBodyServer(401, true, "0", true)) {
            LlmClient.Config config = new LlmClient.Config("http://127.0.0.1:" + server.http.getAddress().getPort(), "fixture", "fixture");
            config.timeoutMs = 5000; config.totalTimeoutMs = 5000;
            long start = System.nanoTime();
            LlmClient.Reply reply = new LlmClient(config).send(Arrays.asList(Message.user("fixture")), null, null);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(reply.error != null && reply.error.startsWith("HTTP 401:") && elapsed < 1800,
                    "Error-body trickle prolonged a known permanent failure: " + elapsed + " " + reply.error);
        }
    }
    private static void silentResponseBodyUsesTheConfiguredIdleBudget() throws Exception {
        try (StreamingServer server = new StreamingServer("", null, false, true)) {
            long start = System.nanoTime();
            LlmClient.Reply reply = send(server, server.client(), null);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(reply.error != null && reply.error.contains("长时间没有输出") && !reply.hasToolCalls(),
                    "Silent open body did not end at its meaningful-output deadline: " + reply.error);
            check(elapsed >= 700 && elapsed < 1800 && server.requests.get() == 1,
                    "Body read ignored a changed Okio deadline or silently retried: " + elapsed);
        }
    }
    private static void totalBudgetStopsEvenAStreamWithContinuousModelOutput() throws Exception {
        try (StreamingServer server = new StreamingServer(CONTENT_DELTA, null, false, false, false, 0L, true)) {
            long start = System.nanoTime();
            LlmClient.Reply reply = send(server, headerClient(server, 1000, 550), null);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(reply.error != null && !reply.hasToolCalls() && elapsed >= 300 && elapsed < 1300,
                    "Continuous output defeated the call total budget: " + elapsed + " " + reply.error);
            check(server.requests.get() == 1, "Total timeout was retried inside the HTTP stack");
        }
    }
    private static void cancellationInterruptsSilentHeadersAndBodyImmediately() throws Exception {
        for (boolean headers : new boolean[]{true, false}) {
            try (final StreamingServer server = new StreamingServer("", null, false, true, false, headers ? 30000L : 0L)) {
                final LlmClient client = headerClient(server, 30000, 40000);
                final java.util.concurrent.atomic.AtomicReference<LlmClient.Reply> reply = new java.util.concurrent.atomic.AtomicReference<LlmClient.Reply>();
                Thread worker = new Thread(new Runnable() {
                    @Override public void run() { reply.set(send(server, client, null)); }
                });
                worker.setDaemon(true); worker.start();
                try {
                    check(server.started.await(2, TimeUnit.SECONDS), "Silent cancellation fixture never received request");
                    Thread.sleep(100L);
                    long start = System.nanoTime(); client.abort(); worker.join(1500);
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    check(!worker.isAlive() && reply.get() != null && reply.get().error == null
                                    && reply.get().content.length() == 0 && !reply.get().hasToolCalls(),
                            "Cancelled headers/body leaked output or remained blocked");
                    check(elapsed < 1000 && server.requests.get() == 1, "Cancellation waited for socket timeout: " + elapsed);
                } finally { client.abort(); server.stop.countDown(); worker.join(2000); }
            }
        }
    }
    private static void serverRetryAndRedirectInstructionsNeverRepeatRequestsInsideTransport() throws Exception {
        for (int i = 0; i < 5; i++) {
            int status = i == 0 ? 408 : i == 4 ? 302 : 503;
            String retryAfter = i == 2 ? "00" : i == 3 ? "999999999999999999999999" : "0";
            try (ErrorBodyServer server = new ErrorBodyServer(status, false, retryAfter)) {
                LlmClient.Config config = new LlmClient.Config("http://127.0.0.1:" + server.http.getAddress().getPort(), "fixture", "fixture");
                config.timeoutMs = 1000; config.totalTimeoutMs = 4000;
                LlmClient.Reply reply = new LlmClient(config).send(Arrays.asList(Message.user("fixture")), null, null);
                check(reply.error != null && reply.error.startsWith("HTTP " + status + ":") && server.requests.get() == 1,
                        "Transport hid an additional retry or redirect: " + status + " " + server.requests.get() + " " + reply.error);
            }
        }
    }
    private static void requestActivityTracksOnlyLiveSafeMonotonicTiming() throws Exception {
        try (final StreamingServer server = new StreamingServer(CONTENT_DELTA, null, true, false, false, 800L)) {
            final LlmClient client = headerClient(server, 5000, 7000);
            final CountDownLatch content = new CountDownLatch(1);
            final CountDownLatch resumed = new CountDownLatch(1);
            final AtomicInteger contents = new AtomicInteger();
            Thread worker = new Thread(new Runnable() {
                @Override public void run() {
                    send(server, client, new LlmClient.Sink() {
                        @Override public void onReasoning(String value) { }
                        @Override public void onContent(String value) {
                            if (contents.incrementAndGet() == 1) content.countDown();
                            else resumed.countDown();
                        }
                        @Override public void onToolCall(int index, String id, String name, String args) { }
                    });
                }
            });
            worker.setDaemon(true); worker.start();
            try {
                check(server.started.await(2, TimeUnit.SECONDS), "Activity fixture never received headers request");
                Thread.sleep(50L);
                LlmClient.RequestActivity headers = client.requestActivity();
                check(headers != null && headers.quietMs >= 30L,
                        "Pending header activity exposed progress or lost the original monotonic origin");
                check(content.await(2, TimeUnit.SECONDS), "Activity fixture never delivered content");
                Thread.sleep(200L);
                LlmClient.RequestActivity quiet = client.requestActivity();
                check(quiet != null && quiet.quietMs >= 150L && quiet.quietMs < 500L,
                        "Empty heartbeat reset live silence");
                server.extraContent = true;
                check(resumed.await(2, TimeUnit.SECONDS), "Activity fixture never resumed meaningful content");
                server.extraContent = false;
                Thread.sleep(20L);
                LlmClient.RequestActivity progress = client.requestActivity();
                check(progress != null && progress.quietMs < 150L,
                        "New meaningful SDK content did not reset live quiet time");
                client.abort();
                check(client.requestActivity() == null, "Cancelled request still exposed active metadata");
                worker.join(1500L); check(!worker.isAlive(), "Cancelled activity worker leaked");
            } finally { client.abort(); server.stop.countDown(); worker.join(2000L); }
        }
        try (FiniteServer server = new FiniteServer(SSE, false, false)) {
            LlmClient client = server.client();
            check(client.requestActivity() == null, "Unused client exposed request metadata");
            check(client.send(Arrays.asList(Message.user("fixture")), null, null).error == null
                            && client.requestActivity() == null, "Completed SDK request still exposed live metadata");
        }
    }
    private static void responseSizeLimitAppliesToSdkJsonAndSseBodies() throws Exception {
        StringBuilder text = new StringBuilder(); for (int i = 0; i < 300; i++) text.append('x');
        for (boolean json : new boolean[]{true, false}) {
            String body = json ? "{\"choices\":[{\"message\":{\"content\":\"" + text + "\"}}]}"
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\ndata: [DONE]\n\n";
            try (FiniteServer server = new FiniteServer(body, json, false)) {
                LlmClient.Config config = new LlmClient.Config("http://127.0.0.1:" + server.http.getAddress().getPort(), "fixture", "fixture");
                config.timeoutMs = 1000; config.totalTimeoutMs = 4000; config.maxResponseChars = 100;
                LlmClient.Reply reply = new LlmClient(config).send(Arrays.asList(Message.user("fixture")), null, null);
                check(reply.error != null && reply.error.contains("Response size limit exceeded") && !reply.hasToolCalls(),
                        "SDK body bypassed response size limit: " + json + " " + reply.error);
            }
        }
    }
    private static void httpFailureRetainsPrivateProviderStatusAndCauseWithoutChangingUiError() throws Exception {
        try (ErrorBodyServer server = new ErrorBodyServer(503, false)) {
            LlmClient.Config config = new LlmClient.Config("http://127.0.0.1:" + server.http.getAddress().getPort(), "fixture-no-match-key", "fixture");
            config.providerId = "fixture-provider";
            LlmClient.Reply reply = new LlmClient(config).send(Arrays.asList(Message.user("request only")), null, null);
            JSONObject details = reply.diagnostic;
            check(reply.error.startsWith("HTTP 503:") && details != null
                    && details.getInt("http_status") == 503 && "http_error".equals(details.getString("stage"))
                    && "fixture-provider".equals(details.getString("provider"))
                    && "fixture-private-request-id".equals(details.getString("request_id"))
                    && details.getString("provider_error").contains("incomplete provider detail")
                    && details.getString("exception_class").contains("InternalServerException")
                    && !reply.error.contains(" at ") && server.requests.get() == 1,
                    "HTTP failure lost its private diagnostic or added it to the visible error: " + details);
        }
    }
    private static void diagnosticsDistinguishHeaderWaitFromSilentStreamBody() throws Exception {
        for (boolean headers : new boolean[]{true, false}) {
            try (StreamingServer server = new StreamingServer("", null, false, true, false, headers ? 1500L : 0L)) {
                LlmClient.Reply reply = send(server, headerClient(server, 1000, 4000), null);
                JSONObject details = reply.diagnostic;
                check(reply.error != null && details != null && details.getString("stage").equals(headers ? "headers" : "stream_body")
                        && details.getBoolean("response_started") != headers && !details.getBoolean("has_progress")
                        && details.getLong("elapsed_ms") >= 700 && details.getString("cause_class").contains("Timeout"),
                        "Private diagnostic confused header and body timeout: " + details);
            }
        }
    }
    private static void providerEchoesOfCredentialsPromptsAndToolArgumentsAreRedacted() throws Exception {
        String prompt = "private request line1\n\"line2\"";
        String thought = "private reasoning transcript";
        String arguments = new JSONObject().put("token", "private-tool-token").toString();
        String detail = new JSONObject().put("error", new JSONObject().put("message",
                "fixture-secret-key " + prompt + " " + thought + " " + arguments)).toString();
        try (ErrorBodyServer server = new ErrorBodyServer(400, false, "0", false, detail)) {
            LlmClient.Config config = new LlmClient.Config("http://127.0.0.1:" + server.http.getAddress().getPort(), "fixture-secret-key", "fixture");
            Message assistant = Message.assistant("previous private answer", new JSONArray().put(new JSONObject()
                    .put("function", new JSONObject().put("name", "fixture").put("arguments", arguments))));
            assistant.reasoning = thought;
            LlmClient.Reply reply = new LlmClient(config).send(Arrays.asList(Message.user(prompt), assistant), null, null);
            String stored = reply.diagnostic.toString();
            check(!stored.contains("fixture-secret-key") && !stored.contains("private request")
                    && !stored.contains(thought) && !stored.contains("private-tool-token")
                    && stored.contains("400") && stored.length() <= 8192,
                    "Private failure diagnostics retained echoed request secrets");
        }
    }
    private static void modelListFailuresCarryTheSamePrivateDiagnostic() throws Exception {
        try (ErrorBodyServer server = new ErrorBodyServer(403, false)) {
            LlmClient.ModelsResult result = LlmClient.fetchModels("http://127.0.0.1:" + server.http.getAddress().getPort(),
                    "fixture-model-list-key", "fixture-model-provider");
            JSONObject details = result.diagnostic;
            check(result.error.startsWith("HTTP 403:") && details != null
                    && details.getInt("http_status") == 403 && "http_error".equals(details.getString("stage"))
                    && "fixture-model-provider".equals(details.getString("provider"))
                    && "GET".equals(details.getString("http_method")) && details.getString("endpoint").endsWith("/v1/models")
                    && details.getString("cause_class").contains("PermissionDeniedException")
                    && !details.toString().contains("fixture-model-list-key") && server.requests.get() == 1,
                    "Model-list HTTP failure lost diagnostic context: " + details);
        }
    }
    private static void clippedLongPromptEchoIsOmittedWithoutLosingHttpDiagnosis() throws Exception {
        StringBuilder prompt = new StringBuilder("private-long-echo:");
        for (int i = 0; i < 6000; i++) prompt.append('x');
        String detail = new JSONObject().put("error", new JSONObject().put("message", prompt.toString())).toString();
        try (ErrorBodyServer server = new ErrorBodyServer(400, false, "0", false, detail)) {
            LlmClient.Config config = new LlmClient.Config("http://127.0.0.1:" + server.http.getAddress().getPort(),
                    "fixture-key-long-echo", "fixture");
            LlmClient.Reply reply = new LlmClient(config).send(Arrays.asList(Message.user(prompt.toString())), null, null);
            JSONObject stored = reply.diagnostic;
            check(stored != null && stored.getInt("http_status") == 400
                            && stored.getString("stage").equals("http_error")
                            && stored.getString("provider_error").equals("供应商错误正文不完整，已省略")
                            && stored.getString("exception_class").length() > 0 && stored.getString("cause_class").length() > 0
                            && !stored.toString().contains("private-long-echo") && server.requests.get() == 1,
                    "Clipped provider echo leaked request text or erased HTTP diagnosis");
            check(reply.error.startsWith("HTTP 400:") && reply.error.contains("private-long-echo"),
                    "Diagnostic omission changed the request classification or original error evidence");
        }
    }
    private static void stalledPartialJsonErrorBodyIsOmittedWithoutChangingTheReadBudget() throws Exception {
        for (String detail : new String[]{"{\"error\":{\"message\":\"private-partial-request",
                "[{\"message\":\"private-partial-request"}) {
            try (ErrorBodyServer server = new ErrorBodyServer(400, true, "0", false, detail)) {
                LlmClient.Config config = new LlmClient.Config("http://127.0.0.1:" + server.http.getAddress().getPort(),
                        "fixture-key-partial-echo", "fixture");
                config.timeoutMs = 6000; config.totalTimeoutMs = 9000;
                long started = System.nanoTime();
                LlmClient.Reply reply = new LlmClient(config).send(Arrays.asList(Message.user("private-partial-request-full")), null, null);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                JSONObject stored = reply.diagnostic;
                check(reply.error.startsWith("HTTP 400:") && stored != null && stored.getInt("http_status") == 400
                                && stored.getString("stage").equals("http_error") && stored.getBoolean("response_started")
                                && stored.getString("provider_error").equals("供应商错误正文不完整，已省略")
                                && stored.getString("exception_class").length() > 0
                                && !stored.toString().contains("private-partial-request")
                                && elapsed < 3000L && server.requests.get() == 1,
                        "Partial stalled JSON leaked a prompt fragment, lost status, or waited for the model read budget");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        String[] cases = {"sseDoneClosesWithoutDrainingOpenStream", "firstDoneDoesNotWaitForAnotherNetworkRead",
                "malformedSseStillClosesStream", "networkReadFailureStillClosesStream", "ordinaryBodyReadsToEofAndCloses",
                "ordinaryBodyFailureClosesAndPreservesCause", "closeFailureDoesNotDiscardCompleteReply", "cancelledAttemptClosesWithoutReading",
                "finishedToolCallDoesNotWaitForDoneOrConnectionClose", "delayedUsageTailIsRetainedWithoutDone",
                "emptyPayloadsDoNotKeepUnfinishedGenerationAlive", "partialToolArgumentsNeverEscapeAtDoneFinishOrEof",
                "completeEofCallsRemainCompatibleButTruncatedFinishCannotExecute", "abortDuringCompletionTailDiscardsAllOutput",
                "responseHeadersCanArriveAfterTheTenSecondStreamSlice", "headerTimeoutUsesIdleAndRemainingTotalBudgetAndReportsItsStage",
                "failedErrorBodyReadPreservesTheReceivedHttpStatus", "silentResponseBodyUsesTheConfiguredIdleBudget",
                "totalBudgetStopsEvenAStreamWithContinuousModelOutput", "cancellationInterruptsSilentHeadersAndBodyImmediately",
                "serverRetryAndRedirectInstructionsNeverRepeatRequestsInsideTransport",
                "requestActivityTracksOnlyLiveSafeMonotonicTiming", "responseSizeLimitAppliesToSdkJsonAndSseBodies",
                "httpFailureRetainsPrivateProviderStatusAndCauseWithoutChangingUiError",
                "diagnosticsDistinguishHeaderWaitFromSilentStreamBody", "providerEchoesOfCredentialsPromptsAndToolArgumentsAreRedacted",
                "modelListFailuresCarryTheSamePrivateDiagnostic", "clippedLongPromptEchoIsOmittedWithoutLosingHttpDiagnosis",
                "stalledPartialJsonErrorBodyIsOmittedWithoutChangingTheReadBudget"};
        if (args.length > 0) cases = args;
        for (String name : cases) { LlmStreamLifecycleRegressionTest.class.getDeclaredMethod(name).invoke(null); System.out.println("PASS " + name); }
        System.out.println(cases.length + " stream lifecycle tests passed");
    }
}
