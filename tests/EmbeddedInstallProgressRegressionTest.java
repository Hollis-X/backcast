package com.mkei.backcast.tool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Installation progress follows verified source and expanded bytes, never a timer. */
public final class EmbeddedInstallProgressRegressionTest {
    private static File root;
    private static final ToolchainInstaller.Cancellation LIVE = new ToolchainInstaller.Cancellation() { public void check() { } };
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Payload implements EmbeddedToolchain.Assets {
        final byte[] commonTar, common, nativeTar;
        final JSONObject manifest;
        int opens, closedStreams;
        boolean damaged;
        Payload() throws Exception {
            commonTar = tar("apktool/apktool-dex.jar", 90000);
            nativeTar = tar("usr/bin/greadelf", 170000);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(bytes); gzip.write(commonTar); gzip.close(); common = bytes.toByteArray();
            manifest = new JSONObject().put("version", "progress-fixture").put("artifacts", new JSONArray()
                    .put(artifact("any", "common", commonTar, common))
                    .put(artifact("arm64-v8a", "native", nativeTar, nativeTar)));
        }
        JSONObject artifact(String abi, String name, byte[] raw, byte[] source) throws Exception {
            return new JSONObject().put("abi", abi).put("asset", "toolchain/" + name + ".tar.gz")
                    .put("tar_asset", "toolchain/" + name + ".tar").put("sha256", hash(source))
                    .put("bytes", source.length).put("tar_sha256", hash(raw)).put("tar_bytes", raw.length);
        }
        public InputStream open(String name) throws Exception {
            opens++;
            byte[] source = name.endsWith("manifest.json") ? manifest.toString().getBytes("UTF-8") : name.contains("common") ? common : nativeTar;
            if (damaged && name.contains("native")) { source = source.clone(); source[source.length - 1] = 1; }
            return new ByteArrayInputStream(source) {
                private boolean closed;
                @Override public void close() throws java.io.IOException {
                    if (!closed) { closed = true; closedStreams++; }
                    super.close();
                }
            };
        }
        ToolchainStore store(String name) { return new ToolchainStore(new File(root, name), this, "arm64-v8a", 30); }
    }
    private static final class Events implements EmbeddedToolchain.ProgressListener {
        final List<EmbeddedToolchain.Progress> values = new ArrayList<EmbeddedToolchain.Progress>();
        public void onProgress(EmbeddedToolchain.Progress progress) { values.add(progress); }
        boolean has(String stage) { for (EmbeddedToolchain.Progress value : values) if (stage.equals(value.stage)) return true; return false; }
        void validate(boolean complete) {
            long previous = 0, total = -1;
            for (int i = 0; i < values.size(); i++) {
                EmbeddedToolchain.Progress value = values.get(i);
                check(value.completed >= previous && value.completed <= value.total, "Non-monotonic or overflowing real progress");
                previous = value.completed;
                if (value.total > 0) { if (total < 0) total = value.total; else check(total == value.total, "Known whole-package budget changed mid-install"); }
                if (value.percent() == 100) check(complete && i == values.size() - 1 && "complete".equals(value.stage), "100% preceded publication or escaped failure");
            }
            check(!values.isEmpty(), "No installation progress was delivered");
            if (complete) check(values.get(values.size() - 1).percent() == 100, "Successful installation did not complete");
            else check(!has("complete"), "Unsuccessful installation reported completion");
        }
    }
    private static byte[] tar(String name, int length) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] header = new byte[512];
        put(header, 0, name); put(header, 100, "0000755"); put(header, 124, String.format("%011o", length));
        header[156] = '0'; put(header, 257, "ustar");
        for (int i = 148; i < 156; i++) header[i] = ' ';
        int sum = 0; for (byte value : header) sum += value & 255;
        put(header, 148, String.format("%06o", sum)); header[154] = 0; header[155] = ' ';
        bytes.write(header);
        for (int i = 0; i < length; i++) bytes.write((i * 53 + i / 13) & 255);
        bytes.write(new byte[(512 - length % 512) % 512]); bytes.write(new byte[1024]); return bytes.toByteArray();
    }
    private static void put(byte[] target, int offset, String text) throws Exception {
        byte[] bytes = text.getBytes("UTF-8"); System.arraycopy(bytes, 0, target, offset, bytes.length);
    }
    private static String hash(byte[] bytes) throws Exception { return ToolchainInstaller.hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void stagingGone(ToolchainStore store) {
        File[] files = store.root().listFiles();
        if (files != null) for (File file : files) check(!file.getName().startsWith(".embedded-"), "Installation staging leaked");
    }
    private static void mixedArchiveFormatsReportAnExactWholePackageBudget() throws Exception {
        Payload payload = new Payload(); final ToolchainStore store = payload.store("real"); final Events events = new Events();
        check(store.installBundled(LIVE, new EmbeddedToolchain.ProgressListener() { public void onProgress(EmbeddedToolchain.Progress value) {
            events.onProgress(value);
            if (value.percent() == 100) try {
                check(store.packageStatus().getBoolean("installed"), "100% preceded verified publication of both packages");
                JSONArray catalog = ToolCatalog.list();
                for (int i = 0; i < catalog.length(); i++) check("bundled".equals(store.configuration(catalog.getJSONObject(i).getString("id")).optString("origin")),
                        "100% preceded durable launcher registration");
            } catch (Exception failure) { throw new AssertionError(failure); }
        } }).getBoolean("installed"), "Offline install failed"); events.validate(true);
        EmbeddedToolchain.Progress last = events.values.get(events.values.size() - 1);
        check(last.total == payload.common.length + payload.commonTar.length + 2L * payload.nativeTar.length + 3, "Budget did not include actual gzip/TAR reads, both publications and registration");
        List<String> phases = new ArrayList<String>();
        List<Long> starts = new ArrayList<Long>();
        for (EmbeddedToolchain.Progress value : events.values) {
            String phase = value.stage + (value.artifact.isEmpty() ? "" : ":" + value.artifact);
            if (phases.isEmpty() || !phase.equals(phases.get(phases.size() - 1))) {
                phases.add(phase); starts.add(value.completed);
            }
            if (!value.artifact.isEmpty()) check("any".equals(value.artifact) || "arm64-v8a".equals(value.artifact), "Progress lost payload identity");
        }
        check(phases.equals(java.util.Arrays.asList("checking", "verifying:any", "unpacking:any", "publishing:any",
                "verifying:arm64-v8a", "unpacking:arm64-v8a", "publishing:arm64-v8a", "registering", "complete")),
                "Real phase transitions were omitted, duplicated or reordered");
        long[] work = {payload.common.length, payload.commonTar.length, 1, payload.nativeTar.length, payload.nativeTar.length, 1, 1};
        for (int i = 0; i < work.length; i++) check(starts.get(i + 2) - starts.get(i + 1) == work[i],
                "Real total progress did not account for the completed phase " + phases.get(i + 1));
        check(events.values.size() < 30, "Fast byte reads were emitted without throttling"); stagingGone(store);
    }
    private static void cachedAndReinstalledPackagesResetTheirProgress() throws Exception {
        Payload payload = new Payload(); ToolchainStore store = payload.store("cache"); store.installBundled(LIVE, null);
        int opened = payload.opens; Events cached = new Events(); store.installBundled(LIVE, cached); cached.validate(true);
        check(payload.opens == opened && !cached.has("verifying") && !cached.has("unpacking"), "Cached installation faked copying or reread assets");
        check(cached.values.get(cached.values.size() - 1).total == 1, "Cached progress retained an earlier byte budget");
        store.removeBundled(LIVE); Events fresh = new Events(); store.installBundled(LIVE, fresh); fresh.validate(true);
        check(fresh.has("verifying") && fresh.values.get(0).completed == 0, "Reinstallation reused stale progress");
    }
    private static void cancellationNeverCompletesAndCleansEveryExtractionPhase() throws Exception {
        for (final String stage : new String[]{"verifying", "unpacking", "publishing", "registering"}) {
            Payload payload = new Payload(); ToolchainStore store = payload.store("cancel-" + stage); final Events events = new Events();
            final AtomicBoolean cancelled = new AtomicBoolean(); boolean interrupted = false;
            try { store.installBundled(new ToolchainInstaller.Cancellation() { public void check() throws Exception {
                if (cancelled.get()) throw new InterruptedException("cancelled progress fixture");
            } }, new EmbeddedToolchain.ProgressListener() { public void onProgress(EmbeddedToolchain.Progress value) {
                events.onProgress(value); if (stage.equals(value.stage)) cancelled.set(true);
            } }); } catch (InterruptedException expected) { interrupted = true; }
            check(interrupted, "Progress callback swallowed cancellation at " + stage); events.validate(false); stagingGone(store);
            check(store.installBundled(LIVE, null).getBoolean("installed"), "Cancelled phase prevented a verified retry");
        }
    }
    private static void checksumAndObserverFailureCannotReportSuccess() throws Exception {
        Payload payload = new Payload(); payload.damaged = true; ToolchainStore store = payload.store("sha"); Events events = new Events();
        boolean failed = false; try { store.installBundled(LIVE, events); } catch (java.io.IOException expected) { failed = true; }
        check(failed && !store.packageStatus().getBoolean("installed"), "Corrupt payload was published"); events.validate(false); stagingGone(store);
        check(payload.opens == payload.closedStreams, "Checksum failure leaked an APK asset stream");
        for (final String observerStage : new String[]{"verifying", "unpacking", "bytes"}) {
            final Events observed = new Events(); Payload listenerPayload = new Payload(); ToolchainStore listenerStore = listenerPayload.store("observer-" + observerStage);
            failed = false; try { listenerStore.installBundled(LIVE, new EmbeddedToolchain.ProgressListener() {
                public void onProgress(EmbeddedToolchain.Progress value) {
                    observed.onProgress(value);
                    if (observerStage.equals(value.stage) || "bytes".equals(observerStage) && "verifying".equals(value.stage) && value.completed > 0)
                        throw new IllegalStateException("broken observer");
                }
            }); } catch (IllegalStateException expected) { failed = true; }
            check(failed, "Observer failure was swallowed"); observed.validate(false); stagingGone(listenerStore);
            check(listenerPayload.opens == listenerPayload.closedStreams, "Observer failure leaked an APK asset stream at " + observerStage);
        }
        final Payload outputPayload = new Payload(); final ToolchainStore outputStore = outputPayload.store("output-open-failure"); final Events outputEvents = new Events();
        failed = false;
        try { outputStore.installBundled(LIVE, new EmbeddedToolchain.ProgressListener() { public void onProgress(EmbeddedToolchain.Progress value) {
            outputEvents.onProgress(value);
            if ("verifying".equals(value.stage) && value.completed == 0) {
                for (File directory : outputStore.root().listFiles()) if (directory.getName().startsWith(".embedded-"))
                    check(new File(directory, "payload.archive").mkdir(), "Could not inject a destination-open failure");
            }
        } }); } catch (java.io.IOException expected) { failed = true; }
        check(failed && outputPayload.opens == outputPayload.closedStreams, "Opening the destination failed after opening its asset stream without closing it");
        outputEvents.validate(false); stagingGone(outputStore);
        final ToolchainStore registryStore = new Payload().store("registry-failure"); final Events registryEvents = new Events();
        failed = false;
        try { registryStore.installBundled(LIVE, new EmbeddedToolchain.ProgressListener() { public void onProgress(EmbeddedToolchain.Progress value) {
            registryEvents.onProgress(value);
            if ("registering".equals(value.stage)) try { Files.write(new File(registryStore.root(), "registry.json").toPath(), "corrupted fixture".getBytes("UTF-8")); }
            catch (Exception failure) { throw new AssertionError(failure); }
        } }); } catch (Exception expected) { failed = true; }
        check(failed, "Broken persistent registration was reported as successful"); registryEvents.validate(false); stagingGone(registryStore);
        Files.delete(new File(registryStore.root(), "registry.json").toPath());
        check(registryStore.installBundled(LIVE, null).getBoolean("installed"), "Registration failure prevented a clean verified retry");
    }
    private static void callbacksKeepThePackageMutationGateHeld() throws Exception {
        final ToolchainStore store = new Payload().store("gate"); final Throwable[] failure = new Throwable[1]; final boolean[] tested = new boolean[1];
        store.installBundled(LIVE, new EmbeddedToolchain.ProgressListener() { public void onProgress(EmbeddedToolchain.Progress progress) {
            if (tested[0] || !"verifying".equals(progress.stage)) return; tested[0] = true;
            Thread other = new Thread(new Runnable() { public void run() {
                try { store.removeBundled(LIVE); failure[0] = new AssertionError("Progress released the mutation gate"); }
                catch (IllegalStateException expected) { } catch (Throwable error) { failure[0] = error; }
            } }); other.start();
            try { other.join(2000); if (other.isAlive()) throw new AssertionError("Busy mutation did not return promptly"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        } });
        check(tested[0] && failure[0] == null, "Cross-thread package deletion bypassed the installer");
    }
    private static void progressSnapshotsAreImmutable() throws Exception {
        for (Field field : EmbeddedToolchain.Progress.class.getFields()) check(Modifier.isFinal(field.getModifiers()), "Progress fields can change after delivery");
        Payload payload = new Payload(); Events events = new Events();
        ToolkitTool tool = new ToolkitTool(new ShellTool(false, root.getPath(), null), payload.store("toolkit"), root.getPath(), null, "arm64-v8a");
        check(tool.installBundled(events).getBoolean("installed"), "Toolkit/UI bridge did not forward installation progress"); events.validate(true);
        check(events.values.get(0).completed == 0 && events.values.get(0).total == 0, "The initial immutable snapshot was mutated during installation");
    }
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("backcast-install-progress-").toFile();
        try {
            for (String method : new String[]{"mixedArchiveFormatsReportAnExactWholePackageBudget", "cachedAndReinstalledPackagesResetTheirProgress",
                    "cancellationNeverCompletesAndCleansEveryExtractionPhase", "checksumAndObserverFailureCannotReportSuccess",
                    "callbacksKeepThePackageMutationGateHeld", "progressSnapshotsAreImmutable"}) {
                EmbeddedInstallProgressRegressionTest.class.getDeclaredMethod(method).invoke(null); System.out.println("PASS " + method);
            }
        } finally { remove(root); }
    }
    private static void remove(File file) throws Exception {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        if (file.exists() && !file.delete()) throw new AssertionError("Fixture cleanup failed: " + file);
    }
}
