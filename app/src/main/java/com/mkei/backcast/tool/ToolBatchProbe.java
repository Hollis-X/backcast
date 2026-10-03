package com.mkei.backcast.tool;

import org.json.JSONArray;
import org.json.JSONObject;

/** A UI probe owns one independent toolkit session; failures do not skip other tools. */
public final class ToolBatchProbe {
    public interface Listener { void onProgress(Progress progress); }
    public static final class Progress {
        public final String stage, id, name;
        public final int completed, total, ready, failed;
        private final String result;
        private Progress(String stage, String id, String name, int completed, int total, int ready, int failed, JSONObject result) {
            this.stage = stage; this.id = id; this.name = name; this.completed = completed;
            this.total = total; this.ready = ready; this.failed = failed; this.result = result == null ? "" : result.toString();
        }
        public JSONObject result() throws Exception { return result.length() == 0 ? null : new JSONObject(result); }
    }
    private ToolBatchProbe() { }
    public static JSONObject run(ToolkitTool toolkit, ToolchainInstaller.Cancellation cancellation, Listener listener) throws Exception {
        JSONArray catalog = ToolCatalog.list(), results = new JSONArray();
        int ready = 0, failed = 0;
        for (int i = 0; i < catalog.length(); i++) {
            cancellation.check();
            JSONObject tool = catalog.getJSONObject(i); String id = tool.getString("id"), name = tool.getString("name");
            if (listener != null) listener.onProgress(new Progress("running", id, name, i, catalog.length(), ready, failed, null));
            cancellation.check();
            JSONObject result;
            try { result = toolkit.status(id); }
            catch (InterruptedException interrupted) { throw interrupted; }
            catch (Exception error) {
                cancellation.check();
                result = new JSONObject().put("id", id).put("name", name).put("state", "error").put("ready", false)
                        .put("error", error.getMessage() == null ? error.toString() : error.getMessage());
            }
            cancellation.check();
            result.put("id", id).put("name", name);
            String output = result.optString("probe_output");
            if (output.length() > 12000) result.put("probe_output", output.substring(0, 12000)).put("output_truncated", true);
            if ("ready".equals(result.optString("state")) && result.optBoolean("ready")) ready++; else failed++;
            results.put(result);
            if (listener != null) listener.onProgress(new Progress("finished", id, name, i + 1, catalog.length(), ready, failed, result));
        }
        cancellation.check();
        return new JSONObject().put("state", "batch_complete").put("completed", catalog.length()).put("total", catalog.length())
                .put("ready_count", ready).put("failed_count", failed).put("results", results);
    }
}
