package com.mkei.backcast.tool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Creates persisted legacy registry data without retaining removed production setters. */
final class ToolchainFixtures {
    static final ToolchainInstaller.Cancellation LIVE = new ToolchainInstaller.Cancellation() {
        public void check() throws InterruptedException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Test tool preparation cancelled");
        }
    };
    private ToolchainFixtures() { }

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
