package com.mkei.backcast.tool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline payloads are verified and published inside the app's private files. */
public final class EmbeddedToolchain {
    public interface Assets { InputStream open(String name) throws Exception; }
    private final ToolchainStore store;
    private final Assets assets;
    private final String abi;
    private final int sdk;
    private JSONObject manifest;
    private File prepared;
    private File preparedCommon;

    EmbeddedToolchain(ToolchainStore store, Assets assets, String abi, int sdk) {
        this.store = store; this.assets = assets;
        this.abi = "armeabi".equals(abi) ? "armeabi-v7a" : abi; this.sdk = sdk;
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
        return new JSONObject().put("state", removed ? "removed" : installed ? "installed" : "not_installed")
                .put("installed", installed && !removed).put("storage", store.root().getPath()).put("abi", abi)
                .put("version", version).put("manifest", new JSONObject(data.toString()))
                .put("installed_bytes", installed ? common.optLong("tar_bytes") + nativeTools.optLong("tar_bytes") : 0);
    }

    private boolean verified(File directory, String digest) throws Exception {
        File receipt = store.managed(new File(directory, ".verified-sha256").getPath());
        return receipt.isFile() && digest.equals(new String(ToolPaths.readBytes(receipt, 128, false), "UTF-8"));
    }

    public synchronized File prepare(ToolchainInstaller.Cancellation cancellation) throws Exception {
        cancellation.check();
        if (prepared != null) {
            if (verified(preparedCommon, artifact("any").getString("sha256")) && verified(prepared, artifact(abi).getString("sha256"))) {
                store.bundledInstalled(preparedCommon, prepared, manifest(), abi);
                return prepared;
            }
            resetPrepared();
        }
        if (!supports("apktool")) throw new IllegalArgumentException("内置工具需要 Android 8.0+ 和 ARM/ARM64；当前 ABI=" + abi + "，API=" + sdk);
        JSONObject data = manifest();
        String version = data.getString("version");
        JSONObject commonArtifact = artifact("any"), nativeArtifact = artifact(abi);
        File common = unpack(commonArtifact, "builtin-common-" + version + "-" + commonArtifact.getString("sha256").substring(0, 16), cancellation);
        File nativeTools = unpack(nativeArtifact, "builtin-" + abi + "-" + version + "-" + nativeArtifact.getString("sha256").substring(0, 16), cancellation);
        cancellation.check();
        store.bundledInstalled(common, nativeTools, data, abi);
        preparedCommon = common;
        prepared = nativeTools;
        return prepared;
    }

    private JSONObject manifest() throws Exception {
        if (manifest != null) return manifest;
        InputStream stream = assets.open("toolchain/manifest.json");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) >= 0) {
                bytes.write(buffer, 0, count);
                if (bytes.size() > 512 * 1024) throw new IOException("内置工具清单过大。");
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
        throw new IOException("APK 中缺少当前 ABI 的内置工具。");
    }

    private File unpack(JSONObject artifact, String name, ToolchainInstaller.Cancellation cancellation) throws Exception {
        File destination = store.managed(new File(store.root(), name).getPath());
        File receipt = store.managed(new File(destination, ".verified-sha256").getPath());
        String digest = artifact.getString("sha256");
        if (receipt.isFile() && digest.equals(new String(ToolPaths.readBytes(receipt, 128, false), "UTF-8"))) return destination;
        if (destination.exists()) throw new IOException("内置工具目录校验记录不一致。");
        if (!store.root().isDirectory() && !store.root().mkdirs()) throw new IOException("无法创建私有工具目录。");
        File stage = store.managed(new File(store.root(), ".embedded-" + UUID.randomUUID()).getPath());
        if (!stage.mkdir()) throw new IOException("无法创建内置工具暂存目录。");
        boolean published = false;
        try {
            File archive = new File(stage, "payload.archive"), payload = new File(stage, "payload");
            PushbackInputStream input = new PushbackInputStream(openArtifact(artifact), 2);
            FileOutputStream output = new FileOutputStream(archive);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long total = 0;
            boolean gzip;
            try {
                int first = input.read(), second = input.read();
                if (second >= 0) input.unread(second);
                if (first >= 0) input.unread(first);
                gzip = first == 0x1f && second == 0x8b;
                String expectedDigest = gzip ? digest : artifact.optString("tar_sha256", "");
                long expectedBytes = gzip ? artifact.getLong("bytes") : artifact.optLong("tar_bytes", -1);
                if (expectedDigest.length() != 64 || expectedBytes < 0) throw new IOException("内置工具缺少实际归档格式的校验记录。");
                byte[] buffer = new byte[16384]; int read;
                while ((read = input.read(buffer)) >= 0) {
                    cancellation.check(); total += read;
                    if (total > expectedBytes) throw new IOException("内置工具大小校验失败。");
                    sha.update(buffer, 0, read); output.write(buffer, 0, read);
                }
                output.getFD().sync();
                if (total != expectedBytes || !expectedDigest.equals(ToolchainInstaller.hex(sha.digest()))) throw new IOException("内置工具 SHA-256 校验失败。");
            } finally { try { input.close(); } finally { output.close(); } }
            if (!payload.mkdir()) throw new IOException("无法解包内置工具。");
            InputStream stored = new FileInputStream(archive);
            if (gzip) {
                try { stored = new GZIPInputStream(stored); }
                catch (IOException invalid) { stored.close(); throw invalid; }
            }
            VerifiedTarStream tar = new VerifiedTarStream(stored, artifact);
            try {
                ToolchainInstaller.extractTar(tar, payload, "", cancellation);
                byte[] remainder = new byte[16384]; while (tar.read(remainder) >= 0) { cancellation.check(); }
                tar.verify();
            } finally { tar.close(); }
            ToolPaths.writeBytes(new File(payload, ".verified-sha256"), digest.getBytes("UTF-8"), false);
            cancellation.check();
            store.managed(destination.getPath());
            if (!payload.renameTo(destination)) throw new IOException("无法发布内置工具。");
            published = true; cancellation.check();
            return destination;
        } catch (Exception failure) {
            if (published) ToolchainInstaller.remove(destination, store.root());
            throw failure;
        } finally { ToolchainInstaller.remove(stage, store.root()); }
    }

    private InputStream openArtifact(JSONObject artifact) throws Exception {
        String primary = artifact.getString("asset");
        try { return assets.open(primary); }
        catch (IOException unavailable) {
            String alternate = artifact.optString("tar_asset", "");
            if (alternate.length() == 0 || artifact.optString("tar_sha256", "").length() != 64
                    || artifact.optLong("tar_bytes", -1) < 0) throw unavailable;
            try { return assets.open(alternate); }
            catch (IOException missing) {
                throw new IOException("APK 中缺少内置工具资源：" + primary + " 或 " + alternate, missing);
            }
        }
    }

    private static final class VerifiedTarStream extends FilterInputStream {
        private final MessageDigest sha;
        private final String expectedDigest;
        private final long expectedBytes;
        private long count;
        VerifiedTarStream(InputStream stream, JSONObject artifact) throws Exception {
            super(stream); sha = MessageDigest.getInstance("SHA-256");
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
            if (count > 512L * 1024 * 1024 || expectedBytes >= 0 && count > expectedBytes) throw new IOException("内置工具解压大小校验失败。");
        }
        void verify() throws IOException {
            if (expectedDigest.length() > 0 && (count != expectedBytes || !expectedDigest.equals(ToolchainInstaller.hex(sha.digest())))) {
                throw new IOException("内置工具解压后的 TAR SHA-256 校验失败。");
            }
        }
    }
}
