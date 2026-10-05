package com.mkei.backcast.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具注册表：持有全部工具，并产出请求体里的 tools 字段。 */
public class ToolRegistry {

    /** Dynamic sources expose cached schemas only; UI reads must never connect to a server. */
    public interface Source {
        List<Tool> tools();
        void abort();
    }

    private final Map<String, Tool> tools = new LinkedHashMap<String, Tool>();
    private final List<Source> sources = new ArrayList<Source>();

    public void addSource(Source source) { sources.add(source); }

    public void register(Tool tool) {
        if (tool != null) {
            tools.put(tool.name(), tool);
        }
    }

    public Tool get(String name) {
        Tool local = tools.get(name);
        if (local != null) return local;
        for (Source source : sources) for (Tool tool : source.tools())
            if (name.equals(tool.name())) return tool;
        return null;
    }

    public List<Tool> all() {
        List<Tool> result = new ArrayList<Tool>(tools.values());
        for (Source source : sources) result.addAll(source.tools());
        return result;
    }

    public boolean isEmpty() {
        return all().isEmpty();
    }

    public void beginTurn() {
        for (Tool tool : tools.values()) {
            if (tool instanceof TemporaryCleanup) ((TemporaryCleanup) tool).beginTurn();
        }
    }

    public String cleanupTemporary(boolean finishing) {
        StringBuilder errors = new StringBuilder();
        for (Tool tool : tools.values()) {
            if (!(tool instanceof TemporaryCleanup)) continue;
            String error;
            try {
                error = finishing ? ((TemporaryCleanup) tool).finishTurn()
                        : ((TemporaryCleanup) tool).cleanupTemporary();
            } catch (Exception failure) {
                error = failure.getMessage();
                if (error == null || error.length() == 0) error = failure.getClass().getSimpleName();
            }
            if (error != null) {
                if (errors.length() > 0) errors.append('\n');
                errors.append(error);
            }
        }
        // Remote sessions belong to this turn, including a registry pinned before retarget.
        if (finishing) for (Source source : sources) {
            try { source.abort(); } catch (Exception ignored) { }
        }
        return errors.length() == 0 ? null : errors.toString();
    }

    /** 打断正在跑的工具，例如用户点了停止。 */
    public void abort() {
        for (Tool tool : tools.values()) {
            try {
                tool.abort();
            } catch (Exception ignored) {
            }
        }
        for (Source source : sources) {
            try { source.abort(); } catch (Exception ignored) { }
        }
    }

    public JSONArray toSchema() {
        JSONArray arr = new JSONArray();
        for (Tool t : all()) {
            try {
                JSONObject fn = new JSONObject();
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.put("parameters", t.parameters());

                JSONObject wrapper = new JSONObject();
                wrapper.put("type", "function");
                wrapper.put("function", fn);

                arr.put(wrapper);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }
}
