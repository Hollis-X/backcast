import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.ApprovalGate;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.Tool;
import com.mkei.backcast.agent.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Transcript persistence and live callbacks share one immutable UI snapshot boundary. */
public final class UiSnapshotRegressionTest {
    private static int passed;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }

    private interface Script {
        LlmClient.Reply next(Fixture fixture, LlmClient.Sink sink) throws Exception;
    }
    private static final class Fixture {
        final ToolRegistry tools = new ToolRegistry();
        final ArrayList<Message> stored = new ArrayList<Message>();
        final AgentLoop loop;
        Script script;
        int calls;
        Fixture() {
            LlmClient client = new LlmClient(new LlmClient.Config("http://localhost", "fixture", "fixture")) {
                @Override public Reply send(List<Message> messages, JSONArray schema, Sink sink) {
                    calls++;
                    check(calls <= 4, "Unexpected extra model request");
                    try { return script.next(Fixture.this, sink); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                }
            };
            loop = new AgentLoop(client, tools, new AgentLoop.Quiet());
            loop.reset("fixture");
            loop.setRecorder(new AgentLoop.Recorder() {
                @Override public void record(long sid, Message message) { stored.add(message); }
                @Override public void replace(long sid, List<Message> messages) { }
            });
        }
        void run() { loop.submit("fixture request", 1, loop.generation(), 9); }
        AgentLoop.UiSnapshot<List<Message>> snapshot(AgentLoop.Listener target) throws Exception {
            return loop.snapshotUi(new AgentLoop.UiSnapshotReader<List<Message>>() {
                @Override public List<Message> read() { return new ArrayList<Message>(stored); }
            }, target);
        }
    }

    private static final class Capture extends AgentLoop.Quiet {
        final AgentLoop source;
        final ArrayList<String> values = new ArrayList<String>();
        final ArrayList<Long> sequences = new ArrayList<Long>();
        final ArrayList<Boolean> replay = new ArrayList<Boolean>();
        Capture(AgentLoop source) { this.source = source; }
        void add(String value) {
            check(AgentLoop.callingUiSource() == source, "Callback lost its source loop");
            check(source.callingUiSequence() > 0, "Callback has no UI sequence");
            check(source.callingToken() == 9, "Callback lost its accepted UI token");
            values.add(value); sequences.add(Long.valueOf(source.callingUiSequence()));
            replay.add(Boolean.valueOf(source.isReplayingUiSnapshot()));
        }
        @Override public void onAssistantText(int gen, String value) { add("text:" + value); }
        @Override public void onReasoning(int gen, String value) { add("reason:" + value); }
        @Override public void onToolPreview(int gen, int index, String id, String name, String args) {
            add("preview:" + index + ":" + args);
        }
        @Override public void onToolStart(int gen, String name, String args) { add("start:" + name); }
        @Override public void onToolEnd(int gen, String name, String result) { add("end:" + name); }
    }

    private static LlmClient.Reply answer(String text) {
        LlmClient.Reply reply = new LlmClient.Reply(); reply.content = text; return reply;
    }
    private static LlmClient.Reply toolReply() throws Exception {
        LlmClient.Reply reply = new LlmClient.Reply();
        reply.content = "persisted body";
        reply.toolCalls = new JSONArray().put(new JSONObject().put("id", "tool-id").put("type", "function")
                .put("function", new JSONObject().put("name", "probe").put("arguments", "{}")));
        return reply;
    }
    private static void probe(Fixture fixture, final Runnable action) {
        fixture.tools.register(new Tool() {
            @Override public String name() { return "probe"; }
            @Override public String description() { return "fixture"; }
            @Override public JSONObject parameters() { return new JSONObject(); }
            @Override public String run(JSONObject args) { if (action != null) action.run(); return "tool result"; }
            @Override public void abort() { }
        });
    }

    private static void unpersistedPartialIsReplayedAndFutureHasLargerSequence() throws Exception {
        final Fixture fixture = new Fixture();
        final Capture capture = new Capture(fixture.loop);
        fixture.script = new Script() {
            @Override public LlmClient.Reply next(Fixture f, LlmClient.Sink sink) throws Exception {
                sink.onReasoning("think "); sink.onReasoning("more");
                sink.onContent("hello "); sink.onContent("there");
                AgentLoop.UiSnapshot<List<Message>> snapshot = f.snapshot(capture);
                check(snapshot.data.size() == 1 && Message.USER.equals(snapshot.data.get(0).role),
                        "Partial stream unexpectedly appeared in persisted transcript");
                f.loop.replayUiSnapshot(snapshot, capture);
                check(capture.values.equals(java.util.Arrays.asList("reason:think more", "text:hello there")),
                        "Adjacent partial deltas were lost or replayed separately: " + capture.values);
                check(capture.replay.get(0).booleanValue() && capture.replay.get(1).booleanValue(), "Replay flag missing");
                sink.onContent(" future");
                check(capture.sequences.get(2).longValue() > snapshot.sequence && !capture.replay.get(2).booleanValue(),
                        "Future callback did not follow the snapshot boundary");
                check(f.loop.callingToken() == 9 && f.loop.callingUiSequence() == -1
                        && AgentLoop.callingUiSource() == null && !f.loop.isReplayingUiSnapshot(),
                        "Replay did not restore caller thread state");
                return answer("hello there future");
            }
        };
        fixture.run();
        check(AgentLoop.callingUiSource() == null && fixture.loop.callingToken() == -1, "Turn leaked callback state");
        pass("unpersistedPartialIsReplayedAndFutureHasLargerSequence");
    }

    private static void persistedPayloadIsNotReplayed() throws Exception {
        final Fixture fixture = new Fixture();
        final Capture queued = new Capture(fixture.loop), newView = new Capture(fixture.loop);
        fixture.loop.setListener(queued); probe(fixture, null);
        fixture.script = new Script() {
            @Override public LlmClient.Reply next(Fixture f, LlmClient.Sink sink) throws Exception {
                if (f.calls == 1) { sink.onContent("persisted body"); return toolReply(); }
                AgentLoop.UiSnapshot<List<Message>> snapshot = f.snapshot(newView);
                check(snapshot.data.size() == 3 && "persisted body".equals(snapshot.data.get(1).content)
                        && Message.TOOL.equals(snapshot.data.get(2).role), "Snapshot did not contain committed messages");
                f.loop.replayUiSnapshot(snapshot, newView);
                check(newView.values.isEmpty(), "Persisted text or tool results were replayed twice");
                for (Long sequence : queued.sequences) check(sequence.longValue() <= snapshot.sequence,
                        "An already stored UI event fell after the snapshot boundary");
                sink.onContent("final answer");
                check(newView.values.equals(java.util.Arrays.asList("text:final answer")), "New answer was lost");
                return answer("final answer");
            }
        };
        fixture.run(); pass("persistedPayloadIsNotReplayed");
    }

    private static void previewReplacementKeepsItsOriginalPosition() throws Exception {
        final Fixture fixture = new Fixture();
        final Capture capture = new Capture(fixture.loop);
        fixture.script = new Script() {
            @Override public LlmClient.Reply next(Fixture f, LlmClient.Sink sink) throws Exception {
                sink.onContent("before"); sink.onToolCall(0, "id", "probe", "{");
                sink.onReasoning("between"); sink.onToolCall(0, "id", "probe", "{}");
                sink.onContent("after");
                AgentLoop.UiSnapshot<List<Message>> snapshot = f.snapshot(capture);
                f.loop.replayUiSnapshot(snapshot, capture);
                check(capture.values.equals(java.util.Arrays.asList("text:before", "preview:0:{}", "reason:between", "text:after")),
                        "Preview delta changed chronological position or duplicated: " + capture.values);
                return answer("beforeafter");
            }
        };
        fixture.run(); pass("previewReplacementKeepsItsOriginalPosition");
    }

    private static void failureDropsUncommittedOutputBeforeExplicitResume() throws Exception {
        final Fixture fixture = new Fixture();
        final Capture capture = new Capture(fixture.loop);
        fixture.script = new Script() {
            @Override public LlmClient.Reply next(Fixture f, LlmClient.Sink sink) throws Exception {
                if (f.calls == 1) {
                    sink.onContent("discarded"); sink.onToolCall(0, "id", "probe", "old");
                    LlmClient.Reply reply = new LlmClient.Reply(); reply.error = "connection refused fixture failure"; return reply;
                }
                AgentLoop.UiSnapshot<List<Message>> snapshot = f.snapshot(capture);
                f.loop.replayUiSnapshot(snapshot, capture);
                check(capture.values.isEmpty(), "Explicit resume retained the failed request's uncommitted content");
                sink.onContent("fresh"); return answer("fresh");
            }
        };
        fixture.run();
        check(fixture.calls == 1 && fixture.stored.size() == 1 && !fixture.loop.busy(), "Network failure retried or committed partial output");
        AgentLoop.UiSnapshot<List<Message>> failed = fixture.snapshot(capture);
        fixture.loop.replayUiSnapshot(failed, capture);
        check(capture.values.isEmpty() && failed.data.size() == 1,
                "Failure snapshot resurrected uncommitted body or parameter previews");
        fixture.loop.resume(1, 9);
        check(fixture.calls == 2 && fixture.stored.size() == 2
                        && capture.values.equals(java.util.Arrays.asList("text:fresh")), "Explicit resume lost the fresh stream or duplicated persisted history");
        pass("failureDropsUncommittedOutputBeforeExplicitResume");
    }

    private static void explicitResumeAfterPartialToolKeepsZeroRetriesAndAtomicStages() throws Exception {
        final Fixture fixture = new Fixture();
        final int[] executions = {0};
        final ArrayList<String> stages = new ArrayList<String>();
        final AgentLoop.Quiet listener = new AgentLoop.Quiet() {
            @Override public void onProgress(int gen, String phase, String name, String detail) {
                check(AgentLoop.callingUiSource() == fixture.loop && fixture.loop.callingUiSequence() > 0,
                        "Progress bypassed the source/token/snapshot boundary");
                stages.add(phase);
                check(!detail.contains("secret-token"), "Stage exposed provider credentials or echoed content");
            }
        };
        fixture.loop.setListener(listener);
        probe(fixture, new Runnable() { @Override public void run() { executions[0]++; } });
        fixture.script = new Script() {
            @Override public LlmClient.Reply next(Fixture f, LlmClient.Sink sink) throws Exception {
                if (f.calls == 1) {
                    sink.onToolCall(0, "tool-id", "probe", "{");
                    check(executions[0] == 0, "A parameter preview executed the tool");
                    LlmClient.Reply failed = new LlmClient.Reply();
                    failed.error = "HTTP 503: secret-token and echoed prompt"; return failed;
                }
                if (f.calls == 2) {
                    check(!stages.contains("retry"), "Explicit user resume was reported as an automatic retry");
                    final ArrayList<String> snapshotStages = new ArrayList<String>();
                    AgentLoop.Quiet replay = new AgentLoop.Quiet() {
                        @Override public void onProgress(int gen, String phase, String name, String detail) {
                            snapshotStages.add(phase);
                        }
                    };
                    f.loop.replayUiSnapshot(f.snapshot(replay), replay);
                    check(snapshotStages.equals(java.util.Arrays.asList("model")), "Reentry lost the current phase or replayed a failed-request retry");
                    assertCurrentStageSurvivesReentryWithoutInventedRetries(f);
                    f.loop.setListener(listener);
                    sink.onToolCall(0, "tool-id", "probe", "{}"); return toolReply();
                }
                check(executions[0] == 1, "Explicit continuation duplicated a real tool execution");
                check(!stages.contains("retry"), "Normal followup invented an automatic retry");
                assertCurrentStageSurvivesReentryWithoutInventedRetries(f);
                f.loop.setListener(listener);
                return answer("done");
            }
        };
        fixture.run();
        check(fixture.calls == 1 && executions[0] == 0 && fixture.stored.size() == 1 && !stages.contains("retry"),
                "Failed parameter generation retried, executed an incomplete tool or polluted history");
        fixture.loop.resume(1, 9);
        check(fixture.calls == 3 && executions[0] == 1 && stages.contains("tool_ready"),
                "Explicit continuation did not execute one actual complete tool and its ordinary followup");
        pass("explicitResumeAfterPartialToolKeepsZeroRetriesAndAtomicStages");
    }

    private static void assertCurrentStageSurvivesReentryWithoutInventedRetries(final Fixture fixture) throws Exception {
        final ArrayList<String> phases = new ArrayList<String>();
        final ArrayList<Long> sequences = new ArrayList<Long>();
        AgentLoop.Quiet replay = new AgentLoop.Quiet() {
            @Override public void onProgress(int gen, String phase, String name, String detail) {
                check(AgentLoop.callingUiSource() == fixture.loop && fixture.loop.callingToken() == 9
                                && fixture.loop.isReplayingUiSnapshot() && gen == fixture.loop.generation(),
                        "Recovered progress lost its source, token, generation or replay boundary");
                check(!"retry".equals(phase), "Recovered current phase invented an automatic retry");
                phases.add(phase); sequences.add(fixture.loop.callingUiSequence());
            }
            @Override public void onToolPreview(int gen, int index, String id, String name, String args) {
                throw new AssertionError("Stored parameter previews were replayed after committing output");
            }
            @Override public void onToolStart(int gen, String name, String args) {
                throw new AssertionError("Stored completed tool start was resurrected on reentry");
            }
        };
        fixture.loop.replayUiSnapshot(fixture.snapshot(replay), replay);
        check(phases.equals(java.util.Arrays.asList("model")) && sequences.get(0).longValue() > 0,
                "Reentry did not retain the current model phase at the immutable sequence boundary: " + phases);
    }

    private static void approvalAndReviewPrecedeActualToolStart() throws Exception {
        for (final boolean permit : new boolean[]{false, true}) {
            final Fixture fixture = new Fixture();
            final ArrayList<String> events = new ArrayList<String>();
            probe(fixture, new Runnable() { @Override public void run() { events.add("execute"); } });
            fixture.loop.setAccessLevel(ApprovalGate.ACCESS_GUARDED);
            fixture.loop.setApprovalGate(new ApprovalGate() {
                @Override public boolean approve(String name, JSONObject args) {
                    check(!events.contains("start") && !events.contains("execute"), "Tool started before user approval");
                    events.add("approval"); return permit;
                }
            });
            fixture.loop.setListener(new AgentLoop.Quiet() {
                @Override public void onProgress(int gen, String phase, String name, String detail) { events.add(phase); }
                @Override public void onToolStart(int gen, String name, String args) { events.add("start"); }
            });
            fixture.script = new Script() {
                @Override public LlmClient.Reply next(Fixture f, LlmClient.Sink sink) throws Exception {
                    if (f.calls == 1) return toolReply();
                    if (f.calls == 2) {
                        check(events.contains("tool_review") && !events.contains("start"), "Security review counted as tool execution");
                        return answer("DANGEROUS fixture");
                    }
                    return answer("done");
                }
            };
            fixture.run();
            check(events.indexOf("tool_ready") < events.indexOf("tool_review")
                    && events.indexOf("tool_review") < events.indexOf("tool_approval")
                    && events.indexOf("tool_approval") < events.indexOf("approval"), "Approval stages were out of order: " + events);
            check(permit ? events.indexOf("approval") < events.indexOf("start") && events.indexOf("start") < events.indexOf("execute")
                    : !events.contains("start") && !events.contains("execute"), "Denied or unapproved tool executed: " + events);
        }
        pass("approvalAndReviewPrecedeActualToolStart");
    }

    private static void activeToolStartSurvivesButStoredPreviewDoesNot() throws Exception {
        final Fixture fixture = new Fixture();
        final Capture capture = new Capture(fixture.loop);
        probe(fixture, new Runnable() {
            @Override public void run() {
                try {
                    AgentLoop.UiSnapshot<List<Message>> snapshot = fixture.snapshot(capture);
                    fixture.loop.replayUiSnapshot(snapshot, capture);
                    check(capture.values.equals(java.util.Arrays.asList("start:probe")),
                            "In-flight tool start lost or duplicated stored preview: " + capture.values);
                } catch (Exception error) { throw new IllegalStateException(error); }
            }
        });
        fixture.script = new Script() {
            @Override public LlmClient.Reply next(Fixture f, LlmClient.Sink sink) throws Exception {
                if (f.calls == 1) { sink.onContent("persisted body"); sink.onToolCall(0, "tool-id", "probe", "{}"); return toolReply(); }
                return answer("done");
            }
        };
        fixture.run();
        check(capture.values.equals(java.util.Arrays.asList("start:probe", "end:probe")), "Future tool result was lost or duplicated");
        pass("activeToolStartSurvivesButStoredPreviewDoesNot");
    }

    private static void toolCommitAndCompletionEventAreAtomic() throws Exception {
        final Fixture fixture = new Fixture();
        final Capture previous = new Capture(fixture.loop), next = new Capture(fixture.loop);
        fixture.loop.setListener(previous); probe(fixture, null);
        final CountDownLatch committed = new CountDownLatch(1), release = new CountDownLatch(1), read = new CountDownLatch(1);
        final Throwable[] failure = new Throwable[2];
        fixture.loop.setRecorder(new AgentLoop.Recorder() {
            @Override public void record(long sid, Message message) {
                fixture.stored.add(message);
                if (Message.TOOL.equals(message.role)) {
                    committed.countDown();
                    try { check(release.await(3, TimeUnit.SECONDS), "Tool commit never released"); }
                    catch (InterruptedException error) { throw new IllegalStateException(error); }
                }
            }
            @Override public void replace(long sid, List<Message> messages) { }
        });
        fixture.script = new Script() {
            @Override public LlmClient.Reply next(Fixture f, LlmClient.Sink sink) throws Exception {
                return f.calls == 1 ? toolReply() : answer("done");
            }
        };
        Thread worker = new Thread(new Runnable() {
            @Override public void run() { try { fixture.run(); } catch (Throwable error) { failure[0] = error; } }
        });
        Thread snapshotter = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    AgentLoop.UiSnapshot<List<Message>> snapshot = fixture.snapshot(next); read.countDown();
                    int index = previous.values.indexOf("end:probe");
                    check(index >= 0 && previous.sequences.get(index).longValue() <= snapshot.sequence,
                            "Tool completion was emitted after snapshot of its committed result");
                    fixture.loop.replayUiSnapshot(snapshot, next);
                    check(!next.values.contains("end:probe"), "Committed result was replayed again");
                } catch (Throwable error) { failure[1] = error; }
            }
        });
        worker.start();
        check(committed.await(3, TimeUnit.SECONDS), "Tool was not committed"); snapshotter.start();
        try { check(!read.await(100, TimeUnit.MILLISECONDS), "Snapshot read between commit and corresponding completion event"); }
        finally { release.countDown(); }
        worker.join(3000); snapshotter.join(3000);
        check(!worker.isAlive() && !snapshotter.isAlive() && failure[0] == null && failure[1] == null,
                "Atomic tool snapshot failed: " + failure[0] + "/" + failure[1]);
        pass("toolCommitAndCompletionEventAreAtomic");
    }

    private static void listenerIdentityAndReadFailureArePreserved() throws Exception {
        Fixture fixture = new Fixture();
        Capture first = new Capture(fixture.loop), next = new Capture(fixture.loop);
        fixture.loop.setListener(first); check(fixture.loop.listener() == first, "Getter exposed forwarding wrapper");
        try {
            fixture.loop.snapshotUi(new AgentLoop.UiSnapshotReader<Object>() {
                @Override public Object read() { throw new IllegalStateException("fixture reader failure"); }
            }, next);
            throw new AssertionError("Failed snapshot reader was swallowed");
        } catch (IllegalStateException expected) { }
        check(fixture.loop.listener() == first, "Failed snapshot replaced the listener");
        fixture.snapshot(next); check(fixture.loop.listener() == next, "Snapshot did not atomically attach target listener");
        pass("listenerIdentityAndReadFailureArePreserved");
    }

    private static void cancelledSnapshotCannotResurrectOldPartial() throws Exception {
        final Fixture fixture = new Fixture();
        final Capture capture = new Capture(fixture.loop);
        fixture.script = new Script() {
            @Override public LlmClient.Reply next(Fixture f, LlmClient.Sink sink) throws Exception {
                sink.onContent("old"); AgentLoop.UiSnapshot<List<Message>> snapshot = f.snapshot(capture);
                f.loop.cancel(); f.loop.replayUiSnapshot(snapshot, capture);
                check(capture.values.isEmpty(), "Cancelled snapshot resurrected partial text");
                return answer("old");
            }
        };
        fixture.run(); pass("cancelledSnapshotCannotResurrectOldPartial");
    }

    private static void listenerSwitchDoesNotWaitForSnapshotOrGetOverwritten() throws Exception {
        final Fixture fixture = new Fixture();
        final Capture snapshotTarget = new Capture(fixture.loop), replacement = new Capture(fixture.loop);
        final CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        Thread snapshotter = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    fixture.loop.snapshotUi(new AgentLoop.UiSnapshotReader<String>() {
                        @Override public String read() throws Exception {
                            reading.countDown();
                            check(release.await(3, TimeUnit.SECONDS), "Reader never released");
                            return "fixture page";
                        }
                    }, snapshotTarget);
                } catch (Throwable error) { failure[0] = error; }
            }
        });
        snapshotter.start();
        check(reading.await(2, TimeUnit.SECONDS), "Snapshot did not start read");
        long started = System.nanoTime();
        fixture.loop.setListener(replacement);
        long elapsed = (System.nanoTime() - started) / 1000000L;
        release.countDown(); snapshotter.join(3000);
        check(elapsed < 500 && !snapshotter.isAlive() && failure[0] == null,
                "Listener replacement blocked on snapshot reader: " + elapsed + "ms / " + failure[0]);
        check(fixture.loop.listener() == replacement, "Old snapshot resurrected detached listener");
        pass("listenerSwitchDoesNotWaitForSnapshotOrGetOverwritten");
    }

    public static void main(String[] args) throws Exception {
        unpersistedPartialIsReplayedAndFutureHasLargerSequence();
        persistedPayloadIsNotReplayed();
        previewReplacementKeepsItsOriginalPosition();
        failureDropsUncommittedOutputBeforeExplicitResume();
        explicitResumeAfterPartialToolKeepsZeroRetriesAndAtomicStages();
        approvalAndReviewPrecedeActualToolStart();
        activeToolStartSurvivesButStoredPreviewDoesNot();
        toolCommitAndCompletionEventAreAtomic();
        listenerIdentityAndReadFailureArePreserved();
        cancelledSnapshotCannotResurrectOldPartial();
        listenerSwitchDoesNotWaitForSnapshotOrGetOverwritten();
        System.out.println(passed + " UI snapshot tests passed");
    }
}
