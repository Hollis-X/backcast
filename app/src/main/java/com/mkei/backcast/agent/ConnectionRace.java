package com.mkei.backcast.agent;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Races connection probes only. Neither the probes nor this scheduler send an HTTP request. */
public final class ConnectionRace {
    public interface Probe extends Closeable {
        String id();
        void connect(long deadlineNanos) throws IOException;
    }
    public interface Feed {
        List<Probe> initial();
        Probe poll();
        /** True while an asynchronously requested network may still arrive. */
        boolean waiting();
    }
    public static final class Winner {
        public final Probe probe;
        public final JSONObject diagnostic;
        private Winner(Probe probe, JSONObject diagnostic) { this.probe = probe; this.diagnostic = diagnostic; }
    }
    public static final class Failed extends IOException {
        public final JSONObject diagnostic;
        private Failed(JSONObject diagnostic) { super("All connection probes failed"); this.diagnostic = diagnostic; }
    }

    // DNS may ignore interruption in the platform resolver. Bound both workers and queued work.
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(4, 4, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(12), new ThreadFactory() {
        private int sequence;
        @Override public synchronized Thread newThread(Runnable work) {
            Thread worker = new Thread(work, "backcast-network-probe-" + ++sequence);
            worker.setDaemon(true); return worker;
        }
    }, new ThreadPoolExecutor.AbortPolicy());
    private static final class Result {
        final Probe probe;
        final String failure;
        final long elapsed;
        Result(Probe probe, String failure, long elapsed) { this.probe = probe; this.failure = failure; this.elapsed = elapsed; }
    }
    private ConnectionRace() { }

    public static Winner connect(Feed feed, LlmClient.RequestValidity valid, long timeoutMs) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMs));
        final BlockingQueue<Result> completed = new ArrayBlockingQueue<Result>(32);
        List<Probe> probes = new ArrayList<Probe>();
        List<Future<?>> futures = new ArrayList<Future<?>>();
        JSONArray evidence = new JSONArray();
        int finished = 0;
        try {
            for (Probe probe : feed.initial()) launch(probe, deadline, completed, probes, futures);
            while (true) {
                if (!NetworkRouting.current(valid)) throw new InterruptedIOException("Cancelled network selection");
                Probe next;
                while ((next = feed.poll()) != null) launch(next, deadline, completed, probes, futures);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) throw new Failed(diagnostic(evidence, "deadline"));
                Result result = completed.poll(Math.min(TimeUnit.MILLISECONDS.toNanos(30L), remaining), TimeUnit.NANOSECONDS);
                if (result != null) {
                    finished++;
                    evidence.put(new JSONObject().put("network", result.probe.id())
                            .put("outcome", result.failure == null ? "connected" : "failed")
                            .put("elapsed_ms", result.elapsed).put("error_class", result.failure == null ? "" : result.failure));
                    if (result.failure == null) return new Winner(result.probe, diagnostic(evidence, "connected"));
                }
                if (finished == probes.size() && !feed.waiting()) throw new Failed(diagnostic(evidence, "failed"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Cancelled network selection");
        } catch (org.json.JSONException impossible) {
            throw new IOException("Network diagnostic unavailable", impossible);
        } finally {
            for (Probe probe : probes) close(probe);
            for (Future<?> future : futures) future.cancel(true);
        }
    }

    private static void launch(final Probe probe, final long deadline, final BlockingQueue<Result> completed,
            List<Probe> probes, List<Future<?>> futures) {
        // A late modem callback cannot grow the global work queue without bound.
        if (probes.size() >= 8) { close(probe); return; }
        probes.add(probe);
        try {
            futures.add(WORKERS.submit(new Runnable() {
                @Override public void run() {
                    long started = System.nanoTime(); String error = null;
                    try { probe.connect(deadline); }
                    catch (IOException | RuntimeException failure) { error = failure.getClass().getName(); }
                    finally { close(probe); }
                    completed.offer(new Result(probe, error, Math.max(0L,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))));
                }
            }));
        } catch (RejectedExecutionException busy) {
            close(probe); completed.offer(new Result(probe, busy.getClass().getName(), 0L));
        }
    }
    private static JSONObject diagnostic(JSONArray evidence, String outcome) {
        JSONObject result = new JSONObject();
        try { result.put("selection", outcome).put("connection_probes", evidence); }
        catch (org.json.JSONException ignored) { }
        return result;
    }
    private static void close(Closeable value) {
        try { value.close(); } catch (IOException | RuntimeException ignored) { }
    }
}
