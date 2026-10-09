package com.mkei.backcast.tool;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real status entrypoints run on independent leases and share the installed-tool locks. */
public final class ToolBatchProbeRegressionTest {
    private static File root;
    private static final ToolchainInstaller.Cancellation LIVE = () -> { };
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static final class Fixture implements ToolBatchProbe.SessionFactory {
        final File project, state;
        final ToolchainStore store;
        final List<String> invoked = Collections.synchronizedList(new ArrayList<String>());
        final List<File> materials = Collections.synchronizedList(new ArrayList<File>());
        final AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger(), running = new AtomicInteger(), peak = new AtomicInteger(), aborts = new AtomicInteger();
        volatile String broken = "";
        volatile String configuration = "root=false|project";
        volatile boolean oversized, cleanupFailure;
        volatile CountDownLatch gate, entered;
        Fixture(String name) throws Exception {
            project = new File(root, name + "-project"); project.mkdir();
            state = new File(root, name + "-state");
            store = new ToolchainStore(new File(root, name + "-software"), null, "", 0);
            JSONArray catalog = ToolCatalog.list();
            for (int i = 0; i < catalog.length(); i++) {
                String id = catalog.getJSONObject(i).getString("id");
                File executable = new File(project, id + ("apktool".equals(id) ? ".jar" : ""));
                Files.write(executable.toPath(), new byte[]{1});
                ToolchainFixtures.configure(store, id, executable.getPath(), "apktool".equals(id) ? "/usr/bin/java" : null);
            }
        }
        public String context() { return configuration; }
        public ToolBatchProbe.Session open() throws Exception {
            int number = opened.incrementAndGet(); final Thread owner = Thread.currentThread();
            TemporaryWorkspace temporary = new TemporaryWorkspace(project.getPath(), false, new File(state, "job-" + number), 0);
            temporary.beginTurn(); materials.add(temporary.directory());
            ShellTool shell = new ShellTool(false, project.getPath(), temporary) {
                @Override String runProgram(ToolchainStore.Launcher launcher, List<String> args, boolean temp, int timeout, int epoch) throws Exception {
                    invoked.add(launcher.id);
                    int count = running.incrementAndGet(); peak.accumulateAndGet(count, Math::max);
                    try {
                        if (entered != null) entered.countDown();
                        if (gate != null) gate.await(5, TimeUnit.SECONDS); else Thread.sleep(30);
                        if (launcher.id.equals(broken)) throw new java.io.IOException("fixture missing library");
                        String version = "apktool".equals(launcher.id) ? "2.9.3" : "objection".equals(launcher.id) ? "objection: 1.12.5"
                            : "radare2".equals(launcher.id) || "rabin2".equals(launcher.id) ? launcher.id + " 6.2.2" : "GNU " + launcher.id + " 2.47";
                        StringBuilder output = new StringBuilder("exit=0\n" + version + "\n");
                        if (oversized) for (int i = 0; i < 13000; i++) output.append('x');
                        return output.toString();
                    } finally { running.decrementAndGet(); }
                }
            };
            final ToolkitTool toolkit = new ToolkitTool(shell, store, project.getPath(), temporary, "arm64-v8a", false);
            return new ToolBatchProbe.Session() {
                public ToolkitTool toolkit() { return toolkit; }
                public void abort() { aborts.incrementAndGet(); toolkit.abort(); }
                public void close() throws Exception {
                    check(Thread.currentThread() == owner, "Session cleaned on a foreign thread");
                    String cleanup = temporary.finishTurn(); closed.incrementAndGet();
                    check(cleanup == null, "Independent lease cleanup failed: " + cleanup);
                    if (cleanupFailure) throw new java.io.IOException("fixture cleanup failed");
                }
            };
        }
    }
    private static void parallelRealStatusesAndCleanupKeepFailureEvidence() throws Exception {
        Fixture fixture = new Fixture("parallel"); fixture.broken = "radare2";
        List<ToolBatchProbe.Progress> events = Collections.synchronizedList(new ArrayList<ToolBatchProbe.Progress>());
        JSONObject result = ToolBatchProbe.run(fixture, LIVE, events::add, (id, stage, error) -> { });
        check(result.getInt("completed") == 13 && result.getInt("ready_count") == 12 && result.getInt("failed_count") == 1, "One tool failure stopped batch");
        check(fixture.peak.get() > 1 && fixture.peak.get() <= 4, "Probe was sequential or exceeded concurrency limit");
        check(fixture.invoked.size() == 13 && fixture.opened.get() == 13 && fixture.closed.get() == 13, "Status or same-worker cleanup was skipped");
        check(new java.util.HashSet<File>(fixture.materials).size() == 13, "Parallel probes shared temporary material directories");
        int completed = 0;
        for (ToolBatchProbe.Progress event : events) {
            check(event.completed >= completed, "Completion progress moved backwards"); completed = event.completed;
            if ("finished".equals(event.stage)) check(fixture.closed.get() >= event.completed, "Completion was published before owner cleanup");
        }
        JSONArray catalog = ToolCatalog.list(), results = result.getJSONArray("results");
        for (int i = 0; i < 13; i++) {
            String id = catalog.getJSONObject(i).getString("id"); JSONObject row = results.getJSONObject(i);
            check(id.equals(row.getString("id")), "Completion order changed inventory order");
            if (id.equals(fixture.broken)) check("error".equals(row.getString("state")) && row.getString("error").contains("missing library"), "Failure evidence lost");
            else check(row.getBoolean("ready") && row.getString("probe_output").contains("exit=0"), "Ready status has no actual probe output");
        }
        for (ToolBatchProbe.Progress event : events) if (event.result() != null) {
            JSONObject delivered = event.result(); delivered.put("state", "mutated");
            check(!"mutated".equals(event.result().getString("state")), "Published progress is mutable"); break;
        }
    }
    private static void explicitCancellationStopsQueueAndWaitsForOwnedCleanup() throws Exception {
        Fixture fixture = new Fixture("cancel"); AtomicBoolean cancel = new AtomicBoolean(); boolean stopped = false;
        try {
            ToolBatchProbe.run(fixture, () -> { if (cancel.get()) throw new InterruptedException("cancel fixture"); }, progress -> {
                if ("finished".equals(progress.stage)) cancel.set(true);
            }, null);
        } catch (InterruptedException expected) { stopped = true; }
        check(stopped && fixture.invoked.size() < 13 && fixture.opened.get() == fixture.closed.get(), "Cancel skipped cleanup or ran queued statuses");
    }
    private static void cleanupFailuresPreserveResultsAndAllOwnersFinish() throws Exception {
        Fixture fixture = new Fixture("cleanup"); fixture.cleanupFailure = true; AtomicInteger errors = new AtomicInteger();
        JSONObject result = ToolBatchProbe.run(fixture, LIVE, null, (id, stage, error) -> { if ("cleanup".equals(stage)) errors.incrementAndGet(); });
        check(result.getInt("ready_count") == 13 && errors.get() == 13 && fixture.closed.get() == 13, "Cleanup failure rewrote valid probe results or skipped an owner");
        for (int i = 0; i < 13; i++) check(result.getJSONArray("results").getJSONObject(i).getBoolean("cleanup_pending"), "Cleanup evidence omitted");
        Fixture observer = new Fixture("observer"); boolean failed = false;
        try { ToolBatchProbe.run(observer, LIVE, progress -> { if ("finished".equals(progress.stage)) throw new IllegalStateException("observer fixture"); }, null); }
        catch (IllegalStateException expected) { failed = true; }
        check(failed && observer.opened.get() == observer.closed.get() && observer.running.get() == 0, "Unexpected worker failure ended while peers still ran");
    }
    private static void snapshotsStayBoundedAndRemovedToolsAreNotInstalled() throws Exception {
        Fixture fixture = new Fixture("bounded"); fixture.oversized = true;
        JSONArray results = ToolBatchProbe.run(fixture, LIVE, null, null).getJSONArray("results");
        for (int i = 0; i < results.length(); i++) check(results.getJSONObject(i).getString("probe_output").length() == 12000
                && results.getJSONObject(i).getBoolean("output_truncated"), "Navigation snapshot is unbounded");
        ToolchainStore store = new ToolchainStore(new File(root, "removed"), name -> { throw new AssertionError("Probe downloaded or installed tools"); }, "arm64-v8a", 30);
        store.root().mkdir(); Files.write(new File(store.root(), "registry.json").toPath(), "{\"bundled_removed\":true,\"tools\":{}}".getBytes("UTF-8"));
        ToolBatchProbe.SessionFactory removed = () -> {
            ToolkitTool toolkit = new ToolkitTool(new ShellTool(false, root.getPath(), null), store, root.getPath(), null, "arm64-v8a", false);
            return new ToolBatchProbe.Session() { public ToolkitTool toolkit(){return toolkit;} public void abort(){toolkit.abort();} public void close(){} };
        };
        JSONObject result = ToolBatchProbe.run(removed, LIVE, null, null);
        check(result.getInt("ready_count") == 0 && result.getInt("failed_count") == 13, "Removed tools claimed ready");
    }
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("backcast-batch-probe-").toFile();
        try {
            parallelRealStatusesAndCleanupKeepFailureEvidence(); System.out.println("PASS independent parallel statuses preserve ordered evidence");
            explicitCancellationStopsQueueAndWaitsForOwnedCleanup(); System.out.println("PASS explicit cancellation drains only owned work");
            cleanupFailuresPreserveResultsAndAllOwnersFinish(); System.out.println("PASS cleanup/worker failures settle all owners");
            snapshotsStayBoundedAndRemovedToolsAreNotInstalled(); System.out.println("PASS bounded snapshots and no implicit installation");
        } finally { remove(root); }
    }
    private static void remove(File file) throws Exception { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); Files.deleteIfExists(file.toPath()); }
}
