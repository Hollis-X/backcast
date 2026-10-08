package com.mkei.backcast.mcp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private App storage only. A schema cache never includes authentication/session headers. */
public final class McpStore {
    private static final Object LOCK = new Object();
    private final File file;

    public McpStore(File directory) { file = new File(directory, "connections.json"); }

    public List<McpServer> servers() {
        synchronized (LOCK) {
            List<McpServer> result = new ArrayList<McpServer>();
            JSONArray entries = read().optJSONArray("servers");
            if (entries != null) for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) throw new IllegalStateException("MCP 配置损坏");
                result.add(decode(entry));
            }
            return Collections.unmodifiableList(result);
        }
    }

    public void save(McpServer server) {
        synchronized (LOCK) {
            JSONObject state = read();
            JSONArray next = new JSONArray();
            boolean replaced = false;
            JSONArray entries = state.optJSONArray("servers");
            try {
                if (entries != null) for (int i = 0; i < entries.length(); i++) {
                    JSONObject existing = entries.getJSONObject(i);
                    if (server.id.equals(existing.getString("id"))) {
                        next.put(encode(server)); replaced = true;
                        if (!sameConnection(decode(existing), server)) {
                            JSONObject cache = state.optJSONObject("tools");
                            if (cache != null) cache.remove(server.id);
                        }
                    } else next.put(existing);
                }
                if (!replaced) next.put(encode(server));
                if (next.length() > 16) throw new IllegalArgumentException("最多添加 16 个 MCP 连接");
                state.put("servers", next); write(state);
            } catch (RuntimeException error) { throw error; }
            catch (Exception error) { throw new IllegalStateException("MCP 配置保存失败"); }
        }
    }

    public void remove(String id) {
        synchronized (LOCK) {
            JSONObject state = read();
            JSONArray next = new JSONArray();
            JSONArray entries = state.optJSONArray("servers");
            try {
                if (entries != null) for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    if (!id.equals(entry.getString("id"))) next.put(entry);
                }
                state.put("servers", next);
                JSONObject cache = state.optJSONObject("tools");
                if (cache != null) cache.remove(id);
                write(state);
            } catch (Exception error) { throw new IllegalStateException("MCP 连接删除失败"); }
        }
    }

    public List<McpToolInfo> cachedTools(String id) {
        synchronized (LOCK) {
            JSONObject cache = read().optJSONObject("tools");
            JSONArray entries = cache == null ? null : cache.optJSONArray(id);
            List<McpToolInfo> result = new ArrayList<McpToolInfo>();
            try {
                if (entries != null) for (int i = 0; i < entries.length(); i++)
                    result.add(new McpToolInfo(entries.getJSONObject(i)));
            } catch (Exception invalid) { throw new IllegalStateException("MCP 工具缓存损坏，请重新探测"); }
            return Collections.unmodifiableList(result);
        }
    }

    /** One locked read keeps chooser connection identity and cached schemas together. */
    List<McpCatalog.Server> catalog() {
        synchronized (LOCK) {
            List<McpCatalog.Server> result = new ArrayList<McpCatalog.Server>();
            for (McpServer server : servers()) if (server.enabled)
                result.add(new McpCatalog.Server(server, cachedTools(server.id)));
            return Collections.unmodifiableList(result);
        }
    }

    public void validateSelection(McpSelection selection) {
        if (selection == null) return;
        synchronized (LOCK) {
            for (McpServer server : servers()) if (server.enabled && server.id.equals(selection.serverId)) {
                for (McpToolInfo tool : cachedTools(server.id)) if (selection.matches(server, tool)) return;
            }
        }
        throw new IllegalStateException("所选 MCP 连接或工具定义已变化，请重新选择");
    }

    public boolean cacheTools(McpServer expected, List<McpToolInfo> tools) {
        return cacheTools(expected, tools, null);
    }

    boolean cacheTools(McpServer expected, List<McpToolInfo> tools, String expectedCacheId) {
        synchronized (LOCK) {
            if (expectedCacheId != null && !expectedCacheId.equals(McpSelection.cacheId(cachedTools(expected.id)))) return false;
            JSONObject state = read();
            try {
                boolean matches = false;
                JSONArray servers = state.optJSONArray("servers");
                if (servers != null) for (int i = 0; i < servers.length(); i++) {
                    McpServer current = decode(servers.getJSONObject(i));
                    if (sameConnection(expected, current) && current.enabled == expected.enabled) matches = true;
                }
                if (!matches) return false;
                JSONObject cache = state.optJSONObject("tools");
                if (cache == null) { cache = new JSONObject(); state.put("tools", cache); }
                JSONArray entries = new JSONArray();
                for (McpToolInfo tool : tools) entries.put(tool.toJson());
                cache.put(expected.id, entries); write(state);
                return true;
            } catch (Exception error) { throw new IllegalStateException("MCP 工具缓存保存失败"); }
        }
    }

    static boolean sameConnection(McpServer a, McpServer b) {
        return a.id.equals(b.id) && a.endpoint.equals(b.endpoint)
                && a.bearerToken.equals(b.bearerToken) && a.timeoutSeconds == b.timeoutSeconds;
    }

    private JSONObject read() {
        if (!file.exists()) return new JSONObject();
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] block = new byte[8192]; int read;
            while ((read = input.read(block)) != -1) {
                if (bytes.size() + read > 4 * 1024 * 1024) throw new IllegalStateException("MCP 配置过大");
                bytes.write(block, 0, read);
            }
            return new JSONObject(bytes.toString("UTF-8"));
        } catch (Exception error) { throw new IllegalStateException("MCP 配置读取失败，请检查私有存储"); }
    }

    private void write(JSONObject state) throws Exception {
        byte[] bytes = state.toString().getBytes("UTF-8");
        if (bytes.length > 4 * 1024 * 1024) throw new IllegalStateException("MCP 配置及工具缓存超过 4MB");
        File directory = file.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("MCP 私有目录创建失败");
        File pending = new File(directory, "connections.pending");
        try {
            try (FileOutputStream output = new FileOutputStream(pending)) {
                output.write(bytes); output.getFD().sync();
            }
            if (!pending.renameTo(file)) throw new IllegalStateException("MCP 配置原子写入失败");
        } finally { if (pending.exists()) pending.delete(); }
    }

    private static JSONObject encode(McpServer server) throws Exception {
        return new JSONObject().put("id", server.id).put("name", server.name)
                .put("endpoint", server.endpoint).put("bearerToken", server.bearerToken)
                .put("enabled", server.enabled).put("timeoutSeconds", server.timeoutSeconds);
    }

    private static McpServer decode(JSONObject entry) {
        try { return new McpServer(entry.getString("id"), entry.getString("name"),
                entry.getString("endpoint"), entry.optString("bearerToken", ""),
                entry.optBoolean("enabled", false), entry.optInt("timeoutSeconds", 60)); }
        catch (Exception error) { throw new IllegalStateException("MCP 连接配置无效"); }
    }
}
