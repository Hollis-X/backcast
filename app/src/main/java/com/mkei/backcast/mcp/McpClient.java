package com.mkei.backcast.mcp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.json.JSONArray;
import org.json.JSONObject;

/** Streamable HTTP only: no POST retries and no replay of a failed tools/call. */
public final class McpClient {
    private static final String VERSION = "2025-11-25";
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private final McpServer server;
    private final OkHttpClient http;
    private final Object cancellation = new Object();
    private long sequence, epoch;
    private volatile Call running;
    private volatile Long requestId;
    private volatile String session = "", protocol = "";
    private volatile boolean initialized, closed;

    public McpClient(McpServer server) {
        this.server = server;
        http = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    /** All pages share one hard deadline, including initialization. */
    public synchronized List<McpToolInfo> discover() throws Exception {
        long operation = operation(), deadline = deadline();
        initialize(operation, deadline);
        List<McpToolInfo> tools = new ArrayList<McpToolInfo>();
        Set<String> cursors = new HashSet<String>(), names = new HashSet<String>();
        String cursor = "";
        for (int page = 0; page < 64; page++) {
            JSONObject params = new JSONObject();
            if (cursor.length() > 0) params.put("cursor", cursor);
            JSONObject result = rpc("tools/list", params, operation, deadline);
            JSONArray items = ((JSONObject) redactValue(result)).getJSONArray("tools");
            for (int i = 0; i < items.length(); i++) {
                McpToolInfo tool = new McpToolInfo(items.getJSONObject(i));
                if (!names.add(tool.name)) throw failure("MCP 工具列表含重名");
                tools.add(tool);
                if (tools.size() > 512) throw failure("MCP 工具数量超过 512");
            }
            cursor = result.optString("nextCursor", "");
            if (cursor.length() == 0) return Collections.unmodifiableList(tools);
            if (!cursors.add(cursor)) throw failure("MCP 分页游标重复");
        }
        throw failure("MCP 工具分页超过上限");
    }

    public synchronized JSONObject call(String name, JSONObject arguments) throws Exception {
        long operation = operation(), deadline = deadline();
        initialize(operation, deadline);
        JSONObject params = new JSONObject().put("name", name)
                .put("arguments", arguments == null ? new JSONObject() : arguments);
        JSONObject result = rpc("tools/call", params, operation, deadline);
        // Keep structuredContent and resource blocks; a tool error is data for the model.
        if (result.optJSONArray("content") == null && result.optJSONObject("structuredContent") == null)
            throw failure("MCP 工具返回缺少 content/structuredContent");
        return (JSONObject) redactValue(result);
    }

    private void initialize(long operation, long deadline) throws Exception {
        if (initialized) return;
        session = ""; protocol = "";
        JSONObject params = new JSONObject().put("protocolVersion", VERSION)
                .put("capabilities", new JSONObject())
                .put("clientInfo", new JSONObject().put("name", "backcast").put("version", "1"));
        JSONObject result = rpc("initialize", params, operation, deadline);
        String agreed = result.getString("protocolVersion");
        if (!VERSION.equals(agreed) && !"2025-06-18".equals(agreed) && !"2025-03-26".equals(agreed)) {
            session = ""; throw failure("MCP 协议版本不支持；需要 Streamable HTTP");
        }
        JSONObject capabilities = result.getJSONObject("capabilities");
        if (capabilities.optJSONObject("tools") == null) {
            session = ""; throw failure("MCP 服务器没有声明 tools 能力");
        }
        protocol = agreed;
        post(new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"),
                null, operation, deadline, true);
        initialized = true;
    }

    private JSONObject rpc(String method, JSONObject params, long operation, long deadline) throws Exception {
        long id = ++sequence;
        JSONObject message = new JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("method", method).put("params", params);
        try { return post(message, Long.valueOf(id), operation, deadline, true); }
        catch (InterruptedIOException timeout) {
            if (current(operation) && !"initialize".equals(method)) notifyCancelled(id);
            throw failure(current(operation) ? "MCP 请求超时，已停止等待" : "MCP 请求已取消");
        } catch (IOException error) {
            if (!current(operation)) throw failure("MCP 请求已取消");
            if (error instanceof Failure) throw error;
            throw failure("MCP 网络或 TLS 请求失败，未自动重试");
        } catch (Exception invalid) {
            throw failure("MCP 响应格式无效");
        }
    }

    private JSONObject post(JSONObject message, Long id, long operation, long deadline, boolean tracked) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new InterruptedIOException("deadline");
        Request request = request().post(RequestBody.create(message.toString(), MediaType.get("application/json; charset=utf-8"))).build();
        Call call = http.newCall(request);
        call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
        if (tracked) synchronized (cancellation) {
            if (!current(operation)) throw failure("MCP 请求已取消");
            running = call; requestId = id;
        }
        try (Response response = call.execute()) {
            int status = response.code();
            if (status == 404 && session.length() > 0) {
                initialized = false; session = ""; protocol = "";
                throw failure("MCP 会话已失效；本次调用未重发，下次调用将重新连接");
            }
            if (status < 200 || status >= 300) throw failure("MCP HTTP " + status + "，未自动重试");
            if (id == null) {
                // Streamable HTTP specifies an empty 202; some servers use an empty 200/204.
                if (status != 200 && status != 202 && status != 204) throw failure("MCP 通知未被正确接受");
                ResponseBody acknowledgment = response.body();
                if (acknowledgment != null && !acknowledgment.source().exhausted())
                    throw failure("MCP 通知响应必须为空，服务器未正确确认通知");
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) throw failure("MCP 返回空响应");
            String type = response.header("Content-Type", "").split(";", 2)[0].trim();
            JSONObject result;
            if ("text/event-stream".equalsIgnoreCase(type)) result = readEvents(body.source(), id, operation, deadline);
            else if ("application/json".equalsIgnoreCase(type)) {
                BufferedSource source = body.source();
                if (source.request(MAX_BODY + 1L) && source.buffer().size() > MAX_BODY)
                    throw failure("MCP 响应超过 8MB");
                result = handle(new JSONObject(source.readUtf8()), id, operation, deadline);
                if (result == null) throw failure("MCP 响应 ID 不匹配");
            } else throw failure("MCP 响应类型不支持；需要 Streamable HTTP JSON/SSE");
            if ("initialize".equals(message.optString("method"))) {
                String value = response.header("MCP-Session-Id", "");
                for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 0x21 || value.charAt(i) > 0x7e)
                    throw failure("MCP session header 无效");
                if (value.length() > 8192) throw failure("MCP session header 过长");
                session = value;
            }
            return result;
        } finally {
            if (tracked) synchronized (cancellation) {
                if (running == call) { running = null; requestId = null; }
            }
        }
    }

    private JSONObject readEvents(BufferedSource source, Long id, long operation, long deadline) throws Exception {
        StringBuilder data = new StringBuilder(); int total = 0;
        while (!source.exhausted()) {
            if (!current(operation)) throw failure("MCP 请求已取消");
            String line = source.readUtf8LineStrict(1024 * 1024L);
            total += line.length();
            if (total > MAX_BODY) throw failure("MCP SSE 响应超过 8MB");
            if (line.length() == 0) {
                if (data.length() > 0) {
                    JSONObject result = handle(new JSONObject(data.toString()), id, operation, deadline);
                    data.setLength(0);
                    if (result != null) return result;
                }
            } else if (line.startsWith("data:")) {
                String piece = line.substring(5); if (piece.startsWith(" ")) piece = piece.substring(1);
                if (data.length() > 0) data.append('\n'); data.append(piece);
            }
        }
        throw failure("MCP SSE 在收到调用结果前断开；本次调用未重发");
    }

    private JSONObject handle(JSONObject message, Long expected, long operation, long deadline) throws Exception {
        if (!"2.0".equals(message.optString("jsonrpc"))) throw failure("MCP JSON-RPC 版本无效");
        if (message.has("method")) {
            if (message.has("id")) {
                JSONObject reply = new JSONObject().put("jsonrpc", "2.0").put("id", message.get("id"));
                if ("ping".equals(message.optString("method"))) reply.put("result", new JSONObject());
                else reply.put("error", new JSONObject().put("code", -32601).put("message", "Client capability not supported"));
                post(reply, null, operation, deadline, false);
            }
            return null; // Notifications are untrusted data, never instructions.
        }
        Object actual = message.opt("id");
        if (!(actual instanceof Number) || ((Number) actual).doubleValue() != expected.longValue()) return null;
        if (message.has("error")) {
            JSONObject error = message.getJSONObject("error");
            throw failure("MCP JSON-RPC 错误 " + error.optInt("code", -32603));
        }
        return message.getJSONObject("result");
    }

    private Request.Builder request() {
        Request.Builder builder = new Request.Builder().url(server.endpoint)
                .header("Accept", "application/json, text/event-stream");
        if (server.bearerToken.length() > 0) builder.header("Authorization", "Bearer " + server.bearerToken);
        if (session.length() > 0) builder.header("MCP-Session-Id", session);
        if (protocol.length() > 0) builder.header("MCP-Protocol-Version", protocol);
        return builder;
    }

    private long operation() throws Failure {
        synchronized (cancellation) {
            if (closed || Thread.currentThread().isInterrupted()) throw failure("MCP 连接已关闭");
            return epoch;
        }
    }
    private long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(server.timeoutSeconds); }
    private boolean current(long operation) {
        synchronized (cancellation) { return !closed && epoch == operation && !Thread.currentThread().isInterrupted(); }
    }

    public void abort() {
        Long id;
        synchronized (cancellation) {
            epoch++; id = requestId;
            if (running != null) running.cancel();
        }
        if (id != null && initialized) notifyCancelled(id.longValue());
    }

    private void notifyCancelled(final long id) {
        final Request.Builder headers = request();
        Thread sender = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject notification = new JSONObject().put("jsonrpc", "2.0")
                            .put("method", "notifications/cancelled")
                            .put("params", new JSONObject().put("requestId", id).put("reason", "Client stopped waiting"));
                    Call call = http.newCall(headers.post(RequestBody.create(notification.toString(), MediaType.get("application/json"))).build());
                    call.timeout().timeout(2, TimeUnit.SECONDS);
                    try (Response ignored = call.execute()) { }
                } catch (Exception ignored) { }
            }
        }, "mcp-cancel");
        sender.setDaemon(true); sender.start();
    }

    /** Cancellation is immediate; best effort DELETE never blocks the UI. */
    public void close() {
        synchronized (cancellation) { if (closed) return; }
        abort(); closed = true;
        final Request deletion = session.length() == 0 ? null : request().delete().build();
        Thread closer = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (deletion != null) {
                        Call call = http.newCall(deletion);
                        call.timeout().timeout(1, TimeUnit.SECONDS);
                        try (Response ignored = call.execute()) { }
                    }
                } catch (Exception ignored) { }
                finally { http.connectionPool().evictAll(); }
            }
        }, "mcp-close");
        closer.setDaemon(true); closer.start();
    }

    private Object redactValue(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject result = new JSONObject();
            java.util.Iterator<String> keys = ((JSONObject) value).keys();
            while (keys.hasNext()) {
                String key = keys.next();
                result.put(redactString(key), redactValue(((JSONObject) value).get(key)));
            }
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray result = new JSONArray();
            for (int i = 0; i < ((JSONArray) value).length(); i++) result.put(redactValue(((JSONArray) value).get(i)));
            return result;
        }
        return value instanceof String ? redactString((String) value) : value;
    }
    private String redactString(String value) {
        String safe = server.bearerToken.length() == 0 ? value : value.replace(server.bearerToken, "[redacted]");
        return session.length() == 0 ? safe : safe.replace(session, "[redacted-session]");
    }
    private static Failure failure(String message) { return new Failure(message); }
    private static final class Failure extends IOException { Failure(String message) { super(message); } }
}
