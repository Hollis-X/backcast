package com.mkei.backcast.tool;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Executes the same manager used by RunHub and all tool configuration pages. */
public final class ToolkitOperationManagerRegressionTest {
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static ToolkitOperationManager manager(ToolBatchProbeRegressionTest.Fixture fixture, AtomicInteger diagnostics) {
        return new ToolkitOperationManager(() -> {
            final String context = fixture.context();
            return new ToolBatchProbe.SessionFactory() {
                public String context() { return context; }
                public ToolBatchProbe.Session open() throws Exception { return fixture.open(); }
            };
        }, (action, tool, stage, error, progress) -> diagnostics.incrementAndGet(), () -> { });
    }
    private static void idle(ToolkitOperationManager manager) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (manager.busy() && System.nanoTime() < deadline) Thread.sleep(5);
        check(!manager.busy(), "Application operation did not naturally settle");
    }
    public static void main(String[] args) throws Exception {
        File root = java.nio.file.Files.createTempDirectory("backcast-tool-operation-manager-").toFile();
        java.lang.reflect.Field fixtureRoot = ToolBatchProbeRegressionTest.class.getDeclaredField("root");
        fixtureRoot.setAccessible(true); fixtureRoot.set(null, root);
        try { run(root); }
        finally {
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(root.toPath())) {
                for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))
                    java.nio.file.Files.deleteIfExists(path);
            }
        }
    }
    static void run(File root) throws Exception {
        ToolBatchProbeRegressionTest.Fixture cold = new ToolBatchProbeRegressionTest.Fixture("manager-cold");
        CountDownLatch inventoryEntered = new CountDownLatch(1), inventoryRelease = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        ToolkitOperationManager loading = new ToolkitOperationManager(() -> new ToolBatchProbe.SessionFactory() {
            public String context() { return "cold"; }
            public ToolBatchProbe.Session open() throws Exception {
                if (first.compareAndSet(true, false)) { inventoryEntered.countDown(); inventoryRelease.await(3, TimeUnit.SECONDS); }
                return cold.open();
            }
        }, null, null);
        loading.refreshInventory(); check(inventoryEntered.await(2, TimeUnit.SECONDS), "Cold inventory did not begin");
        boolean rejected = false;
        try { loading.start("batch_status", ""); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected && loading.snapshot().refreshing && !loading.snapshot().busy, "Cold probe captured an unknown package identity");
        inventoryRelease.countDown();
        long inventoryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (loading.snapshot().refreshing && System.nanoTime() < inventoryDeadline) Thread.sleep(5);
        loading.start("batch_status", ""); idle(loading);
        check(loading.snapshot().context.equals(loading.configurationContext()) && "batch_complete".equals(loading.snapshot().state), "Cold batch summary was silently invalidated");
        System.out.println("PASS cold inventory settles before probe configuration is captured");

        ToolBatchProbeRegressionTest.Fixture fixture = new ToolBatchProbeRegressionTest.Fixture("manager");
        fixture.gate = new CountDownLatch(1); fixture.entered = new CountDownLatch(2);
        AtomicInteger diagnostics = new AtomicInteger(); ToolkitOperationManager manager = manager(fixture, diagnostics);
        List<ToolkitOperationManager.Snapshot> states = new CopyOnWriteArrayList<ToolkitOperationManager.Snapshot>();
        ToolkitOperationManager.Listener page = states::add;
        manager.subscribe(page); long id = manager.start("batch_status", "");
        check(fixture.entered.await(3, TimeUnit.SECONDS), "Manager did not start concurrent probe workers");
        manager.unsubscribe(page); int detached = states.size();
        check(manager.busy(), "Detaching a tool page cancelled operation"); fixture.gate.countDown(); idle(manager);
        check(states.size() == detached && fixture.aborts.get() == 0, "Detached page received state or normal finish aborted sessions");
        manager.subscribe(page);
        ToolkitOperationManager.Snapshot complete = states.get(states.size() - 1);
        check(complete.id == id && !complete.busy && complete.result().getInt("completed") == 13 && fixture.closed.get() == 13,
                "Recreated page lost automatic completion/results");
        complete.result().put("state", "mutated"); check("batch_complete".equals(manager.snapshot().result().getString("state")), "View mutated shared terminal state");
        manager.cancel(id); check("batch_complete".equals(manager.snapshot().state), "Late Cancel rewrote naturally completed operation");
        String captured = complete.context;
        fixture.configuration = "root=true|project|additional";
        check(captured.equals(manager.snapshot().context) && !captured.equals(manager.configurationContext()), "Settings changes relabelled old probe evidence");
        manager.refreshInventory();
        long refreshDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (manager.snapshot().id != 0 && System.nanoTime() < refreshDeadline) Thread.sleep(5);
        check(manager.snapshot().id == 0, "Reopening with changed root/workspaces restored stale ready results");
        manager.unsubscribe(page);
        System.out.println("PASS app-owned probe survives page detach and restores one terminal snapshot");

        ToolBatchProbeRegressionTest.Fixture cancel = new ToolBatchProbeRegressionTest.Fixture("manager-cancel");
        cancel.gate = new CountDownLatch(1); cancel.entered = new CountDownLatch(2);
        ToolkitOperationManager interrupted = manager(cancel, diagnostics);
        long cancelledId = interrupted.start("batch_status", "");
        check(cancel.entered.await(3, TimeUnit.SECONDS), "Cancel fixture did not enter probes");
        interrupted.cancel(cancelledId); idle(interrupted); cancel.gate.countDown();
        check("cancelled".equals(interrupted.snapshot().state) && cancel.opened.get() == cancel.closed.get() && cancel.invoked.size() < 13,
                "Explicit cancel lost identity, cleanup or queued-work cancellation");
        int aborted = cancel.aborts.get(); interrupted.cancel(cancelledId); check(cancel.aborts.get() == aborted, "Duplicate cancellation revived finished work");
        ToolBatchProbe.Session unrelated = fixture.open();
        try { check(unrelated.toolkit().status("readelf").getBoolean("ready"), "UI cancel invalidated an independent runner"); }
        finally { unrelated.close(); }
        long replacement = interrupted.start("batch_status", ""); idle(interrupted);
        check(replacement != cancelledId && "batch_complete".equals(interrupted.snapshot().state), "Cancelled callback contaminated replacement operation");
        System.out.println("PASS explicit cancellation preserves runner isolation and cannot overwrite replacement work");

        ToolBatchProbeRegressionTest.Fixture cleanup = new ToolBatchProbeRegressionTest.Fixture("manager-cleanup"); cleanup.cleanupFailure = true;
        ToolkitOperationManager pending = manager(cleanup, diagnostics);
        pending.subscribe(state -> { throw new IllegalStateException("broken UI observer"); });
        pending.start("batch_status", ""); idle(pending);
        check("batch_complete".equals(pending.snapshot().state) && pending.snapshot().result().getInt("ready_count") == 13
                && cleanup.closed.get() == 13 && diagnostics.get() >= 13, "Cleanup/observer errors stranded busy state or rewrote valid evidence");
        System.out.println("PASS cleanup and subscriber exceptions preserve results and release controls");
    }
}
