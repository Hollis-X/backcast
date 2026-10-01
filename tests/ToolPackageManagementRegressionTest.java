package com.mkei.backcast.tool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Package deletion must share a gate with real model and UI tool invocations. */
public final class ToolPackageManagementRegressionTest {
    private static File root;
    private static final ToolchainInstaller.Cancellation LIVE = new ToolchainInstaller.Cancellation() { public void check() { } };
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class Payload implements EmbeddedToolchain.Assets {
        final byte[] gzip;
        final JSONObject manifest;
        int opens;
        Payload() throws Exception {
            ByteArrayOutputStream tar = new ByteArrayOutputStream();
            for (String path : new String[]{"apktool/apktool-dex.jar", "usr/bin/greadelf", "usr/bin/python3", "radare2/bin/radare2"}) {
                byte[] header = new byte[512], data = "managed offline fixture".getBytes("UTF-8");
                put(header, 0, path); put(header, 100, "0000755"); put(header, 124, String.format("%011o", data.length));
                header[156] = '0'; put(header, 257, "ustar");
                for (int i = 148; i < 156; i++) header[i] = ' ';
                int sum = 0; for (byte value : header) sum += value & 255;
                put(header, 148, String.format("%06o", sum)); header[154] = 0; header[155] = ' ';
                tar.write(header); tar.write(data); tar.write(new byte[512 - data.length]);
            }
            tar.write(new byte[1024]); byte[] raw = tar.toByteArray();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(); GZIPOutputStream zipped = new GZIPOutputStream(compressed);
            zipped.write(raw); zipped.close(); gzip = compressed.toByteArray();
            JSONObject artifact = new JSONObject().put("asset", "toolchain/fixture.tar.gz").put("tar_asset", "toolchain/fixture.tar")
                    .put("sha256", hash(gzip)).put("bytes", gzip.length).put("tar_sha256", hash(raw)).put("tar_bytes", raw.length);
            manifest = new JSONObject().put("version", "fixture").put("artifacts", new JSONArray()
                    .put(new JSONObject(artifact.toString()).put("abi", "any"))
                    .put(new JSONObject(artifact.toString()).put("abi", "arm64-v8a")));
        }
        public InputStream open(String name) throws Exception {
            opens++;
            return new ByteArrayInputStream(name.endsWith("manifest.json") ? manifest.toString().getBytes("UTF-8") : gzip);
        }
        ToolchainStore store(String name) { return new ToolchainStore(new File(root, name), this, "arm64-v8a", 30); }
    }
    private static String hash(byte[] data) throws Exception { return ToolchainInstaller.hex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private static void put(byte[] target, int offset, String value) throws Exception {
        byte[] data = value.getBytes("UTF-8"); System.arraycopy(data, 0, target, offset, data.length);
    }

    private static void repeatedOfflineInstallationRetainsTheSameVerifiedFiles() throws Exception {
        Payload payload = new Payload(); ToolchainStore store = payload.store("repeat");
        check("not_installed".equals(store.packageStatus().getString("state")), "Listing installed software as a side effect");
        check(store.installBundled(LIVE).getBoolean("installed"), "Offline installation failed");
        File jar = new File(store.launcher("apktool").prefix.get(2)); byte[] original = Files.readAllBytes(jar.toPath());
        int opens = payload.opens;
        check(store.installBundled(LIVE).getBoolean("installed") && payload.opens == opens, "Repeated installation recopied APK resources");
        check(java.util.Arrays.equals(original, Files.readAllBytes(jar.toPath())), "Repeated installation rewrote the verified tool");
        ToolchainStore second = new ToolchainStore(store.root(), payload, "arm64-v8a", 30);
        second.removeBundled(LIVE);
        check(store.installBundled(LIVE).getBoolean("installed") && new File(store.launcher("apktool").prefix.get(2)).isFile(),
                "A different store deleted software but its old prepared cache prevented reinstallation");
    }

    private static final class BlockingShell extends ShellTool {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        BlockingShell() { super(false, root.getPath()); }
        @Override String runProgram(ToolchainStore.Launcher launcher, List<String> arguments, boolean temporary, int timeout, int epoch) throws Exception {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture command did not finish");
            return "exit=0\nGNU readelf 2.47\n";
        }
    }
    private static void modelRunAndUiProbeBothBlockInstallAndDelete() throws Exception {
        for (final String action : new String[]{"run", "status"}) {
            Payload payload = new Payload(); final ToolchainStore store = payload.store("busy-" + action); store.installBundled(LIVE);
            final BlockingShell shell = new BlockingShell(); final ToolkitTool tool = new ToolkitTool(shell, store, root.getPath(), null, "arm64-v8a");
            final Throwable[] failure = new Throwable[1];
            Thread worker = new Thread(new Runnable() { public void run() {
                try {
                    JSONObject result = new JSONObject(tool.run(new JSONObject().put("action", action).put("tool", "readelf")));
                    check(!"error".equals(result.optString("state")), "Tool invocation failed: " + result);
                } catch (Throwable error) { failure[0] = error; }
            } });
            worker.start(); check(shell.entered.await(3, TimeUnit.SECONDS), "Actual invocation did not enter the shell");
            try {
                for (boolean remove : new boolean[]{false, true}) {
                    boolean busy = false;
                    try { if (remove) store.removeBundled(LIVE); else store.installBundled(LIVE); }
                    catch (IllegalStateException expected) { busy = expected.getMessage().contains("正在执行"); }
                    check(busy, "Package mutation bypassed active " + action);
                }
                ToolchainStore second = new ToolchainStore(store.root(), payload, "arm64-v8a", 30);
                boolean busy = false; try { second.removeBundled(LIVE); } catch (IllegalStateException expected) { busy = true; }
                check(busy && store.packageStatus().getBoolean("installed"), "A second store instance deleted a running private package");
            } finally { shell.release.countDown(); worker.join(5000); }
            check(!worker.isAlive() && failure[0] == null, "Invocation did not release its package gate");
            check("removed".equals(store.removeBundled(LIVE).getString("state")), "Finished invocation left the package permanently busy");
        }
    }

    private static void deleteOnlyTouchesOwnedPrivatePayloadAndRequiresExplicitReinstallation() throws Exception {
        Payload payload = new Payload(); ToolchainStore store = payload.store("scope"); store.installBundled(LIVE);
        File project = new File(root, "project.txt"), persistent = new File(store.root(), "sessions.json"), unknown = new File(store.root(), "unrelated");
        Files.write(project.toPath(), "project".getBytes("UTF-8")); Files.write(persistent.toPath(), "history".getBytes("UTF-8")); unknown.mkdir();
        store.removeBundled(LIVE); int opens = payload.opens;
        check(project.isFile() && persistent.isFile() && unknown.isDirectory(), "Deletion removed project or unrelated private data");
        for (File child : store.root().listFiles()) check(!child.getName().startsWith("builtin-"), "Released shared package survived deletion");
        ToolkitTool toolkit = new ToolkitTool(new ShellTool(false, root.getPath()), store, root.getPath(), null, "arm64-v8a");
        check("removed".equals(toolkit.status("readelf").getString("state")) && payload.opens == opens, "Probe silently reinstalled deleted software");
        boolean refused = false; try { store.launcher("readelf"); } catch (IllegalStateException expected) { refused = true; }
        check(refused && store.installBundled(LIVE).getBoolean("installed"), "Explicit offline reinstallation did not restore removed tools");
    }

    private static void cancelledDeleteKeepsOwnershipAndCanBeResumed() throws Exception {
        for (boolean resumeDelete : new boolean[]{false, true}) {
            Payload payload = new Payload(); ToolchainStore store = payload.store("cancel-delete-" + resumeDelete); store.installBundled(LIVE);
            final int[] checks = new int[1]; boolean cancelled = false;
            try { store.removeBundled(new ToolchainInstaller.Cancellation() { public void check() throws Exception {
                if (++checks[0] == 7) throw new InterruptedException("cancelled fixture");
            } }); } catch (InterruptedException expected) { cancelled = true; }
            check(cancelled && store.bundledRemoved(), "Cancelled deletion re-enabled incomplete software");
            if (resumeDelete) check("removed".equals(store.removeBundled(LIVE).getString("state")), "Cancelled deletion lost ownership evidence and cannot be retried");
            check(store.installBundled(LIVE).getBoolean("installed"), "Cancelled deletion blocked clean reinstallation");
            check(new File(store.launcher("apktool").prefix.get(2)).isFile(), "Reinstallation trusted the receipt of a partially deleted tool package");
            check(new File(store.launcher("readelf").executable).isFile(), "Reinstallation did not restore all shared native tools");
        }
    }

    private static void linkedOrUnownedPrivatePayloadIsRejected() throws Exception {
        Payload payload = new Payload(); ToolchainStore store = payload.store("linked"); store.installBundled(LIVE);
        File target = new File(root, "outside"); target.mkdir(); File precious = new File(target, "precious.txt"); Files.write(precious.toPath(), "keep".getBytes("UTF-8"));
        File candidate = new File(store.root(), "builtin-common-malicious-1234567890abcdef"); Files.createSymbolicLink(candidate.toPath(), target.toPath());
        boolean refused = false; try { store.removeBundled(LIVE); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused && precious.isFile() && !store.bundledRemoved(), "Deletion followed a substituted managed directory");
        Files.delete(candidate.toPath()); candidate.mkdir();
        refused = false; try { store.removeBundled(LIVE); } catch (java.io.IOException expected) { refused = true; }
        check(refused && candidate.isDirectory() && !store.bundledRemoved(), "Deletion removed an unverified private directory");
    }

    private static void cancelledInstallCleansItsStagingAndLeavesNoUsablePartialPackage() throws Exception {
        Payload payload = new Payload(); ToolchainStore store = payload.store("cancel-install"); final int[] checks = new int[1];
        boolean cancelled = false;
        try { store.installBundled(new ToolchainInstaller.Cancellation() { public void check() throws Exception {
            if (++checks[0] == 8) throw new InterruptedException("cancelled fixture");
        } }); } catch (InterruptedException expected) { cancelled = true; }
        check(cancelled && !store.packageStatus().getBoolean("installed"), "Cancelled installation published a runnable package");
        for (File file : store.root().listFiles()) check(!file.getName().startsWith(".embedded-"), "Cancelled installation leaked extraction staging");
        check(store.installBundled(LIVE).getBoolean("installed"), "Cancelled installation cannot be retried");
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("backcast-tool-package-tests-").toFile();
        try {
            for (String name : new String[]{"repeatedOfflineInstallationRetainsTheSameVerifiedFiles", "modelRunAndUiProbeBothBlockInstallAndDelete",
                    "deleteOnlyTouchesOwnedPrivatePayloadAndRequiresExplicitReinstallation", "cancelledDeleteKeepsOwnershipAndCanBeResumed",
                    "linkedOrUnownedPrivatePayloadIsRejected", "cancelledInstallCleansItsStagingAndLeavesNoUsablePartialPackage"}) {
                ToolPackageManagementRegressionTest.class.getDeclaredMethod(name).invoke(null); System.out.println("PASS " + name);
            }
        } finally { remove(root); }
    }
    private static void remove(File path) throws Exception {
        if (Files.isSymbolicLink(path.toPath())) { Files.delete(path.toPath()); return; }
        File[] children = path.listFiles(); if (children != null) for (File child : children) remove(child);
        if (path.exists() && !path.delete()) throw new AssertionError("Fixture cleanup failed: " + path);
    }
}
