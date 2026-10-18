import android.os.SystemClock;
import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.ApprovalGate;
import com.mkei.backcast.agent.Compactor;
import com.mkei.backcast.agent.Goal;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.Tool;
import com.mkei.backcast.agent.ToolRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 网络抖动先原样重发几次；重发用完或不该重发的失败才停下，保留已完成的工作和安全的诊断。 */
public final class RequestPolicyRegressionTest {
    private static int passed;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static LlmClient.Reply reply(String text, String error) {
        LlmClient.Reply result = new LlmClient.Reply(); result.content = text; result.error = error; return result;
    }
    private static final class Record implements AgentLoop.Recorder, AgentLoop.DetailedRequestRecorder {
        boolean failDiagnostics;
        final List<Message> messages = new ArrayList<Message>();
        final List<String> diagnostics = new ArrayList<String>();
        final List<Long> elapsed = new ArrayList<Long>();
        final List<String> details = new ArrayList<String>();
        @Override public void record(long sid, Message value) { messages.add(value); }
        @Override public void replace(long sid, List<Message> values) { }
        @Override public void recordRequest(long sid, String purpose, long ms, String outcome, String reason, int count, String detail) {
            if (failDiagnostics) throw new IllegalStateException("diagnostic database unavailable");
            diagnostics.add(sid + ":" + purpose + ":" + outcome + ":" + count + ":" + reason);
            elapsed.add(Long.valueOf(ms));
            details.add(detail);
        }
    }
    private static final class Fixture {
        final Record records = new Record();
        final ToolRegistry tools = new ToolRegistry();
        final List<String> errors = new ArrayList<String>();
        final List<LlmClient.Reply> responses = new ArrayList<LlmClient.Reply>();
        final AgentLoop loop;
        int calls, executions;
        final List<String> phases = new ArrayList<String>();
        boolean cancelDuringRequest, queueResumeDuringRequest, streamBeforeFailure, cancelOnRetry;
        Fixture(LlmClient.Reply... script) {
            responses.addAll(Arrays.asList(script));
            LlmClient client = new LlmClient(new LlmClient.Config("http://localhost", "fixture", "fixture")) {
                @Override public Reply send(List<Message> messages, JSONArray schema, Sink sink) {
                    calls++; check(calls <= responses.size(), "Unbounded or unexpected request: " + calls);
                    SystemClock.advance(37L);
                    if (cancelDuringRequest) loop.cancel();
                    if (queueResumeDuringRequest) {
                        Thread queued = new Thread(new Runnable() {
                            @Override public void run() { loop.resume(7L, 9); }
                        });
                        queued.start();
                        try { queued.join(2000L); } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                        }
                        check(!queued.isAlive(), "Queued resume blocked instead of handing off to the active turn");
                    }
                    LlmClient.Reply next = responses.get(calls - 1);
                    if (streamBeforeFailure && next.error != null && sink != null) sink.onContent("partial");
                    return next;
                }
            };
            loop = new AgentLoop(client, tools, new AgentLoop.Quiet() {
                @Override public void onError(int gen, String error) { errors.add(error); }

                @Override public void onProgress(int gen, String phase, String name, String detail) {
                    phases.add(phase);
                    if (cancelOnRetry && "retrying".equals(phase)) loop.cancel();
                }
            });
            loop.bindSession(7L); loop.reset("fixture"); loop.setRecorder(records);
            loop.setRequestRetryDelays(1L, 1L, 1L, 1L);
            tools.register(new Tool() {
                @Override public String name() { return "probe"; }
                @Override public String description() { return "fixture"; }
                @Override public JSONObject parameters() { return new JSONObject(); }
                @Override public String run(JSONObject args) { executions++; return "verified"; }
                @Override public void abort() { }
            });
        }
        void run() { loop.submit("inspect fixture", 7L, loop.generation(), 1, null); }
    }
    private static void permanentErrorsStopGoalsImmediately() {
        for (String error : new String[]{"HTTP 401: bad-secret context_length_exceeded", "HTTP 403: context window denied", "HTTP 401 context window denied",
                "HTTP 400: invalid model; connection reset HTTP 503",
                "模型工具调用参数不完整或无效，未执行。"}) {
            Fixture f = new Fixture(reply("", error)); f.loop.setGoal("inspect fixture"); f.run();
            check(f.calls == 1 && f.errors.size() == 1, "Permanent error retried");
            check(Goal.ACTIVE.equals(f.loop.goalStatus()) && !f.loop.busy(), "Failed goal was completed or restarted");
            check(!f.records.diagnostics.toString().contains("bad-secret"), "Provider text entered diagnostics");
        }
        pass("permanentErrorsStopGoalsImmediately");
    }
    private static final String[] TRANSIENT = {
            "HTTP 408: echoed-secret", "HTTP 429: echoed-secret", "HTTP 500: echoed-secret", "HTTP 502: echoed-secret",
            "HTTP 503: echoed-secret", "HTTP 504: echoed-secret", "SocketTimeoutException: echoed-secret",
            "SocketException: connection reset echoed-secret", "UnknownHostException: echoed-secret",
            "network is unreachable echoed-secret", "SSLHandshakeException: echoed-secret", "EOFException: echoed-secret"};
    private static LlmClient.Reply transportFailure(String cause) throws Exception {
        LlmClient.Reply error = reply("", cause);
        error.userMessage = "网络可访问，但AI服务器连接失败";
        error.diagnostic = new JSONObject().put("provider", "deepseek").put("stage", "transport")
                .put("api_key", "secret-key").put("messages", "private request");
        return error;
    }
    /** 握手失败、连接被掐、408/429/5xx：等一会儿原样重发，下一次成功就接着跑，不打扰用户。 */
    private static void networkFailuresRetryAndRecover() throws Exception {
        for (String cause : TRANSIENT) {
            Fixture f = new Fixture(transportFailure(cause), transportFailure(cause), reply("recovered", null)); f.run();
            check(f.calls == 3 && f.errors.isEmpty() && !f.loop.busy() && f.phases.contains("retrying"),
                    "Transient failure was not retried until it recovered: " + cause + " calls=" + f.calls + " " + f.errors);
            check(f.records.diagnostics.size() == 3 && f.records.diagnostics.get(0).contains(":retryable_error:0:")
                            && f.records.diagnostics.get(1).contains(":retryable_error:1:")
                            && f.records.diagnostics.get(2).contains(":success:2:")
                            && "recovered".equals(f.records.messages.get(f.records.messages.size() - 1).content),
                    "Retried attempts were not recorded separately or the recovered answer was lost: " + f.records.diagnostics);
            JSONObject saved = new JSONObject(f.records.details.get(0));
            check("deepseek".equals(saved.getString("provider")) && "transport".equals(saved.getString("stage"))
                            && !saved.toString().contains("secret-key") && !saved.toString().contains("private request"),
                    "Detailed local diagnostic lost provider/stage or retained confidential request data");
        }
        // 只靠 LlmClient 的异常分类（文字认不出）也要重发。
        LlmClient.Reply flagged = reply("", "IOException: proxy closed"); flagged.retryable = true;
        Fixture f = new Fixture(flagged, reply("recovered", null)); f.run();
        check(f.calls == 2 && f.errors.isEmpty(), "A failure classified retryable by the client was not retried");
        pass("networkFailuresRetryAndRecover");
    }
    /** 重发 4 次都失败才停下：只报一次错，目标不被关掉，发送键可以续跑。 */
    private static void exhaustedRetriesStopOnce() throws Exception {
        for (boolean goal : new boolean[]{false, true}) {
            LlmClient.Reply error = transportFailure("SSLHandshakeException: echoed-secret");
            Fixture f = new Fixture(error, error, error, error, error, reply("must not be requested", null));
            if (goal) { f.loop.setGoal("inspect fixture"); f.queueResumeDuringRequest = true; }
            f.run();
            check(f.calls == 5 && f.errors.size() == 1 && !f.loop.busy(),
                    "Exhausted retries did not stop after the fifth attempt: calls=" + f.calls + " " + f.errors);
            check(f.errors.get(0).equals(error.userMessage) && !f.errors.get(0).contains("echoed-secret"),
                    "Terminal notification lost the short transport classification or exposed provider data");
            check(f.records.diagnostics.size() == 5 && f.records.diagnostics.get(4).contains(":retryable_error:4:")
                            && f.records.messages.size() == 1,
                    "Failed attempts were not all recorded or a failed assistant answer was persisted");
            check(f.loop.interrupted() && f.loop.interruptedTransiently(), "Exhausted network retry did not leave a resumable interruption");
            if (goal) check(Goal.ACTIVE.equals(f.loop.goalStatus()), "Request failure falsely completed or blocked an unfinished goal");
        }
        pass("exhaustedRetriesStopOnce");
    }
    /** 已经吐过字的请求断了不重发（界面上已有半截内容）；停止会立刻打断重试等待。 */
    private static void streamedFailuresAndStopsAreNotRetried() throws Exception {
        LlmClient.Reply error = transportFailure("SocketException: connection reset");
        Fixture streamed = new Fixture(error, reply("must not be requested", null));
        streamed.streamBeforeFailure = true; streamed.run();
        check(streamed.calls == 1 && streamed.errors.size() == 1 && !streamed.phases.contains("retrying"),
                "A request that already streamed output was sent again");
        Fixture stopped = new Fixture(error, reply("must not be requested", null));
        stopped.loop.setRequestRetryDelays(60000L); stopped.cancelOnRetry = true;
        long started = System.nanoTime();
        stopped.run();
        check(stopped.calls == 1 && System.nanoTime() - started < 5_000_000_000L && !stopped.loop.busy(),
                "Stop did not interrupt the retry wait");
        pass("streamedFailuresAndStopsAreNotRetried");
    }
    private static void failedFollowupPreservesWorkUntilExplicitResume() throws Exception {
        LlmClient.Reply call = reply("", null);
        call.toolCalls = new JSONArray().put(new JSONObject().put("id", "fixture-call").put("type", "function")
                .put("function", new JSONObject().put("name", "probe").put("arguments", "{}")));
        Fixture f = new Fixture(call, reply("", "SocketException: connection reset"), reply("done", null));
        f.loop.setRequestRetryDelays(); f.run();
        check(f.calls == 2 && f.executions == 1 && f.errors.size() == 1,
                "Failed followup was automatically retried or repeated completed work");
        check(f.records.diagnostics.get(0).contains(":success:0:") && f.records.diagnostics.get(1).contains(":retryable_error:0:")
                        && Message.TOOL.equals(f.loop.historySnapshot().get(f.loop.historySnapshot().size() - 1).role)
                        && "verified".equals(f.loop.historySnapshot().get(f.loop.historySnapshot().size() - 1).content),
                "Completed tool history was discarded when the next request failed");
        f.loop.resume(7L, 2);
        check(f.calls == 3 && f.executions == 1 && f.records.diagnostics.get(2).contains(":success:0:")
                        && "done".equals(f.loop.historySnapshot().get(f.loop.historySnapshot().size() - 1).content),
                "Explicit user resume failed or re-executed an already completed tool");
        pass("failedFollowupPreservesWorkUntilExplicitResume");
    }
    private static void compactionOutagesStopWithoutChangingTheWindow() {
        LlmClient.Reply error = reply("", "SocketTimeoutException: fixture");
        Fixture f = new Fixture(error, error, error, error, error, reply("must not be requested", null));
        f.loop.loadHistory("fixture", Arrays.asList(Message.user("previous"), Message.assistant("answer", null)));
        f.loop.compactNow(7L, f.loop.generation(), 1);
        check(f.calls == 5 && f.errors.size() == 1 && f.phases.contains("retrying") && !f.loop.busy(),
                "Compaction outage was not retried and then stopped: " + f.calls);
        for (String value : f.records.diagnostics) check(value.contains(":compact:retryable_error:"), "Compaction misclassified");
        check(f.loop.historySnapshot().get(f.loop.historySnapshot().size() - 1).content.equals("answer"), "Failed compression destroyed window");
        Fixture automatic = new Fixture(error, error, error, error, error, reply("must not be requested", null));
        automatic.loop.setGoal("inspect fixture"); automatic.loop.setContextBudget(1, .9f);
        automatic.loop.loadHistory("fixture", Arrays.asList(Message.user("previous"), Message.assistant("answer", null)));
        automatic.run();
        check(automatic.calls == 5 && automatic.errors.size() == 1 && !automatic.loop.busy()
                        && automatic.records.diagnostics.get(0).contains(":compact:retryable_error:0:")
                        && Goal.ACTIVE.equals(automatic.loop.goalStatus()) && automatic.loop.historySnapshot().get(1).content.equals("previous"),
                "Automatic compaction outage was not bounded, sent a generation request, lost the old window or closed the goal");
        pass("compactionOutagesStopWithoutChangingTheWindow");
    }
    private static void cancelledRequestsLeaveNoSuccessfulResult() {
        Fixture f = new Fixture(reply("should be discarded", null)); f.cancelDuringRequest = true; f.run();
        check(f.records.diagnostics.size() == 1 && f.records.diagnostics.get(0).equals("7:model:cancelled:0:"), "Cancellation missing");
        check(f.errors.isEmpty() && f.records.messages.size() == 1, "Cancelled request persisted answer or error");
        pass("cancelledRequestsLeaveNoSuccessfulResult");
    }
    private static void reviewRequestsHaveSeparateDiagnostics() throws Exception {
        LlmClient.Reply call = reply("", null);
        call.toolCalls = new JSONArray().put(new JSONObject().put("id", "fixture-call").put("type", "function")
                .put("function", new JSONObject().put("name", "probe").put("arguments", "{}")));
        Fixture f = new Fixture(call, reply("SAFE", null), reply("done", null));
        f.loop.setAccessLevel(ApprovalGate.ACCESS_GUARDED);
        f.loop.setApprovalGate(new ApprovalGate() {
            @Override public boolean approve(String name, JSONObject args) { throw new AssertionError("SAFE requested approval"); }
        });
        f.run();
        check(f.executions == 1 && f.records.diagnostics.get(1).equals("7:review:success:0:"), "Review timing hidden as tool execution");
        pass("reviewRequestsHaveSeparateDiagnostics");
    }
    private static void reviewFailuresStopBeforeApprovalOrExecution() throws Exception {
        for (String cause : new String[]{"HTTP 503: provider secret", "SocketTimeoutException: provider secret", "HTTP 401: provider secret"}) {
            LlmClient.Reply call = reply("", null);
            call.toolCalls = new JSONArray().put(new JSONObject().put("id", "fixture-call").put("type", "function")
                    .put("function", new JSONObject().put("name", "probe").put("arguments", "{}")));
            LlmClient.Reply failure = reply("", cause); failure.userMessage = "AI服务器连接失败";
            Fixture f = new Fixture(call, failure, reply("must not be requested", null));
            f.loop.setGoal("inspect fixture");
            f.loop.setAccessLevel(ApprovalGate.ACCESS_GUARDED);
            f.loop.setApprovalGate(new ApprovalGate() {
                @Override public boolean approve(String name, JSONObject args) { throw new AssertionError("Failed API review fell through to approval"); }
            });
            f.run();
            check(f.calls == 2 && f.executions == 0 && f.errors.equals(Arrays.asList(failure.userMessage))
                            && !f.phases.contains("tool_approval") && !f.phases.contains("retry") && !f.loop.busy()
                            && Goal.ACTIVE.equals(f.loop.goalStatus()),
                    "Review failure triggered another API call, weaker authorization, tool execution or a completed goal");
            check(f.records.diagnostics.size() == 2 && f.records.diagnostics.get(1).contains(":review:")
                            && Message.TOOL.equals(f.loop.historySnapshot().get(f.loop.historySnapshot().size() - 1).role)
                            && "fixture-call".equals(f.loop.historySnapshot().get(f.loop.historySnapshot().size() - 1).toolCallId)
                            && !f.loop.historySnapshot().get(f.loop.historySnapshot().size() - 1).content.contains("provider secret"),
                    "Stopped review lost its diagnostic or left an unpaired tool call for the next explicit turn");
        }
        pass("reviewFailuresStopBeforeApprovalOrExecution");
    }
    private static void contextOverflowStopsModelAndShrinksCompaction() {
        Fixture failed = new Fixture(reply("", "HTTP 400: context_length_exceeded"), reply("must not be requested", null));
        failed.loop.setGoal("inspect fixture");
        failed.loop.loadHistory("fixture", Arrays.asList(Message.user("previous"), Message.assistant("answer", null)));
        failed.run();
        check(failed.calls == 1 && failed.errors.size() == 1 && !failed.phases.contains("retry")
                        && Goal.ACTIVE.equals(failed.loop.goalStatus()) && !failed.loop.busy()
                        && failed.loop.historySnapshot().get(1).content.equals("previous")
                        && failed.records.diagnostics.size() == 1
                        && failed.records.diagnostics.get(0).contains(":model:error:0:"),
                "Context error issued an automatic repair request or replaced the uncompressed window");
        // 摘要请求超窗时缩小请求再发一次，新窗口仍保留完整历史里的用户要求。
        Fixture shrunk = new Fixture(reply("", "HTTP 400: context_length_exceeded"), reply("shrunk checkpoint", null));
        shrunk.loop.loadHistory("fixture", Arrays.asList(Message.user("previous"), Message.assistant("answer", null)));
        shrunk.loop.compactNow(7L, shrunk.loop.generation(), 1);
        List<Message> window = shrunk.loop.historySnapshot();
        check(shrunk.calls == 2 && shrunk.errors.isEmpty() && !shrunk.loop.busy()
                        && "previous".equals(window.get(1).content)
                        && Compactor.isSummary(window.get(window.size() - 1))
                        && window.get(window.size() - 1).content.contains("shrunk checkpoint")
                        && shrunk.records.diagnostics.size() == 2
                        && shrunk.records.diagnostics.get(0).contains(":compact:error:0:"),
                "Compaction overflow was not retried with a smaller request");
        pass("contextOverflowStopsModelAndShrinksCompaction");
    }
    private static void diagnosticStorageFailureDoesNotDiscardAResponse() {
        Fixture f = new Fixture(reply("verified answer", null)); f.records.failDiagnostics = true; f.run();
        check(f.calls == 1 && f.errors.isEmpty() && f.records.messages.size() == 2
                && "verified answer".equals(f.records.messages.get(1).content), "Optional diagnostics discarded a valid answer");
        pass("diagnosticStorageFailureDoesNotDiscardAResponse");
    }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }
    public static void main(String[] args) throws Exception {
        permanentErrorsStopGoalsImmediately(); networkFailuresRetryAndRecover(); exhaustedRetriesStopOnce();
        streamedFailuresAndStopsAreNotRetried(); failedFollowupPreservesWorkUntilExplicitResume();
        compactionOutagesStopWithoutChangingTheWindow(); cancelledRequestsLeaveNoSuccessfulResult(); reviewRequestsHaveSeparateDiagnostics();
        reviewFailuresStopBeforeApprovalOrExecution(); contextOverflowStopsModelAndShrinksCompaction();
        diagnosticStorageFailureDoesNotDiscardAResponse();
        System.out.println("Passed " + passed + " request policy regressions");
    }
}
