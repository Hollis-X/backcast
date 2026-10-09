package com.mkei.backcast.tool;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses actual ToolkitTool.status calls and observes independent cancellation boundaries. */
public final class ToolBatchProbeRegressionTest {
    private static File root;
    private static final ToolchainInstaller.Cancellation LIVE = new ToolchainInstaller.Cancellation() { public void check() { } };
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Fixture {
        final ToolchainStore store;
        final TemporaryWorkspace temporary;
        final List<String> invoked = new ArrayList<String>();
        String broken = ""; boolean oversized;
        final ToolkitTool toolkit;
        Fixture(String name) throws Exception {
            File project = new File(root, name + "-project"); project.mkdir();
            store = new ToolchainStore(new File(root, name + "-software"), null, "", 0);
            temporary = new TemporaryWorkspace(project.getPath(), false, new File(root, name + "-state"), 1); temporary.beginTurn();
            JSONArray catalog = ToolCatalog.list();
            for (int i = 0; i < catalog.length(); i++) {
                String id = catalog.getJSONObject(i).getString("id"); File executable = new File(project, id + ("apktool".equals(id) ? ".jar" : ""));
                Files.write(executable.toPath(), new byte[]{1});
                ToolchainFixtures.configure(store, id, executable.getPath(), "apktool".equals(id) ? "/usr/bin/java" : null);
            }
            ShellTool shell = new ShellTool(false, project.getPath(), temporary) {
                @Override String runProgram(ToolchainStore.Launcher launcher, List<String> arguments, boolean temp, int timeout, int epoch) throws Exception {
                    invoked.add(launcher.id);
                    if (launcher.id.equals(broken)) throw new java.io.IOException("fixture missing library");
                    String version = "apktool".equals(launcher.id) ? "2.9.3" : "objection".equals(launcher.id) ? "objection: 1.12.5"
                            : "radare2".equals(launcher.id) || "rabin2".equals(launcher.id) ? launcher.id + " 6.2.2" : "GNU " + launcher.id + " 2.47";
                    StringBuilder result = new StringBuilder("exit=0\n" + version + "\n");
                    if (oversized) for (int i = 0; i < 13000; i++) result.append('x');
                    return result.toString();
                }
            };
            toolkit = new ToolkitTool(shell, store, project.getPath(), temporary, "arm64-v8a", false);
        }
        void close() { toolkit.abort(); check(temporary.finishTurn() == null, "Independent probe temporary cleanup failed"); }
    }
    private static void probesAllThirteenRealStatusEntrypointsAndKeepsFailureEvidence() throws Exception {
        Fixture fixture = new Fixture("all"); fixture.broken = "radare2"; final List<ToolBatchProbe.Progress> events = new ArrayList<ToolBatchProbe.Progress>();
        try {
            JSONObject result = ToolBatchProbe.run(fixture.toolkit, LIVE, new ToolBatchProbe.Listener() { public void onProgress(ToolBatchProbe.Progress progress) { events.add(progress); } });
            check(result.getInt("completed") == 13 && result.getInt("ready_count") == 12 && result.getInt("failed_count") == 1,
                    "One failure stopped or corrupted the aggregate probe");
            check(fixture.invoked.size() == 13 && events.size() == 26, "Catalog probes were skipped, duplicated or faked");
            JSONArray catalog = ToolCatalog.list(), results = result.getJSONArray("results");
            for (int i = 0; i < 13; i++) {
                String id = catalog.getJSONObject(i).getString("id");
                check(id.equals(fixture.invoked.get(i)) && events.get(i * 2).completed == i && "running".equals(events.get(i * 2).stage)
                        && events.get(i * 2 + 1).completed == i + 1, "Progress is not attached to the actual status call");
                JSONObject row = results.getJSONObject(i);
                if (id.equals(fixture.broken)) check("error".equals(row.getString("state")) && row.getString("error").contains("missing library"), "Failed tool lost actionable output");
                else check(row.getBoolean("ready") && row.getString("probe_output").contains("exit=0"), "Success did not include actual probe evidence");
            }
            JSONObject delivered = events.get(1).result(); delivered.put("state", "mutated");
            check(!"mutated".equals(events.get(1).result().getString("state")), "Worker snapshot was mutable after delivery");
        } finally { fixture.close(); }
    }
    private static void cancellationStopsRemainingProbesAndRetainsOwnerCleanup() throws Exception {
        for (final String stage : new String[]{"running", "finished"}) {
            Fixture fixture = new Fixture("cancel-" + stage); final AtomicBoolean cancel = new AtomicBoolean(); boolean stopped = false;
            try {
                ToolBatchProbe.run(fixture.toolkit, new ToolchainInstaller.Cancellation() { public void check() throws Exception {
                    if (cancel.get()) throw new InterruptedException("cancel fixture");
                } }, new ToolBatchProbe.Listener() { public void onProgress(ToolBatchProbe.Progress progress) {
                    if (stage.equals(progress.stage)) cancel.set(true);
                } });
            } catch (InterruptedException expected) { stopped = true; }
            finally { fixture.close(); }
            check(stopped && fixture.invoked.size() == ("running".equals(stage) ? 0 : 1), "Cancelled batch started a remaining tool");
        }
    }
    private static void outputSnapshotsAreBoundedWithoutConvertingFailureToReady() throws Exception {
        Fixture fixture = new Fixture("bounded"); fixture.oversized = true;
        try {
            JSONArray results = ToolBatchProbe.run(fixture.toolkit, LIVE, null).getJSONArray("results");
            for (int i = 0; i < results.length(); i++) check(results.getJSONObject(i).getString("probe_output").length() == 12000
                    && results.getJSONObject(i).getBoolean("output_truncated"), "Per-row snapshot can overflow activity navigation/state");
        } finally { fixture.close(); }
    }
    private static void deletedPackagesNeverGetInstalledByBatchProbe() throws Exception {
        ToolchainStore store = new ToolchainStore(new File(root, "removed"), new EmbeddedToolchain.Assets() {
            public java.io.InputStream open(String name) { throw new AssertionError("Removed tools implicitly opened APK assets"); }
        }, "arm64-v8a", 30);
        store.root().mkdir(); Files.write(new File(store.root(), "registry.json").toPath(), "{\"bundled_removed\":true,\"tools\":{}}".getBytes("UTF-8"));
        ToolkitTool toolkit = new ToolkitTool(new ShellTool(false, root.getPath(), null), store, root.getPath(), null, "arm64-v8a", false);
        JSONObject result = ToolBatchProbe.run(toolkit, LIVE, null);
        check(result.getInt("ready_count") == 0 && result.getInt("failed_count") == 13, "Deleted tools were claimed ready or restored");
        for (int i = 0; i < result.getJSONArray("results").length(); i++) check("removed".equals(result.getJSONArray("results").getJSONObject(i).getString("state")), "Removed state was concealed");
    }
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("backcast-batch-probe-").toFile();
        try { for (String method : new String[]{"probesAllThirteenRealStatusEntrypointsAndKeepsFailureEvidence", "cancellationStopsRemainingProbesAndRetainsOwnerCleanup",
                "outputSnapshotsAreBoundedWithoutConvertingFailureToReady", "deletedPackagesNeverGetInstalledByBatchProbe"}) {
            ToolBatchProbeRegressionTest.class.getDeclaredMethod(method).invoke(null); System.out.println("PASS " + method);
        } } finally { remove(root); }
    }
    private static void remove(File file) throws Exception {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        if (file.exists() && !file.delete()) throw new AssertionError("Fixture cleanup failed: " + file);
    }
}
