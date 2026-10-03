import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.ApprovalGate;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.SubAgentManager;
import com.mkei.backcast.agent.Tool;
import com.mkei.backcast.agent.ToolRegistry;
import com.mkei.backcast.tool.SubAgentTools;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Genuine child stream, permission and request-retry boundaries remain independently observable. */
public final class SubAgentProgressRegressionTest {
    private static final String SCRIPT = "PRIVATE_TOOL_SCRIPT_SENTINEL";
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void await(CountDownLatch latch) throws Exception { check(latch.await(6, TimeUnit.SECONDS), "Progress fixture timed out"); }
    private static LlmClient.Reply text(String value) { LlmClient.Reply reply = new LlmClient.Reply(); reply.content = value; return reply; }
    private static LlmClient.Reply call(String name, JSONObject args) throws Exception {
        LlmClient.Reply reply = new LlmClient.Reply();
        reply.toolCalls = new JSONArray().put(new JSONObject().put("id", "call-" + name).put("type", "function")
                .put("function", new JSONObject().put("name", name).put("arguments", args.toString()))); return reply;
    }

    private static final class Store implements SubAgentManager.Store {
        final Map<String, SubAgentManager.Record> records = new LinkedHashMap<String, SubAgentManager.Record>();
        @Override public synchronized List<SubAgentManager.Record> load() throws Exception {
            List<SubAgentManager.Record> result = new ArrayList<SubAgentManager.Record>();
            for (SubAgentManager.Record record : records.values()) result.add(SubAgentManager.Record.fromJson(record.toJson())); return result;
        }
        @Override public synchronized void save(SubAgentManager.Record record) throws Exception {
            records.put(record.id, SubAgentManager.Record.fromJson(new JSONObject(record.toJson().toString())));
        }
        synchronized SubAgentManager.Record find(String id) throws Exception { return SubAgentManager.Record.fromJson(records.get(id).toJson()); }
    }

    private static final class Fixture implements SubAgentManager.Factory {
        final Store store = new Store(); final SubAgentManager manager; final AgentLoop root;
        final CountDownLatch preview = new CountDownLatch(1), releasePreview = new CountDownLatch(1), approving = new CountDownLatch(1),
                releaseApproval = new CountDownLatch(1), tool = new CountDownLatch(1), releaseTool = new CountDownLatch(1),
                nextRequest = new CountDownLatch(1), releaseRequest = new CountDownLatch(1), review = new CountDownLatch(1), releaseReview = new CountDownLatch(1);
        final AtomicInteger toolsExecuted = new AtomicInteger();
        volatile AgentLoop.Listener childListener;
        boolean retry, guarded, reviewMode, waitMode;
        String nestedId = "";
        Fixture() throws Exception {
            root = new AgentLoop(new LlmClient(new LlmClient.Config("http://fixture", "fixture", "fixture")), new ToolRegistry(), new AgentLoop.Quiet());
            root.bindSession(1); root.reset("trusted root"); manager = new SubAgentManager(2, this, store); manager.attachRoot(root); root.setAutomaticDelegation(true);
        }
        @Override public AgentLoop create(final SubAgentManager.Record task, AgentLoop.Listener listener, final SubAgentManager shared) {
            childListener = listener;
            LlmClient client = new LlmClient(new LlmClient.Config("http://fixture", "fixture", "fixture")) {
                int requests;
                @Override public Reply send(List<Message> history, JSONArray schema, Sink sink) {
                    try {
                        if (reviewMode && schema == null) { review.countDown(); await(releaseReview); return text("SAFE"); }
                        int request = ++requests;
                        if ("nested".equals(task.name)) { nextRequest.countDown(); await(releaseRequest); return text("nested complete"); }
                        if (retry) {
                            if (request == 1) { Reply failure = new Reply(); failure.error = "HTTP 503 provider echoed " + SCRIPT; return failure; }
                            nextRequest.countDown(); await(releaseRequest); return text("request recovered");
                        }
                        if (waitMode) {
                            if (request == 1) return call("spawn_agent", new JSONObject().put("name", "nested").put("task", "hold nested work").put("fork", false));
                            if (request == 2) {
                                await(nextRequest);
                                for (SubAgentManager.Record record : shared.records()) if (task.id.equals(record.parentId)) nestedId = record.id;
                                return call("wait_agent", new JSONObject().put("target", nestedId).put("timeout_ms", 5000));
                            }
                            return text("wait completed");
                        }
                        if (request > 1) return text("tool completed");
                        JSONObject args = new JSONObject().put("command", SCRIPT);
                        sink.onToolCall(0, "call-probe", "probe", args.toString()); preview.countDown(); await(releasePreview); return call("probe", args);
                    } catch (Exception error) { throw new IllegalStateException(error); }
                }
            };
            ToolRegistry registry = new ToolRegistry(); SubAgentTools.register(registry, shared, task.id);
            registry.register(new Tool() {
                public String name() { return "probe"; } public String description() { return "Inspect fixture"; }
                public JSONObject parameters() { return new JSONObject(); }
                public String run(JSONObject args) throws Exception { toolsExecuted.incrementAndGet(); tool.countDown(); await(releaseTool); return "evidence complete"; }
                public void abort() { releaseTool.countDown(); }
            });
            AgentLoop child = new AgentLoop(client, registry, listener); child.reset("trusted child");
            if (guarded || reviewMode) {
                child.setAccessLevel(reviewMode ? ApprovalGate.ACCESS_GUARDED : ApprovalGate.ACCESS_STRICT);
                child.setApprovalGate(new ApprovalGate() { public boolean approve(String name, JSONObject args) {
                    approving.countDown(); try { await(releaseApproval); return true; } catch (Exception error) { return false; }
                } });
            }
            return child;
        }
        String spawn(String name) throws Exception { return manager.spawn("main", name, "inspect evidence", false).getString("id"); }
        void finish() throws Exception { manager.waitFor("main", null, 6000); check(!manager.hasPendingWork(), "Child did not finish"); }
        void release() { releasePreview.countDown(); releaseApproval.countDown(); releaseTool.countDown(); releaseRequest.countDown(); releaseReview.countDown(); }
    }

    private static void previewApprovalAndExecutionRemainSeparateAndDoNotExposeScripts() throws Exception {
        Fixture f = new Fixture(); f.guarded = true; String id = f.spawn("stages");
        try {
            await(f.preview); SubAgentManager.Record preview = f.manager.find(id);
            check("generating_tool".equals(preview.phase) && "probe".equals(preview.activeTool) && f.toolsExecuted.get() == 0,
                    "Tool generation was shown as execution");
            check(!preview.progress.contains(SCRIPT), "Tool script appeared in compact progress");
            f.releasePreview.countDown(); await(f.approving); SubAgentManager.Record approval = f.manager.find(id);
            check("tool_approval".equals(approval.phase) && f.toolsExecuted.get() == 0 && !approval.progress.contains(SCRIPT),
                    "Waiting for permission was shown as running a tool or exposed arguments");
            f.releaseApproval.countDown(); await(f.tool); SubAgentManager.Record executing = f.manager.find(id);
            check("tool".equals(executing.phase) && f.toolsExecuted.get() == 1, "Permission-approved tool did not move into execution");
            JSONObject view = f.manager.list("main").getJSONArray("agents").getJSONObject(0);
            check("tool".equals(view.getString("phase")) && view.getInt("retryAttempt") == 0
                    && !view.getString("progress").contains(SCRIPT), "Parent sees different or unbounded child stage data");
            check(f.manager.find("main").retryAttempt == 0, "Child stage changed parent's retry status");
        } finally { f.release(); f.finish(); }
    }

    private static void guardedReviewIsNotReportedAsToolExecution() throws Exception {
        Fixture f = new Fixture(); f.reviewMode = true; String id = f.spawn("review");
        try {
            await(f.preview); f.releasePreview.countDown(); await(f.review);
            SubAgentManager.Record record = f.manager.find(id);
            check("tool_review".equals(record.phase) && f.toolsExecuted.get() == 0 && !record.progress.contains(SCRIPT),
                    "AI tool review was mislabeled as execution or exposed arguments");
        } finally { f.release(); f.finish(); }
    }

    private static void genuineProviderRetryPersistsCountReasonWithoutAffectingParent() throws Exception {
        Fixture f = new Fixture(); f.retry = true; String id = f.spawn("retry");
        try {
            await(f.nextRequest); SubAgentManager.Record record = f.manager.find(id);
            check(record.retryAttempt == 1 && "接口返回 HTTP 503".equals(record.retryReason), "Actual provider retry has no accurate count or cause");
            check(!record.retryReason.contains(SCRIPT) && f.manager.find("main").retryAttempt == 0, "Provider echo leaked or child retry was attributed to parent");
            SubAgentManager.Record saved = f.store.find(id);
            check(saved.retryAttempt == 1 && saved.retryReason.equals(record.retryReason), "Retry evidence was not persisted during request");
            JSONObject view = f.manager.list("main").getJSONArray("agents").getJSONObject(0);
            check(view.getInt("retryAttempt") == 1 && view.getString("retryReason").equals(record.retryReason), "Parent cannot inspect the child's request retry");
        } finally { f.release(); f.finish(); }
        check(f.manager.find(id).retryAttempt == 1, "Completion erased useful retry evidence");
    }

    private static void childWaitHasADistinctWaitingStage() throws Exception {
        Fixture f = new Fixture(); f.waitMode = true;
        try {
            String id = f.spawn("waiter"); await(f.nextRequest);
            long deadline = System.currentTimeMillis() + 3000L;
            while (!SubAgentManager.WAITING.equals(f.manager.find(id).status) && System.currentTimeMillis() < deadline) Thread.sleep(10L);
            SubAgentManager.Record record = f.manager.find(id);
            check(SubAgentManager.WAITING.equals(record.status) && "waiting".equals(record.phase)
                    && f.manager.find(f.nestedId).status.equals(SubAgentManager.RUNNING), "Real child wait was reported as ordinary active execution");
        } finally { f.release(); f.finish(); }
    }

    private static void legacySavedRecordsLoadWithZeroRetriesAndNewFieldsRoundTrip() throws Exception {
        JSONObject old = new JSONObject().put("id", "legacy").put("parentId", "main").put("status", "idle").put("phase", "completed");
        SubAgentManager.Record record = SubAgentManager.Record.fromJson(old);
        check(record.retryAttempt == 0 && record.retryReason.length() == 0, "Legacy records invented retries");
        record.retryAttempt = 3; record.retryReason = "等待模型响应超时";
        SubAgentManager.Record restored = SubAgentManager.Record.fromJson(record.toJson());
        check(restored.retryAttempt == 3 && restored.retryReason.equals(record.retryReason), "Retry fields disappeared from saved record copies");
    }

    public static void main(String[] args) throws Exception {
        int passed = 0;
        for (String test : new String[]{"previewApprovalAndExecutionRemainSeparateAndDoNotExposeScripts", "guardedReviewIsNotReportedAsToolExecution",
                "genuineProviderRetryPersistsCountReasonWithoutAffectingParent", "childWaitHasADistinctWaitingStage", "legacySavedRecordsLoadWithZeroRetriesAndNewFieldsRoundTrip"}) {
            SubAgentProgressRegressionTest.class.getDeclaredMethod(test).invoke(null); System.out.println("PASS " + test); passed++;
        }
        System.out.println(passed + " child progress tests passed");
    }
}
