import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.Goal;
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
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual worker threads, durable result cursors, safe fork boundaries and explicit task dispatch. */
public final class SubAgentTaskLifecycleRegressionTest {
    interface Script {
        LlmClient.Reply run(Fixture fixture, SubAgentManager.Record record, List<Message> messages, LlmClient.Sink sink) throws Exception;
    }
    interface ParentScript { LlmClient.Reply run(Fixture fixture, List<Message> messages) throws Exception; }
    static final class Store implements SubAgentManager.Store {
        final Map<String, SubAgentManager.Record> saved = new LinkedHashMap<String, SubAgentManager.Record>();
        public synchronized List<SubAgentManager.Record> load() throws Exception {
            List<SubAgentManager.Record> result = new ArrayList<SubAgentManager.Record>();
            for (SubAgentManager.Record value : saved.values()) result.add(copy(value));
            return result;
        }
        public synchronized void save(SubAgentManager.Record value) throws Exception { saved.put(value.id, copy(value)); }
    }
    static final class Fixture implements SubAgentManager.ContextFactory {
        final Store store;
        final SubAgentManager manager;
        final AgentLoop root;
        final AtomicInteger childCalls = new AtomicInteger(), rootCalls = new AtomicInteger();
        volatile boolean rootFailure;
        Script script;
        ParentScript parentScript;
        final ToolRegistry rootTools = new ToolRegistry();
        Fixture() throws Exception { this(new Store()); }
        Fixture(Store store) throws Exception {
            this.store = store;
            root = new AgentLoop(new LlmClient(new LlmClient.Config("http://fixture", "private-key", "parent-original")) {
                public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
                    rootCalls.incrementAndGet();
                    if (rootFailure) { Reply reply = new Reply(); reply.error = "HTTP 503"; return reply; }
                    if (parentScript != null) try { return parentScript.run(Fixture.this, messages); }
                    catch (Exception invalid) { throw new IllegalStateException(invalid); }
                    return text("parent response");
                }
            }, rootTools, new AgentLoop.Quiet());
            root.bindSession(1); root.reset("captured system"); root.setAutomaticDelegation(true);
            manager = new SubAgentManager(2, this, store); manager.attachRoot(root);
        }
        public JSONObject capture(AgentLoop parent) throws Exception { return parent.captureChildContext(); }
        public AgentLoop create(final SubAgentManager.Record record, AgentLoop.Listener listener, final SubAgentManager shared) {
            LlmClient client = new LlmClient(new LlmClient.Config("http://fixture", "fixture", "child")) {
                public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
                    childCalls.incrementAndGet();
                    try { return script == null ? text("done " + assigned(messages)) : script.run(Fixture.this, record, messages, sink); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                }
            };
            ToolRegistry tools = new ToolRegistry(); SubAgentTools.register(tools, shared, record.id);
            AgentLoop child = new AgentLoop(client, tools, listener); child.reset("new child system"); return child;
        }
        String spawn(boolean fork) throws Exception { return manager.spawn("main", "worker", "first assignment", fork).getString("id"); }
        void settled() throws Exception {
            manager.waitFor("main", null, 5000);
            check(!manager.hasPendingWork(), "Worker did not settle");
        }
    }
    static SubAgentManager.Record copy(SubAgentManager.Record record) throws Exception {
        return SubAgentManager.Record.fromJson(new JSONObject(record.toJson().toString()));
    }
    static LlmClient.Reply text(String content) { LlmClient.Reply reply = new LlmClient.Reply(); reply.content = content; return reply; }
    static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    static void await(CountDownLatch latch) throws Exception { check(latch.await(5, TimeUnit.SECONDS), "Latch timeout"); }
    static String assigned(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) if (messages.get(i).delegatedRequest != null) return messages.get(i).delegatedRequest;
        return "";
    }
    static String repeat(int count) { StringBuilder out = new StringBuilder(); for (int i = 0; i < count; i++) out.append('R'); return out.toString(); }
    static JSONArray toolCalls(String id) throws Exception {
        return new JSONArray().put(new JSONObject().put("id", id).put("type", "function")
                .put("function", new JSONObject().put("name", "probe").put("arguments", "{}")));
    }

    static void defaultSpawnIsIndependentAndCapturesParentConfiguration() throws Exception {
        Fixture fixture = new Fixture();
        List<Message> prior = new ArrayList<Message>(); prior.add(Message.user("prior secret project details"));
        fixture.root.loadHistory("captured system", prior);
        ToolRegistry tools = new ToolRegistry(); SubAgentTools.register(tools, fixture.manager, "main");
        String id = new JSONObject(tools.get("spawn_agent").run(new JSONObject().put("task", "ordinary assignment"))).getString("id");
        fixture.settled(); SubAgentManager.Record record = fixture.manager.find(id);
        check(record.forkHistory.length() == 0 && !record.history.toString().contains("prior secret project details"), "Default spawn implicitly forked");
        check(record.forkConfig.getJSONObject("client").getString("model").equals("parent-original"), "Dispatch config was not captured");
        check(record.tasks.length() == 1 && record.currentTaskId.equals(record.tasks.getJSONObject(0).getString("taskId")), "Task identity missing");
    }
    static void ordinaryMailNeverBecomesAnIdleTask() throws Exception {
        Fixture fixture = new Fixture(); String id = fixture.spawn(false); fixture.settled();
        String original = fixture.manager.find(id).result;
        fixture.manager.send("main", id, "message only", "message");
        check(fixture.childCalls.get() == 1 && fixture.manager.find(id).tasks.length() == 1
                && fixture.manager.find(id).inbox.length() == 1 && !fixture.manager.hasPendingWork(), "Ordinary mail launched another task");
        check(fixture.manager.find(id).result.equals(original), "Mail overwrote the completed result");
    }
    static void explicitTasksKeepEarlierResultsAndDistinctIds() throws Exception {
        Fixture fixture = new Fixture(); String id = fixture.spawn(false); fixture.settled();
        SubAgentManager.Record before = fixture.manager.find(id);
        String firstTask = before.currentTaskId, firstResult = before.results.getJSONObject(0).getString("resultId");
        fixture.manager.send("main", id, "second assignment", "task"); fixture.settled();
        SubAgentManager.Record after = fixture.manager.find(id);
        check(after.tasks.length() == 2 && after.results.length() == 2 && !after.currentTaskId.equals(firstTask), "Reuse overwrote the task attempt");
        check(after.results.getJSONObject(0).getString("resultId").equals(firstResult)
                && after.results.getJSONObject(0).getString("content").contains("first assignment"), "Earlier immutable result disappeared");
        check(fixture.manager.readResult("main", id, firstResult, 0, 8000).getString("result").contains("first assignment"), "Older result cannot be read by id");
    }
    static void failedFollowupDoesNotEraseCompletedEvidence() throws Exception {
        Fixture fixture = new Fixture(); String id = fixture.spawn(false); fixture.settled();
        fixture.script = (f, record, messages, sink) -> { LlmClient.Reply reply = new LlmClient.Reply(); reply.error = "HTTP 400"; return reply; };
        fixture.manager.send("main", id, "failing task", "task"); fixture.settled();
        SubAgentManager.Record record = fixture.manager.find(id);
        check(SubAgentManager.FAILED.equals(record.status) && record.results.length() == 2 && record.result.isEmpty(), "Failure displayed a stale current final");
        check(record.results.getJSONObject(0).getString("content").contains("first assignment")
                && record.results.getJSONObject(1).getString("error").length() > 0, "Failure lost prior evidence or its distinct error");
        boolean rejected = false; try { fixture.manager.send("main", id, "late message", "message"); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected && fixture.childCalls.get() == 2, "Message revived a failed task");
    }
    static void emptyFollowupCannotReuseAnOldAnswer() throws Exception {
        Fixture fixture = new Fixture(); String id = fixture.spawn(false); fixture.settled();
        fixture.script = (f, record, messages, sink) -> text("");
        fixture.manager.send("main", id, "empty followup", "task"); fixture.settled();
        SubAgentManager.Record record = fixture.manager.find(id);
        check(SubAgentManager.FAILED.equals(record.status) && record.results.getJSONObject(1).getString("content").isEmpty(), "Empty answer reused previous task evidence");
    }
    static void stoppedTaskPreservesPartialAndRejectsLateResurrection() throws Exception {
        Fixture fixture = new Fixture(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        fixture.script = (f, record, messages, sink) -> { sink.onContent("observed partial evidence"); started.countDown(); await(release); return text("late final"); };
        String id = fixture.spawn(false); await(started);
        try {
            boolean rejected = false; try { fixture.manager.close("main", id); } catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "close silently cancelled active work");
            fixture.manager.stopFromUser(id);
            rejected = false; try { fixture.manager.send("main", id, "late message", "message"); } catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "Stopped instance was revived by mail");
        } finally { release.countDown(); fixture.manager.awaitSettled(5000, () -> true); fixture.settled(); }
        SubAgentManager.Record record = fixture.manager.find(id);
        check(SubAgentManager.STOPPED.equals(record.status) && record.results.length() == 1
                && record.results.getJSONObject(0).getString("partial").contains("observed partial evidence")
                && record.results.getJSONObject(0).getString("content").isEmpty(), "Late completion replaced stopped outcome or lost partial output");
        fixture.manager.close("main", id);
        check(fixture.manager.find(id).results.length() == 1, "Closing terminal instance deleted the result");
    }
    static void reportsSeparateProgressPartialAndFinal() throws Exception {
        Fixture fixture = new Fixture();
        fixture.script = (f, record, messages, sink) -> {
            f.manager.report(record.id, "progress", "checking resources", "resources");
            f.manager.report(record.id, "partial", "verified manifest", "");
            f.manager.report(record.id, "error", "one optional file absent", "");
            return text("final verified output");
        };
        String id = fixture.spawn(false); fixture.settled(); SubAgentManager.Record record = fixture.manager.find(id);
        JSONObject task = record.tasks.getJSONObject(0), result = record.results.getJSONObject(0);
        check(task.getString("partial").equals("verified manifest") && result.getString("content").equals("final verified output"), "Progress replaced final output");
        check(task.getString("progress").equals("checking resources") && record.events.toString().contains("one optional file absent"), "Report history not preserved");
        check(fixture.manager.peekInbox("main").getJSONArray("messages").length() == 3 && fixture.manager.shouldWakeRoot(),
                "Explicit partial/error reports did not reach or wake the parent");
    }
    static void longStoppedAndFailedPartialIsDeliveredCompletely() throws Exception {
        for (boolean stopped : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            String partial = repeat(7999) + "😀" + repeat(17000);
            fixture.script = (f, record, messages, sink) -> {
                sink.onContent(partial); started.countDown(); await(release);
                LlmClient.Reply reply = text(""); reply.error = "HTTP 400"; return reply;
            };
            String id = fixture.spawn(false); await(started);
            try { if (stopped) fixture.manager.stopFromUser(id); } finally { release.countDown(); fixture.settled(); }
            StringBuilder delivered = new StringBuilder(); int chunks = 0;
            while (fixture.manager.hasUncollectedResults()) {
                JSONArray batch = fixture.manager.collectResults("main", id).getJSONArray("agents");
                for (int i = 0; i < batch.length(); i++) {
                    JSONObject chunk = batch.getJSONObject(i); String part = chunk.getString("partial");
                    check(chunk.getString("stream").equals("partial") && chunk.getString("result").isEmpty(), "Partial was mislabeled as final evidence");
                    check(part.isEmpty() || !Character.isHighSurrogate(part.charAt(part.length() - 1)), "Unicode split at partial boundary");
                    delivered.append(part); chunks++;
                }
                fixture.manager.acknowledgeResults();
                if (fixture.manager.hasUncollectedResults()) check(fixture.manager.find("main").acknowledgedResults.length() == 0, "Partial range was acknowledged too soon");
            }
            check(chunks == 4 && partial.equals(delivered.toString()), "Long stopped/failed partial was truncated");
        }
    }
    static void distinctLongFinalAndPartialStreamsBothReachTheParent() throws Exception {
        Fixture fixture = new Fixture(); String partial = "partial:" + repeat(22000), content = "final:" + repeat(26000);
        fixture.script = (f, record, messages, sink) -> { sink.onContent(partial); return text(content); };
        fixture.spawn(false); fixture.settled(); StringBuilder finalRead = new StringBuilder(), partialRead = new StringBuilder();
        int pieces = 0;
        while (fixture.manager.hasUncollectedResults()) {
            JSONArray batch = fixture.manager.collectResults("main", null).getJSONArray("agents");
            for (int i = 0; i < batch.length(); i++) {
                JSONObject chunk = batch.getJSONObject(i); pieces++;
                finalRead.append(chunk.getString("result")); partialRead.append(chunk.getString("partial"));
            }
        }
        check(pieces == 7 && finalRead.toString().equals(content) && partialRead.toString().equals(partial), "One long stream was lost or overwritten");
    }
    static void parentCheckpointPersistsChunksAndRestartSkipsOnlyKnownPieces() throws Exception {
        Store store = new Store(); Fixture fixture = new Fixture(store);
        fixture.script = (f, record, messages, sink) -> text(repeat(16000)); fixture.spawn(false); fixture.settled();
        List<Message> durable = new ArrayList<Message>();
        AgentLoop.Recorder recorder = new AgentLoop.Recorder() {
            public void record(long sid, Message message) {
                if (!Message.SYSTEM.equals(message.role)) try { durable.add(Message.fromCheckpointJson(message.toCheckpointJson())); }
                catch (Exception invalid) { throw new IllegalStateException(invalid); }
            }
            public void replace(long sid, List<Message> history) { throw new AssertionError("Unexpected compaction"); }
        };
        fixture.root.setSubAgents(fixture.manager); fixture.root.setRecorder(recorder); fixture.rootFailure = true;
        fixture.root.submit("consume first chunk", 1, fixture.root.generation(), 1, null);
        check(durable.size() == 2 && Message.isCoordination(durable.get(1).content), "First collected chunk was not recorded durably");
        String firstChunkId = durable.get(1).coordinationIds.getString(0);
        Fixture restarted = new Fixture(store); restarted.root.setSubAgents(restarted.manager); restarted.root.setRecorder(recorder);
        List<Message> rawRows = new ArrayList<Message>();
        for (Message stored : durable) rawRows.add(new Message(stored.role, stored.content));
        restarted.root.loadHistory("restored system", rawRows);
        restarted.root.submit("resume collection", 1, restarted.root.generation(), 2, null);
        int appearances = 0, notes = 0;
        for (Message stored : durable) if (Message.isCoordination(stored.content)) {
            notes++; stored.restoreCoordinationIds();
            for (int i = 0; i < stored.coordinationIds.length(); i++) if (firstChunkId.equals(stored.coordinationIds.optString(i))) appearances++;
        }
        check(notes == 2 && appearances == 1 && !restarted.manager.hasUncollectedResults(), "Restart lost or repeated durable result chunks");
    }
    static void parentPersistenceFailureNeverAcknowledgesCollectedEvidence() throws Exception {
        Store store = new Store(); Fixture fixture = new Fixture(store); fixture.spawn(false); fixture.settled();
        fixture.root.setSubAgents(fixture.manager);
        fixture.root.setRecorder(new AgentLoop.Recorder() {
            public void record(long sid, Message message) { if (Message.isCoordination(message.content)) throw new IllegalStateException("fixture disk failure"); }
            public void replace(long sid, List<Message> history) { }
        });
        fixture.root.submit("collect evidence", 1, fixture.root.generation(), 1, null);
        check(fixture.rootCalls.get() == 0 && fixture.manager.hasUncollectedResults()
                && fixture.manager.find("main").acknowledgedResults.length() == 0
                && new Fixture(store).manager.hasUncollectedResults(), "Storage failure confirmed evidence that did not reach durable parent history");
    }
    static void partialChunkCannotBeAcknowledgedAndRestartReplaysIt() throws Exception {
        Store store = new Store(); Fixture fixture = new Fixture(store);
        fixture.script = (f, record, messages, sink) -> text(repeat(17000));
        String id = fixture.spawn(false); fixture.settled();
        JSONObject first = fixture.manager.collectResults("main", id).getJSONArray("agents").getJSONObject(0);
        String resultId = first.getString("resultId");
        check(first.getInt("endOffset") == 8000 && fixture.manager.hasUncollectedResults(), "First truncated chunk completed delivery");
        fixture.manager.acknowledgeResults();
        check(fixture.manager.find("main").acknowledgedResults.optInt(resultId, 0) == 0, "Truncated result was acknowledged");
        Fixture restarted = new Fixture(store);
        check(restarted.manager.collectResults("main", id).getJSONArray("agents").getJSONObject(0).getInt("offset") == 0, "Restart skipped unconfirmed evidence");
        while (restarted.manager.hasUncollectedResults()) restarted.manager.collectResults();
        restarted.manager.acknowledgeResults();
        check(new Fixture(store).manager.hasUncollectedResults() == false, "Fully confirmed result replayed after restart");
    }
    static void skippedResultRangesDoNotCountAsDelivered() throws Exception {
        Fixture fixture = new Fixture(); fixture.script = (f, record, messages, sink) -> text(repeat(16000));
        String id = fixture.spawn(false); fixture.settled();
        fixture.manager.readResult("main", id, "", 8000, 8000); fixture.manager.acknowledgeResults();
        check(fixture.manager.hasUncollectedResults() && fixture.manager.find("main").acknowledgedResults.length() == 0, "Unseen leading range was acknowledged");
        fixture.manager.readResult("main", id, "", 0, 8000); check(fixture.manager.hasUncollectedResults(), "Leading chunk hid remaining evidence");
        fixture.manager.readResult("main", id, "", 8000, 8000); check(!fixture.manager.hasUncollectedResults(), "Contiguous complete delivery did not settle");
    }
    static void goalCompletionRequiresAllStableResultChunks() throws Exception {
        Fixture fixture = new Fixture(); fixture.root.setSubAgents(fixture.manager); fixture.root.setGoal("collect complete evidence");
        fixture.script = (f, record, messages, sink) -> text(repeat(16000));
        String id = fixture.spawn(false); fixture.settled(); fixture.manager.collectResults();
        check(fixture.root.closeGoal(Goal.COMPLETE, "").startsWith("错误"), "Goal completed with truncated evidence");
        fixture.manager.collectResults();
        check(fixture.root.closeGoal(Goal.COMPLETE, "").startsWith("{"), "Complete evidence blocked goal completion");
    }
    static void forkKeepsAllPairedHistoryAndDeepCopies() throws Exception {
        Fixture fixture = new Fixture(); List<Message> history = new ArrayList<Message>();
        for (int i = 0; i < 25; i++) history.add(Message.user("retained history " + i));
        history.add(Message.assistant("observed invocation", toolCalls("complete")));
        history.add(Message.toolResult("complete", "complete paired evidence"));
        fixture.root.loadHistory("parent captured policy", history);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        fixture.script = (f, record, messages, sink) -> {
            check(messages.size() == 29 && messages.get(0).content.equals("parent captured policy")
                    && messages.get(27).toolCallId.equals("complete"), "Fork truncated or lost paired tool records");
            started.countDown(); await(release); return text("fork completed");
        };
        String id = fixture.spawn(true); await(started);
        try {
            fixture.root.setEnvironment("changed parent policy", "/new-path");
            SubAgentManager.Record record = fixture.manager.find(id);
            record.forkHistory.getJSONObject(0).put("content", "mutated external copy");
            check(fixture.manager.find(id).forkHistory.getJSONObject(0).getString("content").equals("parent captured policy"), "Fork has a shared mutable state");
        } finally { release.countDown(); fixture.settled(); }
    }
    static void forkDuringRetargetUsesOriginalRequestAndCompleteBoundary() throws Exception {
        Fixture fixture = new Fixture(); fixture.root.setSubAgents(fixture.manager);
        SubAgentTools.register(fixture.rootTools, fixture.manager, "main");
        List<Message> history = new ArrayList<Message>(); history.add(Message.user("retained earlier user"));
        fixture.root.loadHistory("original environment", history);
        fixture.parentScript = (f, messages) -> {
            if (f.rootCalls.get() > 1) return text("parent summary");
            f.root.retarget(new LlmClient(new LlmClient.Config("http://other", "other-key", "changed-model")) {
                public Reply send(List<Message> next, JSONArray tools, Sink sink) { return text("parent summary"); }
            }, new ToolRegistry());
            f.root.setEnvironment("changed environment", "/changed");
            LlmClient.Reply reply = new LlmClient.Reply();
            reply.toolCalls = new JSONArray().put(new JSONObject().put("id", "spawn-boundary").put("type", "function")
                    .put("function", new JSONObject().put("name", "spawn_agent")
                            .put("arguments", new JSONObject().put("task", "fork assignment").put("fork", true).toString())));
            return reply;
        };
        fixture.script = (f, record, messages, sink) -> {
            check(record.forkConfig.getJSONObject("client").getString("model").equals("parent-original")
                    && record.forkConfig.getString("system").equals("original environment"), "Retarget changed dispatch configuration");
            check(messages.get(0).content.equals("original environment"), "Fork effective system disagrees with its captured request");
            for (Message message : messages) check(message.toolCalls == null, "Incomplete spawning tool block was copied into fork");
            return text("captured fork completed");
        };
        fixture.root.submit("delegate with fork", 1, fixture.root.generation(), 1, null);
        fixture.settled();
        check(fixture.childCalls.get() == 1 && fixture.manager.list("main", 0).getJSONArray("agents").getJSONObject(0)
                .getString("result").equals("captured fork completed"), "Fork did not complete from its captured context");
    }
    static void settlementTimeoutDoesNotTriggerAnotherModelRequest() throws Exception {
        Fixture fixture = new Fixture(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), waiting = new CountDownLatch(1), finished = new CountDownLatch(1);
        fixture.script = (f, record, messages, sink) -> { started.countDown(); await(release); return text("settled evidence"); };
        fixture.spawn(false); await(started); AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread waiter = new Thread(() -> {
            waiting.countDown();
            try { fixture.manager.awaitSettled(1, () -> true); } catch (Throwable error) { failure.set(error); }
            finally { finished.countDown(); }
        });
        waiter.start(); await(waiting);
        try { check(!finished.await(100, TimeUnit.MILLISECONDS) && fixture.childCalls.get() == 1, "Empty timeout escaped local waiting"); }
        finally { release.countDown(); await(finished); fixture.settled(); }
        check(failure.get() == null, "Local settlement failed: " + failure.get());
    }
    static void stoppedAndFailedMainAreNotRevivedByChildEvents() throws Exception {
        for (boolean failed : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(); fixture.root.setSubAgents(fixture.manager);
            fixture.rootFailure = failed; fixture.root.submit("start ordinary turn", 1, fixture.root.generation(), 1, null);
            if (!failed) fixture.root.cancel();
            if (!failed) fixture.manager.resumePending();
            fixture.root.setAutomaticDelegation(true);
            fixture.spawn(false); fixture.settled();
            check(!fixture.root.resumeForChildEvents(1, -1) && fixture.rootCalls.get() == 1, "Late event revived stopped or failed main");
        }
    }
    static void completedGoalRejectsLateChildReportWake() throws Exception {
        Fixture fixture = new Fixture(); fixture.root.setSubAgents(fixture.manager); fixture.root.setGoal("finish evidence");
        String id = fixture.spawn(false); fixture.settled(); fixture.manager.collectResults(); fixture.manager.acknowledgeResults();
        check(fixture.root.closeGoal(Goal.COMPLETE, "").startsWith("{"), "Completed goal fixture did not close");
        fixture.manager.send(id, "main", "late report after completion", "message");
        check(!fixture.manager.shouldWakeRoot() && !fixture.root.resumeForChildEvents(1, -1) && fixture.rootCalls.get() == 0,
                "Late report revived a completed goal");
        Fixture restored = new Fixture(fixture.store); restored.root.setSubAgents(restored.manager);
        restored.root.armRecoveredChildEvents();
        check(!restored.root.resumeForChildEvents(1, -1), "Recovery resurrected a terminal goal's late message");
    }
    static void validIdleParentConsumesEventsWithoutAnotherHumanTask() throws Exception {
        Fixture fixture = new Fixture(); fixture.root.setSubAgents(fixture.manager);
        fixture.root.submit("initial task", 1, fixture.root.generation(), 1, null);
        fixture.spawn(false); fixture.settled();
        check(fixture.manager.shouldWakeRoot() && fixture.root.resumeForChildEvents(1, -1)
                && fixture.rootCalls.get() == 2 && !fixture.manager.hasUncollectedResults(), "Idle parent did not aggregate durable child events");
        for (Message message : fixture.root.historySnapshot())
            check(!message.content.equals("resume collaboration") && !message.content.equals("continue"), "Wake invented a human instruction");
    }
    static void nestedDispatchKeepsCapturedHumanPathScope() throws Exception {
        Fixture fixture = new Fixture(); fixture.root.setCapturedTaskPaths(new JSONArray().put("/approved/project"));
        fixture.script = (f, record, messages, sink) -> {
            AgentLoop child = f.manager.runtimeLoops().get(0);
            check(record.forkConfig.getJSONArray("taskPaths").getString(0).equals("/approved/project")
                    && child.captureChildContext().getJSONArray("taskPaths").getString(0).equals("/approved/project"),
                    "Nested child capture replaced inherited human scope with model task paths");
            return text("scope preserved");
        };
        fixture.spawn(false); fixture.settled();
        check(fixture.manager.list("main", 0).getJSONArray("agents").getJSONObject(0).getString("result").equals("scope preserved"), "Scope fixture failed");
    }
    static void legacyQueuedAssignmentsKeepDistinctIdentitiesAndOutcomes() throws Exception {
        Store store = new Store();
        JSONObject legacy = new JSONObject().put("id", "legacy_worker").put("parentId", "main").put("name", "legacy")
                .put("task", "completed old work").put("status", SubAgentManager.QUEUED).put("result", "old verified evidence")
                .put("sessionId", 2).put("pending", new JSONArray()
                        .put(new JSONObject().put("from", "main").put("text", "queued first"))
                        .put(new JSONObject().put("from", "main").put("text", "queued second")));
        store.save(SubAgentManager.Record.fromJson(legacy)); Fixture fixture = new Fixture(store);
        SubAgentManager.Record migrated = fixture.manager.find("legacy_worker");
        check(migrated.tasks.length() == 3 && !migrated.pending.getJSONObject(0).getString("taskId")
                .equals(migrated.pending.getJSONObject(1).getString("taskId")), "Legacy queue assigned every message to the same task");
        fixture.manager.resumePending(); fixture.settled(); SubAgentManager.Record completed = fixture.manager.find("legacy_worker");
        check(completed.results.length() == 3 && completed.results.getJSONObject(0).getString("content").equals("old verified evidence")
                && completed.results.getJSONObject(1).getString("content").contains("queued first")
                && completed.results.getJSONObject(2).getString("content").contains("queued second"), "Legacy reuse overwrote or skipped an outcome");
        check(!completed.results.getJSONObject(1).getString("taskId").equals(completed.results.getJSONObject(2).getString("taskId")), "Legacy outcomes share task identity");
    }
    static void trustedTaskAfterGlobalStopRunsOnlyTheSelectedChild() throws Exception {
        Fixture fixture = new Fixture(); fixture.root.setSubAgents(fixture.manager);
        fixture.root.submit("parent initial task", 1, fixture.root.generation(), 1, null);
        String selected = fixture.spawn(false); fixture.settled();
        String peer = fixture.manager.spawn("main", "peer", "peer old task", false).getString("id"); fixture.settled();
        fixture.root.cancel(); int before = fixture.childCalls.get();
        for (String kind : new String[]{"message", "task"}) {
            boolean rejected = false; try { fixture.manager.send("main", selected, "model late request", kind); }
            catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "Model request bypassed global stop before explicit user restart");
        }
        boolean rejected = false; try { fixture.manager.sendFromUser(selected, "ordinary message", "message"); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "Ordinary user mail bypassed global stop");
        fixture.manager.sendFromUser(selected, "explicit selected work", "task"); fixture.settled();
        check(fixture.childCalls.get() == before + 1 && fixture.manager.find(selected).results.length() == 2
                && fixture.manager.find(peer).tasks.length() == 1 && fixture.manager.find(peer).results.length() == 1,
                "Trusted restart lost evidence or re-ran another child");
        SubAgentManager.Record root = fixture.manager.find("main");
        check(root.userRestartOnly && !root.rootWakeAllowed && !fixture.root.resumeForChildEvents(1, -1)
                && fixture.rootCalls.get() == 1, "Selected restart revived the old parent");
        for (String kind : new String[]{"message", "task"}) {
            rejected = false; try { fixture.manager.send("main", peer, "model still stopped", kind); }
            catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "Selected user task granted the stopped model unrelated authority");
        }
        Fixture restored = new Fixture(fixture.store); restored.root.setSubAgents(restored.manager);
        restored.root.armRecoveredChildEvents();
        check(!restored.manager.shouldWakeRoot() && !restored.root.resumeForChildEvents(1, -1), "Recovery revived the old stopped parent");
    }
    static void trustedRestartWaitsForSelectedCleanup() throws Exception {
        Fixture fixture = new Fixture(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        fixture.script = (f, record, messages, sink) -> { started.countDown(); await(release); return text("late old result"); };
        String id = fixture.spawn(false); await(started); fixture.manager.cancelAll();
        try {
            boolean rejected = false; try { fixture.manager.sendFromUser(id, "new task during cleanup", "task"); }
            catch (IllegalStateException expected) { rejected = true; }
            check(rejected && fixture.manager.find(id).tasks.length() == 1, "New task overlapped old cleanup");
        } finally { release.countDown(); fixture.manager.awaitSettled(5000, () -> true); fixture.settled(); }
        fixture.script = (f, record, messages, sink) -> text("new selected result");
        fixture.manager.sendFromUser(id, "new task after cleanup", "task"); fixture.settled();
        check(fixture.manager.find(id).results.length() == 2 && fixture.manager.find(id).result.equals("new selected result"), "Completed cleanup blocked explicit reuse");
    }
    static void oversizedErrorCannotStarveResultDelivery() throws Exception {
        Store store = new Store(); SubAgentManager.Record failed = SubAgentManager.Record.fromJson(new JSONObject()
                .put("id", "oversized_error").put("parentId", "main").put("task", "failed task").put("status", "failed")
                .put("error", repeat(80000)).put("sessionId", 2));
        store.save(failed); Fixture fixture = new Fixture(store);
        JSONObject chunk = fixture.manager.collectResults("main", null).getJSONArray("agents").getJSONObject(0);
        check(chunk.getString("error").length() == 2000 && chunk.getBoolean("errorTruncated")
                && !fixture.manager.hasUncollectedResults(), "Oversized error starved its result batch");
        fixture.manager.acknowledgeResults();
        check(!new Fixture(store).manager.hasUncollectedResults() && fixture.manager.find(failed.id).error.length() == 80000,
                "Clipped delivery destroyed the stored error or could not be acknowledged");
    }
    static void archivedResultIndexIsBoundedAndNeverConfirmsEvidence() throws Exception {
        Fixture fixture = new Fixture(); String id = fixture.spawn(false); fixture.settled();
        for (int i = 1; i < 27; i++) { fixture.manager.send("main", id, "archived task " + i, "task"); fixture.settled(); }
        ToolRegistry tools = new ToolRegistry(); SubAgentTools.register(tools, fixture.manager, "main");
        JSONObject first = new JSONObject(tools.get("read_agent_result").run(new JSONObject().put("target", id).put("list", true)));
        JSONObject second = new JSONObject(tools.get("read_agent_result").run(new JSONObject().put("target", id).put("list", true)
                .put("cursor", first.getInt("nextCursor"))));
        check(first.getJSONArray("results").length() == 20 && second.getJSONArray("results").length() == 7
                && second.isNull("nextCursor") && first.getInt("totalResults") == 27, "Archived result index did not paginate all old identities");
        check(fixture.manager.hasUncollectedResults() && fixture.manager.find("main").deliveredResults.length() == 0
                && fixture.manager.find("main").acknowledgedResults.length() == 0, "Listing result identities confirmed unseen evidence");
        String archived = first.getJSONArray("results").getJSONObject(0).getString("resultId");
        check(new JSONObject(tools.get("read_agent_result").run(new JSONObject().put("target", id).put("result_id", archived)))
                .getString("result").contains("first assignment"), "Compacted context cannot discover and re-read an older result");
    }
    static void trustedRestartDoesNotRunCancelledPeerQueues() throws Exception {
        Fixture fixture = new Fixture(); fixture.manager.setMaxParallel(1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        fixture.script = (f, record, messages, sink) -> { started.countDown(); await(release); return text("late cancelled work"); };
        String selected = fixture.spawn(false); await(started);
        String queued = fixture.manager.spawn("main", "old-queued", "cancelled queued work", false).getString("id");
        check(SubAgentManager.QUEUED.equals(fixture.manager.find(queued).status), "Fixture did not queue peer work");
        fixture.manager.cancelAll(); release.countDown(); fixture.manager.awaitSettled(5000, () -> true); fixture.settled();
        fixture.script = (f, record, messages, sink) -> text("selected fresh evidence");
        fixture.manager.sendFromUser(selected, "fresh selected task", "task"); fixture.settled();
        check(fixture.childCalls.get() == 2 && fixture.manager.find(queued).tasks.length() == 1
                && SubAgentManager.STOPPED.equals(fixture.manager.find(queued).status)
                && fixture.manager.find(queued).results.getJSONObject(0).getString("content").isEmpty(),
                "Trusted selected restart resurrected a cancelled peer queue");
    }
    static void escapedMailboxCannotBePermanentlySkipped() throws Exception {
        Fixture fixture = new Fixture(); String id = fixture.spawn(false); fixture.settled();
        fixture.manager.collectResults(); fixture.manager.acknowledgeResults();
        StringBuilder text = new StringBuilder("X"); for (int i = 1; i < 32000; i++) text.append('\n');
        fixture.manager.send(id, "main", text.toString(), "message");
        JSONObject peek = fixture.manager.peekInbox("main");
        check(peek.getJSONArray("messages").length() == 1
                && peek.getJSONArray("messages").getJSONObject(0).getString("text").equals(text.toString()), "Escaped mailbox peek returned an empty batch");
        JSONObject batch = fixture.manager.collectResults("main", null);
        check(batch.getJSONArray("inbox").length() == 1
                && batch.getJSONArray("inbox").getJSONObject(0).getString("text").equals(text.toString()), "Escaped mail permanently exceeded collection budget");
        fixture.manager.acknowledgeResults(); check(!fixture.manager.hasUncollectedResults(), "Escaped mail could not be acknowledged");
    }
    static void encodedResultChunkAdaptsWithoutLosingControlCharacters() throws Exception {
        Store store = new Store(); StringBuilder text = new StringBuilder(), error = new StringBuilder();
        for (int i = 0; i < 10000; i++) text.append('\u0001');
        for (int i = 0; i < 2000; i++) error.append('\u0002');
        SubAgentManager.Record record = SubAgentManager.Record.fromJson(new JSONObject().put("id", "encoded_result")
                .put("parentId", "main").put("task", "encoded evidence").put("status", "failed")
                .put("result", text.toString()).put("error", error.toString()).put("sessionId", 2));
        store.save(record); Fixture fixture = new Fixture(store); StringBuilder received = new StringBuilder(); int pieces = 0;
        while (fixture.manager.hasUncollectedResults()) {
            JSONObject batch = fixture.manager.collectResults("main", null);
            check(batch.toString().length() < 61000 && batch.getJSONArray("agents").length() > 0, "Encoded result exceeded budget or starved");
            JSONArray chunks = batch.getJSONArray("agents");
            for (int i = 0; i < chunks.length(); i++) { received.append(chunks.getJSONObject(i).getString("result")); pieces++; }
        }
        fixture.manager.acknowledgeResults();
        check(pieces == 2 && received.toString().equals(text.toString()) && !new Fixture(store).manager.hasUncollectedResults(),
                "Encoded result shortened, lost a character or remained unconfirmed");
    }
    static void childOnlyRecoveryRestartsOnlyPersistedUserAssignments() throws Exception {
        Fixture fixture = new Fixture(); fixture.root.setSubAgents(fixture.manager);
        String selected = fixture.spawn(false); fixture.settled();
        String peer = fixture.manager.spawn("main", "peer", "old peer task", false).getString("id"); fixture.settled();
        fixture.root.cancel(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        fixture.script = (f, record, messages, sink) -> { started.countDown(); await(release); return text("original worker resumed"); };
        fixture.manager.sendFromUser(selected, "new authorized assignment", "task"); await(started);
        Store snapshot = new Store();
        try {
            fixture.manager.sendFromUser(selected, "live user clarification", "message");
            for (SubAgentManager.Record value : fixture.store.load()) snapshot.save(value);
            // An interrupted old stop transaction must not revive a sibling that still looks queued.
            SubAgentManager.Record stale = snapshot.saved.get(peer);
            stale.status = SubAgentManager.QUEUED;
            stale.pending.put(new JSONObject().put("id", "old-pending-mail").put("from", "main").put("text", "old peer task")
                    .put("kind", "task").put("taskId", stale.currentTaskId)); snapshot.save(stale);
        } finally { release.countDown(); fixture.settled(); }
        Fixture restored = new Fixture(snapshot); restored.root.setSubAgents(restored.manager);
        check(restored.manager.resumeUserRestartOnlyPending(), "Explicit new child task was not recognized after process restart");
        restored.settled();
        check(restored.childCalls.get() == 1 && restored.manager.find(selected).results.length() == 2
                && restored.manager.find(selected).result.contains("new authorized assignment")
                && SubAgentManager.STOPPED.equals(restored.manager.find(peer).status), "Recovery ran stale sibling work or lost selected evidence");
        check(!restored.manager.shouldWakeRoot() && !restored.root.resumeForChildEvents(1, -1)
                && restored.rootCalls.get() == 0 && restored.manager.usageLease(selected) == SubAgentManager.DETACHED_USAGE_LEASE,
                "Child-only recovery restarted or billed the stopped parent");
    }
    static void restrictedUserRestartCannotBypassAnExhaustedGoalBudget() throws Exception {
        Fixture fixture = new Fixture(); fixture.root.setSubAgents(fixture.manager);
        String selected = fixture.spawn(false); fixture.settled();
        fixture.root.setGoal("bounded parent work"); fixture.root.setGoalBudget(1L);
        fixture.parentScript = (f, messages) -> { LlmClient.Reply reply = text("budget summary"); reply.promptTokens = 1L; return reply; };
        fixture.root.submit("finish bounded work", 1, fixture.root.generation(), 1, null);
        check(Goal.BUDGET_LIMITED.equals(fixture.root.goalStatus()), "Fixture did not reach its goal budget");
        fixture.root.cancel();
        Store restrictedStore = new Store();
        for (SubAgentManager.Record record : fixture.store.load()) {
            if (SubAgentManager.ROOT.equals(record.id)) {
                record.managerCancelled = false; record.userRestartOnly = true; record.rootWakeAllowed = false;
            }
            restrictedStore.save(record);
        }
        SubAgentManager restricted = new SubAgentManager(2, fixture, restrictedStore); restricted.attachRoot(fixture.root);
        fixture.root.setSubAgents(restricted);
        boolean rejected = false;
        try { restricted.sendFromUser(selected, "another detached assignment", "task"); }
        catch (IllegalStateException expected) { rejected = expected.getMessage().contains("预算"); }
        check(rejected && fixture.childCalls.get() == 1 && restricted.find(selected).tasks.length() == 1
                && !restricted.shouldWakeRoot(), "Restricted child restart bypassed the exhausted parent budget");
    }
    public static void main(String[] args) throws Exception {
        String[] names = {"defaultSpawnIsIndependentAndCapturesParentConfiguration", "ordinaryMailNeverBecomesAnIdleTask",
                "explicitTasksKeepEarlierResultsAndDistinctIds", "failedFollowupDoesNotEraseCompletedEvidence", "emptyFollowupCannotReuseAnOldAnswer",
                "stoppedTaskPreservesPartialAndRejectsLateResurrection", "reportsSeparateProgressPartialAndFinal",
                "longStoppedAndFailedPartialIsDeliveredCompletely", "distinctLongFinalAndPartialStreamsBothReachTheParent",
                "parentCheckpointPersistsChunksAndRestartSkipsOnlyKnownPieces", "parentPersistenceFailureNeverAcknowledgesCollectedEvidence",
                "partialChunkCannotBeAcknowledgedAndRestartReplaysIt", "skippedResultRangesDoNotCountAsDelivered",
                "goalCompletionRequiresAllStableResultChunks", "forkKeepsAllPairedHistoryAndDeepCopies",
                "forkDuringRetargetUsesOriginalRequestAndCompleteBoundary",
                "settlementTimeoutDoesNotTriggerAnotherModelRequest", "stoppedAndFailedMainAreNotRevivedByChildEvents",
                "completedGoalRejectsLateChildReportWake", "validIdleParentConsumesEventsWithoutAnotherHumanTask", "nestedDispatchKeepsCapturedHumanPathScope",
                "legacyQueuedAssignmentsKeepDistinctIdentitiesAndOutcomes", "trustedTaskAfterGlobalStopRunsOnlyTheSelectedChild",
                "trustedRestartWaitsForSelectedCleanup", "oversizedErrorCannotStarveResultDelivery",
                "archivedResultIndexIsBoundedAndNeverConfirmsEvidence", "trustedRestartDoesNotRunCancelledPeerQueues",
                "escapedMailboxCannotBePermanentlySkipped", "encodedResultChunkAdaptsWithoutLosingControlCharacters",
                "childOnlyRecoveryRestartsOnlyPersistedUserAssignments", "restrictedUserRestartCannotBypassAnExhaustedGoalBudget"};
        int failed = 0;
        for (String name : names) try {
            SubAgentTaskLifecycleRegressionTest.class.getDeclaredMethod(name).invoke(null); System.out.println("PASS " + name);
        } catch (Throwable error) { failed++; Throwable cause = error.getCause() == null ? error : error.getCause(); System.out.println("FAIL " + name + ": " + cause); cause.printStackTrace(); }
        if (failed > 0) throw new AssertionError(failed + " task lifecycle tests failed");
        System.out.println(names.length + " task lifecycle tests passed");
    }
}
