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

/** Genuine child stream, permission and request-failure boundaries remain independently observable. */
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
            for (SubAgentManager.Record record : records.values()) result.add(RecordFixtures.fromJson(record.toJson())); return result;
        }
        @Override public synchronized void save(SubAgentManager.Record record) throws Exception {
            records.put(record.id, RecordFixtures.fromJson(new JSONObject(record.toJson().toString())));
        }
        synchronized SubAgentManager.Record find(String id) throws Exception { return RecordFixtures.fromJson(records.get(id).toJson()); }
    }

    private static final class Fixture implements SubAgentManager.Factory {
        final Store store = new Store(); final SubAgentManager manager; final AgentLoop root;
        final CountDownLatch preview = new CountDownLatch(1), releasePreview = new CountDownLatch(1), approving = new CountDownLatch(1),
                releaseApproval = new CountDownLatch(1), tool = new CountDownLatch(1), releaseTool = new CountDownLatch(1),
                nextRequest = new CountDownLatch(1), releaseRequest = new CountDownLatch(1), review = new CountDownLatch(1), releaseReview = new CountDownLatch(1);
        final AtomicInteger toolsExecuted = new AtomicInteger();
        final AtomicInteger requestsIssued = new AtomicInteger();
        volatile AgentLoop.Listener childListener;
        boolean retry, guarded, reviewMode;
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
                        requestsIssued.incrementAndGet();
                        if ("nested".equals(task.name)) { nextRequest.countDown(); await(releaseRequest); return text("nested complete"); }
                        if (retry) {
                            if (request == 1) { Reply failure = new Reply(); failure.error = "HTTP 503 provider echoed " + SCRIPT; return failure; }
                            nextRequest.countDown(); await(releaseRequest); return text("request recovered");
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
            JSONObject view = f.manager.list("main", 0).getJSONArray("agents").getJSONObject(0);
            check("tool".equals(view.getString("phase"))
                    && !view.getString("progress").contains(SCRIPT), "Parent sees different or unbounded child stage data");
            check(f.manager.find("main").phase.equals(SubAgentManager.QUEUED), "Child stage changed parent's state");
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

    /**
     * 服务暂时不可用（503）：不立刻报失败，等待期间进度显示「重试」且不泄露服务端回显；
     * 重试那次请求恢复后接着完成同一个任务，不执行多余工具、不影响主代理。
     */
    private static void providerFailureRetriesAndRecovers() throws Exception {
        Fixture f = new Fixture(); f.retry = true; f.manager.setRetryDelays(10L, 10L, 10L); String id = f.spawn("retry");
        try {
            await(f.nextRequest);
            SubAgentManager.Record waiting = f.manager.find(id);
            check(SubAgentManager.RUNNING.equals(waiting.status) && waiting.error.isEmpty(),
                    "Transient provider failure was reported as a failure before retrying");
            check(!waiting.progress.contains(SCRIPT) && !waiting.toJson().toString().contains(SCRIPT),
                    "Retry progress leaked the provider echo");
            f.release(); f.finish();
            SubAgentManager.Record record = f.manager.find(id);
            check(f.requestsIssued.get() == 2 && f.toolsExecuted.get() == 0 && SubAgentManager.IDLE.equals(record.status)
                            && record.error.isEmpty() && record.result.contains("request recovered") && record.tasks.length() == 1,
                    "Retry did not recover the same task: " + record.status + " " + record.error + " " + f.requestsIssued.get());
            check(f.manager.find("main").error.isEmpty(), "Child retry changed the parent state");
            check(SubAgentManager.IDLE.equals(f.store.find(id).status), "Recovered state was not persisted");
        } finally { f.release(); f.finish(); }
    }

    private static void legacyRetryMetadataIsIgnoredAndLiveProgressRoundTrips() throws Exception {
        JSONObject old = new JSONObject().put("id", "legacy").put("parentId", "main").put("status", "idle").put("phase", "completed")
                .put("retryAttempt", 3).put("retryReason", "old retry text");
        SubAgentManager.Record record = RecordFixtures.fromJson(old);
        check(record.phase.equals("completed") && !record.toJson().has("retryAttempt") && !record.toJson().has("retryReason"),
                "Legacy retry metadata escaped into the current runtime record");
        record.phase = "tool"; record.activeTool = "read"; record.progress = "Inspecting task evidence";
        SubAgentManager.Record restored = RecordFixtures.fromJson(record.toJson());
        check(restored.phase.equals("tool") && restored.activeTool.equals("read") && restored.progress.equals(record.progress),
                "Live task progress disappeared from saved record copies");
    }

    public static void main(String[] args) throws Exception {
        int passed = 0;
        for (String test : new String[]{"previewApprovalAndExecutionRemainSeparateAndDoNotExposeScripts", "guardedReviewIsNotReportedAsToolExecution",
                "providerFailureRetriesAndRecovers", "legacyRetryMetadataIsIgnoredAndLiveProgressRoundTrips"}) {
            SubAgentProgressRegressionTest.class.getDeclaredMethod(test).invoke(null); System.out.println("PASS " + test); passed++;
        }
        System.out.println(passed + " child progress tests passed");
    }
}
