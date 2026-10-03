import android.os.SystemClock;
import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.ApprovalGate;
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

/** Actual request failures must stop, preserve completed work, and leave bounded safe diagnostics. */
public final class RequestPolicyRegressionTest {
    private static int passed;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static LlmClient.Reply reply(String text, String error) {
        LlmClient.Reply result = new LlmClient.Reply(); result.content = text; result.error = error; return result;
    }
    private static final class Record implements AgentLoop.Recorder, AgentLoop.RequestRecorder {
        boolean failDiagnostics;
        final List<Message> messages = new ArrayList<Message>();
        final List<String> diagnostics = new ArrayList<String>();
        final List<Long> elapsed = new ArrayList<Long>();
        @Override public void record(long sid, Message value) { messages.add(value); }
        @Override public void replace(long sid, List<Message> values) { }
        @Override public void recordRequest(long sid, String purpose, long ms, String outcome, String reason, int count) {
            if (failDiagnostics) throw new IllegalStateException("diagnostic database unavailable");
            diagnostics.add(sid + ":" + purpose + ":" + outcome + ":" + count + ":" + reason);
            elapsed.add(Long.valueOf(ms));
        }
    }
    private static final class Fixture {
        final Record records = new Record();
        final ToolRegistry tools = new ToolRegistry();
        final List<String> errors = new ArrayList<String>();
        final List<LlmClient.Reply> responses = new ArrayList<LlmClient.Reply>();
        final AgentLoop loop;
        int calls, executions, retries;
        boolean cancelDuringRequest;
        Fixture(LlmClient.Reply... script) {
            responses.addAll(Arrays.asList(script));
            LlmClient client = new LlmClient(new LlmClient.Config("http://localhost", "fixture", "fixture")) {
                @Override public Reply send(List<Message> messages, JSONArray schema, Sink sink) {
                    calls++; check(calls <= responses.size(), "Unbounded or unexpected request: " + calls);
                    SystemClock.advance(37L);
                    if (cancelDuringRequest) loop.cancel();
                    return responses.get(calls - 1);
                }
            };
            loop = new AgentLoop(client, tools, new AgentLoop.Quiet() {
                @Override public void onError(int gen, String error) { errors.add(error); }
                @Override public void onRetry(int gen) { retries++; }
            });
            loop.bindSession(7L); loop.reset("fixture"); loop.setRecorder(records);
            tools.register(new Tool() {
                @Override public String name() { return "probe"; }
                @Override public String description() { return "fixture"; }
                @Override public JSONObject parameters() { return new JSONObject(); }
                @Override public String run(JSONObject args) { executions++; return "verified"; }
                @Override public void abort() { }
            });
        }
        void run() { loop.submit("inspect fixture", 7L, loop.generation(), 1); }
    }
    private static void permanentErrorsStopGoalsImmediately() {
        for (String error : new String[]{"HTTP 401: bad-secret context_length_exceeded", "HTTP 403: context window denied", "HTTP 401 context window denied",
                "HTTP 400: invalid model; connection reset HTTP 503",
                "模型工具调用参数不完整或无效，未执行。"}) {
            Fixture f = new Fixture(reply("", error)); f.loop.setGoal("inspect fixture"); f.run();
            check(f.calls == 1 && f.retries == 0 && f.errors.size() == 1, "Permanent error retried");
            check(Goal.ACTIVE.equals(f.loop.goalStatus()) && !f.loop.busy(), "Failed goal was completed or restarted");
            check(!f.records.diagnostics.toString().contains("bad-secret"), "Provider text entered diagnostics");
        }
        pass("permanentErrorsStopGoalsImmediately");
    }
    private static void networkFailuresAreBounded() {
        LlmClient.Reply error = reply("", "HTTP 503: echoed-secret");
        Fixture f = new Fixture(error, error, error); f.run();
        check(f.calls == 3 && f.retries == 2 && f.errors.size() == 1, "Network retries never stopped");
        check(f.errors.get(0).contains("重试 2 次") && !f.errors.get(0).contains("echoed-secret"), "Unsafe or vague terminal error");
        check(f.records.diagnostics.size() == 3 && f.records.diagnostics.get(2).contains(":2:"), "Failed attempts were lost");
        for (Long elapsed : f.records.elapsed) check(elapsed.longValue() == 37L, "Request time includes backoff or tools");
        pass("networkFailuresAreBounded");
    }
    private static void followupRetriesNeverRepeatExecutedTools() throws Exception {
        LlmClient.Reply call = reply("", null);
        call.toolCalls = new JSONArray().put(new JSONObject().put("id", "fixture-call").put("type", "function")
                .put("function", new JSONObject().put("name", "probe").put("arguments", "{}")));
        Fixture f = new Fixture(call, reply("", "SocketException: connection reset"), reply("done", null)); f.run();
        check(f.calls == 3 && f.executions == 1 && f.retries == 1, "Retry repeated completed work");
        check(f.records.diagnostics.get(0).contains(":success:0:") && f.records.diagnostics.get(2).contains(":success:1:"),
                "Normal followup or retry diagnostics wrong");
        pass("followupRetriesNeverRepeatExecutedTools");
    }
    private static void compactionOutagesAreBounded() {
        LlmClient.Reply error = reply("", "SocketTimeoutException: fixture");
        Fixture f = new Fixture(error, error, error);
        f.loop.loadHistory("fixture", Arrays.asList(Message.user("previous"), Message.assistant("answer", null)));
        f.loop.compactNow(7L, f.loop.generation(), 1);
        check(f.calls == 3 && f.errors.size() == 1, "Compaction retried forever");
        for (String value : f.records.diagnostics) check(value.contains(":compact:retryable_error:"), "Compaction misclassified");
        check(f.loop.history().get(f.loop.history().size() - 1).content.equals("answer"), "Failed compression destroyed window");
        pass("compactionOutagesAreBounded");
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
    private static void diagnosticStorageFailureDoesNotDiscardAResponse() {
        Fixture f = new Fixture(reply("verified answer", null)); f.records.failDiagnostics = true; f.run();
        check(f.calls == 1 && f.errors.isEmpty() && f.records.messages.size() == 2
                && "verified answer".equals(f.records.messages.get(1).content), "Optional diagnostics discarded a valid answer");
        pass("diagnosticStorageFailureDoesNotDiscardAResponse");
    }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }
    public static void main(String[] args) throws Exception {
        permanentErrorsStopGoalsImmediately(); networkFailuresAreBounded(); followupRetriesNeverRepeatExecutedTools();
        compactionOutagesAreBounded(); cancelledRequestsLeaveNoSuccessfulResult(); reviewRequestsHaveSeparateDiagnostics();
        diagnosticStorageFailureDoesNotDiscardAResponse();
        System.out.println("Passed " + passed + " request policy regressions");
    }
}
