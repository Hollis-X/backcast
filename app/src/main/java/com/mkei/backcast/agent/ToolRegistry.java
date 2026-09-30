package com.mkei.backcast.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具注册表：持有全部工具，并产出请求体里的 tools 字段。 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<String, Tool>();

    public void register(Tool tool) {
        if (tool != null) {
            tools.put(tool.name(), tool);
        }
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Tool> all() {
        return new ArrayList<Tool>(tools.values());
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /** 打断正在跑的工具，例如用户点了停止。 */
    public void abort() {
        for (Tool tool : tools.values()) {
            try {
                tool.abort();
            } catch (Exception ignored) {
            }
        }
    }

    public JSONArray toSchema() {
        JSONArray arr = new JSONArray();
        for (Tool t : tools.values()) {
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