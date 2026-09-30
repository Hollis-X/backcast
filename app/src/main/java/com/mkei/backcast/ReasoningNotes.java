package com.mkei.backcast;

import android.os.Handler;
import android.os.Looper;
import com.mkei.backcast.agent.*;
import com.mkei.backcast.ui.TurnTrace;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Summaries are isolated from the agent's transport and do not enter its history. */
public final class ReasoningNotes {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30L,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(8), new ThreadFactory() {
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "reasoning-notes");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private final Settings settings;
    private final ChatStore store;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public ReasoningNotes(Settings settings, ChatStore store) {
        this.settings = settings;
        this.store = store;
    }

    public void request(final TurnTrace.Piece piece, final Runnable changed) {
        if (!piece.sealed || piece.think == null || piece.think.length() == 0 || piece.summaryPending
                || piece.summaryError.length() > 0) return;
        final int size = piece.think.length();
        if (piece.sealed && piece.summaryComplete && piece.summaryChars == size) return;
        if (piece.think.length() == PromptGuard.REFUSAL.length()
                && PromptGuard.REFUSAL.contentEquals(piece.think)) {
            piece.summaryError = PromptGuard.REFUSAL;
            return;
        }
        final String sample = ReasoningSummary.sample(piece.think);
        final boolean complete = piece.sealed;
        final int version = piece.summaryVersion;
        final LlmClient.Config config = new LlmClient.Config(settings.baseUrl(), settings.apiKey(), settings.model());
        config.maxTokens = 600;
        config.timeoutMs = 15000;
        config.totalTimeoutMs = 25000;
        config.maxResponseChars = 128000;
        final String instructions = settings.systemPrompt(), environment = settings.environmentContext();
        piece.summaryPending = true;
        piece.requestedChars = size;
        try {
            WORKER.execute(new Runnable() {
                public void run() {
                    String result = "", error = "";
                    try {
                        String key = key(config.baseUrl + config.model + ReasoningSummary.PROMPT + complete + size + sample);
                        result = store.reasoningNote(key);
                        if (result.length() == 0) {
                            LlmClient.Reply reply = new LlmClient(config).send(ReasoningSummary.request(sample, complete), null, null);
                            if (reply.error != null) throw new IllegalStateException(reply.error);
                            result = ReasoningSummary.validate(reply.content);
                            result = PromptGuard.redact(result, instructions, environment,
                                    ReasoningSummary.PROMPT + "\n" + Compactor.PROMPT);
                            if (PromptGuard.REFUSAL.equals(result)) throw new IllegalStateException("Protected output");
                            store.saveReasoningNote(key, result);
                        }
                    } catch (Exception failure) {
                        error = "摘要暂不可用";
                    }
                    final String summary = result, problem = error;
                    ui.post(new Runnable() {
                        public void run() {
                            if (piece.summaryVersion != version || !piece.sealed || piece.think.length() != size) return;
                            piece.summaryPending = false;
                            piece.summary = summary;
                            piece.summaryError = problem;
                            piece.summaryChars = size;
                            piece.summaryComplete = complete;
                            changed.run();
                        }
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            piece.summaryPending = false;
            piece.summaryError = "摘要队列繁忙";
        }
    }

    private static String key(String source) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes("UTF-8"));
        StringBuilder out = new StringBuilder(64);
        for (byte b : hash) {
            out.append(Character.forDigit((b >> 4) & 15, 16));
            out.append(Character.forDigit(b & 15, 16));
        }
        return out.toString();
    }
}
