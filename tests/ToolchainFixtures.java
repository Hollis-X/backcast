package com.mkei.backcast.tool;

import java.io.File;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Creates persisted legacy registry data without retaining removed production setters. */
final class ToolchainFixtures {
    static final ToolchainInstaller.Cancellation LIVE = new ToolchainInstaller.Cancellation() {
        public void check() throws InterruptedException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Test tool preparation cancelled");
        }
    };
    private ToolchainFixtures() { }

    static JSONObject pin(JSONObject artifact, String version, byte[] source) throws Exception {
        String file = artifact.optString("file", "");
        if (file.isEmpty()) file = new File(artifact.getString("asset")).getName();
        artifact.put("file", file).put("url", "https://github.com/Hollis-X/backcast/releases/download/toolchain-" + version + "/" + file);
        int prefix = Math.min(65536, source.length);
        artifact.put("prefix_bytes", prefix).put("prefix_sha256", sha(java.util.Arrays.copyOf(source, prefix))).put("chunk_bytes", 4 * 1024 * 1024);
        JSONArray chunks = new JSONArray();
        for (int from = 0; from < source.length; from += 4 * 1024 * 1024) chunks.put(sha(java.util.Arrays.copyOfRange(source, from, Math.min(source.length, from + 4 * 1024 * 1024))));
        return artifact.put("chunk_sha256", chunks);
    }

    static String sha(byte[] bytes) throws Exception { return ToolchainInstaller.hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    /** The production downloader runs against a range-capable injected server, not an APK fallback. */
    static ToolchainDownloader.Transport transport(final EmbeddedToolchain.Assets files, final JSONObject manifest) {
        return new ToolchainDownloader.Transport() { public ToolchainDownloader.Exchange open(String url, final long first, final long last, long timeout, boolean probe) throws Exception {
            final String filename = new File(new URI(url).getPath()).getName();
            JSONArray items = manifest.getJSONArray("artifacts"); long total = -1;
            for (int i = 0; i < items.length(); i++) if (filename.equals(items.getJSONObject(i).getString("file"))) { total = items.getJSONObject(i).getLong("bytes"); break; }
            if (total < 0) throw new IOException("Unknown fixture release file");
            final long expected = total;
            return new ToolchainDownloader.Exchange() {
                volatile boolean cancelled;
                InputStream active;
                public ToolchainDownloader.Reply execute() throws Exception {
                    if (cancelled) throw new IOException("Fixture cancelled");
                    InputStream source = files.open("toolchain/" + filename); active = source;
                    long skip = first;
                    while (skip > 0) { long moved = source.skip(skip); if (moved <= 0) { if (source.read() < 0) break; moved = 1; } skip -= moved; }
                    InputStream bounded = new FilterInputStream(source) {
                        long left = last - first + 1;
                        public int read() throws IOException { if (left <= 0) return -1; int value = in.read(); if (value >= 0) left--; return value; }
                        public int read(byte[] buffer, int offset, int length) throws IOException {
                            if (left <= 0) return -1; int read = in.read(buffer, offset, (int) Math.min((long) length, left)); if (read > 0) left -= read; return read;
                        }
                    };
                    return new ToolchainDownloader.Reply(206, last - first + 1, "bytes " + first + "-" + last + "/" + expected, "", "application/octet-stream", bounded, null);
                }
                public void cancel() { cancelled = true; try { if (active != null) active.close(); } catch (IOException ignored) { } }
                public void close() { cancel(); }
            };
        } };
    }

    static void configure(ToolchainStore store, String id, String path, String runtime) throws Exception {
        JSONObject value = new JSONObject().put("path", path).put("origin", "configured");
        if (runtime != null && runtime.length() > 0) value.put("runtime", runtime);
        write(store, id, value);
    }

    static void clear(ToolchainStore store, String id) throws Exception { write(store, id, null); }

    private static void write(ToolchainStore store, String id, JSONObject value) throws Exception {
        File registry = new File(store.root(), "registry.json");
        JSONObject data = registry.isFile()
                ? new JSONObject(new String(Files.readAllBytes(registry.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
        JSONObject tools = data.optJSONObject("tools");
        if (tools == null) { tools = new JSONObject(); data.put("tools", tools); }
        if (value == null) tools.remove(id); else tools.put(id, value);
        Files.createDirectories(store.root().toPath());
        Files.write(registry.toPath(), data.toString().getBytes(StandardCharsets.UTF_8));
    }
}
