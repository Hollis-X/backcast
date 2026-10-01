package com.mkei.backcast.tool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    public synchronized File prepare(ToolchainInstaller.Cancellation cancellation) throws Exception {
        cancellation.check();
        if (prepared != null) {
            store.bundledInstalled(preparedCommon, prepared, manifest(), abi);
            return prepared;
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
            File archive = new File(stage, "payload.tar.gz"), payload = new File(stage, "payload");
            InputStream input = assets.open(artifact.getString("asset"));
            FileOutputStream output = new FileOutputStream(archive);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try {
                byte[] buffer = new byte[16384]; int read;
                while ((read = input.read(buffer)) >= 0) {
                    cancellation.check(); total += read;
                    if (total > artifact.getLong("bytes")) throw new IOException("内置工具大小校验失败。");
                    sha.update(buffer, 0, read); output.write(buffer, 0, read);
                }
                output.getFD().sync();
            } finally { try { input.close(); } finally { output.close(); } }
            if (total != artifact.getLong("bytes") || !digest.equals(ToolchainInstaller.hex(sha.digest()))) throw new IOException("内置工具 SHA-256 校验失败。");
            if (!payload.mkdir()) throw new IOException("无法解包内置工具。");
            input = new GZIPInputStream(new FileInputStream(archive));
            try { ToolchainInstaller.extractTar(input, payload, "", cancellation); }
            finally { input.close(); }
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
}
