import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.ApprovalGate;
import com.mkei.backcast.agent.Goal;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.SubAgentManager;
import com.mkei.backcast.agent.Tool;
import com.mkei.backcast.agent.ToolRegistry;
import com.mkei.backcast.tool.GoalTool;
import com.mkei.backcast.tool.SubAgentTools;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real parent and child loops: final-answer handoff, budget, recovery, and approval ownership. */
public final class SubAgentLoopIntegrationTest {
    private interface Script {
        LlmClient.Reply next(Fixture f, List<Message> messages, JSONArray tools) throws Exception;
    }
    private static final class Client extends LlmClient {
        Fixture fixture;
        Script script;
        final AtomicInteger calls = new AtomicInteger();
        Client() { super(new Config("http://fixture", "fixture", "fixture")); }
        @Override public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
            int count = calls.incrementAndGet(); check(count <= 10, "Parent loop unexpectedly kept requesting");
            try {
                Reply reply = script.next(fixture, messages, tools);
                if (reply.content != null && reply.content.length() > 0) sink.onContent(reply.content);
                return reply;
            }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
    }
    private static final class Fixture implements SubAgentManager.Factory {
        final Client client = new Client();
        final ToolRegistry registry = new ToolRegistry();
        final AgentLoop root;
        final SubAgentManager manager;
        final List<String> errors = new ArrayList<String>();
        final List<String> visibleAnswers = new ArrayList<String>();
        final AtomicInteger childCalls = new AtomicInteger(), writes = new AtomicInteger(), writeAborts = new AtomicInteger();
        CountDownLatch childStarted, childRelease;
        CountDownLatch usageStarted, usageRelease;
        CountDownLatch writeStarted, writeRelease;
        long childTokens;
        boolean childAttemptsWrite;
        String childId;
        AgentLoop child;
        Fixture() throws Exception { this(null); }
        Fixture(SubAgentManager.Store store) throws Exception {
            root = new AgentLoop(client, registry, new AgentLoop.Quiet() {
                @Override public void onError(int gen, String error) { synchronized (errors) { errors.add(error); } }
                @Override public void onAssistantText(int gen, String text) { visibleAnswers.add(text); }
            });
            client.fixture = this; root.bindSession(1L); root.reset("fixture trusted policy");
            root.setAutomaticDelegation(true);
            manager = new SubAgentManager(1, this, store); manager.attachRoot(root); root.setSubAgents(manager);
            SubAgentTools.register(registry, manager, "main"); registry.register(new GoalTool(root));
        }
        @Override public AgentLoop create(final SubAgentManager.Record task, AgentLoop.Listener listener,
                final SubAgentManager shared) {
            LlmClient childClient = new LlmClient(new LlmClient.Config("http://fixture", "fixture", "fixture")) {
                @Override public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
                    int count = childCalls.incrementAndGet();
                    if (childStarted != null) childStarted.countDown();
                    try { if (childRelease != null && count == 1) await(childRelease); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                    Reply reply;
                    try { reply = childAttemptsWrite && count == 1 ? call("write", "{}") : text("child_verified_result"); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                    reply.promptTokens = childTokens;
                    return reply;
                }
            };
            ToolRegistry tools = new ToolRegistry(); SubAgentTools.register(tools, shared, task.id);
            tools.register(new Tool() {
                @Override public String name() { return "write"; }
                @Override public String description() { return "fixture write"; }
                @Override public JSONObject parameters() { return new JSONObject(); }
                @Override public String run(JSONObject args) {
                    writes.incrementAndGet();
                    if (writeStarted != null) writeStarted.countDown();
                    try { if (writeRelease != null) await(writeRelease); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                    return "written";
                }
                @Override public void abort() { if (writeRelease != null) { writeAborts.incrementAndGet(); writeRelease.countDown(); } }
            });
            child = new AgentLoop(childClient, tools, listener);
            child.reset("child trusted policy"); child.setAccessLevel(root.accessLevel()); child.setApprovalGate(root.approvalGate());
            child.setDelegationParent(root);
            child.setUsageObserver(new AgentLoop.UsageObserver() {
                @Override public void onUsage(long tokens) {
                    shared.accountUsage(task.id, tokens);
                    if (usageStarted != null) usageStarted.countDown();
                    try { if (usageRelease != null) await(usageRelease); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                    root.accountExternalUsage(tokens, shared.usageLease(task.id));
                }
            });
            return child;
        }
        String spawn() throws Exception {
            childId = manager.spawn("main", "review", "check independent evidence", false).getString("id");
            return childId;
        }
        void submit() { root.submit("finish the requested work", 1L, root.generation(), 1); }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void await(CountDownLatch latch) throws Exception { check(latch.await(5, TimeUnit.SECONDS), "Fixture latch timed out"); }
    private static LlmClient.Reply text(String text) { LlmClient.Reply reply = new LlmClient.Reply(); reply.content = text; return reply; }
    private static LlmClient.Reply call(String name, String arguments) throws Exception {
        LlmClient.Reply reply = new LlmClient.Reply();
        reply.toolCalls = new JSONArray().put(new JSONObject().put("id", "fixture-call")
                .put("type", "function").put("function", new JSONObject().put("name", name).put("arguments", arguments)));
        return reply;
    }
    private static int resultNotes(List<Message> messages) {
        int count = 0;
        for (Message message : messages) if (Goal.isSteer(message.content) && message.content.contains("child_verified_result")) count++;
        return count;
    }

    private static void prematureParentFinalWaitsAndReceivesExactlyOneChildResult() throws Exception {
        final Fixture f = new Fixture(); f.childStarted = new CountDownLatch(1); f.childRelease = new CountDownLatch(1);
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1) return call("spawn_agent", "{\"name\":\"review\",\"task\":\"check independently\",\"fork\":false}");
                if (f.client.calls.get() == 2) {
                    await(f.childStarted);
                    check(f.manager.hasPendingWork(), "Fixture child was already settled before premature answer");
                    f.childRelease.countDown(); return text("premature parent answer");
                }
                check(f.client.calls.get() == 3 && resultNotes(messages) == 1,
                        "Parent did not receive a unique child result before its final answer");
                check(!f.manager.hasPendingWork(), "Parent final was requested before child cleanup completed");
                return text("parent verified child result and finished");
            }
        };
        f.submit();
        check(f.errors.isEmpty() && f.client.calls.get() == 3 && f.childCalls.get() == 1 && !f.root.needsResume(),
                "Parent final handoff failed: " + f.errors);
        check(!f.manager.hasUncollectedResults(), "Parent final left a duplicate result delivery");
        check(f.visibleAnswers.equals(java.util.Arrays.asList("parent verified child result and finished")),
                "Parent streamed a final answer before delegated evidence: " + f.visibleAnswers);
        for (Message message : f.root.historySnapshot()) check(!message.content.contains("premature parent answer"),
                "A provisional parent final was retained as a delivered answer");
    }

    private static void parentRunsIndependentToolsWhileItsChildIsWorking() throws Exception {
        final Fixture f = new Fixture(); f.childStarted = new CountDownLatch(1); f.childRelease = new CountDownLatch(1);
        final AtomicInteger checks = new AtomicInteger();
        f.registry.register(new Tool() {
            @Override public String name() { return "parent_probe"; }
            @Override public String description() { return "Verify the parent's independent integration work"; }
            @Override public JSONObject parameters() { return new JSONObject(); }
            @Override public String run(JSONObject args) {
                check(f.manager.hasPendingWork() && f.childRelease.getCount() == 1,
                        "Parent integration waited until its child finished");
                checks.incrementAndGet(); return "parent_integration_evidence";
            }
            @Override public void abort() { }
        });
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1) return call("spawn_agent", "{\"name\":\"review\",\"task\":\"check independently\",\"fork\":false}");
                if (f.client.calls.get() == 2) {
                    await(f.childStarted);
                    LlmClient.Reply reply = call("parent_probe", "{}"); reply.content = "checking integration"; return reply;
                }
                if (f.client.calls.get() == 3) {
                    check(checks.get() == 1, "Parent did not execute its own tool");
                    f.childRelease.countDown(); return text("unverified provisional final");
                }
                check(f.client.calls.get() == 4 && resultNotes(messages) == 1 && !f.manager.hasPendingWork(),
                        "Parent did not wait for and collect its child's evidence");
                return text("parent and child evidence verified");
            }
        };
        try {
            f.submit();
            check(f.errors.isEmpty() && checks.get() == 1 && f.client.calls.get() == 4,
                    "Parallel parent integration failed: " + f.errors);
            check(f.visibleAnswers.equals(java.util.Arrays.asList("checking integration", "parent and child evidence verified")),
                    "Parent commentary or final ordering was wrong: " + f.visibleAnswers);
        } finally { f.childRelease.countDown(); f.root.cancel(); }
    }

    private static void goalCompletionRejectsPendingAndUncollectedChildResults() throws Exception {
        final Fixture f = new Fixture(); f.root.setGoal("verify delegation");
        f.childStarted = new CountDownLatch(1); f.childRelease = new CountDownLatch(1);
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1) {
                    f.spawn(); await(f.childStarted);
                    check(!f.root.closeGoal("complete", "").startsWith("{"), "Pending child did not block goal completion");
                    f.childRelease.countDown(); f.manager.waitFor("main", null, 5000L);
                    check(!f.root.closeGoal("complete", "").startsWith("{"), "Uncollected child result did not block goal completion");
                    return text("continuing after the result becomes available");
                }
                if (f.client.calls.get() == 2) {
                    check(resultNotes(messages) == 1, "Goal completion never received child evidence");
                    return call("update_goal", "{\"status\":\"complete\"}");
                }
                check(tools == null || tools.length() == 0, "Completed goal final re-exposed tools");
                return text("verified and complete");
            }
        };
        f.submit(); check(f.errors.isEmpty() && Goal.COMPLETE.equals(f.root.goalStatus()) && f.client.calls.get() == 3,
                "Delegated goal did not finish after collection: " + f.errors);
    }

    private static void childUsageExhaustsParentBudgetBeforeItsToolCanExecute() throws Exception {
        final Fixture f = new Fixture(); f.root.setGoal("limited delegated work"); f.root.setGoalBudget(100L);
        f.childTokens = 150L; f.childAttemptsWrite = true;
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1) {
                    f.spawn(); f.manager.waitFor("main", null, 5000L);
                    check(Goal.BUDGET_LIMITED.equals(f.root.goalStatus()) && f.writes.get() == 0,
                            "Child token exhaustion allowed a subsequent child tool");
                    return text("budget was exhausted by the child");
                }
                check(f.client.calls.get() == 2, "Budget-limited parent got more than one wrap-up request");
                boolean prompt = false;
                for (Message message : messages) if (Goal.isSteer(message.content)
                        && message.content.contains("预算已经用完")) prompt = true;
                check(prompt, "Child budget exhaustion did not reach the parent's wrap-up prompt");
                return text("work stopped at the budget limit");
            }
        };
        f.submit();
        check(f.errors.isEmpty() && f.client.calls.get() == 2 && f.root.goalTokensUsed() == 150L
                && f.writes.get() == 0 && !f.root.needsResume(), "Child budget handoff did not stop once: " + f.errors);
    }

    private static void endedGoalDoesNotChargeExternalUsage() throws Exception {
        final Fixture f = new Fixture(); f.root.setGoal("finish one request");
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1) { LlmClient.Reply reply = call("update_goal", "{\"status\":\"complete\"}"); reply.promptTokens = 20L; return reply; }
                return text("complete");
            }
        };
        f.submit(); f.root.accountExternalUsage(200L, f.root.goalUsageLease());
        check(f.root.goalTokensUsed() == 20L && Goal.COMPLETE.equals(f.root.goalStatus()), "External usage changed an ended goal's ledger");
    }

    private static void parentCancellationInterruptsChildWaitAndKeepsIdleContextReusable() throws Exception {
        final Fixture f = new Fixture(); String idle = f.spawn(); f.manager.waitFor("main", null, 5000L);
        f.manager.collectResults(); f.manager.acknowledgeResults();
        f.childStarted = new CountDownLatch(1); f.childRelease = new CountDownLatch(1);
        f.childCalls.set(0);
        final CountDownLatch parentAnswered = new CountDownLatch(1);
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1) return call("spawn_agent", "{\"name\":\"blocked\",\"task\":\"long request\",\"fork\":false}");
                await(f.childStarted); parentAnswered.countDown(); return text("premature final while child runs");
            }
        };
        Thread thread = new Thread(new Runnable() { @Override public void run() { f.submit(); } }); thread.start();
        await(parentAnswered); long before = System.currentTimeMillis(); f.root.cancel(); thread.join(2000L);
        check(!thread.isAlive() && System.currentTimeMillis() - before < 2000L, "Parent cancellation waited for the 60-second child timeout");
        f.childRelease.countDown();
        for (int i = 0; i < 100 && f.manager.hasPendingWork(); i++) Thread.sleep(10L);
        check(!f.manager.hasPendingWork(), "Cancelled child did not finish its cleanup");
        check(SubAgentManager.IDLE.equals(f.manager.find(idle).status), "Cancelling a parent closed its idle reusable child");
        f.manager.resumePending(); f.manager.send("main", idle, "reuse after cancellation"); f.manager.waitFor("main", null, 5000L);
        check(SubAgentManager.IDLE.equals(f.manager.find(idle).status), "Idle context was unusable after parent cancellation");
    }

    private static void parentResumeStartsRestoredChildBeforeItsFinalAnswer() throws Exception {
        final List<SubAgentManager.Record> saved = new ArrayList<SubAgentManager.Record>();
        SubAgentManager.Record record = new SubAgentManager.Record(); record.id = "agent_restored"; record.parentId = "main";
        record.name = "restored"; record.task = "resume evidence"; record.status = SubAgentManager.QUEUED; record.sessionId = 90L;
        record.pending.put(new JSONObject().put("from", "main").put("text", "resume evidence")); saved.add(record);
        final Fixture f = new Fixture(new SubAgentManager.Store() {
            @Override public List<SubAgentManager.Record> load() { return saved; }
            @Override public void save(SubAgentManager.Record task) { }
        });
        List<Message> parentHistory = new ArrayList<Message>(); parentHistory.add(Message.user("resume the requested work"));
        f.root.loadHistory("fixture trusted policy", parentHistory);
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1 && resultNotes(messages) == 0) return text("parent provisional recovery answer");
                check(resultNotes(messages) == 1 && f.childCalls.get() == 1, "Parent resume never started and collected the saved child task");
                return text("recovered parent final");
            }
        };
        check(f.childCalls.get() == 0, "Child recovery ran before parent resume");
        f.root.resume(1L, 1);
        check(f.errors.isEmpty() && f.childCalls.get() == 1 && !f.root.needsResume(), "Parent recovery did not settle its child work");
    }

    private static void childApprovalUsesAndClearsItsOwnCallingSource() throws Exception {
        final Fixture f = new Fixture(); final AtomicInteger approvals = new AtomicInteger();
        f.root.setAccessLevel(ApprovalGate.ACCESS_STRICT); f.childAttemptsWrite = true;
        f.root.setApprovalGate(new ApprovalGate() {
            @Override public boolean approve(String name, JSONObject args) {
                approvals.incrementAndGet(); check(AgentLoop.callingApprovalSource() == f.child,
                        "Child approval was attributed to the parent loop"); return false;
            }
        });
        f.spawn(); f.manager.waitFor("main", null, 5000L);
        check(approvals.get() == 1 && f.writes.get() == 0, "Child permission inheritance bypassed approval");
        check(AgentLoop.callingApprovalSource() == null, "Approval source escaped its callback scope");
    }

    private static void cancelledOldChildReplyNeverChargesANewGoal() throws Exception {
        final Fixture f = new Fixture(); f.root.setGoal("old delegated goal"); f.childTokens = 77L;
        f.childStarted = new CountDownLatch(1); f.childRelease = new CountDownLatch(1);
        final CountDownLatch parentAnswered = new CountDownLatch(1);
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1) return call("spawn_agent", "{\"name\":\"old\",\"task\":\"slow old task\",\"fork\":false}");
                await(f.childStarted); parentAnswered.countDown(); return text("old provisional answer");
            }
        };
        Thread parent = new Thread(new Runnable() { @Override public void run() { f.submit(); } }); parent.start();
        await(parentAnswered); f.root.cancel(); parent.join(2000L);
        check(!parent.isAlive(), "Old parent did not cancel before changing its goal");
        f.root.setGoal("new unrelated goal"); f.childRelease.countDown();
        for (int i = 0; i < 100 && f.manager.hasPendingWork(); i++) Thread.sleep(10L);
        check(!f.manager.hasPendingWork() && f.root.goalTokensUsed() == 0L,
                "A cancelled child's late token report was charged to the new goal");
        check(Goal.ACTIVE.equals(f.root.goalStatus()) && "new unrelated goal".equals(f.root.goalText()),
                "Cancelled child altered the new goal state");
        f.root.clearGoal();
    }

    private static void lateOldParentCleanupCannotCancelNewDelegatedWork() throws Exception {
        final Fixture f = new Fixture();
        final CountDownLatch oldStarted = new CountDownLatch(1), oldRelease = new CountDownLatch(1);
        final CountDownLatch newParentStarted = new CountDownLatch(1), newParentRelease = new CountDownLatch(1);
        f.childStarted = new CountDownLatch(1); f.childRelease = new CountDownLatch(1);
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1) { oldStarted.countDown(); await(oldRelease); return text("late obsolete answer"); }
                if (f.client.calls.get() == 2) return call("spawn_agent", "{\"name\":\"new\",\"task\":\"new delegated work\",\"fork\":false}");
                if (f.client.calls.get() == 3) {
                    await(f.childStarted); newParentStarted.countDown(); await(newParentRelease);
                    return text("new provisional answer");
                }
                check(resultNotes(messages) == 1, "New parent lost its child result after old cleanup");
                return text("new final with evidence");
            }
        };
        Thread old = new Thread(new Runnable() { @Override public void run() { f.submit(); } });
        Thread current = new Thread(new Runnable() { @Override public void run() { f.submit(); } });
        try {
            old.start(); await(oldStarted); f.root.cancel(); current.start(); await(newParentStarted);
            check(f.manager.hasPendingWork(), "New delegated task was not running before old cleanup");
            oldRelease.countDown(); old.join(2000L);
            check(!old.isAlive() && f.manager.hasPendingWork()
                    && !f.manager.list("main", 0).getBoolean("cancelled"), "Old parent cleanup cancelled new delegated work");
            newParentRelease.countDown(); f.childRelease.countDown(); current.join(2000L);
            check(!current.isAlive() && f.errors.isEmpty() && f.client.calls.get() == 4,
                    "New parent failed to finish after old parent exit: " + f.errors);
        } finally {
            oldRelease.countDown(); newParentRelease.countDown(); f.childRelease.countDown(); f.root.cancel();
            old.join(2000L); current.join(2000L);
        }
    }

    private static void goalLeaseRejectsAUsageCallbackAlreadyInProgress() throws Exception {
        final Fixture f = new Fixture(); f.root.setGoal("old goal"); f.childTokens = 77L;
        f.usageStarted = new CountDownLatch(1); f.usageRelease = new CountDownLatch(1);
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1) return call("spawn_agent", "{\"name\":\"old\",\"task\":\"old work\",\"fork\":false}");
                return text("waiting for old usage callback");
            }
        };
        Thread parent = new Thread(new Runnable() { @Override public void run() { f.submit(); } }); parent.start();
        try {
            await(f.usageStarted); long oldLease = f.root.goalUsageLease();
            f.root.setGoal("new goal"); f.root.cancel();
            check(f.root.goalUsageLease() != oldLease, "Replacing a goal retained the old usage lease");
            f.usageRelease.countDown(); parent.join(2000L);
            for (int i = 0; i < 100 && f.manager.hasPendingWork(); i++) Thread.sleep(10L);
            check(!parent.isAlive() && !f.manager.hasPendingWork() && f.root.goalTokensUsed() == 0L,
                    "An already-entered child usage callback charged the replacement goal");
        } finally { f.usageRelease.countDown(); f.root.cancel(); parent.join(2000L); }
    }

    private static void idleChildReuseBindsEachTaskToItsCurrentGoalLease() throws Exception {
        Fixture f = new Fixture(); f.root.setGoal("first goal"); String id = f.spawn();
        f.manager.waitFor("main", null, 5000L); long first = f.manager.usageLease(id);
        check(first == f.root.goalUsageLease(), "Initial delegated task lacked its parent's usage lease");
        f.manager.collectResults(); f.manager.acknowledgeResults(); f.root.setGoal("second goal");
        f.manager.send("main", id, "new goal work"); f.manager.waitFor("main", null, 5000L);
        check(first != f.manager.usageLease(id) && f.manager.usageLease(id) == f.root.goalUsageLease()
                && f.childCalls.get() == 2 && SubAgentManager.IDLE.equals(f.manager.find(id).status),
                "Idle loop reuse retained the old goal's billing lease");
    }

    private static void parentRecoveryAfterAPrematureAnswerSettlesSavedChildWork() throws Exception {
        for (int variant = 0; variant < 2; variant++) {
            final List<SubAgentManager.Record> saved = new ArrayList<SubAgentManager.Record>();
            SubAgentManager.Record task = new SubAgentManager.Record(); task.id = "saved_child"; task.parentId = "main";
            task.name = "saved"; task.task = "saved evidence"; task.sessionId = 50L; task.revision = 3L;
            if (variant == 0) {
                task.status = SubAgentManager.QUEUED;
                task.pending.put(new JSONObject().put("from", "main").put("text", task.task));
            } else { task.status = SubAgentManager.IDLE; task.result = "child_verified_result"; }
            saved.add(task);
            final Fixture f = new Fixture(new SubAgentManager.Store() {
                @Override public List<SubAgentManager.Record> load() { return saved; }
                @Override public void save(SubAgentManager.Record record) { }
            });
            List<Message> history = new ArrayList<Message>(); history.add(Message.user("finish delegated work"));
            Message provisional = new Message(Message.ASSISTANT, "premature answer before child settlement");
            history.add(provisional); f.root.loadHistory("fixture trusted policy", history);
            f.client.script = new Script() {
                @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                    if (resultNotes(messages) == 0) return text("still pending child evidence");
                    check(resultNotes(messages) == 1, "Recovered evidence was injected more than once");
                    return text("recovered final after saved child evidence");
                }
            };
            check(f.root.needsResume(), "A premature stored assistant answer hid saved child settlement work");
            f.root.resume(1L, 1);
            check(f.errors.isEmpty() && !f.root.needsResume() && !f.manager.needsSettlement(),
                    "Parent recovery did not durably settle its saved child work: " + f.errors);
        }
    }

    private static void strictRecoveredChildrenWaitUntilParentApprovalIsInstalled() throws Exception {
        final List<SubAgentManager.Record> saved = new ArrayList<SubAgentManager.Record>();
        SubAgentManager.Record task = new SubAgentManager.Record(); task.id = "strict_saved"; task.parentId = "main";
        task.name = "strict"; task.task = "requires approved write"; task.status = SubAgentManager.QUEUED; task.sessionId = 80L;
        task.pending.put(new JSONObject().put("from", "main").put("text", task.task)); saved.add(task);
        final Fixture f = new Fixture(new SubAgentManager.Store() {
            @Override public List<SubAgentManager.Record> load() { return saved; }
            @Override public void save(SubAgentManager.Record record) { }
        });
        f.childAttemptsWrite = true; f.manager.setMaxParallel(3); Thread.sleep(50L);
        check(f.childCalls.get() == 0 && f.writes.get() == 0, "Concurrency setup ran a restored child with default full access");
        final AtomicInteger approvals = new AtomicInteger(); f.root.setAccessLevel(ApprovalGate.ACCESS_STRICT);
        f.root.setApprovalGate(new ApprovalGate() {
            @Override public boolean approve(String name, JSONObject args) { approvals.incrementAndGet(); return false; }
        });
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) {
                return text("parent verified child permissions");
            }
        };
        f.submit();
        check(f.errors.isEmpty() && approvals.get() == 1 && f.writes.get() == 0 && f.childCalls.get() == 2,
                "Restored child bypassed its configured strict approval: " + f.errors);
    }

    private static void parentUsageExhaustionImmediatelyAbortsAChildTool() throws Exception {
        final Fixture f = new Fixture(); f.root.setGoal("parent budget limit"); f.root.setGoalBudget(100L);
        f.childAttemptsWrite = true; f.writeStarted = new CountDownLatch(1); f.writeRelease = new CountDownLatch(1);
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1) return call("spawn_agent", "{\"name\":\"working\",\"task\":\"work before exhaustion\",\"fork\":false}");
                if (f.client.calls.get() == 2) {
                    await(f.writeStarted); LlmClient.Reply reply = text("parent reaches budget limit"); reply.promptTokens = 150L; return reply;
                }
                check(f.client.calls.get() == 3 && f.writeAborts.get() > 0 && Goal.BUDGET_LIMITED.equals(f.root.goalStatus()),
                        "Parent's own usage did not immediately abort the active child tool");
                return text("single budget wrap-up after child cancellation");
            }
        };
        try {
            f.submit();
            check(f.errors.isEmpty() && f.client.calls.get() == 3 && f.childCalls.get() == 1 && f.root.goalTokensUsed() == 150L,
                    "Parent budget exhaustion allowed additional child model work: " + f.errors);
        } finally { f.writeRelease.countDown(); f.root.cancel(); }
    }

    private static void budgetLimitedRepliesCannotDispatchAnotherChild() throws Exception {
        final Fixture f = new Fixture(); f.root.setGoal("do not overspend"); f.root.setGoalBudget(100L);
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1) {
                    LlmClient.Reply reply = call("spawn_agent", "{\"name\":\"forbidden\",\"task\":\"late work\",\"fork\":false}");
                    reply.promptTokens = 150L; return reply;
                }
                check(!f.root.delegationAllowed(), "Budget exhaustion left delegated work enabled");
                boolean rejected = false;
                try { f.manager.spawn("main", "forbidden", "must not dispatch", false); }
                catch (IllegalStateException expected) { rejected = true; }
                check(rejected && f.childCalls.get() == 0 && f.manager.list("main", 0).getInt("totalAgents") == 0,
                        "A budget-limited reply created a child task");
                return text("stopped without dispatching late work");
            }
        };
        f.submit(); check(f.errors.isEmpty() && f.client.calls.get() == 2 && f.childCalls.get() == 0,
                "A budget-limited spawn restarted cancelled coordination: " + f.errors);
    }

    private static void renamingAnActiveGoalStopsOldWorkAndAllowsNewDelegation() throws Exception {
        final Fixture f = new Fixture(); f.root.setGoal("old scope");
        f.childStarted = new CountDownLatch(1); f.childRelease = new CountDownLatch(1);
        final List<String> oldId = new ArrayList<String>();
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1) return call("spawn_agent", "{\"name\":\"old\",\"task\":\"old scope work\",\"fork\":false}");
                if (f.client.calls.get() == 2) {
                    await(f.childStarted);
                    JSONArray tasks = f.manager.list("main", 0).getJSONArray("agents");
                    for (int i = 0; i < tasks.length(); i++) oldId.add(tasks.getJSONObject(i).getString("id"));
                    f.root.renameGoal("new scope");
                    check(!f.manager.list("main", 0).getBoolean("cancelled")
                            && SubAgentManager.FAILED.equals(f.manager.find(oldId.get(0)).status),
                            "Objective change disabled the manager or kept old work active");
                    f.childRelease.countDown(); return text("continue the updated objective");
                }
                if (f.client.calls.get() == 3) {
                    check("new scope".equals(f.root.goalText()), "Parent resumed with the old objective");
                    return call("spawn_agent", "{\"name\":\"new\",\"task\":\"new scope evidence\",\"fork\":false}");
                }
                if (Goal.COMPLETE.equals(f.root.goalStatus())) return text("new objective completed with verified child evidence");
                if (resultNotes(messages) == 0) return text("wait for new child evidence");
                return call("update_goal", "{\"status\":\"complete\"}");
            }
        };
        try {
            f.submit();
            check(f.errors.isEmpty() && Goal.COMPLETE.equals(f.root.goalStatus()) && f.childCalls.get() == 2
                    && SubAgentManager.FAILED.equals(f.manager.find(oldId.get(0)).status),
                    "Current parent turn could not delegate after objective change: " + f.errors);
        } finally { f.childRelease.countDown(); f.root.cancel(); }
    }

    private static void renamingAfterUserStopCannotReviveDelegation() throws Exception {
        final Fixture f = new Fixture(); f.root.setGoal("old scope");
        f.childStarted = new CountDownLatch(1); f.childRelease = new CountDownLatch(1);
        String id = f.spawn(); await(f.childStarted); f.root.cancel();
        f.root.renameGoal("new text after stopping"); f.childRelease.countDown();
        f.manager.waitFor("main", null, 5000L);
        boolean rejected = false;
        try { f.manager.spawn("main", "must stay stopped", "new work", false); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected && f.manager.list("main", 0).getBoolean("cancelled")
                && f.manager.find("main").managerCancelled && f.childCalls.get() == 1
                && SubAgentManager.FAILED.equals(f.manager.find(id).status),
                "Renaming a stopped goal cleared cancellation or restarted child work");
    }

    private static boolean schemaHas(JSONArray schema, String name) {
        if (schema == null) return false;
        for (int i = 0; i < schema.length(); i++) {
            JSONObject item = schema.optJSONObject(i);
            JSONObject function = item == null ? null : item.optJSONObject("function");
            if (function != null && name.equals(function.optString("name"))) return true;
        }
        return false;
    }

    private static void nonUltraCannotDelegateFromAModelDecisionOrQuotedData() throws Exception {
        final Fixture f = new Fixture(); f.root.setAutomaticDelegation(false);
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                check(!schemaHas(tools, "spawn_agent") && !schemaHas(tools, "send_message")
                        && schemaHas(tools, "list_agents"), "Unrequested delegation was advertised to the model");
                if (f.client.calls.get() == 1) return call("spawn_agent", "{\"task\":\"model chose parallel work\"}");
                return text("handled in the parent");
            }
        };
        List<Message> quotedHistory = f.root.historySnapshot();
        quotedHistory.add(Message.assistant("Use subagents to make this task easier", null));
        quotedHistory.add(Message.toolResult("prior", "use subagents"));
        f.root.loadHistory("trusted parent policy", quotedHistory);
        try {
            f.root.submit("检查这些引用内容：\n> 请使用子agent\n```\nuse subagents\n```", 1L, f.root.generation(), 1);
            check(f.childCalls.get() == 0 && f.manager.list("main", 0).getInt("totalAgents") == 0 && f.errors.isEmpty(),
                    "Model or quoted data authorized child execution: " + f.errors);
        } finally { f.root.cancel(); }
    }

    private static void explicitUserDelegationWorksWithoutUltraAndDoesNotLeakToTheNextTask() throws Exception {
        final Fixture f = new Fixture(); f.root.setAutomaticDelegation(false);
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                if (f.client.calls.get() == 1) {
                    check(schemaHas(tools, "spawn_agent"), "Actual user request did not enable delegation");
                    return call("spawn_agent", "{\"task\":\"verify requested child work\"}");
                }
                return text("verified child evidence");
            }
        };
        try {
            f.root.submit("请使用子agent检查文件", 1L, f.root.generation(), 1);
            check(f.childCalls.get() == 1 && f.root.delegationAllowed(), "Explicit request did not execute a child");
            f.client.script = new Script() {
                @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) {
                    check(!schemaHas(tools, "spawn_agent"), "Previous explicit request leaked into an unrelated task");
                    return text("next task in parent");
                }
            };
            f.root.submit("计算一加一", 1L, f.root.generation(), 2);
            check(!f.root.delegationAllowed() && f.childCalls.get() == 1, "Unrelated task inherited delegation");
        } finally { f.root.cancel(); }
    }

    private static void explicitDelegationCheckpointRestoresAndUltraDoesNotCreateManualPermission() throws Exception {
        final Fixture f = new Fixture(); f.root.setAutomaticDelegation(false);
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) { return text("checkpoint"); }
        };
        try {
            f.root.submit("Use subagents to review the file", 1L, f.root.generation(), 1);
            List<Message> saved = f.root.historySnapshot();
            AgentLoop recovered = new AgentLoop(f.client, new ToolRegistry(), new AgentLoop.Quiet());
            recovered.loadHistory("trusted", saved);
            check(recovered.delegationAllowed(), "Explicit delegation permission was lost on recovery");
            for (Message message : saved) check(!message.toJson().has("delegation_authorized"), "Local authorization leaked onto the API wire");
            f.root.setAutomaticDelegation(true);
            f.root.submit("continue", 1L, f.root.generation(), 2);
            f.root.submit("计算一加一", 1L, f.root.generation(), 3);
            f.root.setAutomaticDelegation(false);
            check(!f.root.delegationAllowed(), "Ultra automatic permission became explicit manual permission");
        } finally { f.root.cancel(); }
    }

    private static void explicitDelegationDetectionRejectsNegationsAndMentions() {
        for (String request : new String[]{"不要使用子agent", "don't use subagents", "子agent有问题", "max思考检查文件", "修复spawn_agent工具",
                "请修复子agent功能", "帮我修改子agent界面", "创建工具方便子agent使用", "文档说“使用子agent”，检查这篇文档"})
            check(!AgentLoop.explicitlyRequestsDelegation(request), "Mention or refusal granted delegation: " + request);
        for (String request : new String[]{"使用子agent检查", "启动子代理检查", "请调用子agent", "开三个子agent", "把任务派给两个agent", "spawn a subagent for this review"})
            check(AgentLoop.explicitlyRequestsDelegation(request), "Explicit delegation was not recognized: " + request);
    }

    private static void anExplicitUserBanOverridesUltraAndSurvivesRecovery() throws Exception {
        final Fixture f = new Fixture(); f.root.setAutomaticDelegation(true);
        f.client.script = new Script() {
            @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) throws Exception {
                check(!schemaHas(tools, "spawn_agent"), "Ultra overrode the user's explicit ban");
                if (f.client.calls.get() == 1) return call("spawn_agent", "{\"task\":\"forbidden child\"}");
                return text("parent only");
            }
        };
        try {
            f.root.submit("不要使用子agent，由主会话检查文件", 1L, f.root.generation(), 1);
            check(f.childCalls.get() == 0 && !f.root.delegationAllowed(), "User ban did not stop child execution");
            AgentLoop recovered = new AgentLoop(f.client, new ToolRegistry(), new AgentLoop.Quiet());
            recovered.setAutomaticDelegation(true); recovered.loadHistory("trusted", f.root.historySnapshot());
            check(!recovered.delegationAllowed(), "Recovery lost the user ban in ultra");
            f.root.submit("continue", 1L, f.root.generation(), 2);
            check(!f.root.delegationAllowed(), "Continue silently removed the explicit user ban");
        } finally { f.root.cancel(); }
    }

    private static void userChildMessagesWorkInMaxWithoutAuthorizingModelDelegation() throws Exception {
        Fixture f = new Fixture();
        try {
            String id = f.spawn(); f.manager.waitFor("main", null, 5000L);
            f.root.setAutomaticDelegation(false);
            boolean rejected = false;
            try { f.manager.send("main", id, "model-chosen follow-up"); }
            catch (IllegalStateException expected) { rejected = true; }
            check(rejected && f.childCalls.get() == 1, "A model follow-up bypassed max's explicit-user requirement");
            f.manager.sendFromUser(id, "user-directed follow-up");
            f.manager.waitFor("main", null, 5000L);
            check(f.childCalls.get() == 2 && "user-directed follow-up".equals(f.manager.find(id).task)
                    && !f.root.delegationAllowed() && !f.child.delegationAllowed(),
                    "User child conversation failed or granted unrelated model delegation");
            rejected = false;
            try { f.manager.spawn(id, "unrequested descendant", "new work", false); }
            catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "A user child message gave the child unlimited delegation authority");
            f.root.cancel(); rejected = false;
            try { f.manager.sendFromUser(id, "must remain stopped"); }
            catch (IllegalStateException expected) { rejected = true; }
            check(rejected && f.childCalls.get() == 2, "User child sending revived a stopped manager");
        } finally { f.root.cancel(); }
    }

    private static void userChildMessagesCannotBypassTheGoalBudget() throws Exception {
        final Fixture f = new Fixture();
        try {
            String id = f.spawn(); f.manager.waitFor("main", null, 5000L);
            f.root.setAutomaticDelegation(false); f.root.setGoal("finish parent work"); f.root.setGoalBudget(1L);
            f.client.script = new Script() {
                @Override public LlmClient.Reply next(Fixture fixture, List<Message> messages, JSONArray tools) {
                    LlmClient.Reply reply = text("budget summary"); reply.promptTokens = 1L; return reply;
                }
            };
            f.submit();
            check(Goal.BUDGET_LIMITED.equals(f.root.goalStatus()), "Fixture did not exhaust the parent goal budget");
            boolean rejected = false;
            try { f.manager.sendFromUser(id, "overspend from UI"); }
            catch (IllegalStateException expected) { rejected = true; }
            check(rejected && f.childCalls.get() == 1, "User child sending bypassed the parent budget");
        } finally { f.root.cancel(); }
    }

    public static void main(String[] args) throws Exception {
        String[] tests = {"prematureParentFinalWaitsAndReceivesExactlyOneChildResult", "parentRunsIndependentToolsWhileItsChildIsWorking",
                "goalCompletionRejectsPendingAndUncollectedChildResults",
                "childUsageExhaustsParentBudgetBeforeItsToolCanExecute", "endedGoalDoesNotChargeExternalUsage",
                "parentCancellationInterruptsChildWaitAndKeepsIdleContextReusable", "parentResumeStartsRestoredChildBeforeItsFinalAnswer",
                "childApprovalUsesAndClearsItsOwnCallingSource", "cancelledOldChildReplyNeverChargesANewGoal",
                "lateOldParentCleanupCannotCancelNewDelegatedWork", "goalLeaseRejectsAUsageCallbackAlreadyInProgress",
                "idleChildReuseBindsEachTaskToItsCurrentGoalLease", "parentRecoveryAfterAPrematureAnswerSettlesSavedChildWork",
                "strictRecoveredChildrenWaitUntilParentApprovalIsInstalled", "parentUsageExhaustionImmediatelyAbortsAChildTool",
                "budgetLimitedRepliesCannotDispatchAnotherChild", "renamingAnActiveGoalStopsOldWorkAndAllowsNewDelegation",
                "renamingAfterUserStopCannotReviveDelegation", "nonUltraCannotDelegateFromAModelDecisionOrQuotedData",
                "explicitUserDelegationWorksWithoutUltraAndDoesNotLeakToTheNextTask",
                "explicitDelegationCheckpointRestoresAndUltraDoesNotCreateManualPermission",
                "explicitDelegationDetectionRejectsNegationsAndMentions", "anExplicitUserBanOverridesUltraAndSurvivesRecovery",
                "userChildMessagesWorkInMaxWithoutAuthorizingModelDelegation", "userChildMessagesCannotBypassTheGoalBudget"};
        int failures = 0;
        for (String name : tests) {
            try { SubAgentLoopIntegrationTest.class.getDeclaredMethod(name).invoke(null); System.out.println("PASS " + name); }
            catch (Exception error) { failures++; System.out.println("FAIL " + name + ": " + (error.getCause() == null ? error : error.getCause())); }
        }
        if (failures != 0) throw new AssertionError(failures + " child loop integration tests failed");
        System.out.println(tests.length + " child loop integration tests passed");
    }
}
