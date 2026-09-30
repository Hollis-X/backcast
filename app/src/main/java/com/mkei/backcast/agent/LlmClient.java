package com.mkei.backcast.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容的 Chat Completions 客户端。
 *
 * 不依赖任何第三方 HTTP 库：HttpURLConnection + 内置 org.json。
 * 只要服务端兼容 /chat/completions，就能接。
 */
public class LlmClient {
    /** 单次 read 的切片。空闲是否结束看有没有思考、正文或工具，不看保活行。 */
    private static final int READ_SLICE_MS = 10000;

    public static class Config {
        public String baseUrl;
        public String apiKey;
        public String model;
        public int timeoutMs = 120000;
        public int maxTokens;
        public int totalTimeoutMs;
        public int maxResponseChars;

        /**
         * 思考强度，透传为请求体里的 reasoning_effort。
         *
         * 空值或 "off" 表示不带该字段 —— 不支持思考参数的接口必须保持空，
         * 否则会被服务端以参数非法拒绝。
         */
        public String reasoningEffort;

        public Config(String baseUrl, String apiKey, String model) {
            this.baseUrl = normalize(baseUrl);
            this.apiKey = apiKey;
            this.model = model;
        }

        public Config(String baseUrl, String apiKey, String model, String reasoningEffort) {
            this(baseUrl, apiKey, model);
            this.reasoningEffort = reasoningEffort;
        }

        /** 是否需要发送 reasoning_effort。 */
        boolean reasoningWanted() {
            return reasoningEffort != null
                    && reasoningEffort.length() > 0
                    && !"off".equalsIgnoreCase(reasoningEffort);
        }

        /** 取接口根地址，去掉已知的端点后缀。 */
        static String root(String base) {
            String b = base == null ? "" : base.trim();
            while (b.endsWith("/")) {
                b = b.substring(0, b.length() - 1);
            }
            if (b.endsWith("/chat/completions")) {
                b = b.substring(0, b.length() - "/chat/completions".length());
            } else if (b.endsWith("/models")) {
                b = b.substring(0, b.length() - "/models".length());
            }
            return b;
        }

        /** 允许只填域名，自动补成 chat/completions。 */
        static String normalize(String base) {
            String b = root(base);
            if (b.endsWith("/v1")) {
                return b + "/chat/completions";
            }
            return b + "/v1/chat/completions";
        }

        /** 模型列表端点。 */
        public String modelsUrl() {
            String b = root(baseUrl);
            if (b.endsWith("/v1")) {
                return b + "/models";
            }
            return b + "/v1/models";
        }
    }

    /** 一次模型响应的解析结果。 */
    public static class Reply {
        public String content = "";
        public JSONArray toolCalls;
        public JSONArray displayParts;
        private JSONObject lastDisplayPart;
        /** 思考模型的推理内容，回放历史时必须原样带回。 */
        public String reasoning;
        public String raw;
        public String error;
        /** 这一次请求服务端报的用量。0 表示对方没给。 */
        public long promptTokens;
        public long completionTokens;
        private StringBuilder contentBuffer;
        private StringBuilder reasoningBuffer;

        /**
         * 记下服务端报的用量。
         *
         * 流式响应里 usage 可能分几次给，取较大的那份，避免后半段覆盖前半段。
         */
        public void applyUsage(JSONObject usage) {
            if (usage == null) {
                return;
            }
            promptTokens = Math.max(promptTokens, usage.optLong("prompt_tokens", 0));
            completionTokens = Math.max(completionTokens, usage.optLong("completion_tokens", 0));
        }

        private JSONObject recordText(String kind, int from, int to) {
            try {
                if (displayParts == null) displayParts = new JSONArray();
                if (lastDisplayPart != null && kind.equals(lastDisplayPart.optString("type"))
                        && lastDisplayPart.optInt("to", -1) == from) {
                    lastDisplayPart.put("to", to);
                    return lastDisplayPart;
                }
                JSONObject part = new JSONObject();
                part.put("type", kind); part.put("from", from); part.put("to", to);
                displayParts.put(part);
                lastDisplayPart = part;
                return part;
            } catch (Exception invalid) { throw new IllegalStateException(invalid); }
        }

        private JSONObject recordTool(int index) {
            try {
                if (displayParts == null) displayParts = new JSONArray();
                JSONObject part = new JSONObject();
                part.put("type", "tool"); part.put("index", index);
                displayParts.put(part);
                lastDisplayPart = part;
                return part;
            } catch (Exception invalid) { throw new IllegalStateException(invalid); }
        }

        private void finishText() {
            if (contentBuffer != null) content = contentBuffer.toString();
            if (reasoningBuffer != null) reasoning = reasoningBuffer.toString();
        }

        public boolean hasToolCalls() {
            return toolCalls != null && toolCalls.length() > 0;
        }
    }

    /**
     * 流式增量。在读响应的线程上回调，调用方自己切到界面线程。
     * 工具参数是拼到目前为止的全文，不是这一小段。
     */
    public interface Sink {
        void onReasoning(String delta);

        void onContent(String delta);

        void onToolCall(int index, String id, String name, String arguments);
    }

    private final Config config;
    private volatile HttpURLConnection active;
    private volatile boolean usageOptionUnsupported;

    /** 这一次请求。停止时把它标死并断开，不碰到下一次请求。 */
    private static class Attempt {
        volatile HttpURLConnection conn;
        volatile boolean dead;
        long deadline;
        int maxChars;
    }

    private volatile Attempt attempt;

    public LlmClient(Config config) {
        this.config = config;
    }

    /** 断开正在进行的请求。用户点停止时调用。 */
    public void abort() {
        Attempt current = attempt;
        if (current == null) {
            return;
        }
        current.dead = true;
        HttpURLConnection conn = current.conn;
        if (conn != null) {
            conn.disconnect();
        }
    }

    public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
        Attempt mine = new Attempt();
        mine.deadline = config.totalTimeoutMs > 0 ? System.currentTimeMillis() + config.totalTimeoutMs : 0;
        mine.maxChars = config.maxResponseChars;
        attempt = mine;
        Reply reply = sendAttempt(messages, tools, sink, mine, !usageOptionUnsupported);
        if (!mine.dead && rejectsUsageOption(reply.error)) {
            usageOptionUnsupported = true;
            reply = sendAttempt(messages, tools, sink, mine, false);
        }
        return reply;
    }

    private static boolean rejectsUsageOption(String error) {
        if (error == null || !error.startsWith("HTTP 400:")) return false;
        String lower = error.toLowerCase(java.util.Locale.US);
        return (lower.contains("stream_options") || lower.contains("include_usage"))
                && (lower.contains("unsupported") || lower.contains("unknown")
                || lower.contains("unrecognized") || lower.contains("not supported")
                || lower.contains("not permitted") || lower.contains("unexpected"));
    }

    private Reply sendAttempt(List<Message> messages, JSONArray tools, Sink sink,
            Attempt mine, boolean includeUsage) {
        Reply reply = new Reply();
        if (mine.dead) {
            return reply;
        }
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", config.model);
            body.put("stream", true);
            if (includeUsage) body.put("stream_options", new JSONObject().put("include_usage", true));
            if (config.maxTokens > 0) body.put("max_tokens", config.maxTokens);

            if (config.reasoningWanted()) {
                body.put("reasoning_effort", config.reasoningEffort);
            }

            JSONArray msgs = new JSONArray();
            for (Message m : messages) {
                msgs.put(m.toJson());
            }
            body.put("messages", msgs);

            if (tools != null && tools.length() > 0) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }

            conn = (HttpURLConnection) new URL(config.baseUrl).openConnection();
            mine.conn = conn;
            active = conn;
            if (mine.dead) {
                conn.disconnect();
                return reply;
            }
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            // 切片读，空闲预算另算。保活行不能把「一直没输出」续成无限等待。
            conn.setReadTimeout(READ_SLICE_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            conn.setRequestProperty("Accept", "text/event-stream");
            // 关掉压缩，否则整包缓冲完才吐，流式等于没开。
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Connection", "close");

            byte[] payload = body.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String text = readAll(conn.getErrorStream());
                reply.raw = text;
                reply.error = "HTTP " + code + ": " + trim(text, 500);
                return reply;
            }
            if (mine.dead) {
                return reply;
            }
            readStream(conn.getInputStream(), reply, mine, sink, config.timeoutMs);
        } catch (Exception e) {
            if (!mine.dead) {
                reply.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        } finally {
            if (active == conn) {
                active = null;
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
        if (mine.dead) {
            reply.error = null;
            reply.content = "";
            reply.toolCalls = null;
            reply.reasoning = null;
        }
        return reply;
    }

    /** 一路工具调用的拼装。流式里名字和参数是分段到的。 */
    private static class CallAcc {
        String id = "";
        String name = "";
        StringBuilder args = new StringBuilder();
        JSONObject displayPart;
    }

private static void readStream(InputStream in, Reply reply, Attempt mine, Sink sink, long idleMs)
            throws Exception {
        if (in == null) {
            reply.error = "响应为空。";
            return;
        }
        if (idleMs < 1000L) {
            idleMs = 1000L;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder raw = new StringBuilder();
        List<CallAcc> calls = new ArrayList<CallAcc>();
        long[] clock = new long[] { System.currentTimeMillis() + idleMs, idleMs };
        try {
            Pulled first = pullLine(reader, raw, mine, clock);
            if (first.idle) {
                reply.raw = raw.toString();
                noteIdle(reply, calls);
                return;
            }
            if (first.text == null || mine.dead) {
                reply.raw = raw.toString();
                return;
            }
            if (first.text.charAt(0) == '{') {
                StringBuilder json = new StringBuilder(first.text);
                while (!mine.dead) {
                    Pulled rest = pullLine(reader, raw, mine, clock);
                    if (rest.idle) {
                        reply.raw = raw.toString();
                        noteIdle(reply, null);
                        return;
                    }
                    if (rest.text == null) {
                        break;
                    }
                    json.append('\n').append(rest.text);
                }
                reply.raw = raw.toString();
                if (!mine.dead && reply.error == null) {
                    parseInto(reply, json.toString());
                    emitFull(reply, sink);
                }
                return;
            }
            consumeSse(first.text, reply, calls, sink);
            while (reply.error == null && !mine.dead) {
                Pulled next = pullLine(reader, raw, mine, clock);
                if (next.idle) {
                    reply.raw = raw.toString();
                    noteIdle(reply, calls);
                    return;
                }
                if (next.text == null) {
                    break;
                }
                if (!consumeSse(next.text, reply, calls, sink)) {
                    break;
                }
            }
            reply.raw = raw.toString();
            if (reply.error == null && !mine.dead) {
                reply.toolCalls = callsToJson(calls);
            }
        } finally {
            reply.finishText();
            reader.close();
        }
    }

    /** 读到的一行。保活和空行不算，不会把空闲时钟续上。 */
    private static final class Pulled {
        String text;
        boolean idle;
    }

    /**
     * 读下一行有内容的载荷。
     *
     * 注释行（: ping）和空行是保活，不重置空闲预算。预算耗尽返回 idle，
     * 调用方结束这一轮，而不是一直占着停止按钮。
     */
    private static Pulled pullLine(BufferedReader reader, StringBuilder raw, Attempt mine,
            long[] clock) throws Exception {
        Pulled out = new Pulled();
        while (!mine.dead) {
            if (mine.deadline > 0 && System.currentTimeMillis() >= mine.deadline) {
                throw new java.net.SocketTimeoutException("Request deadline exceeded");
            }
            if (mine.maxChars > 0 && raw.length() >= mine.maxChars) {
                throw new java.io.IOException("Response size limit exceeded");
            }
            if (System.currentTimeMillis() >= clock[0]) {
                out.idle = true;
                return out;
            }
            String line;
            try {
                line = reader.readLine();
            } catch (java.net.SocketTimeoutException timed) {
                continue;
            }
            if (line == null) {
                return out;
            }
            raw.append(line).append('\n');
            String trimmed = line.trim();
            if (trimmed.length() == 0 || trimmed.charAt(0) == ':' || "data:".equals(trimmed)) {
                continue;
            }
            clock[0] = System.currentTimeMillis() + clock[1];
            out.text = trimmed;
            return out;
        }
        return out;
    }

    /** 长时间没有思考、正文或工具。有正文就留下并结束；半截工具不执行。 */
    private static void noteIdle(Reply reply, List<CallAcc> calls) {
        reply.finishText();
        boolean hasText = reply.content != null && reply.content.trim().length() > 0;
        boolean hasCalls = calls != null && !calls.isEmpty();
        reply.toolCalls = null;
        if (hasCalls || !hasText) {
            reply.error = "模型长时间没有输出，已结束。";
        }
    }

    /** @return false 表示流结束。 */
    private static boolean consumeSse(String line, Reply reply, List<CallAcc> calls, Sink sink)
            throws Exception {
        if (!line.startsWith("data:")) {
            return true;
        }
        String data = line.substring(5).trim();
        if ("[DONE]".equals(data)) {
            return false;
        }
        if (data.length() == 0) {
            return true;
        }
        absorbEvent(data, reply, calls, sink);
        return reply.error == null;
    }

    private static void absorbEvent(String data, Reply reply, List<CallAcc> calls, Sink sink)
            throws Exception {
        JSONObject root = new JSONObject(data);
        if (root.has("error") && !root.isNull("error")) {
            JSONObject err = root.optJSONObject("error");
            reply.error = err != null ? err.optString("message", data) : root.optString("error", data);
            return;
        }
        // 用量可能挂在顶层，也可能跟着 usage 字段分片下发。
        reply.applyUsage(root.optJSONObject("usage"));
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return;
        }
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) {
            return;
        }
        reply.applyUsage(choice.optJSONObject("usage"));
        JSONObject delta = choice.optJSONObject("delta");
        if (delta != null) {
            absorbDelta(delta, reply, calls, sink);
            return;
        }
        JSONObject msg = choice.optJSONObject("message");
        if (msg != null) {
            absorbDelta(msg, reply, calls, sink);
        }
    }

    private static void absorbDelta(JSONObject delta, Reply reply, List<CallAcc> calls, Sink sink) {
        String reasoning = textField(delta, "reasoning_content");
        if (reasoning == null) {
            reasoning = textField(delta, "reasoning");
        }
        if (reasoning != null && reasoning.length() > 0) {
            if (reply.reasoningBuffer == null) reply.reasoningBuffer = new StringBuilder();
            int from = reply.reasoningBuffer.length();
            reply.reasoningBuffer.append(reasoning);
            reply.recordText("think", from, reply.reasoningBuffer.length());
            if (sink != null) {
                sink.onReasoning(reasoning);
            }
        }
        String content = textField(delta, "content");
        if (content != null && content.length() > 0) {
            if (reply.contentBuffer == null) reply.contentBuffer = new StringBuilder();
            int from = reply.contentBuffer.length();
            reply.contentBuffer.append(content);
            reply.recordText("body", from, reply.contentBuffer.length());
            if (sink != null) {
                sink.onContent(content);
            }
        }
        JSONArray tcs = delta.optJSONArray("tool_calls");
        if (tcs == null) {
            return;
        }
        for (int i = 0; i < tcs.length(); i++) {
            JSONObject tc = tcs.optJSONObject(i);
            if (tc == null) {
                continue;
            }
            int index = tc.has("index") ? tc.optInt("index", calls.size()) : calls.size();
            if (index < 0) {
                index = calls.size();
            }
            while (calls.size() <= index) {
                calls.add(new CallAcc());
            }
            CallAcc acc = calls.get(index);
            String id = tc.optString("id", "");
            if (id.length() > 0) {
                acc.id = id;
            }
            JSONObject fn = tc.optJSONObject("function");
            if (fn != null) {
                String name = fn.optString("name", "");
                if (name.length() > 0) {
                    if (acc.name.length() == 0 || name.startsWith(acc.name)) {
                        acc.name = name;
                    } else if (!acc.name.endsWith(name)) {
                        acc.name = acc.name + name;
                    }
                }
                if (fn.has("arguments") && !fn.isNull("arguments")) {
                    acc.args.append(fn.optString("arguments", ""));
                }
            }
            if (acc.displayPart == null && (acc.name.length() > 0 || acc.args.length() > 0))
                acc.displayPart = reply.recordTool(index);
            if (sink != null && (acc.name.length() > 0 || acc.args.length() > 0)) {
                sink.onToolCall(index, acc.id, acc.name, acc.args.toString());
            }
        }
    }

    private static void emitFull(Reply reply, Sink sink) {
        if (sink == null || reply.error != null) {
            return;
        }
        if (reply.reasoning != null && reply.reasoning.length() > 0) {
            sink.onReasoning(reply.reasoning);
        }
        if (reply.content != null && reply.content.length() > 0) {
            sink.onContent(reply.content);
        }
        if (reply.toolCalls == null) {
            return;
        }
        for (int i = 0; i < reply.toolCalls.length(); i++) {
            JSONObject call = reply.toolCalls.optJSONObject(i);
            if (call == null) {
                continue;
            }
            JSONObject fn = call.optJSONObject("function");
            String name = fn == null ? "" : fn.optString("name", "");
            String args = fn == null ? "" : fn.optString("arguments", "");
            sink.onToolCall(i, call.optString("id", ""), name, args);
        }
    }

    private static JSONArray callsToJson(List<CallAcc> calls) throws Exception {
        if (calls.isEmpty()) {
            return null;
        }
        JSONArray arr = new JSONArray();
        for (int i = 0; i < calls.size(); i++) {
            CallAcc acc = calls.get(i);
            if (acc.name.length() == 0 && acc.args.length() == 0) {
                continue;
            }
            JSONObject fn = new JSONObject();
            fn.put("name", acc.name);
            fn.put("arguments", acc.args.length() == 0 ? "{}" : acc.args.toString());
            JSONObject call = new JSONObject();
            call.put("id", acc.id.length() == 0 ? "call_" + i : acc.id);
            call.put("type", "function");
            call.put("function", fn);
            if (acc.displayPart != null) acc.displayPart.put("index", arr.length());
            arr.put(call);
        }
        return arr.length() == 0 ? null : arr;
    }

    private static String textField(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) {
            return null;
        }
        return o.optString(key, "");
    }

    private static void parseInto(Reply reply, String text) throws Exception {
        JSONObject root = new JSONObject(text);

        if (root.has("error")) {
            JSONObject err = root.optJSONObject("error");
            reply.error = err != null ? err.optString("message", text) : text;
            return;
        }

        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            reply.error = "响应里没有 choices: " + trim(text, 300);
            return;
        }

        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        if (msg == null) {
            reply.error = "choices[0] 里没有 message: " + trim(text, 300);
            return;
        }

        reply.content = msg.optString("content", "");
        reply.reasoning = msg.optString("reasoning_content", "");
        reply.applyUsage(root.optJSONObject("usage"));
        JSONArray calls = msg.optJSONArray("tool_calls");
        if (calls != null && calls.length() > 0) {
            reply.toolCalls = calls;
        }
    }

    /** 拉取模型列表的结果。 */
    public static class ModelsResult {
        public List<String> models = new ArrayList<String>();
        public String error;
    }

    /** 请求 /v1/models，返回可用模型 id 列表。 */
    public static ModelsResult fetchModels(String baseUrl, String apiKey) {
        ModelsResult result = new ModelsResult();
        HttpURLConnection conn = null;
        try {
            Config cfg = new Config(baseUrl, apiKey, "");
            String url = cfg.modelsUrl();

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);

            if (code < 200 || code >= 300) {
                result.error = "HTTP " + code + "（" + url + "）：" + trim(text, 300);
                return result;
            }

            JSONObject root = new JSONObject(text);
            JSONArray data = root.optJSONArray("data");
            if (data == null) {
                result.error = "响应里没有 data 数组：" + trim(text, 300);
                return result;
            }

            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String id = item.optString("id", "");
                if (id.length() > 0) {
                    result.models.add(id);
                }
            }
            if (result.models.isEmpty()) {
                result.error = "模型列表为空。";
            }
        } catch (Exception e) {
            result.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return result;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }

    static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
