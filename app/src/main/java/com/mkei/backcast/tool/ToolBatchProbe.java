package com.mkei.backcast.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Each concurrent probe owns its runner and temporary lease through cleanup. */
public final class ToolBatchProbe {
    public static final int PARALLELISM = 4;
    public interface Session {
        ToolkitTool toolkit();
        void abort();
        void close() throws Exception;
    }
    public interface SessionFactory {
        Session open() throws Exception;
        default String context() { return ""; }
    }
    public interface FailureListener { void onFailure(String id, String stage, Throwable error); }
    public interface Listener { void onProgress(Progress progress); }
    public static final class Progress {
        public final String stage, id, name;
        public final int completed, total;
        private final String result;
        private Progress(String stage, String id, String name, int completed, int total, JSONObject result) {
            this.stage = stage; this.id = id; this.name = name; this.completed = completed;
            this.total = total; this.result = result == null ? "" : result.toString();
        }
        public JSONObject result() throws Exception { return result.length() == 0 ? null : new JSONObject(result); }
    }
    private ToolBatchProbe() { }
    public static JSONObject run(final SessionFactory factory, final ToolchainInstaller.Cancellation cancellation,
            final Listener listener, final FailureListener failures) throws Exception {
        final JSONArray catalog = ToolCatalog.list();
        final JSONObject[] rows = new JSONObject[catalog.length()];
        final Object progressLock = new Object();
        final int[] next = {0}, completed = {0};
        final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
        final List<Thread> owners = new ArrayList<Thread>();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
        List<Future<?>> workers = new ArrayList<Future<?>>();
        try {
            for (int worker = 0; worker < PARALLELISM; worker++) workers.add(pool.submit(new java.util.concurrent.Callable<Void>() {
                @Override public Void call() throws Exception {
                    synchronized (owners) { owners.add(Thread.currentThread()); }
                    try {
                        while (true) {
                            if (stopped.get()) return null;
                            cancellation.check();
                            final int index;
                            synchronized (progressLock) {
                                if (next[0] >= rows.length) return null;
                                index = next[0]++;
                            }
                            JSONObject tool = catalog.getJSONObject(index);
                            String id = tool.getString("id"), name = tool.getString("name");
                            synchronized (progressLock) {
                                if (listener != null) listener.onProgress(new Progress("running", id, name, completed[0], rows.length, null));
                            }
                            Session session = null;
                            JSONObject result = null;
                            try {
                                cancellation.check();
                                session = factory.open();
                                cancellation.check();
                                result = session.toolkit().status(id);
                            } catch (InterruptedException interrupted) {
                                cancellation.check();
                                result = failure(id, name, interrupted);
                                Thread.interrupted();
                                if (failures != null) failures.onFailure(id, "probe", interrupted);
                            } catch (Exception error) {
                                cancellation.check();
                                result = failure(id, name, error);
                                if (failures != null) failures.onFailure(id, "probe", error);
                            } finally {
                                if (session != null) try { session.close(); }
                                catch (Exception cleanup) {
                                    if (result != null) result.put("cleanup_pending", true);
                                    if (failures != null) failures.onFailure(id, "cleanup", cleanup);
                                }
                            }
                            if (result != null) {
                                result.put("id", id).put("name", name);
                                String output = result.optString("probe_output");
                                if (output.length() > 12000) result.put("probe_output", output.substring(0, 12000)).put("output_truncated", true);
                                synchronized (progressLock) {
                                    rows[index] = result;
                                    completed[0]++;
                                    if (listener != null) listener.onProgress(new Progress("finished", id, name, completed[0], rows.length, result));
                                }
                            }
                        }
                    } catch (Exception failure) {
                        if (!(failure instanceof InterruptedException)) stopped.set(true);
                        throw failure;
                    } finally { synchronized (owners) { owners.remove(Thread.currentThread()); } }
                }
            }));
            pool.shutdown();
            boolean interrupted = false;
            boolean cancellationSignalled = false;
            Throwable failure = null;
            for (Future<?> worker : workers) while (true) {
                try { cancellation.check(); }
                catch (InterruptedException cancelled) {
                    interrupted = true;
                    if (!cancellationSignalled) {
                        cancellationSignalled = true;
                        synchronized (owners) { for (Thread owner : owners) owner.interrupt(); }
                    }
                }
                try { worker.get(100, TimeUnit.MILLISECONDS); break; }
                catch (TimeoutException waiting) { }
                catch (java.util.concurrent.ExecutionException error) {
                    if (error.getCause() instanceof InterruptedException) interrupted = true;
                    else { failure = error.getCause(); stopped.set(true); }
                    synchronized (owners) { for (Thread owner : owners) owner.interrupt(); }
                    break;
                }
                catch (InterruptedException cancelled) { interrupted = true; Thread.interrupted(); }
            }
            if (failure != null) throw new IllegalStateException("工具探测线程失败。", failure);
            if (interrupted) throw new InterruptedException("工具检测已中断。");
            cancellation.check();
            JSONArray results = new JSONArray(); int ready = 0, failed = 0;
            for (JSONObject row : rows) if (row != null) {
                results.put(row);
                if ("ready".equals(row.optString("state")) && row.optBoolean("ready")) ready++; else failed++;
            }
            return new JSONObject().put("state", "batch_complete").put("completed", completed[0]).put("total", rows.length)
                    .put("ready_count", ready).put("failed_count", failed).put("results", results);
        } finally { pool.shutdown(); }
    }

    private static JSONObject failure(String id, String name, Throwable error) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("state", "error").put("ready", false)
                .put("error", error.getMessage() == null ? error.toString() : error.getMessage());
    }
}
