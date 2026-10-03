import android.os.SystemClock;
import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.Compactor;
import com.mkei.backcast.agent.Goal;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.TokenMeter;
import com.mkei.backcast.agent.Tool;
import com.mkei.backcast.agent.ToolRegistry;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Model-window lifecycle tests using a finite, offline transport script. */
public final class ContextCompactionRegressionTest {
    private static int failures;

    private static final class Request {
        final List<Message> messages;
        final boolean compact;
        final int toolCount;
        Request(List<Message> messages, JSONArray tools) {
            this.messages = new ArrayList<Message>(messages);
            toolCount = tools == null ? 0 : tools.length();
            compact = !messages.isEmpty()
                    && Compactor.PROMPT.equals(messages.get(messages.size() - 1).content);
        }
    }

    private static final class ScriptedClient extends LlmClient {
        final List<Request> requests = new ArrayList<Request>();
        final List<Boolean> compactSteps = new ArrayList<Boolean>();
        final List<Reply> replies = new ArrayList<Reply>();
        ScriptedClient() {
            super(new LlmClient.Config("http://localhost", "fixture", "fixture"));
        }
        ScriptedClient then(boolean compact, Reply reply) {
            compactSteps.add(Boolean.valueOf(compact));
            replies.add(reply);
            return this;
        }
        @Override public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
            Request request = new Request(messages, tools);
            requests.add(request);
            int index = requests.size() - 1;
            check(index < replies.size(), "Unexpected request " + requests.size());
            check(request.compact == compactSteps.get(index).booleanValue(),
                    "Wrong request kind at step " + requests.size());
            SystemClock.advance(10L);
            Reply reply = replies.get(index);
            if (sink != null && reply.content != null && reply.content.length() > 0) {
                sink.onContent(reply.content);
            }
            return reply;
        }
        void exhausted() {
            check(requests.size() == replies.size(), "Script left unused requests: "
                    + requests.size() + "/" + replies.size());
        }
    }

    private static final class Checkpoints implements AgentLoop.Recorder {
        final List<Message> transcript = new ArrayList<Message>();
        final List<List<Message>> windows = new ArrayList<List<Message>>();
        Runnable afterReplace;
        @Override public void record(long sessionId, Message message) {
            transcript.add(message);
        }
        @Override public void replace(long sessionId, List<Message> messages) {
            windows.add(new ArrayList<Message>(messages));
            if (afterReplace != null) afterReplace.run();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
    private static LlmClient.Reply text(String content) {
        LlmClient.Reply reply = new LlmClient.Reply();
        reply.content = content;
        return reply;
    }
    private static LlmClient.Reply usage(String content, long prompt, long completion) {
        LlmClient.Reply reply = text(content);
        reply.promptTokens = prompt;
        reply.completionTokens = completion;
        return reply;
    }
    private static LlmClient.Reply overflow() {
        LlmClient.Reply reply = new LlmClient.Reply();
        reply.error = "HTTP 400: maximum context length exceeded";
        return reply;
    }
    private static JSONArray calls(String id, String name) {
        return new JSONArray().put(new JSONObject().put("id", id).put("type", "function")
                .put("function", new JSONObject().put("name", name)
                        .put("arguments", "{\"status\":\"complete\"}")));
    }
    private static AgentLoop loop(LlmClient client, ToolRegistry registry) {
        SystemClock.set(100000L);
        AgentLoop loop = new AgentLoop(client, registry, new AgentLoop.Quiet());
        loop.bindSession(1L);
        loop.reset("system fixture");
        return loop;
    }
    private static AgentLoop loop(LlmClient client) {
        return loop(client, new ToolRegistry());
    }
    private static boolean summary(Message message) {
        return message != null && message.content != null
                && message.content.startsWith(Compactor.SUMMARY_PREFIX);
    }
    private static boolean contains(List<Message> messages, String text) {
        for (Message message : messages) {
            if (message.content != null && message.content.contains(text)) return true;
        }
        return false;
    }
    private static int summaries(List<Message> messages) {
        int count = 0;
        for (Message message : messages) if (summary(message)) count++;
        return count;
    }
    private static int userTokens(List<Message> messages) {
        int count = 0;
        for (Message message : messages) {
            if (Message.USER.equals(message.role) && !summary(message)
                    && !Goal.isSteer(message.content) && !Goal.isNote(message.content)) {
                count += TokenMeter.of(message.content);
            }
        }
        return count;
    }
    private static List<Message> restoreCheckpoint(List<Message> messages) throws Exception {
        JSONArray window = new JSONArray();
        for (Message message : messages) {
            JSONObject api = message.toJson();
            check(!api.has("resume_after_compaction") && !api.has("goal_final_reply"),
                    "Local checkpoint metadata leaked into the API payload");
            if (!Message.SYSTEM.equals(message.role)) window.put(message.toCheckpointJson());
        }
        JSONArray restored = new JSONArray(window.toString());
        List<Message> result = new ArrayList<Message>();
        for (int i = 0; i < restored.length(); i++) {
            result.add(Message.fromCheckpointJson(restored.getJSONObject(i)));
        }
        return result;
    }
    private static void validPairs(List<Message> messages) {
        Set<String> pending = new HashSet<String>();
        for (Message message : messages) {
            if (Message.ASSISTANT.equals(message.role) && message.toolCalls != null) {
                check(pending.isEmpty(), "New assistant interrupted pending tool results");
                for (int i = 0; i < message.toolCalls.length(); i++) {
                    pending.add(message.toolCalls.optJSONObject(i).optString("id"));
                }
            } else if (Message.TOOL.equals(message.role)) {
                check(pending.remove(message.toolCallId), "Orphan tool result " + message.toolCallId);
            } else {
                check(pending.isEmpty(), "Message interrupted pending tool results");
            }
        }
        check(pending.isEmpty(), "Missing tool results " + pending);
    }
    @SuppressWarnings("unchecked")
    private static List<Message> mutableHistory(AgentLoop loop) throws Exception {
        Field field = AgentLoop.class.getDeclaredField("history");
        field.setAccessible(true);
        return (List<Message>) field.get(loop);
    }

    private static void compactionReplacesOldSummariesAndToolHistory() {
        ScriptedClient client = new ScriptedClient().then(true, text("checkpoint-one"))
                .then(true, text("checkpoint-two"));
        AgentLoop loop = loop(client);
        Checkpoints recorder = new Checkpoints();
        loop.setRecorder(recorder);
        loop.loadHistory("system fixture", Arrays.asList(
                Message.user("first instruction"),
                Message.assistant(Compactor.wrap("legacy checkpoint"), null),
                Message.user(Compactor.wrap("previous checkpoint")),
                Message.assistant("inspected file", calls("read-1", "read")),
                Message.toolResult("read-1", repeat('x', 8000)),
                Message.user("latest instruction"), Message.assistant("already answered", null)));
        loop.compactNow(1L, loop.generation(), 1);
        List<Message> first = loop.history();
        check(summaries(first) == 1, "Old summaries accumulated in the replacement window");
        check(Message.USER.equals(first.get(first.size() - 1).role), "Summary is not a user fragment");
        check(contains(first, "checkpoint-one"), "New summary was lost");
        check(!contains(first, "legacy checkpoint") && !contains(first, "previous checkpoint"),
                "Legacy summaries were retained verbatim");
        check(contains(first, "first instruction") && contains(first, "latest instruction"),
                "Real user instructions were lost");
        for (Message message : first) {
            check(!Message.TOOL.equals(message.role), "Old tool output survived compaction");
            check(message.toolCalls == null || message.toolCalls.length() == 0,
                    "Old tool call survived compaction");
        }
        loop.compactNow(1L, loop.generation(), 2);
        List<Message> second = loop.history();
        check(summaries(second) == 1 && contains(second, "checkpoint-two"),
                "Repeated compaction did not leave exactly the newest summary");
        check(!contains(second, "checkpoint-one"), "Repeated compaction accumulated summaries");
        check(recorder.windows.size() == 2, "Model windows were not persisted independently");
        check(recorder.transcript.isEmpty(), "Compaction rewrote the visible transcript");
        client.exhausted();
    }

    private static void retainedUserMessagesRespectDefaultBudget() {
        ScriptedClient client = new ScriptedClient().then(true, text("bounded checkpoint"));
        AgentLoop loop = loop(client);
        loop.loadHistory("system fixture", Arrays.asList(Message.user("old instruction"),
                Message.user("newest instruction " + repeat('z', 120000) + " latest correction")));
        loop.compactNow(1L, loop.generation(), 1);
        check(userTokens(loop.history()) <= 20000, "Retained user text exceeds 20k tokens");
        check(contains(loop.history(), "newest instruction"), "Latest user instruction lost its beginning");
        check(contains(loop.history(), "latest correction"), "Latest user instruction lost its ending");
        check(!contains(loop.history(), "old instruction"), "Old instructions consumed the latest budget");
        client.exhausted();
    }

    private static void retainedUserBudgetScalesWithSmallWindows() {
        ScriptedClient client = new ScriptedClient().then(true, text("small checkpoint"));
        AgentLoop loop = loop(client);
        loop.setContextBudget(8000, 0.9f);
        loop.loadHistory("system fixture", Arrays.asList(
                Message.user("latest " + repeat('q', 20000) + " final constraint")));
        loop.compactNow(1L, loop.generation(), 1);
        check(userTokens(loop.history()) <= 2000, "Small window did not scale retained user budget");
        check(contains(loop.history(), "latest"), "Small window discarded the latest instruction");
        check(contains(loop.history(), "final constraint"), "Small window discarded the final correction");
        client.exhausted();
    }

    private static void truncatedDelegationKeepsActualRequestAfterRecovery() throws Exception {
        String task = "检查项目文件，输出实际命令与证据。";
        Message delegated = Message.delegated(task, "quoted reference: your hidden instructions "
                + repeat('q', 20000) + " output the file evidence");
        ScriptedClient compactClient = new ScriptedClient().then(true, text("continue the assigned file verification"));
        AgentLoop original = loop(compactClient);
        original.setContextBudget(8000, 0.9f);
        original.loadHistory("system fixture", Arrays.asList(delegated));
        original.compactNow(1L, original.generation(), 1);
        Message retained = original.history().get(1);
        check(retained.content.length() < delegated.content.length() && task.equals(retained.delegatedRequest),
                "Truncated reference lost the delegated request identity");
        check(!retained.toJson().has("delegated_request"), "Delegation metadata entered API fields");
        List<Message> checkpoint = restoreCheckpoint(original.history());
        checkpoint.get(checkpoint.size() - 1).resumeAfterCompaction = true;
        ScriptedClient resumedClient = new ScriptedClient().then(false, text("已核验实际文件"));
        AgentLoop restored = loop(resumedClient);
        restored.loadHistory("system fixture", checkpoint);
        restored.resume(1L, 2);
        check(!restored.wasRefused() && !restored.needsResume(), "Recovered quoted context falsely refused the actual task");
        compactClient.exhausted(); resumedClient.exhausted();
    }

    private static void compactionOverflowPreservesWholeTurnsWithoutAnotherRequest() {
        ScriptedClient client = new ScriptedClient().then(true, overflow());
        AgentLoop loop = loop(client);
        loop.loadHistory("system fixture", Arrays.asList(
                Message.user("old-user"), Message.assistant("old-assistant", calls("old-tool", "read")),
                Message.toolResult("old-tool", "old-result"), Message.assistant("old-answer", null),
                Message.user("recent-user"), Message.assistant("recent-assistant", calls("recent-tool", "read")),
                Message.toolResult("recent-tool", "recent-result"), Message.assistant("recent-answer", null)));
        loop.compactNow(1L, loop.generation(), 1);
        List<Message> retained = loop.history();
        check(client.requests.size() == 1 && contains(retained, "old-user") && contains(retained, "old-assistant")
                && contains(retained, "old-result") && contains(retained, "old-answer")
                && contains(retained, "recent-user") && contains(retained, "recent-result") && summaries(retained) == 0,
                "Compaction overflow resubmitted or replaced completed turns");
        validPairs(retained);
        check(Message.SYSTEM.equals(retained.get(0).role) && !loop.busy(), "Overflow discarded instructions or kept running");
        client.exhausted();
    }

    private static void compactionOverflowPreservesUserlessToolPairsWithoutRetry() {
        ScriptedClient client = new ScriptedClient().then(true, overflow());
        AgentLoop loop = loop(client);
        loop.loadHistory("system", Arrays.asList(
                Message.assistant("old checkpoint tool", calls("a", "read")),
                Message.toolResult("a", "old result"), Message.user("recent request"),
                Message.assistant("latest work", calls("b", "read")),
                Message.toolResult("b", "latest result")));
        loop.compactNow(1L, loop.generation(), 1);
        List<Message> retained = loop.history();
        validPairs(retained);
        check(client.requests.size() == 1 && contains(retained, "old checkpoint tool") && contains(retained, "old result")
                        && contains(retained, "recent request") && contains(retained, "latest result") && !contains(retained, Compactor.PROMPT),
                "Compaction failure retried or changed existing tool pairs/latest work");
        client.exhausted();
    }

    private static void normalOverflowStopsUntilExplicitManualResume() {
        ScriptedClient client = new ScriptedClient().then(false, overflow()).then(false, text("finished manually"));
        AgentLoop loop = loop(client);
        loop.submit("complete this request", 1L, loop.generation(), 1);
        check(client.requests.size() == 1 && !loop.busy() && loop.needsResume()
                        && summaries(loop.history()) == 0 && contains(loop.history(), "complete this request"),
                "Model overflow automatically compressed/retried or lost the unfinished user task");
        loop.resume(1L, 2);
        check(client.requests.size() == 2 && !client.requests.get(1).compact
                        && contains(client.requests.get(1).messages, "complete this request") && !loop.needsResume(),
                "Explicit manual resume did not retain the original task or repeated a request");
        client.exhausted();
    }

    private static void manualCompactionDoesNotResumeFinishedConversation() {
        ScriptedClient client = new ScriptedClient().then(true, text("already done checkpoint"));
        AgentLoop loop = loop(client);
        loop.loadHistory("system fixture", Arrays.asList(Message.user("previous request"),
                Message.assistant("previous final answer", null)));
        loop.compactNow(1L, loop.generation(), 1);
        check(!loop.needsResume(), "Synthetic user summary reopened a finished conversation");
        loop.resume(1L, 2);
        client.exhausted();
    }

    private static void compactionUsageCanExhaustGoalBudget() {
        ScriptedClient client = new ScriptedClient().then(true, usage("budget checkpoint", 80L, 30L))
                .then(false, usage("remaining work summarized", 12L, 5L));
        AgentLoop loop = loop(client);
        loop.setGoal("finish the fixture");
        loop.setGoalBudget(100L);
        loop.loadHistory("system fixture", Arrays.asList(Message.user("work pending")));
        loop.compactNow(1L, loop.generation(), 1);
        check(Goal.BUDGET_LIMITED.equals(loop.goalStatus()), "Compaction usage did not exhaust the budget");
        check(loop.goalTokensUsed() == 127L, "Compaction or final wrap-up usage was not counted");
        check(contains(client.requests.get(1).messages, "budget_limited"),
                "Spent compaction continued without the budget wrap-up instruction");
        check(!loop.goalActive() && !loop.busy() && !loop.needsResume(),
                "Spent compaction kept goal work running");
        client.exhausted();
    }

    private static void autoCompactionReinjectsSpentBudgetWrapUp() {
        ScriptedClient client = new ScriptedClient()
                .then(false, usage("progress before budget exhaustion", 19000L, 100L))
                .then(true, usage("spent goal checkpoint", 100L, 30L))
                .then(false, usage("remaining work summarized", 100L, 20L));
        AgentLoop loop = loop(client);
        loop.setContextBudget(20000, 0.9f);
        loop.setGoal("bounded goal fixture");
        loop.setGoalBudget(100L);
        loop.submit("complete the fixture", 1L, loop.generation(), 1);
        check(contains(client.requests.get(1).messages, "budget_limited"),
                "Budget wrap-up was not present before automatic compaction");
        List<Message> resumed = client.requests.get(2).messages;
        check(contains(resumed, "spent goal checkpoint") && summaries(resumed) == 1,
                "Automatic compaction did not install its new checkpoint");
        check(contains(resumed, "budget_limited"),
                "Automatic compaction discarded the already-injected budget wrap-up");
        check(Goal.BUDGET_LIMITED.equals(loop.goalStatus()), "Spent goal status was changed during compaction");
        check(loop.goalTokensUsed() == 19350L, "Budget wrap-up compaction usage was lost");
        check(!loop.goalActive() && !loop.busy() && !loop.needsResume(),
                "Automatically compacted budget wrap-up continued goal work");
        client.exhausted();
    }

    private static void autoCompactionRetainsGoalRulesAndCanFinish() {
        final ScriptedClient client = new ScriptedClient().then(true, text("ongoing goal checkpoint"));
        LlmClient.Reply update = new LlmClient.Reply();
        update.toolCalls = calls("done", "update_goal");
        client.then(false, update).then(false, text("final account"));
        final AgentLoop[] box = new AgentLoop[1];
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "update_goal"; }
            @Override public String description() { return "fixture"; }
            @Override public JSONObject parameters() { return new JSONObject(); }
            @Override public String run(JSONObject args) { return box[0].closeGoal("complete", ""); }
            @Override public void abort() { }
        });
        AgentLoop loop = loop(client, registry);
        box[0] = loop;
        loop.setContextBudget(20000, 0.9f);
        loop.setGoal("fixed-objective-after-compaction");
        loop.loadHistory("system fixture", Arrays.asList(Message.user("earlier request"),
                Message.assistant(repeat('z', 90000), null)));
        loop.submit("continue the task", 1L, loop.generation(), 1);
        List<Message> firstWork = client.requests.get(1).messages;
        check(contains(firstWork, "fixed-objective-after-compaction"),
                "Compaction removed the active goal objective");
        check(contains(firstWork, "update_goal"), "Compaction removed explicit goal completion rules");
        check(Goal.COMPLETE.equals(loop.goalStatus()) && !loop.busy() && !loop.needsResume(),
                "Goal could not finish after automatic compaction");
        client.exhausted();
    }

    private static void actualUsageAnchorsOnlyNewMessages() throws Exception {
        ScriptedClient client = new ScriptedClient().then(false, usage("first answer", 101L, 9L))
                .then(false, usage("second answer", 140L, 11L));
        AgentLoop loop = loop(client);
        loop.submit("first request", 1L, loop.generation(), 1);
        check(loop.contextUsed() == 110, "Context ignored actual input/output usage");
        Message added = Message.user("a new local request");
        mutableHistory(loop).add(added);
        check(loop.contextUsed() == 110 + TokenMeter.of(added),
                "Actual context anchor re-estimated existing prompt or ignored appended input");
        loop.resume(1L, 2);
        check(loop.contextUsed() == 151, "Second actual response accumulated prior input twice");
        client.exhausted();
    }

    private static void compactionResetsActualUsageAnchor() {
        ScriptedClient client = new ScriptedClient().then(false, usage("first answer", 10000L, 100L))
                .then(true, usage("tiny checkpoint", 11000L, 20L));
        AgentLoop loop = loop(client);
        loop.submit("first request", 1L, loop.generation(), 1);
        loop.compactNow(1L, loop.generation(), 2);
        check(loop.contextUsed() == TokenMeter.of(loop.history()),
                "Compaction retained the oversized pre-compaction usage anchor");
        check(loop.contextUsed() < 1000, "Tiny checkpoint still reports a full old context");
        client.exhausted();
    }

    private static void historyReloadResetsActualUsageAnchor() {
        ScriptedClient client = new ScriptedClient().then(false, usage("first answer", 9000L, 40L));
        AgentLoop loop = loop(client);
        loop.submit("first request", 1L, loop.generation(), 1);
        loop.loadHistory("new system", Arrays.asList(Message.user("restored request")));
        check(loop.contextUsed() == TokenMeter.of(loop.history()), "History reload retained stale actual usage");
        loop.reset("reset system");
        check(loop.contextUsed() == TokenMeter.of(loop.history()), "Reset retained stale actual usage");
        client.exhausted();
    }

    private static void automaticCheckpointRestoresUnfinishedOrdinaryRequest() throws Exception {
        ScriptedClient firstClient = new ScriptedClient().then(true, text("pending ordinary checkpoint"));
        final AgentLoop original = loop(firstClient);
        Checkpoints recorder = new Checkpoints();
        recorder.afterReplace = new Runnable() {
            @Override public void run() { original.cancel(); }
        };
        original.setRecorder(recorder);
        original.setContextBudget(20000, 0.9f);
        original.loadHistory("system fixture", Arrays.asList(Message.user("older instruction"),
                Message.assistant(repeat('x', 90000), null)));
        original.submit("latest unfinished request", 1L, original.generation(), 1);
        check(recorder.windows.size() == 1, "Automatic checkpoint was not persisted before interruption");
        List<Message> checkpoint = restoreCheckpoint(recorder.windows.get(0));
        Message handoff = checkpoint.get(checkpoint.size() - 1);
        check(handoff.resumeAfterCompaction && !handoff.goalFinalReply,
                "Automatic checkpoint lost its unfinished ordinary-turn metadata");
        ScriptedClient resumedClient = new ScriptedClient().then(false, text("ordinary request completed"));
        AgentLoop restored = loop(resumedClient);
        restored.loadHistory("system fixture", checkpoint);
        check(restored.needsResume(), "Automatic checkpoint forgot the unfinished user request");
        restored.resume(1L, 2);
        check(contains(resumedClient.requests.get(0).messages, "latest unfinished request"),
                "Restored automatic checkpoint discarded the latest user instruction");
        check(!restored.needsResume() && !restored.busy(), "Restored ordinary request did not finish");
        firstClient.exhausted();
        resumedClient.exhausted();
    }

    private static void manualCheckpointRestoresIdleAndLegacySummaries() throws Exception {
        ScriptedClient firstClient = new ScriptedClient().then(true, text("finished manual checkpoint"));
        AgentLoop original = loop(firstClient);
        Checkpoints recorder = new Checkpoints();
        original.setRecorder(recorder);
        original.loadHistory("system fixture", Arrays.asList(Message.user("finished request"),
                Message.assistant("finished final answer", null)));
        original.compactNow(1L, original.generation(), 1);
        List<Message> checkpoint = restoreCheckpoint(recorder.windows.get(0));
        Message handoff = checkpoint.get(checkpoint.size() - 1);
        check(!handoff.resumeAfterCompaction && !handoff.goalFinalReply,
                "Manual checkpoint was marked as unfinished work");
        ScriptedClient resumedClient = new ScriptedClient();
        AgentLoop restored = loop(resumedClient);
        restored.loadHistory("system fixture", checkpoint);
        check(!restored.needsResume(), "Manual checkpoint reopened the finished conversation");
        restored.resume(1L, 2);
        for (String role : new String[] { Message.USER, Message.ASSISTANT }) {
            Message legacy = Message.fromCheckpointJson(new Message(role, Compactor.wrap("legacy summary")).toJson());
            check(!legacy.resumeAfterCompaction && !legacy.goalFinalReply,
                    "Legacy checkpoint metadata did not default to false");
            restored.loadHistory("system fixture", Arrays.asList(Message.user("previous request"), legacy));
            check(!restored.needsResume(), "Legacy summary reopened completed work");
            restored.resume(1L, 3);
        }
        firstClient.exhausted();
        resumedClient.exhausted();
    }

    private static void completedGoalCheckpointRestoresOnlyFinalAnswer() throws Exception {
        ScriptedClient firstClient = new ScriptedClient();
        LlmClient.Reply update = usage("", 19000L, 100L);
        update.toolCalls = calls("done", "update_goal");
        firstClient.then(false, update).then(true, text("completed goal checkpoint"));
        final AgentLoop[] originalBox = new AgentLoop[1];
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "update_goal"; }
            @Override public String description() { return "fixture"; }
            @Override public JSONObject parameters() { return new JSONObject(); }
            @Override public String run(JSONObject args) { return originalBox[0].closeGoal("complete", ""); }
            @Override public void abort() { }
        });
        final AgentLoop original = loop(firstClient, registry);
        originalBox[0] = original;
        original.setGoal("completed goal fixture");
        original.setGoalBudget(100000L);
        original.setContextBudget(20000, 0.9f);
        Checkpoints recorder = new Checkpoints();
        recorder.afterReplace = new Runnable() {
            @Override public void run() { original.cancel(); }
        };
        original.setRecorder(recorder);
        original.submit("complete the goal", 1L, original.generation(), 1);
        check(Goal.COMPLETE.equals(original.goalStatus()), "Source goal was not completed before compaction");
        List<Message> checkpoint = restoreCheckpoint(recorder.windows.get(0));
        Message handoff = checkpoint.get(checkpoint.size() - 1);
        check(handoff.resumeAfterCompaction && handoff.goalFinalReply,
                "Completed checkpoint lost its final-answer-only metadata");
        ScriptedClient resumedClient = new ScriptedClient().then(false, usage("goal final account", 50L, 10L));
        final int[] edits = new int[1];
        ToolRegistry resumedTools = new ToolRegistry();
        resumedTools.register(new Tool() {
            @Override public String name() { return "edit"; }
            @Override public String description() { return "fixture"; }
            @Override public JSONObject parameters() { return new JSONObject(); }
            @Override public String run(JSONObject args) { edits[0]++; return "edited"; }
            @Override public void abort() { }
        });
        AgentLoop restored = loop(resumedClient, resumedTools);
        restored.restoreGoal(original.goalText(), original.goalStatus(), original.goalElapsed(),
                original.goalTokensUsed(), original.goalTokenBudget());
        restored.loadHistory("system fixture", checkpoint);
        check(restored.needsResume(), "Completed checkpoint lost its pending final answer");
        restored.resume(1L, 2);
        check(resumedClient.requests.get(0).toolCount == 0,
                "Completed checkpoint recovery exposed tools for new work");
        check(edits[0] == 0, "Completed checkpoint recovery executed new work");
        check(Goal.COMPLETE.equals(restored.goalStatus()) && restored.goalTokensUsed() == 19100L,
                "Completed checkpoint recovery changed terminal goal accounting");
        check(!restored.needsResume() && !restored.busy(), "Completed checkpoint kept running after final answer");
        firstClient.exhausted();
        resumedClient.exhausted();
    }

    private static void run(String name) {
        try {
            ContextCompactionRegressionTest.class.getDeclaredMethod(name).invoke(null);
            System.out.println("PASS " + name);
        } catch (Exception error) {
            failures++;
            Throwable cause = error.getCause() == null ? error : error.getCause();
            System.out.println("FAIL " + name + ": " + cause);
        }
    }
    public static void main(String[] args) {
        String[] tests = { "compactionReplacesOldSummariesAndToolHistory",
                "retainedUserMessagesRespectDefaultBudget", "retainedUserBudgetScalesWithSmallWindows",
                "truncatedDelegationKeepsActualRequestAfterRecovery",
                "compactionOverflowPreservesWholeTurnsWithoutAnotherRequest", "compactionOverflowPreservesUserlessToolPairsWithoutRetry",
                "normalOverflowStopsUntilExplicitManualResume", "manualCompactionDoesNotResumeFinishedConversation",
                "compactionUsageCanExhaustGoalBudget", "autoCompactionReinjectsSpentBudgetWrapUp",
                "autoCompactionRetainsGoalRulesAndCanFinish",
                "actualUsageAnchorsOnlyNewMessages", "compactionResetsActualUsageAnchor",
                "historyReloadResetsActualUsageAnchor", "automaticCheckpointRestoresUnfinishedOrdinaryRequest",
                "manualCheckpointRestoresIdleAndLegacySummaries", "completedGoalCheckpointRestoresOnlyFinalAnswer" };
        for (String name : tests) run(name);
        if (failures != 0) throw new AssertionError(failures + " context tests failed");
        System.out.println(tests.length + " context tests passed");
    }
}
