package com.mkei.backcast.tool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** A small APK manifest pins downloaded release files and private installed directories. */
public final class EmbeddedToolchain {
    public interface Assets { InputStream open(String name) throws Exception; }
    /** Called on the installation worker; observers should post UI updates without waiting. */
    public interface ProgressListener { void onProgress(Progress progress); }
    public static final class Progress {
        public final String stage, artifact;
        public final long completed, total;
        Progress(String stage, String artifact, long completed, long total) {
            this.stage = stage; this.artifact = artifact; this.completed = completed; this.total = total;
        }
        public int percent() {
            if ("complete".equals(stage)) return 100;
            return total <= 0 ? 0 : (int) Math.min(99, completed * 100 / total);
        }
    }
    private static final class ProgressTracker {
        final ProgressListener listener;
        long completed, total, notified;
        String stage = "checking", artifact = "";
        ProgressTracker(ProgressListener listener) { this.listener = listener; }
        void phase(String name, String id) {
            stage = name; artifact = id; emit(true);
        }
        void advance(long bytes) {
            completed += bytes; emit(false);
        }
        void emit(boolean force) {
            if (listener == null) return;
            long now = System.nanoTime();
            if (!force && now - notified < 100000000L) return;
            notified = now;
            listener.onProgress(new Progress(stage, artifact, completed, total));
        }
        void finish() { completed = total; phase("complete", ""); }
    }
    private static final class PayloadPlan {
        final JSONObject artifact;
        final String id;
        final File destination;
        final boolean cached;
        final long archiveBytes, tarBytes;
        PayloadPlan(JSONObject artifact, String id, File destination, boolean cached, long archiveBytes, long tarBytes) {
            this.artifact = artifact; this.id = id; this.destination = destination;
            this.cached = cached; this.archiveBytes = archiveBytes; this.tarBytes = tarBytes;
        }
        long work() { return cached ? 0 : archiveBytes + tarBytes + 1; }
    }
    private final ToolchainStore store;
    private final Assets assets;
    private final String abi;
    private final int sdk;
    private final ToolchainDownloader downloader;
    private JSONObject manifest;
    private File prepared;
    private File preparedCommon;

    EmbeddedToolchain(ToolchainStore store, Assets assets, String abi, int sdk, ToolchainDownloader downloader) {
        this.store = store; this.assets = assets;
        this.abi = "armeabi".equals(abi) ? "armeabi-v7a" : abi; this.sdk = sdk; this.downloader = downloader;
    }

    public boolean supports(String id) {
        ToolCatalog.get(id);
        return sdk >= 26 && ("arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi));
    }

    synchronized boolean isPrepared() { return prepared != null; }
    synchronized void resetPrepared() { prepared = null; preparedCommon = null; }

    JSONObject packageStatus() throws Exception {
        if (!supports("apktool")) return new JSONObject().put("state", "unsupported").put("installed", false);
        JSONObject data = manifest(), common = artifact("any"), nativeTools = artifact(abi);
        String version = data.getString("version");
        File commonDir = new File(store.root(), "builtin-common-" + version + "-" + common.getString("sha256").substring(0, 16));
        File nativeDir = new File(store.root(), "builtin-" + abi + "-" + version + "-" + nativeTools.getString("sha256").substring(0, 16));
        boolean installed = verified(commonDir, common.getString("sha256")) && verified(nativeDir, nativeTools.getString("sha256"));
        boolean removed = store.bundledRemoved();
        long commonBytes = installed && !removed ? store.payloadBytes(commonDir) : 0;
        long nativeBytes = installed && !removed ? store.payloadBytes(nativeDir) : 0;
        long bytes = commonBytes < 0 || nativeBytes < 0 ? -1 : commonBytes + nativeBytes;
        return new JSONObject().put("state", installed && !removed ? "installed" : "not_installed")
                .put("installed", installed && !removed).put("storage", store.root().getPath()).put("abi", abi)
                .put("version", version).put("manifest", new JSONObject(data.toString()))
                .put("installed_bytes", Math.max(0, bytes)).put("installed_size_unknown", bytes < 0);
    }

    private boolean verified(File directory, String digest) throws Exception {
        File receipt = store.managed(new File(directory, ".verified-sha256").getPath());
        return receipt.isFile() && digest.equals(new String(ToolPaths.readBytes(receipt, 128, false), "UTF-8"));
    }

    synchronized File prepare(ToolchainInstaller.Cancellation cancellation, ProgressListener listener, boolean allowDownload) throws Exception {
        cancellation.check();
        ProgressTracker progress = new ProgressTracker(listener);
        progress.phase("checking", "");
        if (!supports("apktool")) throw new IllegalArgumentException("工具包需要 Android 8.0+ 和 ARM/ARM64；当前 ABI=" + abi + "，API=" + sdk);
        JSONObject data = manifest();
        String version = data.getString("version");
        JSONObject commonArtifact = artifact("any"), nativeArtifact = artifact(abi);
        if (prepared != null && (!verified(preparedCommon, commonArtifact.getString("sha256"))
                || !verified(prepared, nativeArtifact.getString("sha256")))) resetPrepared();
        PayloadPlan commonPlan = plan(commonArtifact, "builtin-common-" + version + "-" + commonArtifact.getString("sha256").substring(0, 16), cancellation);
        PayloadPlan nativePlan = plan(nativeArtifact, "builtin-" + abi + "-" + version + "-" + nativeArtifact.getString("sha256").substring(0, 16), cancellation);
        if (!allowDownload && (!commonPlan.cached || !nativePlan.cached)) throw new IllegalStateException("工具包尚未安装，请在工具配置中点击安装。");
        progress.total = commonPlan.work() + nativePlan.work() + 1;
        progress.emit(true);
        File common = unpack(commonPlan, version, cancellation, progress);
        File nativeTools = unpack(nativePlan, version, cancellation, progress);
        cancellation.check();
        progress.phase("registering", "");
        cancellation.check();
        store.bundledInstalled(common, nativeTools, data, abi);
        preparedCommon = common;
        prepared = nativeTools;
        cancellation.check();
        progress.finish();
        return prepared;
    }

    private PayloadPlan plan(JSONObject artifact, String name, ToolchainInstaller.Cancellation cancellation) throws Exception {
        cancellation.check();
        File destination = store.managed(new File(store.root(), name).getPath());
        boolean cached = verified(destination, artifact.getString("sha256"));
        if (cached) return new PayloadPlan(artifact, artifact.getString("abi"), destination, true, 0, 0);
        if (destination.exists()) throw new IOException("工具包目录校验记录不一致。");
        cancellation.check();
        long archiveBytes = artifact.getLong("bytes");
        long tarBytes = artifact.optLong("tar_bytes", -1);
        if (archiveBytes <= 0 || archiveBytes > 512L * 1024 * 1024 || tarBytes <= 0 || tarBytes > 512L * 1024 * 1024
                || !artifact.optString("tar_sha256", "").matches("[0-9a-f]{64}"))
            throw new IOException("工具包缺少有效的归档大小记录。");
        return new PayloadPlan(artifact, artifact.getString("abi"), destination, false, archiveBytes, tarBytes);
    }

    private JSONObject manifest() throws Exception {
        if (manifest != null) return manifest;
        InputStream stream = assets.open("toolchain/manifest.json");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) >= 0) {
                bytes.write(buffer, 0, count);
                if (bytes.size() > 512 * 1024) throw new IOException("工具包清单过大。");
            }
        } finally { stream.close(); }
        manifest = new JSONObject(new String(bytes.toByteArray(), "UTF-8"));
        return manifest;
    }

    private JSONObject artifact(String requestedAbi) throws Exception {
        JSONArray artifacts = manifest().getJSONArray("artifacts");
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject item = artifacts.getJSONObject(i);
            if (requestedAbi.equals(item.getString("abi"))) return item;
        }
        throw new IOException("工具包清单中缺少当前 ABI。");
    }

    private File unpack(final PayloadPlan plan, String version, ToolchainInstaller.Cancellation cancellation, final ProgressTracker progress) throws Exception {
        JSONObject artifact = plan.artifact;
        File destination = plan.destination;
        File receipt = store.managed(new File(destination, ".verified-sha256").getPath());
        String digest = artifact.getString("sha256");
        if (plan.cached) {
            cancellation.check();
            if (!receipt.isFile() || !digest.equals(new String(ToolPaths.readBytes(receipt, 128, false), "UTF-8"))) throw new IOException("工具包目录校验记录已变化。");
            return destination;
        }
        if (destination.exists()) throw new IOException("工具包目录校验记录不一致。");
        if (!store.root().isDirectory() && !store.root().mkdirs()) throw new IOException("无法创建私有工具目录。");
        final long[] accounted = new long[1];
        File archive = downloader.fetch(artifact, version, store.managed(new File(store.root(), ".downloads").getPath()), cancellation,
                new ToolchainDownloader.Listener() { public void update(String stage, long verified, long total) {
                    progress.phase(stage, plan.id);
                    long delta = Math.max(0L, verified - accounted[0]);
                    accounted[0] += delta; progress.advance(delta);
                } });
        File stage = store.managed(new File(store.root(), ".embedded-" + UUID.randomUUID()).getPath());
        if (!stage.mkdir()) throw new IOException("无法创建工具包安装暂存目录。");
        boolean published = false;
        try {
            File payload = new File(stage, "payload");
            if (!payload.mkdir()) throw new IOException("无法解包工具包。");
            progress.phase("unpacking", plan.id);
            InputStream stored = new FileInputStream(archive);
            try {
                stored = new GZIPInputStream(stored);
                VerifiedTarStream tar = new VerifiedTarStream(stored, artifact, progress);
                ToolchainInstaller.extractTar(tar, payload, "", cancellation);
                byte[] remainder = new byte[16384]; while (tar.read(remainder) >= 0) { cancellation.check(); }
                tar.verify();
                progress.emit(true);
            } finally { stored.close(); }
            ToolPaths.writeBytes(new File(payload, ".verified-sha256"), digest.getBytes("UTF-8"), false);
            cancellation.check();
            progress.phase("publishing", plan.id);
            cancellation.check();
            store.managed(destination.getPath());
            if (!payload.renameTo(destination)) throw new IOException("无法发布工具包。");
            published = true; cancellation.check();
            progress.advance(1); progress.emit(true);
            store.managed(archive.getPath());
            if (!archive.delete()) throw new IOException("无法清理已安装工具包的下载缓存。");
            return destination;
        } catch (Exception failure) {
            if (published) ToolchainInstaller.remove(destination, store.root());
            throw failure;
        } finally { ToolchainInstaller.remove(stage, store.root()); }
    }

    private static final class VerifiedTarStream extends FilterInputStream {
        private final MessageDigest sha;
        private final String expectedDigest;
        private final long expectedBytes;
        private final ProgressTracker progress;
        private long count;
        VerifiedTarStream(InputStream stream, JSONObject artifact, ProgressTracker progress) throws Exception {
            super(stream); sha = MessageDigest.getInstance("SHA-256");
            this.progress = progress;
            expectedDigest = artifact.optString("tar_sha256", "");
            expectedBytes = artifact.optLong("tar_bytes", -1);
        }
        @Override public int read() throws IOException {
            int value = in.read(); if (value >= 0) { sha.update((byte) value); counted(1); } return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = in.read(buffer, offset, length); if (read > 0) { sha.update(buffer, offset, read); counted(read); } return read;
        }
        private void counted(int bytes) throws IOException {
            count += bytes;
            if (count > 512L * 1024 * 1024 || expectedBytes >= 0 && count > expectedBytes) throw new IOException("工具包解压大小校验失败。");
            progress.advance(bytes);
        }
        void verify() throws IOException {
            if (expectedDigest.length() > 0 && (count != expectedBytes || !expectedDigest.equals(ToolchainInstaller.hex(sha.digest())))) {
                throw new IOException("工具包解压后的 TAR SHA-256 校验失败。");
            }
        }
    }
}
