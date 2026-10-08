package com.mkei.backcast.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Local chooser snapshots and an independently cancellable, explicit refresh. */
public final class McpCatalog {
    private McpCatalog() { }

    public static final class Server {
        public final String id, name;
        public final List<McpSelection> tools;
        Server(McpServer server, List<McpToolInfo> cached) {
            id = server.id; name = server.name;
            List<McpSelection> result = new ArrayList<McpSelection>();
            for (McpToolInfo tool : cached) result.add(new McpSelection(server, tool));
            tools = Collections.unmodifiableList(result);
        }
    }

    public static List<Server> cached(McpStore store) { return store.catalog(); }

    public static final class Refresh implements AutoCloseable {
        private final McpStore store;
        private final McpServer server;
        private final String cacheId;
        private McpClient client;
        private boolean closed, started;

        public Refresh(McpStore store, String serverId) {
            this.store = store;
            McpServer found = null;
            for (McpServer current : store.servers()) if (current.enabled && current.id.equals(serverId)) found = current;
            if (found == null) throw new IllegalStateException("MCP 连接已禁用或删除");
            server = found; cacheId = McpSelection.cacheId(store.cachedTools(serverId));
        }

        public void run() throws Exception {
            McpClient current;
            synchronized (this) {
                if (closed || started) throw new IllegalStateException("MCP 刷新已取消或已运行");
                started = true; current = new McpClient(server); client = current;
            }
            try {
                List<McpToolInfo> found = current.discover();
                synchronized (this) {
                    if (closed) throw new IllegalStateException("MCP 刷新已取消");
                    if (!store.cacheTools(server, found, cacheId))
                        throw new IllegalStateException("MCP 连接或缓存已变化，请重新刷新");
                }
            } finally { close(); }
        }

        @Override public void close() {
            McpClient current;
            synchronized (this) { closed = true; current = client; client = null; }
            if (current != null) current.close();
        }
    }
}
