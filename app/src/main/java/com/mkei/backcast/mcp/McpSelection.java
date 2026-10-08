package com.mkei.backcast.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local human selection, bound to one configured connection and one cached schema. */
public final class McpSelection {
    public final String serverId, serverName, toolName, mappedName, description;
    private final String connectionId, schemaId, schema;

    McpSelection(McpServer server, McpToolInfo tool) {
        serverId = server.id; serverName = server.name; toolName = tool.name;
        mappedName = mappedName(server.id, tool.name); description = tool.description;
        schema = tool.inputSchema().toString();
        connectionId = connectionId(server); schemaId = schemaId(tool);
    }

    private McpSelection(JSONObject value) throws Exception {
        serverId = value.getString("server_id"); serverName = value.getString("server_name");
        toolName = value.getString("tool_name"); mappedName = value.getString("mapped_name");
        description = value.getString("description"); schema = value.getJSONObject("inputSchema").toString();
        connectionId = value.getString("connection_id"); schemaId = value.getString("schema_id");
        if (!serverId.matches("[a-z0-9_-]{1,40}") || !toolName.matches("[A-Za-z0-9_.-]{1,128}")
                || !mappedName.equals(mappedName(serverId, toolName))
                || !connectionId.matches("[0-9a-f]{64}") || !schemaId.matches("[0-9a-f]{64}")
                || serverName.length() > 80 || description.length() > 16384 || schema.length() > 65536)
            throw new IllegalArgumentException("MCP 工具选择记录无效");
        McpToolInfo decoded = new McpToolInfo(new JSONObject().put("name", toolName)
                .put("description", description).put("inputSchema", inputSchema()));
        if (!schemaId.equals(schemaId(decoded))) throw new IllegalArgumentException("MCP 工具选择 schema 身份不一致");
    }

    public JSONObject inputSchema() {
        try { return new JSONObject(schema); }
        catch (Exception invalid) { throw new IllegalStateException("MCP 选择 schema 无效", invalid); }
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("server_id", serverId).put("server_name", serverName)
                    .put("tool_name", toolName).put("mapped_name", mappedName).put("description", description)
                    .put("inputSchema", inputSchema()).put("connection_id", connectionId).put("schema_id", schemaId);
        } catch (Exception invalid) { throw new IllegalStateException("MCP 工具选择无法保存", invalid); }
    }

    public static McpSelection fromJson(JSONObject value) throws Exception { return new McpSelection(value); }

    boolean matches(McpServer server, McpToolInfo tool) {
        return server.enabled && serverId.equals(server.id) && toolName.equals(tool.name)
                && connectionId.equals(connectionId(server)) && schemaId.equals(schemaId(tool));
    }

    static String mappedName(String serverId, String toolName) {
        return "mcp_" + serverId + "_" + digest(serverId + ":" + toolName).substring(0, 12);
    }

    private static String connectionId(McpServer server) {
        try {
            return digest(canonical(new JSONArray().put(server.id).put(server.endpoint)
                    .put(server.bearerToken).put(server.timeoutSeconds)));
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }

    private static String schemaId(McpToolInfo tool) {
        try { return digest(canonical(tool.toJson())); }
        catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }

    static String cacheId(List<McpToolInfo> tools) {
        StringBuilder value = new StringBuilder();
        for (McpToolInfo tool : tools) value.append(schemaId(tool)).append('\n');
        return digest(value.toString());
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : bytes) hex.append(String.format(java.util.Locale.US, "%02x", item & 255));
            return hex.toString();
        } catch (Exception invalid) { throw new IllegalStateException("MCP 身份编码失败", invalid); }
    }

    /** Object key order is not schema identity. Array order remains meaningful. */
    private static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; List<String> keys = new ArrayList<String>();
            Iterator<String> iterator = object.keys(); while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys); StringBuilder result = new StringBuilder("{");
            for (String key : keys) result.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) result.append(canonical(array.get(i))).append(',');
            return result.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }
}
