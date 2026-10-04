import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.SubAgentManager;
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

/** Exercises real child loops, their mailboxes, scheduling, and private checkpoint contract. */
public final class SubAgentRegressionTest {
    private interface Work {
        LlmClient.Reply run(SubAgentManager.Record task, List<Message> history, JSONArray tools) throws Exception;
    }

    private static final class Store implements SubAgentManager.Store {
        final Map<String, SubAgentManager.Record> records = new LinkedHashMap<String, SubAgentManager.Record>();
        boolean fail;
        @Override public synchronized List<SubAgentManager.Record> load() throws Exception {
            List<SubAgentManager.Record> copies = new ArrayList<SubAgentManager.Record>();
            for (SubAgentManager.Record record : records.values()) copies.add(copy(record));
            return copies;
        }
        @Override public synchronized void save(SubAgentManager.Record task) throws Exception {
            if (fail) throw new IllegalStateException("fixture disk failure");
            records.put(task.id, copy(task));
        }
    }

    private static final class Fixture implements SubAgentManager.Factory {
        final Store store;
        final AtomicInteger created = new AtomicInteger(), calls = new AtomicInteger();
        final Map<String, Work> scripts = new LinkedHashMap<String, Work>();
        final Map<String, AgentLoop> loops = new LinkedHashMap<String, AgentLoop>();
        final AgentLoop root;
        final SubAgentManager manager;
        Fixture(int concurrency) throws Exception { this(concurrency, new Store()); }
        Fixture(int concurrency, Store store) throws Exception {
            this.store = store;
            root = new AgentLoop(new LlmClient(new LlmClient.Config("http://fixture", "fixture", "fixture")),
                    new ToolRegistry(), new AgentLoop.Quiet());
            root.bindSession(1L); root.reset("root system policy");
            root.setContextBudget(50000, 0.8f);
            manager = new SubAgentManager(concurrency, this, store); manager.attachRoot(root);
            root.setAutomaticDelegation(true);
        }
        @Override public AgentLoop create(final SubAgentManager.Record task, AgentLoop.Listener listener,
                final SubAgentManager shared) throws Exception {
            created.incrementAndGet();
            LlmClient client = new LlmClient(new LlmClient.Config("http://fixture", "fixture", "fixture")) {
                @Override public Reply send(List<Message> history, JSONArray tools, Sink sink) {
                    calls.incrementAndGet();
                    try {
                        Work work;
                        synchronized (scripts) { work = scripts.get(task.name); }
                        if (work == null) return text("done: " + lastUser(history));
                        return work.run(task, history, tools);
                    } catch (Exception error) { throw new IllegalStateException(error); }
                }
            };
            ToolRegistry registry = new ToolRegistry(); SubAgentTools.register(registry, shared, task.id);
            AgentLoop loop = new AgentLoop(client, registry, listener);
            loop.reset("child trusted policy"); loop.setContextBudget(root.contextLimit(), 0.8f);
            loop.setAccessLevel(root.accessLevel());
            synchronized (loops) { loops.put(task.id, loop); }
            return loop;
        }
        void work(String name, Work work) { synchronized (scripts) { scripts.put(name, work); } }
        String spawn(String name, String task) throws Exception { return manager.spawn("main", name, task, false).getString("id"); }
        void settle() throws Exception {
            manager.waitFor("main", null, 5000L);
            check(!manager.hasPendingWork(), "Child work failed to settle: " + manager.list("main", 0));
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static SubAgentManager.Record copy(SubAgentManager.Record record) throws Exception {
        return SubAgentManager.Record.fromJson(new JSONObject(record.toJson().toString()));
    }
    private static LlmClient.Reply text(String text) { LlmClient.Reply reply = new LlmClient.Reply(); reply.content = text; return reply; }
    private static String lastUser(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) if (Message.USER.equals(history.get(i).role)) return history.get(i).content;
        return "";
    }
    private static void await(CountDownLatch latch) throws Exception { check(latch.await(5, TimeUnit.SECONDS), "Fixture latch timed out"); }

    private static void childRunsARealLoopAndReusesIdleContext() throws Exception {
        Fixture f = new Fixture(2);
        String id = f.spawn("review", "first task"); f.settle();
        check(SubAgentManager.IDLE.equals(f.manager.find(id).status), "Answered child did not become idle");
        check(f.manager.find(id).result.contains("first task"), "Child final answer was not collected");
        f.manager.send("main", id, "second task"); f.settle();
        check(f.created.get() == 1 && f.calls.get() == 2, "Idle child did not reuse its loop");
        SubAgentManager.Record record = f.manager.find(id);
        check(record.history.length() == 5 && record.result.contains("second task"), "Reused child lost prior context");
        check("second task".equals(record.task), "Reused child still advertises its original assignment");
        check(record.sessionId != f.root.sessionKey(), "Child shares the parent persistence identity");
    }

    private static void concurrentLimitQueuesExcessWork() throws Exception {
        final Fixture f = new Fixture(1); final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        f.work("hold", new Work() {
            @Override public LlmClient.Reply run(SubAgentManager.Record task, List<Message> history, JSONArray tools) throws Exception {
                started.countDown(); await(release); return text("held task done");
            }
        });
        f.spawn("hold", "blocking task"); await(started);
        String queued = f.spawn("queued", "parallel task");
        check(SubAgentManager.QUEUED.equals(f.manager.find(queued).status) && f.created.get() == 1,
                "Concurrency limit started excess work");
        release.countDown(); f.settle(); check(f.calls.get() == 2, "Queued child was lost");
    }

    private static void busyMessagesQueueWithoutCancellingTheCurrentTurn() throws Exception {
        final Fixture f = new Fixture(1); final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger turns = new AtomicInteger();
        f.work("hold", new Work() {
            @Override public LlmClient.Reply run(SubAgentManager.Record task, List<Message> history, JSONArray tools) throws Exception {
                if (turns.incrementAndGet() == 1) { started.countDown(); await(release); }
                return text(lastUser(history));
            }
        });
        String id = f.spawn("hold", "original"); await(started);
        f.manager.send("main", id, "followup one"); f.manager.send("main", id, "followup two");
        check(f.manager.find(id).inbox.length() == 2 && f.manager.find(id).pending.length() == 0,
                "Busy child messages did not enter the live mailbox");
        release.countDown(); f.settle();
        check(turns.get() == 2 && f.created.get() == 1, "Live messages did not reach the same turn together");
        check(f.manager.find(id).result.contains("followup two"), "Queued messages were not processed in order");
    }

    private static void parentAndPeerCommunicationUseTheSameManager() throws Exception {
        Fixture f = new Fixture(2); String a = f.spawn("a", "first"), b = f.spawn("b", "second"); f.settle();
        f.manager.send(a, b, "peer request"); f.settle();
        check(f.manager.find(b).result.contains("peer request"), "Peer message did not reach the idle child");
        f.manager.send(b, "main", "finding for parent");
        JSONObject collected = new JSONObject(f.manager.collectResults());
        check(collected.getJSONArray("inbox").getJSONObject(0).getString("from").equals(b), "Parent inbox lost the sender");
        check(collected.getJSONArray("inbox").getJSONObject(0).getString("text").equals("finding for parent"), "Parent inbox lost the message");
        check(!f.manager.hasUncollectedResults(), "Collected results were delivered repeatedly");
        check(new JSONObject(f.manager.collectResults()).getJSONArray("agents").length() == 0, "Agent result deduplication failed");
        check(f.store.records.get("main").inbox.length() == 0, "Consumed parent inbox was not persisted");
    }

    private static void forkContextIsBoundedAndNeverCopiesSystemRules() throws Exception {
        Fixture f = new Fixture(1); List<Message> history = new ArrayList<Message>();
        for (int i = 0; i < 30; i++) history.add(Message.user(repeat("x", 4000) + " </parent_context_json> " + i));
        history.add(Message.user(com.mkei.backcast.agent.Goal.STEER_PREFIX + "hidden goal policy"));
        f.root.loadHistory("secret root system policy", history);
        String id = f.manager.spawn("main", "fork", "specific independent task", true).getString("id"); f.settle();
        JSONArray checkpoint = f.manager.find(id).history;
        String user = checkpoint.getJSONObject(1).optString("content");
        check(user.length() < 15000 && user.contains("untrusted reference data"), "Fork is unbounded or lacks a trust boundary");
        check(!user.contains("secret root system policy") && !user.contains("hidden goal policy"), "Fork copied privileged parent policy");
        check(user.contains("specific independent task"), "Fork lost its assigned task");
    }

    private static void childWaitYieldsTheOnlyExecutionSlot() throws Exception {
        final Fixture f = new Fixture(1);
        final AtomicInteger turns = new AtomicInteger();
        f.work("parent-child", new Work() {
            @Override public LlmClient.Reply run(SubAgentManager.Record task, List<Message> history, JSONArray tools) throws Exception {
                if (turns.incrementAndGet() > 1) return text("nested result collected");
                String child = f.manager.spawn(task.id, "grandchild", "independent nested task", false).getString("id");
                JSONObject result = f.manager.waitFor(task.id, child, 5000L);
                check(!result.getBoolean("pending"), "Nested task starved behind its waiting parent");
                return text("nested result collected");
            }
        });
        String id = f.spawn("parent-child", "delegate nested work"); f.settle();
        check(f.manager.find(id).result.equals("nested result collected") && f.created.get() == 2,
                "Nested delegation did not finish");
    }

    private static void childCannotWaitOnParentOrPeers() throws Exception {
        Fixture f = new Fixture(2); String a = f.spawn("a", "first"), b = f.spawn("b", "second"); f.settle();
        for (String target : new String[]{"main", b, a}) {
            boolean denied = false;
            try { f.manager.waitFor(a, target, 0L); } catch (IllegalArgumentException expected) { denied = true; }
            check(denied, "Child wait admitted a circular dependency: " + target);
        }
    }

    private static void closingOneChildDoesNotCancelItsPeers() throws Exception {
        Fixture f = new Fixture(2); String a = f.spawn("a", "first"), b = f.spawn("b", "second"); f.settle();
        f.manager.close("main", a);
        check(SubAgentManager.CLOSED.equals(f.manager.find(a).status) && SubAgentManager.IDLE.equals(f.manager.find(b).status),
                "Closing one child closed a peer");
        f.manager.send("main", b, "peer remains reusable"); f.settle();
        check(f.manager.find(b).result.contains("peer remains reusable"), "Peer loop was cancelled by another child's close");
        boolean denied = false;
        try { f.manager.send("main", a, "must not run"); } catch (IllegalStateException expected) { denied = true; }
        check(denied, "Closed child accepted more work");
    }

    private static void cancellationIsIdempotentAndIdleChildrenRemainReusable() throws Exception {
        Fixture f = new Fixture(1); String idle = f.spawn("idle", "retain context"); f.settle();
        f.manager.cancelAll(); f.manager.cancelAll();
        check(SubAgentManager.IDLE.equals(f.manager.find(idle).status), "Cancellation closed reusable idle context");
        f.manager.resumePending(); f.manager.send("main", idle, "after cancellation"); f.settle();
        check(f.created.get() == 1 && f.manager.find(idle).result.contains("after cancellation"), "Cancelled manager could not reuse its child");
    }

    private static void childFailureIsReportedWithoutBlockingSettlement() throws Exception {
        Fixture f = new Fixture(1);
        f.work("failure", new Work() {
            @Override public LlmClient.Reply run(SubAgentManager.Record task, List<Message> history, JSONArray tools) {
                throw new IllegalStateException("fixture child error");
            }
        });
        String id = f.spawn("failure", "fail explicitly"); f.settle();
        check(SubAgentManager.FAILED.equals(f.manager.find(id).status)
                && f.manager.find(id).error.contains("IllegalStateException")
                && !f.manager.find(id).error.contains("fixture child error"),
                "Child failure disappeared, remained running, or exposed the original exception payload");
        check(new JSONObject(f.manager.collectResults()).getJSONArray("agents").length() == 1, "Failed result was not collected");
    }

    private static void restoredRunningTaskWaitsForExplicitRecoveryAndKeepsItsUserMessage() throws Exception {
        Store store = new Store(); SubAgentManager.Record r = new SubAgentManager.Record();
        r.id = "agent_restored"; r.parentId = "main"; r.name = "restored"; r.task = "recover original";
        r.status = SubAgentManager.RUNNING; r.sessionId = 99; r.inFlight = "recover original"; r.resume = true;
        r.inFlightStarted = true;
        r.history.put(Message.system("old child policy").toCheckpointJson());
        r.history.put(Message.user("recover original").toCheckpointJson()); store.save(r);
        Fixture f = new Fixture(1, store);
        check(f.created.get() == 0 && f.manager.hasPendingWork(), "Restore started child work before permission setup");
        f.manager.resumePending(); f.settle();
        JSONArray history = f.manager.find(r.id).history;
        check(history.length() == 3 && history.getJSONObject(1).optString("content").equals("recover original"),
                "Recovery resubmitted or lost the pending user message");
        check(history.getJSONObject(0).optString("content").equals("child trusted policy"), "Restore reused old privileged policy");
    }

    private static void persistenceFailureNeverStartsAnUnreviewableChild() throws Exception {
        Fixture f = new Fixture(1); f.store.fail = true; boolean failed = false;
        try { f.spawn("failed-save", "never run"); } catch (IllegalStateException expected) { failed = true; }
        check(failed && f.created.get() == 0 && !f.manager.hasPendingWork(), "Failed initial persistence started hidden child work");
        check(f.manager.list("main", 0).getInt("totalAgents") == 0, "Failed spawn leaked an orphan task record");
    }

    private static void waitToolCollectsResultsExactlyOnce() throws Exception {
        Fixture f = new Fixture(1); String id = f.spawn("review", "finish"); f.settle();
        ToolRegistry registry = new ToolRegistry(); SubAgentTools.register(registry, f.manager, "main");
        JSONObject result = new JSONObject(registry.get("wait_agent").run(new JSONObject().put("target", id).put("timeout_ms", 0)));
        check(result.getJSONArray("agents").length() == 1 && !f.manager.hasUncollectedResults(), "Wait tool failed to mark its delivered result");
        for (String name : new String[]{"spawn_agent", "send_message", "list_agents", "wait_agent", "close_agent"})
            check(registry.get(name) != null && registry.get(name).parameters().getBoolean("additionalProperties") == false, "Coordination schema is incomplete");
        check(registry.get("update_goal") == null && registry.get("get_goal") == null, "Child toolkit exposed the parent's goal tools");
    }

    private static void restoredRootInboxKeepsRevisionAndConsumesDurably() throws Exception {
        Store store = new Store(); SubAgentManager.Record root = new SubAgentManager.Record();
        root.id = "main"; root.parentId = ""; root.name = "main"; root.task = ""; root.status = SubAgentManager.IDLE; root.revision = 25;
        root.inbox.put(new JSONObject().put("from", "child").put("text", "saved result")); store.save(root);
        Fixture f = new Fixture(1, store); JSONObject result = new JSONObject(f.manager.collectResults());
        check(result.getJSONArray("inbox").length() == 1 && f.store.records.get("main").revision > 25,
                "Restored inbox revision restarted and could not be saved");
        Fixture interrupted = new Fixture(1, store);
        check(interrupted.manager.hasUncollectedResults(), "Unacknowledged inbox disappeared before the parent's final answer");
        f.manager.acknowledgeResults();
        Fixture restarted = new Fixture(1, store);
        check(!restarted.manager.hasUncollectedResults(), "Consumed inbox was delivered after restart");
    }

    private static void unacknowledgedResultsReplayAfterRestartUntilAFinalAnswer() throws Exception {
        Fixture f = new Fixture(1); String id = f.spawn("review", "pending evidence"); f.settle();
        JSONObject collected = new JSONObject(f.manager.collectResults());
        check(collected.getJSONArray("agents").length() == 1 && !f.manager.hasUncollectedResults(), "Initial result delivery failed");
        Fixture interrupted = new Fixture(1, f.store);
        check(interrupted.manager.hasUncollectedResults(), "Collected but unacknowledged result vanished after process death");
        check(new JSONObject(interrupted.manager.collectResults()).getJSONArray("agents").getJSONObject(0).getString("id").equals(id),
                "Restart replayed the wrong child result");
        interrupted.manager.acknowledgeResults();
        Fixture finished = new Fixture(1, f.store);
        check(!finished.manager.hasUncollectedResults(), "Acknowledged child result repeated after restart");
    }

    private static void busyCancellationDoesNotRecursivelyClosePeersAndCanReuseContext() throws Exception {
        final Fixture f = new Fixture(2); final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger turns = new AtomicInteger();
        f.work("held", new Work() {
            @Override public LlmClient.Reply run(SubAgentManager.Record task, List<Message> history, JSONArray tools) throws Exception {
                if (turns.incrementAndGet() == 1) { started.countDown(); await(release); }
                return text("finished safely");
            }
        });
        String idle = f.spawn("peer", "peer ready"); f.settle();
        String held = f.spawn("held", "cancel this run"); await(started);
        f.manager.cancelAll(); f.manager.cancelAll(); release.countDown();
        for (int i = 0; i < 100 && f.manager.hasPendingWork(); i++) Thread.sleep(10L);
        check(!f.manager.hasPendingWork() && SubAgentManager.FAILED.equals(f.manager.find(held).status),
                "Cancelled work did not settle into a reusable failed record");
        check(SubAgentManager.IDLE.equals(f.manager.find(idle).status), "Cancelling busy work closed its idle peer");
        f.manager.resumePending(); f.manager.send("main", held, "new task after cancellation"); f.settle();
        check(SubAgentManager.IDLE.equals(f.manager.find(held).status) && turns.get() == 2,
                "Cancelled busy context could not be reused");
    }

    private static void closingAParentClosesDescendantsWithoutStackRecursion() throws Exception {
        Fixture f = new Fixture(2); String parent = f.spawn("parent", "original"); f.settle();
        String child = f.manager.spawn(parent, "child", "nested work", false).getString("id"); f.settle();
        f.manager.close("main", parent);
        check(SubAgentManager.CLOSED.equals(f.manager.find(parent).status)
                && SubAgentManager.CLOSED.equals(f.manager.find(child).status), "Closing parent did not close descendants");
    }

    private static void recoveryBeforeFirstUserCheckpointDoesNotLoseTheTask() throws Exception {
        Store store = new Store(); SubAgentManager.Record r = new SubAgentManager.Record();
        r.id = "agent_before_submit"; r.parentId = "main"; r.name = "restored"; r.task = "new followup";
        r.status = SubAgentManager.RUNNING; r.sessionId = 123; r.inFlight = "new followup"; r.resume = true;
        r.inFlightStarted = false;
        r.history.put(Message.system("old policy").toCheckpointJson());
        r.history.put(Message.user("earlier task").toCheckpointJson());
        r.history.put(Message.assistant("earlier final", null).toCheckpointJson()); store.save(r);
        Fixture f = new Fixture(1, store); f.manager.resumePending(); f.settle();
        check(f.calls.get() == 1 && f.manager.find(r.id).history.length() == 5
                && f.manager.find(r.id).result.contains("new followup"), "Crash before submit discarded the pending new task");
    }

    private static void closedHistoryCannotHideNewerRestoredWork() throws Exception {
        Store store = new Store();
        for (int i = 0; i < 20; i++) {
            SubAgentManager.Record r = new SubAgentManager.Record(); r.id = "closed_" + i; r.parentId = "main";
            r.name = r.id; r.task = "old"; r.status = SubAgentManager.CLOSED; store.save(r);
        }
        SubAgentManager.Record active = new SubAgentManager.Record(); active.id = "newer_active"; active.parentId = "main";
        active.name = "newer"; active.task = "restore latest"; active.status = SubAgentManager.QUEUED; active.sessionId = 10;
        active.pending.put(new JSONObject().put("from", "main").put("text", "restore latest")); store.save(active);
        Fixture f = new Fixture(1, store); check(f.manager.list("main", 0).getInt("totalAgents") == 21, "Restore silently dropped records after sixteen closed entries");
        f.manager.resumePending(); f.settle();
        check(f.manager.find(active.id).result.contains("restore latest"), "Closed histories hid registered running work");
    }

    private static void resultBatchesStayBoundedAndFullResultsAreReadable() throws Exception {
        Fixture f = new Fixture(3);
        f.work("large", new Work() {
            @Override public LlmClient.Reply run(SubAgentManager.Record task, List<Message> history, JSONArray tools) {
                return text(repeat("R", 16000));
            }
        });
        String first = "";
        for (int i = 0; i < 12; i++) { String id = f.spawn("large", repeat("T", 2000)); if (i == 0) first = id; }
        f.settle(); JSONObject listed = f.manager.list("main", 0);
        check(listed.toString().length() < 65000 && !listed.isNull("nextCursor"), "List result exceeded its batch bound");
        int delivered = 0;
        while (f.manager.hasUncollectedResults()) {
            String batch = f.manager.collectResults(); check(batch.length() < 65000, "Automatic result batch exceeded its bound");
            delivered += new JSONObject(batch).getJSONArray("agents").length();
        }
        check(delivered == 12, "Bounded batches silently lost completed results");
        JSONObject chunk = f.manager.readResult("main", first, 8000, 8000);
        check(chunk.getString("result").length() == 8000 && chunk.isNull("nextOffset")
                && chunk.getInt("totalLength") == 16000, "Full result paging lost the truncated remainder");
        check(f.manager.find(first).result.length() == 16000, "Tool truncation damaged the stored result");
    }

    private static void usageAndFactoryPermissionConfigurationSurviveIdleReuse() throws Exception {
        Fixture f = new Fixture(1); f.root.setAccessLevel("strict");
        String id = f.spawn("inherit", "first"); f.settle();
        AgentLoop child = f.manager.runtimeLoops().get(0);
        check(child.accessLevel().equals(f.root.accessLevel()) && child.contextLimit() == f.root.contextLimit(),
                "Manager discarded factory permission or compaction configuration");
        f.manager.accountUsage(id, 50L); f.manager.accountUsage(id, 20L);
        f.manager.send("main", id, "reuse"); f.settle();
        check(f.manager.find(id).tokensUsed == 70L && f.store.records.get(id).tokensUsed == 70L,
                "Child token accounting was reset or not persisted");
    }

    private static void parentGoalCannotCompleteBeforeChildrenSettleAndResultsAreCollected() throws Exception {
        final Fixture f = new Fixture(1); final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        f.root.setSubAgents(f.manager); f.root.setGoal("finish child review");
        f.work("hold", new Work() {
            @Override public LlmClient.Reply run(SubAgentManager.Record task, List<Message> history, JSONArray tools) throws Exception {
                started.countDown(); await(release); return text("review complete");
            }
        });
        f.spawn("hold", "review independently"); await(started);
        check(!f.root.closeGoal("complete", "").startsWith("{"), "Parent goal completed while children were still running");
        release.countDown(); f.settle();
        f.manager.collectResults();
        check(new JSONObject(f.root.closeGoal("complete", "")).getJSONObject("goal").getString("status").equals("complete"),
                "Settled and collected child work blocked goal completion");
    }

    private static void childFinalWaitsForAndIntegratesItsDelegatedResult() throws Exception {
        final Fixture f = new Fixture(1); final AtomicInteger turns = new AtomicInteger();
        f.work("delegator", new Work() {
            @Override public LlmClient.Reply run(SubAgentManager.Record task, List<Message> history, JSONArray tools) throws Exception {
                if (turns.incrementAndGet() == 1) {
                    f.manager.spawn(task.id, "nested", "nested evidence", false);
                    return text("provisional answer before nested work");
                }
                check(lastUser(history).contains("nested evidence"), "Delegating child did not receive its child's evidence");
                return text("final answer after nested result");
            }
        });
        String id = f.spawn("delegator", "complete nested review"); f.settle();
        check(turns.get() == 2 && f.manager.find(id).result.equals("final answer after nested result"),
                "Child final answer escaped before its nested task finished");
    }

    private static void cancellingAChildOnlyStopsItsOwnedDescendants() throws Exception {
        final Fixture f = new Fixture(2); final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        String parent = f.spawn("parent", "ready"); String peer = f.spawn("peer", "peer ready"); f.settle();
        f.work("nested-hold", new Work() {
            @Override public LlmClient.Reply run(SubAgentManager.Record task, List<Message> history, JSONArray tools) throws Exception {
                started.countDown(); await(release); return text("cancelled work");
            }
        });
        String nested = f.manager.spawn(parent, "nested-hold", "hold nested", false).getString("id"); await(started);
        AgentLoop parentLoop;
        synchronized (f.loops) { parentLoop = f.loops.get(parent); }
        parentLoop.cancel(); release.countDown(); f.settle();
        check(SubAgentManager.FAILED.equals(f.manager.find(nested).status) && SubAgentManager.IDLE.equals(f.manager.find(peer).status),
                "Child cancellation escaped its owned descendants");
        f.manager.send("main", peer, "still useful"); f.settle();
        check(f.manager.find(peer).result.contains("still useful"), "Peer was unusable after sibling cancellation");
    }

    private static void failedFollowupDoesNotExposeThePreviousFinalAnswer() throws Exception {
        Fixture f = new Fixture(1); String id = f.spawn("review", "successful first task"); f.settle();
        f.work("review", new Work() {
            @Override public LlmClient.Reply run(SubAgentManager.Record task, List<Message> history, JSONArray tools) {
                throw new IllegalStateException("new task failed");
            }
        });
        f.manager.send("main", id, "failing followup"); f.settle();
        check(SubAgentManager.FAILED.equals(f.manager.find(id).status) && f.manager.find(id).result.length() == 0,
                "Failed followup presented its previous answer as the new task result");
    }

    private static void longMailboxMessagesReachTheModelWithoutPreviewTruncation() throws Exception {
        Fixture f = new Fixture(1); String id = f.spawn("sender", "ready"); f.settle();
        f.manager.collectResults();
        String first = repeat("a", 31950) + "FIRST_MESSAGE_TAIL";
        String second = repeat("b", 31950) + "SECOND_MESSAGE_TAIL";
        f.manager.send(id, "main", first); f.manager.send(id, "main", second);
        JSONObject preview = f.manager.list("main", 0).getJSONArray("inbox").getJSONObject(0);
        check(preview.getBoolean("truncated") && preview.getString("text").length() == 4000,
                "List preview stopped bounding long inbox entries");
        JSONObject batch = f.manager.collectResults("main", null);
        check(batch.getJSONArray("inbox").length() == 1 && batch.getJSONArray("inbox").getJSONObject(0).getString("text").equals(first)
                && batch.getBoolean("moreResults") && batch.toString().length() < 61000,
                "First large mailbox message was truncated or its batch overflowed");
        batch = f.manager.collectResults("main", null);
        check(batch.getJSONArray("inbox").length() == 1 && batch.getJSONArray("inbox").getJSONObject(0).getString("text").equals(second),
                "Remaining mailbox message lost its tail or was consumed without delivery");
    }

    private static void recoveredWorkCannotStartFromAConcurrencySettingChange() throws Exception {
        Store store = new Store(); SubAgentManager.Record task = new SubAgentManager.Record();
        task.id = "saved"; task.parentId = "main"; task.name = "saved"; task.task = "restore safely";
        task.status = SubAgentManager.QUEUED; task.sessionId = 42L;
        task.pending.put(new JSONObject().put("from", "main").put("text", task.task)); store.save(task);
        Fixture f = new Fixture(1, store); f.manager.setMaxParallel(4);
        Thread.sleep(50L);
        check(f.created.get() == 0 && f.calls.get() == 0 && f.manager.needsSettlement(),
                "Changing restored parallelism started work before root authorization was ready");
        f.manager.resumePending(); f.settle(); check(f.calls.get() == 1, "Explicit recovery did not start restored work");
    }

    private static void aPersistedUserStopPreventsAutomaticSettlement() throws Exception {
        Fixture original = new Fixture(1); String id = original.spawn("review", "result before stopping"); original.settle();
        original.manager.cancelAll();
        Fixture restored = new Fixture(1, original.store); restored.manager.setMaxParallel(2);
        check(!restored.manager.needsSettlement() && restored.manager.hasUncollectedResults(),
                "Restoring a stopped session automatically revived an unconfirmed child result");
        check(restored.store.records.get("main").managerCancelled, "Root cancellation was not persisted");
        restored.manager.resumePending();
        check(restored.manager.needsSettlement() && !restored.store.records.get("main").managerCancelled,
                "An explicit resume failed to durably clear the stop marker");
        restored.manager.collectResults(); restored.manager.acknowledgeResults();
        check(!restored.manager.needsSettlement() && restored.manager.find(id).result.contains("result before stopping"),
                "Explicitly resumed result settlement lost its answer");
    }

    private static String repeat(String value, int count) { StringBuilder out = new StringBuilder(); for (int i = 0; i < count; i++) out.append(value); return out.toString(); }

    public static void main(String[] args) throws Exception {
        String[] tests = {"childRunsARealLoopAndReusesIdleContext", "concurrentLimitQueuesExcessWork",
                "busyMessagesQueueWithoutCancellingTheCurrentTurn", "parentAndPeerCommunicationUseTheSameManager",
                "forkContextIsBoundedAndNeverCopiesSystemRules", "childWaitYieldsTheOnlyExecutionSlot",
                "childCannotWaitOnParentOrPeers", "closingOneChildDoesNotCancelItsPeers",
                "cancellationIsIdempotentAndIdleChildrenRemainReusable", "childFailureIsReportedWithoutBlockingSettlement",
                "restoredRunningTaskWaitsForExplicitRecoveryAndKeepsItsUserMessage", "persistenceFailureNeverStartsAnUnreviewableChild",
                "waitToolCollectsResultsExactlyOnce", "restoredRootInboxKeepsRevisionAndConsumesDurably",
                "busyCancellationDoesNotRecursivelyClosePeersAndCanReuseContext", "closingAParentClosesDescendantsWithoutStackRecursion",
                "recoveryBeforeFirstUserCheckpointDoesNotLoseTheTask", "closedHistoryCannotHideNewerRestoredWork",
                "resultBatchesStayBoundedAndFullResultsAreReadable", "usageAndFactoryPermissionConfigurationSurviveIdleReuse",
                "parentGoalCannotCompleteBeforeChildrenSettleAndResultsAreCollected",
                "childFinalWaitsForAndIntegratesItsDelegatedResult", "cancellingAChildOnlyStopsItsOwnedDescendants",
                "failedFollowupDoesNotExposeThePreviousFinalAnswer", "unacknowledgedResultsReplayAfterRestartUntilAFinalAnswer",
                "longMailboxMessagesReachTheModelWithoutPreviewTruncation", "recoveredWorkCannotStartFromAConcurrencySettingChange",
                "aPersistedUserStopPreventsAutomaticSettlement"};
        int failures = 0;
        for (String name : tests) {
            try { SubAgentRegressionTest.class.getDeclaredMethod(name).invoke(null); System.out.println("PASS " + name); }
            catch (Exception error) { failures++; System.out.println("FAIL " + name + ": " + (error.getCause() == null ? error : error.getCause())); }
        }
        if (failures != 0) throw new AssertionError(failures + " sub-agent tests failed");
        System.out.println(tests.length + " sub-agent tests passed");
    }
}
