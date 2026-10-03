import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Stream ownership and SSE termination through the production parser. */
public final class LlmStreamLifecycleRegressionTest {
    private static final class Tracked extends InputStream {
        final byte[] body;
        int position, reads;
        boolean closed, failRead, failClose, failAtEof;
        int timeoutAt = -1;
        boolean timedOnce;
        Tracked(String body) throws Exception { this.body = body.getBytes("UTF-8"); }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] buffer, int offset, int size) throws IOException {
            reads++;
            if (!timedOnce && position == timeoutAt) { timedOnce = true; throw new java.net.SocketTimeoutException("fixture between partial UTF-8 bytes"); }
            if (failRead || (failAtEof && position == body.length)) throw new IOException("fixture read failure");
            if (position == body.length) return -1;
            int count = 0;
            while (count < size && position < body.length && (timeoutAt < 0 || position < timeoutAt || timedOnce)) {
                byte value = body[position++]; buffer[offset + count++] = value;
                if (value == '\n') break;
            }
            return count;
        }
        @Override public void close() throws IOException {
            closed = true; if (failClose) throw new IOException("fixture close failure");
        }
    }
    private static final String SSE = "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n"
            + "data: [DONE]\n";
    private static final String TOOL = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"fixture-call\",\"function\":{\"name\":\"fixture_tool\",\"arguments\":\"{\\\"path\\\":\\\"fixture.txt\\\"}\"}}]}}]}\n\n";
    private static final String FINISH = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n";
    private static final String USAGE = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":41,\"completion_tokens\":9}}\n\n";
    private static final String EMPTY_DELTA = "data: {\"choices\":[{\"delta\":{}}]}\n\n";
    private static final class StreamingServer implements HttpHandler, java.io.Closeable {
        final HttpServer http;
        final ExecutorService executor;
        final CountDownLatch stop = new CountDownLatch(1), started = new CountDownLatch(1);
        final AtomicInteger requests = new AtomicInteger();
        final String first, tail;
        final boolean payloadHeartbeat, silentTail, fragmented;
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
            this.first = first; this.tail = tail; this.payloadHeartbeat = payloadHeartbeat; this.silentTail = silentTail; this.fragmented = fragmented;
            this.headerDelayMs = headerDelayMs;
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
                    output.write((payloadHeartbeat ? EMPTY_DELTA : ": ping\n\n").getBytes("UTF-8")); output.flush();
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
        ErrorBodyServer(int status) throws IOException {
            this.status = status;
            executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "fixture-error-body"); thread.setDaemon(true); return thread;
                }
            });
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(executor); http.createContext("/v1/chat/completions", this); http.start();
        }
        @Override public void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            InputStream input = exchange.getRequestBody();
            try { byte[] bytes = new byte[4096]; while (input.read(bytes) >= 0) { } }
            finally { input.close(); }
            exchange.sendResponseHeaders(status, 1024);
            try {
                exchange.getResponseBody().write("incomplete provider detail".getBytes("UTF-8"));
                exchange.getResponseBody().flush();
                stop.await();
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }
        @Override public void close() throws IOException {
            stop.countDown(); http.stop(0); executor.shutdownNow();
            try { check(executor.awaitTermination(2, TimeUnit.SECONDS), "Error-body fixture worker leaked"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
        }
    }
    private static Object attempt(boolean cancelled) throws Exception {
        Class<?> type = Class.forName("com.mkei.backcast.agent.LlmClient$Attempt");
        Constructor<?> ctor = type.getDeclaredConstructor(); ctor.setAccessible(true); Object value = ctor.newInstance();
        java.lang.reflect.Field dead = type.getDeclaredField("dead"); dead.setAccessible(true); dead.setBoolean(value, cancelled);
        return value;
    }
    private static LlmClient.Reply stream(Tracked input, boolean cancelled) throws Exception {
        Object attempt = attempt(cancelled);
        Method read = LlmClient.class.getDeclaredMethod("readStream", InputStream.class, LlmClient.Reply.class,
                attempt.getClass(), LlmClient.Sink.class, long.class);
        read.setAccessible(true); LlmClient.Reply reply = new LlmClient.Reply();
        read.invoke(null, input, reply, attempt, null, 1000L); return reply;
    }
    private static String all(Tracked input) throws Exception {
        Method read = LlmClient.class.getDeclaredMethod("readAll", InputStream.class); read.setAccessible(true);
        return (String) read.invoke(null, input);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void sseDoneClosesWithoutDrainingOpenStream() throws Exception {
        Tracked input = new Tracked(SSE); input.failAtEof = true;
        check("answer".equals(stream(input, false).content) && input.closed, "SSE DONE did not close cleanly");
    }
    private static void firstDoneDoesNotWaitForAnotherNetworkRead() throws Exception {
        Tracked input = new Tracked("data: [DONE]\n"); input.failAtEof = true;
        check(stream(input, false).error == null && input.closed && input.reads == 1, "First DONE kept reading the connection");
    }
    private static void malformedSseStillClosesStream() throws Exception {
        Tracked input = new Tracked("data: invalid-json\n"); boolean failed = false;
        try { stream(input, false); } catch (InvocationTargetException expected) { failed = true; }
        check(failed && input.closed, "Parsing error leaked response stream");
    }
    private static void networkReadFailureStillClosesStream() throws Exception {
        Tracked input = new Tracked(""); input.failRead = true; boolean failed = false;
        try { stream(input, false); } catch (InvocationTargetException expected) { failed = expected.getCause() instanceof IOException; }
        check(failed && input.closed, "Read failure leaked response stream");
    }
    private static void ordinaryBodyReadsToEofAndCloses() throws Exception {
        Tracked input = new Tracked("response\n"); check("response\n".equals(all(input)) && input.closed, "Ordinary body was not closed after EOF");
    }
    private static void ordinaryBodyFailureClosesAndPreservesCause() throws Exception {
        Tracked input = new Tracked(""); input.failRead = true; input.failClose = true; boolean failed = false;
        try { all(input); } catch (InvocationTargetException expected) { failed = "fixture read failure".equals(expected.getCause().getMessage()); }
        check(failed && input.closed, "Error body cleanup leaked or masked read failure");
    }
    private static void closeFailureDoesNotDiscardCompleteReply() throws Exception {
        Tracked input = new Tracked(SSE); input.failClose = true;
        check("answer".equals(stream(input, false).content) && input.closed, "Close failure discarded received content");
    }
    private static void cancelledAttemptClosesWithoutReading() throws Exception {
        Tracked input = new Tracked(SSE);
        check(stream(input, true).content.length() == 0 && input.closed && input.reads == 0, "Cancelled parser read or leaked response");
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
            Tracked input = new Tracked(partial + ending); LlmClient.Reply reply = stream(input, false);
            check(reply.error != null && !reply.hasToolCalls() && input.closed, "Truncated args accepted with ending " + ending);
        }
        Tracked trailing = new Tracked(TOOL.replace("fixture.txt\\\"}", "fixture.txt\\\"}garbage") + "data: [DONE]\n");
        check(!stream(trailing, false).hasToolCalls(), "JSON arguments with trailing data were accepted");
    }
    private static void completeEofCallsRemainCompatibleButTruncatedFinishCannotExecute() throws Exception {
        check(stream(new Tracked(TOOL), false).hasToolCalls(), "Provider closing complete calls without DONE was rejected");
        Tracked fragmented = new Tracked(TOOL.replace("fixture.txt", "fixture你好.txt") + "data: [DONE]\n");
        for (int i = 0; i < fragmented.body.length; i++) if ((fragmented.body[i] & 128) != 0) { fragmented.timeoutAt = i + 1; break; }
        LlmClient.Reply splitReply = stream(fragmented, false);
        check(splitReply.error == null && splitReply.hasToolCalls() && splitReply.toolCalls.toString().contains("你好"),
                "Timeout lost partial SSE/UTF-8 bytes: " + splitReply.error);
        Tracked failedTail = new Tracked(TOOL + FINISH); failedTail.failAtEof = true;
        check(stream(failedTail, false).hasToolCalls() && failedTail.closed, "Network failure in usage-only tail discarded a completed call");
        for (String finish : new String[]{"length", "content_filter"}) {
            LlmClient.Reply reply = stream(new Tracked(TOOL + FINISH.replace("tool_calls", finish) + "data: [DONE]\n"), false);
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
                check(reply.error != null && reply.error.startsWith("响应头等待超时：SocketTimeoutException:")
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
                check(reply.error != null && ("HTTP " + status + ":").equals(reply.error.trim()),
                        "Error-body timeout discarded HTTP status: " + reply.error);
                check(!reply.hasToolCalls() && reply.content.length() == 0 && server.requests.get() == 1,
                        "Failed error body produced model output or an optional-parameter retry");
                // JDK streaming POST exposes no 401 error stream; 403/503 exercise the timed-out body read.
                check((status == 401 || elapsed >= 700) && elapsed < 2500,
                        "Error-body fixture did not fail within its configured read timeout: " + status + " " + elapsed);
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
                "failedErrorBodyReadPreservesTheReceivedHttpStatus"};
        for (String name : cases) { LlmStreamLifecycleRegressionTest.class.getDeclaredMethod(name).invoke(null); System.out.println("PASS " + name); }
        System.out.println(cases.length + " stream lifecycle tests passed");
    }
}
