package com.mkei.backcast.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
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
    /** Finished generation may have a final usage frame, but it must not wait for another idle budget. */
    private static final long FINISHED_USAGE_GRACE_MS = 1000L;

    public static class Config {
        public String baseUrl;
        public String apiKey;
        public String model;
        public int timeoutMs = 120000;
        public int maxTokens;
        public int totalTimeoutMs;
        public int maxResponseChars;
        public String verbosity;
        public String responseInstructions;

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
        private String finishReason;
        private long streamProgress;
        private boolean finalUsage;

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

    public interface RequestValidity {
        boolean isCurrent();
    }

    private final Config config;
    private volatile HttpURLConnection active;
    private volatile boolean usageOptionUnsupported;
    private volatile boolean verbosityUnsupported;
    private final ThreadLocal<RequestValidity> requestValidity = new ThreadLocal<RequestValidity>();

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

    /** Register cancellation validity while preserving existing send overrides. */
    public Reply sendIfCurrent(List<Message> messages, JSONArray tools, Sink sink, RequestValidity validity) {
        if (!validity.isCurrent()) return new Reply();
        requestValidity.set(validity);
        try {
            return send(messages, tools, sink);
        } finally {
            requestValidity.remove();
        }
    }

    public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
        Attempt mine = new Attempt();
        mine.deadline = config.totalTimeoutMs > 0 ? System.currentTimeMillis() + config.totalTimeoutMs : 0;
        mine.maxChars = config.maxResponseChars;
        attempt = mine;
        RequestValidity validity = requestValidity.get();
        if (validity != null && !validity.isCurrent()) {
            mine.dead = true;
            return new Reply();
        }
        boolean includeUsage = !usageOptionUnsupported;
        String detail = ResponsePreferences.normalizeVerbosity(config.verbosity);
        boolean includeVerbosity = !verbosityUnsupported && !"default".equals(detail);
        Reply reply;
        while (true) {
            reply = sendAttempt(messages, tools, sink, mine, includeUsage, includeVerbosity ? detail : null);
            if (mine.dead) return reply;
            if (includeUsage && rejectsOption(reply.error, "stream_options", "include_usage")) {
                usageOptionUnsupported = true;
                includeUsage = false;
            } else if (includeVerbosity && rejectsOption(reply.error, "verbosity", "verbosity")) {
                verbosityUnsupported = true;
                includeVerbosity = false;
            } else {
                return reply;
            }
        }
    }

    private static boolean rejectsOption(String error, String option, String alias) {
        if (error == null || !error.startsWith("HTTP 400:")) return false;
        String lower = error.toLowerCase(java.util.Locale.US);
        return (lower.contains(option) || lower.contains(alias))
                && (lower.contains("unsupported") || lower.contains("unknown")
                || lower.contains("unrecognized") || lower.contains("not supported")
                || lower.contains("does not support") || lower.contains("not allowed")
                || lower.contains("not permitted") || lower.contains("unexpected"));
    }

    private Reply sendAttempt(List<Message> messages, JSONArray tools, Sink sink,
            Attempt mine, boolean includeUsage, String verbosity) {
        Reply reply = new Reply();
        if (mine.dead) {
            return reply;
        }
        HttpURLConnection conn = null;
        InputStream response = null;
        OutputStream request = null;
        boolean waitingHeaders = false;
        try {
            JSONObject body = new JSONObject();
            body.put("model", config.model);
            body.put("stream", true);
            if (includeUsage) body.put("stream_options", new JSONObject().put("include_usage", true));
            if (verbosity != null) body.put("verbosity", verbosity);
            if (config.maxTokens > 0) body.put("max_tokens", config.maxTokens);

            if (config.reasoningWanted()) {
                body.put("reasoning_effort", config.reasoningEffort);
            }

            JSONArray msgs = new JSONArray();
            boolean rulesApplied = false;
            for (Message m : messages) {
                JSONObject item = m.toJson();
                if (config.responseInstructions != null && config.responseInstructions.length() > 0
                        && Message.SYSTEM.equals(m.role)) {
                    String content = m.content == null ? "" : m.content;
                    if (!content.contains(config.responseInstructions)) {
                        item.put("content", content + "\n\n" + config.responseInstructions);
                    }
                    rulesApplied = true;
                }
                msgs.put(item);
            }
            if (!rulesApplied && config.responseInstructions != null && config.responseInstructions.length() > 0) {
                JSONArray withRules = new JSONArray().put(Message.system(config.responseInstructions).toJson());
                for (int i = 0; i < msgs.length(); i++) withRules.put(msgs.get(i));
                msgs = withRules;
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
            // A server may wait for model output before returning headers. The stream polling slice
            // cannot shorten this initial wait to ten seconds before the idle budget even starts.
            conn.setReadTimeout(headerTimeout(mine));
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            conn.setRequestProperty("Accept", "text/event-stream");
            // 关掉压缩，否则整包缓冲完才吐，流式等于没开。
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Connection", "close");

            byte[] payload = body.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(payload.length);
            request = conn.getOutputStream();
            request.write(payload);
            request.flush();
            request.close();
            request = null;

            waitingHeaders = true;
            conn.setReadTimeout(headerTimeout(mine));
            int code = conn.getResponseCode();
            waitingHeaders = false;
            // Poll reads once the response exists; meaningful output controls the separate idle clock.
            conn.setReadTimeout(READ_SLICE_MS);
            if (code < 200 || code >= 300) {
                // The status remains authoritative even if the optional error body times out.
                reply.error = "HTTP " + code + ":";
                try {
                    response = conn.getErrorStream();
                    String text = readAll(response);
                    reply.raw = text;
                    reply.error += " " + trim(text, 500);
                } catch (Exception errorBodyFailure) {
                    // Losing provider detail must not turn a permanent HTTP error into a network retry.
                }
                return reply;
            }
            if (mine.dead) {
                return reply;
            }
            response = conn.getInputStream();
            readStream(response, reply, mine, sink, config.timeoutMs);
        } catch (Exception e) {
            if (!mine.dead) {
                reply.error = (waitingHeaders && e instanceof java.net.SocketTimeoutException
                        ? "响应头等待超时：" : "") + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        } finally {
            closeQuietly(request);
            closeQuietly(response);
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

    private int headerTimeout(Attempt mine) throws java.net.SocketTimeoutException {
        long budget = Math.max(1000L, config.timeoutMs);
        if (mine.deadline > 0) {
            long remaining = mine.deadline - System.currentTimeMillis();
            if (remaining <= 0) throw new java.net.SocketTimeoutException("Request deadline exceeded while waiting for response headers");
            budget = Math.min(budget, remaining);
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, budget));
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
        StreamLines reader = new StreamLines(in);
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
                clock[0] = System.currentTimeMillis() + clock[1];
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
                    clock[0] = System.currentTimeMillis() + clock[1];
                }
                reply.raw = raw.toString();
                if (!mine.dead && reply.error == null) {
                    parseInto(reply, json.toString());
                    emitFull(reply, sink);
                }
                return;
            }
            boolean more = consumeSse(first.text, reply, calls, sink, clock);
            long finishedDeadline = finishDeadline(reply, 0L);
            while (more && reply.error == null && !mine.dead) {
                if (finishedDeadline > 0 && reply.finalUsage) break;
                if (finishedDeadline > 0 && System.currentTimeMillis() >= finishedDeadline) break;
                Pulled next;
                try {
                    next = pullLine(reader, raw, mine, clock, finishedDeadline);
                } catch (java.io.IOException failure) {
                    // Generation is already complete; a missing usage tail cannot discard valid output.
                    if (finishedDeadline > 0) break;
                    throw failure;
                }
                if (next.idle) {
                    if (finishedDeadline > 0) break;
                    reply.raw = raw.toString();
                    noteIdle(reply, calls);
                    return;
                }
                if (next.text == null) {
                    break;
                }
                if (!consumeSse(next.text, reply, calls, sink, clock)) {
                    break;
                }
                finishedDeadline = finishDeadline(reply, finishedDeadline);
            }
            reply.raw = raw.toString();
            if (reply.error == null && !mine.dead) {
                reply.toolCalls = callsToJson(calls);
                validateToolCalls(reply);
            }
        } finally {
            reply.finishText();
            closeQuietly(reader);
        }
    }

    private static long finishDeadline(Reply reply, long previous) {
        if (previous > 0 || reply.finishReason == null) return previous;
        return System.currentTimeMillis() + FINISHED_USAGE_GRACE_MS;
    }

    /** Keep incomplete bytes across socket timeouts; decode UTF-8 only after framing a whole line. */
    private static final class StreamLines implements Closeable {
        private final InputStream input;
        private final byte[] buffer = new byte[8192];
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private int position, length;

        StreamLines(InputStream input) { this.input = input; }

        String readLine(Attempt mine, long deadline) throws Exception {
            while (!mine.dead) {
                while (position < length) {
                    int value = buffer[position++] & 255;
                    if (value == '\n') return takeLine();
                    line.write(value);
                    if (mine.maxChars > 0 && line.size() >= mine.maxChars)
                        throw new java.io.IOException("Response size limit exceeded");
                }
                int wanted = buffer.length;
                if (deadline > 0) {
                    if (System.currentTimeMillis() >= deadline) return null;
                    // A usage tail is optional. Poll bytes instead of a blocking read after finish_reason.
                    int available = input.available();
                    if (available <= 0) {
                        Thread.sleep(Math.min(25L, Math.max(1L, deadline - System.currentTimeMillis())));
                        continue;
                    }
                    wanted = Math.min(wanted, available);
                }
                int count = input.read(buffer, 0, wanted);
                if (count < 0) return line.size() == 0 ? null : takeLine();
                position = 0; length = count;
            }
            return null;
        }

        private String takeLine() throws Exception {
            String value = line.toString("UTF-8"); line.reset();
            return value.endsWith("\r") ? value.substring(0, value.length() - 1) : value;
        }

        @Override public void close() throws java.io.IOException { input.close(); }
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
    private static Pulled pullLine(StreamLines reader, StringBuilder raw, Attempt mine,
            long[] clock) throws Exception {
        return pullLine(reader, raw, mine, clock, 0L);
    }

    private static Pulled pullLine(StreamLines reader, StringBuilder raw, Attempt mine,
            long[] clock, long finishedDeadline) throws Exception {
        Pulled out = new Pulled();
        while (!mine.dead) {
            if (finishedDeadline > 0 && System.currentTimeMillis() >= finishedDeadline) {
                out.idle = true;
                return out;
            }
            if (mine.deadline > 0 && System.currentTimeMillis() >= mine.deadline) {
                throw new java.net.SocketTimeoutException("Request deadline exceeded");
            }
            if (mine.maxChars > 0 && raw.length() >= mine.maxChars) {
                throw new java.io.IOException("Response size limit exceeded");
            }
            if (finishedDeadline == 0 && System.currentTimeMillis() >= clock[0]) {
                out.idle = true;
                return out;
            }
            String line;
            try {
                line = reader.readLine(mine, finishedDeadline);
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
    private static boolean consumeSse(String line, Reply reply, List<CallAcc> calls, Sink sink,
            long[] clock)
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
        long progress = reply.streamProgress;
        absorbEvent(data, reply, calls, sink);
        if (reply.streamProgress != progress) clock[0] = System.currentTimeMillis() + clock[1];
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
            if (reply.finishReason != null && root.optJSONObject("usage") != null) reply.finalUsage = true;
            return;
        }
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) {
            return;
        }
        reply.applyUsage(choice.optJSONObject("usage"));
        boolean alreadyFinished = reply.finishReason != null;
        String finish = choice.optString("finish_reason", "");
        if (finish.length() > 0 && !"null".equals(finish)) reply.finishReason = finish;
        // Once generation is finished, later frames may update usage only.
        if (alreadyFinished) return;
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
            reply.streamProgress++;
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
            reply.streamProgress++;
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
                if (!id.equals(acc.id)) reply.streamProgress++;
                acc.id = id;
            }
            JSONObject fn = tc.optJSONObject("function");
            if (fn != null) {
                String name = fn.optString("name", "");
                if (name.length() > 0) {
                    String previousName = acc.name;
                    if (acc.name.length() == 0 || name.startsWith(acc.name)) {
                        acc.name = name;
                    } else if (!acc.name.endsWith(name)) {
                        acc.name = acc.name + name;
                    }
                    if (!previousName.equals(acc.name)) reply.streamProgress++;
                }
                if (fn.has("arguments") && !fn.isNull("arguments")) {
                    String arguments = fn.optString("arguments", "");
                    acc.args.append(arguments);
                    if (arguments.length() > 0) reply.streamProgress++;
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

    /** EOF compatibility accepts complete calls only; malformed/truncated arguments never reach tools. */
    private static void validateToolCalls(Reply reply) {
        if (!reply.hasToolCalls()) return;
        try {
            if ("length".equals(reply.finishReason) || "content_filter".equals(reply.finishReason))
                throw new IllegalArgumentException("generation did not complete");
            for (int i = 0; i < reply.toolCalls.length(); i++) {
                JSONObject call = reply.toolCalls.getJSONObject(i);
                JSONObject function = call.getJSONObject("function");
                if (function.optString("name", "").trim().length() == 0)
                    throw new IllegalArgumentException("missing tool name");
                String arguments = function.optString("arguments", "{}");
                JSONTokener tokenizer = new JSONTokener(arguments);
                Object value = tokenizer.nextValue();
                if (!(value instanceof JSONObject) || tokenizer.nextClean() != 0)
                    throw new IllegalArgumentException("incomplete tool arguments");
            }
        } catch (Exception invalid) {
            reply.toolCalls = null;
            reply.error = "模型工具调用参数不完整或无效，未执行。";
        }
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
            String finish = choices.getJSONObject(0).optString("finish_reason", "");
            if (finish.length() > 0 && !"null".equals(finish)) reply.finishReason = finish;
            validateToolCalls(reply);
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
        InputStream response = null;
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
            response = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(response);

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
            closeQuietly(response);
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
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            closeQuietly(reader);
        }
    }

    private static void closeQuietly(Closeable stream) {
        if (stream == null) return;
        try { stream.close(); } catch (Exception ignored) { }
    }

    static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
