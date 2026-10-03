package com.mkei.backcast.agent;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Parallel public-site probes; a received HTTP status proves reachability without repeating AI HTTP. */
public final class InternetReachability {
    public interface Probe extends Closeable {
        String id();
        int status() throws IOException;
    }
    public static final class Result {
        public final boolean reachable, cancelled;
        public final JSONObject diagnostic;
        private Result(boolean reachable, boolean cancelled, JSONObject diagnostic) {
            this.reachable = reachable; this.cancelled = cancelled; this.diagnostic = diagnostic;
        }
    }
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(3, 3, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(9), new ThreadFactory() {
        @Override public Thread newThread(Runnable work) {
            Thread thread = new Thread(work, "backcast-internet-probe"); thread.setDaemon(true); return thread;
        }
    }, new ThreadPoolExecutor.AbortPolicy());
    private InternetReachability() { }
    public static Result check(List<Probe> probes, LlmClient.RequestValidity valid, long timeoutMs) {
        List<Future<Integer>> tasks = new ArrayList<Future<Integer>>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMs));
        try {
            for (final Probe probe : probes) {
                tasks.add(WORKERS.submit(() -> {
                    try { return probe.status(); }
                    catch (IOException | RuntimeException failure) { return -1; }
                    finally { close(probe); }
                }));
            }
            while (NetworkRouting.current(valid)) {
                int finished = 0;
                for (int i = 0; i < tasks.size(); i++) if (tasks.get(i).isDone()) {
                    finished++; int status = tasks.get(i).get();
                    if (status >= 100) return result(true, false, probes.get(i).id(), status, "");
                }
                if (finished == tasks.size() || System.nanoTime() >= deadline) return result(false, false, "", 0, "unavailable");
                Thread.sleep(25L);
            }
            return result(false, true, "", 0, "cancelled");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return result(false, true, "", 0, "cancelled");
        } catch (Exception failure) {
            return result(false, !NetworkRouting.current(valid), "", 0, failure.getClass().getName());
        } finally {
            for (Probe probe : probes) close(probe);
            for (Future<?> task : tasks) task.cancel(true);
        }
    }
    private static Result result(boolean reachable, boolean cancelled, String network, int status, String error) {
        JSONObject evidence = new JSONObject();
        try {
            evidence.put("internet_probe", cancelled ? "cancelled" : reachable ? "reachable" : "unconfirmed");
            if (reachable) evidence.put("internet_probe_network", network).put("internet_probe_status", status);
            else evidence.put("internet_probe_error", error);
        } catch (org.json.JSONException ignored) { }
        return new Result(reachable, cancelled, evidence);
    }
    private static void close(Closeable probe) {
        try { probe.close(); } catch (IOException | RuntimeException ignored) { }
    }
}
