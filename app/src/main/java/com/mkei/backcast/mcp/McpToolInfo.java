package com.mkei.backcast.mcp;

import org.json.JSONObject;

/** Detached schema snapshot. Server annotations cannot grant local permissions. */
public final class McpToolInfo {
    public final String name, description;
    private final String schema;

    public McpToolInfo(JSONObject tool) throws Exception {
        name = tool.getString("name");
        if (name.length() == 0 || name.length() > 128 || !name.matches("[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("MCP 工具名无效");
        description = tool.optString("description", "");
        if (description.length() > 16384) throw new IllegalArgumentException("MCP 工具说明过长");
        JSONObject input = tool.getJSONObject("inputSchema");
        if (!"object".equals(input.optString("type", "object")))
            throw new IllegalArgumentException("MCP 工具参数须为 object schema");
        JSONObject execution = tool.optJSONObject("execution");
        if (execution != null && "required".equals(execution.optString("taskSupport")))
            throw new IllegalArgumentException("该 MCP 工具要求任务协议，当前不支持");
        schema = input.toString();
        if (schema.length() > 65536) throw new IllegalArgumentException("MCP 工具 schema 过长");
    }

    public JSONObject inputSchema() {
        try { return new JSONObject(schema); }
        catch (Exception invalid) { throw new IllegalStateException("MCP schema 快照损坏"); }
    }

    JSONObject toJson() throws Exception {
        return new JSONObject().put("name", name).put("description", description)
                .put("inputSchema", inputSchema());
    }
}
