package com.mkei.backcast.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.core.LogLevel;
import com.openai.core.Timeout;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.models.Model;
import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;

/**
 * OpenAI 兼容的 Chat Completions 客户端。
 *
 * 使用官方 OpenAI Java SDK 的 Chat Completions、模型目录与 SSE 解析。
 * 只要服务端兼容 /chat/completions，就能接。
 */
public class LlmClient {
    /** Finished generation may have a final usage frame, but it must not wait for another idle budget. */
    private static final long FINISHED_USAGE_GRACE_MS = 1000L;

    public static class Config {
        public String baseUrl;
        public String apiKey;
        public String model;
        /** Stable configuration identifier for private request diagnostics. */
        public String providerId;
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
        public String error;
        /** Private failure evidence for the request recorder, never a model/UI message. */
        public JSONObject diagnostic;
        /** Short notification text; raw provider errors remain private diagnostic evidence. */
        public String userMessage;
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
    private volatile boolean usageOptionUnsupported;
    private volatile boolean verbosityUnsupported;
    private final ThreadLocal<RequestValidity> requestValidity = new ThreadLocal<RequestValidity>();

    /** 这一次请求。停止时把它标死并断开，不碰到下一次请求。 */
    private static class Attempt {
        volatile Call call;
        volatile boolean dead;
        volatile boolean responseStarted;
        long startedNanos;
        volatile long lastProgressNanos;
        volatile BufferedSource source;
        volatile int status;
        String errorDetail;
        String requestId, contentType;
        String stage = "prepare";
        String requestMethod = "POST";
        long idleDeadlineNanos, totalDeadlineNanos;
        int maxChars;
        NetworkRouting.Route route;
        JSONObject networkDiagnostic;
    }

    private volatile Attempt attempt;

    /** The SDK owns request/schema/SSE handling; this hook only exposes this call's cancellation and deadlines. */
    private final class SdkSession implements AutoCloseable {
        final ClientOptions options;
        final OpenAIClient client;
        final Attempt mine;
        SdkSession(final Attempt mine) throws Exception {
            this.mine = mine;
            long headerBudget = headerTimeout(mine);
            final RequestValidity validity = requestValidity.get();
            mine.stage = "network";
            mine.route = NetworkRouting.open(config.baseUrl, new RequestValidity() {
                @Override public boolean isCurrent() { return !mine.dead
                        && (validity == null || validity.isCurrent())
                        && (mine.totalDeadlineNanos == 0L || System.nanoTime() < mine.totalDeadlineNanos); }
            });
            try {
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(headerBudget, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(false).followRedirects(false)
                    .addNetworkInterceptor(new Interceptor() {
                        @Override public Response intercept(Chain chain) throws java.io.IOException {
                            Response response = chain.proceed(chain.request());
                            // Network interceptors run before OkHttp's Retry-After follow-up policy.
                            return response.code() == 503
                                    ? response.newBuilder().removeHeader("Retry-After").build() : response;
                        }
                    })
                    .addInterceptor(new Interceptor() {
                        @Override public Response intercept(Chain chain) throws java.io.IOException {
                            mine.call = chain.call();
                            if (mine.dead) { chain.call().cancel(); throw new java.io.IOException("Cancelled"); }
                            Response response = chain.proceed(chain.request());
                            mine.status = response.code(); mine.responseStarted = true;
                            mine.requestId = response.header("x-request-id");
                            mine.contentType = response.header("Content-Type");
                            if (response.body() != null) {
                                if (mine.status >= 200 && mine.status < 300 && mine.maxChars > 0)
                                    response = limitBody(response, mine.maxChars);
                                mine.source = response.body().source();
                                mine.idleDeadlineNanos = System.nanoTime()
                                        + TimeUnit.MILLISECONDS.toNanos(Math.max(1000L, config.timeoutMs));
                                configureDeadline(mine, 0L);
                                if (mine.status < 200 || mine.status >= 300) {
                                    mine.stage = "http_error";
                                    MediaType type = response.body().contentType();
                                    String detail = readErrorDetail(response);
                                    mine.errorDetail = detail;
                                    response.close();
                                    response = response.newBuilder().body(ResponseBody.create(type, detail)).build();
                                }
                            }
                            return response;
                        }
                    });
            if (mine.route != null) mine.route.configure(builder);
            OkHttpClient http = builder.build();
            Timeout timeout = Timeout.builder().connect(Duration.ofSeconds(20)).write(Duration.ofSeconds(20))
                    .read(Duration.ofMillis(headerBudget))
                    .request(mine.totalDeadlineNanos == 0L ? Duration.ZERO : Duration.ofMillis(remainingTotal(mine)))
                    .build();
            options = ClientOptions.builder()
                    .httpClient(new com.openai.client.okhttp.OkHttpClient(http))
                    .baseUrl(Config.root(config.baseUrl)).apiKey(config.apiKey)
                    .maxRetries(0).logLevel(LogLevel.OFF).responseValidation(false).timeout(timeout).build();
            client = new OpenAIClientImpl(options);
            mine.stage = "GET".equals(mine.requestMethod) ? "models_headers" : "headers";
            } catch (Exception failure) {
                if (mine.route != null) mine.route.close();
                throw failure;
            }
        }
        @Override public void close() {
            try { client.close(); }
            finally { if (mine.route != null) try { mine.route.close(); } catch (java.io.IOException ignored) { } }
        }
    }

    public LlmClient(Config config) {
        this.config = config;
    }

    /** Child checkpoint configuration contains only a credential fingerprint, never the key. */
    public JSONObject configSnapshot() {
        try {
            return new JSONObject().put("baseUrl", config.baseUrl).put("credentialFingerprint", credentialFingerprint(config.apiKey))
                    .put("model", config.model).put("providerId", config.providerId)
                    .put("reasoningEffort", config.reasoningEffort).put("verbosity", config.verbosity)
                    .put("responseInstructions", config.responseInstructions).put("timeoutMs", config.timeoutMs)
                    .put("maxTokens", config.maxTokens).put("totalTimeoutMs", config.totalTimeoutMs)
                    .put("maxResponseChars", config.maxResponseChars);
        } catch (org.json.JSONException invalid) { throw new IllegalStateException("无法捕获父 agent 模型配置。", invalid); }
    }

    public static String credentialFingerprint(String credential) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((credential == null ? "" : credential).getBytes("UTF-8"));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format(java.util.Locale.US, "%02x", value & 255));
            return result.toString();
        } catch (Exception impossible) { throw new IllegalStateException("无法校验模型授权身份。", impossible); }
    }

    /** 断开正在进行的请求。用户点停止时调用。 */
    public void abort() {
        Attempt current = attempt;
        if (current == null) {
            return;
        }
        current.dead = true;
        Call call = current.call;
        if (call != null) call.cancel();
        if (current.route != null) try { current.route.close(); } catch (java.io.IOException ignored) { }
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
        mine.startedNanos = System.nanoTime();
        mine.totalDeadlineNanos = config.totalTimeoutMs > 0
                ? mine.startedNanos + TimeUnit.MILLISECONDS.toNanos(config.totalTimeoutMs) : 0L;
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
        Reply reply = sendAttempt(messages, tools, sink, mine, includeUsage, includeVerbosity ? detail : null);
        if (mine.dead) {
            discardCancelledReply(reply);
            return reply;
        }
        // Learn optional capability failures for the next explicit user request only.
        // A rejected request is still a failed request, never permission to send another POST.
        if (includeUsage && rejectsOption(reply.error, "stream_options", "include_usage")) usageOptionUnsupported = true;
        if (includeVerbosity && rejectsOption(reply.error, "verbosity", "verbosity")) verbosityUnsupported = true;
        if (reply.error != null) sealDiagnostic(reply, mine, messages);
        return reply;
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
        mine.requestId = null; mine.contentType = null; mine.responseStarted = false; mine.stage = "prepare";
        SdkSession sdk = null;
        HttpResponseFor<StreamResponse<ChatCompletionChunk>> response = null;
        StreamResponse<ChatCompletionChunk> stream = null;
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

            sdk = new SdkSession(mine);
            Map<String, Object> fields = sdk.options.jsonMapper().readValue(body.toString(), Map.class);
            ChatCompletionCreateParams.Builder params = ChatCompletionCreateParams.builder()
                    .model(config.model).messages((JsonField) JsonValue.from(fields.remove("messages")));
            fields.remove("model"); fields.remove("stream");
            for (Map.Entry<String, Object> entry : fields.entrySet())
                params.putAdditionalBodyProperty(entry.getKey(), JsonValue.from(entry.getValue()));
            params.putAdditionalHeader("Accept-Encoding", "identity");
            waitingHeaders = true;
            mine.stage = "headers";
            response = sdk.client.chat().completions().withRawResponse().createStreaming(params.build());
            waitingHeaders = false;
            if (mine.dead) {
                return reply;
            }
            boolean jsonResponse = false;
            for (String value : response.headers().values("Content-Type"))
                if (value.toLowerCase(java.util.Locale.US).contains("application/json")) jsonResponse = true;
            if (jsonResponse) {
                mine.stage = "json_body";
                String text = sdk.options.jsonMapper().readTree(response.body()).toString();
                parseInto(reply, text); emitFull(reply, sink);
            } else {
                mine.stage = "stream_body";
                stream = response.parse();
                List<CallAcc> calls = new ArrayList<CallAcc>();
                long finishedDeadline = 0L;
                Iterator<ChatCompletionChunk> chunks = stream.stream().iterator();
                try {
                    while (!mine.dead && chunks.hasNext()) {
                        String data = sdk.options.jsonMapper().writeValueAsString(chunks.next());
                        long previous = reply.streamProgress;
                        absorbEvent(data, reply, calls, sink);
                        if (reply.error != null) break;
                        if (reply.streamProgress != previous) {
                            mine.lastProgressNanos = System.nanoTime();
                            mine.idleDeadlineNanos = mine.lastProgressNanos
                                    + TimeUnit.MILLISECONDS.toNanos(Math.max(1000L, config.timeoutMs));
                        }
                        if (reply.finishReason != null && finishedDeadline == 0L)
                            finishedDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FINISHED_USAGE_GRACE_MS);
                        if (reply.finalUsage) break;
                        configureDeadline(mine, finishedDeadline);
                    }
                } catch (RuntimeException streamFailure) {
                    Throwable failure = rootCause(streamFailure);
                    if (reply.finishReason == null) {
                        if (failure instanceof java.io.InterruptedIOException
                                && (mine.totalDeadlineNanos == 0L || System.nanoTime() < mine.totalDeadlineNanos)) {
                            noteIdle(reply, calls);
                            if (reply.error != null) reply.diagnostic = diagnostic(mine, streamFailure);
                        }
                        else throw streamFailure;
                    }
                }
                if (!mine.dead && reply.error == null) {
                    reply.toolCalls = callsToJson(calls); validateToolCalls(reply);
                    if (reply.error != null) {
                        mine.stage = "validation";
                        reply.diagnostic = diagnostic(mine, null);
                    }
                }
            }
        } catch (Exception e) {
            if (!mine.dead) {
                Throwable failure = rootCause(e);
                if ((mine.status > 0 && mine.status < 200) || mine.status >= 300) {
                    String detail = mine.errorDetail == null ? "" : mine.errorDetail;
                    try { if (detail.length() == 0 && e instanceof com.openai.errors.OpenAIServiceException)
                        detail = sdk.options.jsonMapper().writeValueAsString(((com.openai.errors.OpenAIServiceException) e).body());
                    } catch (Exception missingDetail) { /* HTTP status remains authoritative. */ }
                    reply.error = "HTTP " + mine.status + ": " + trim(detail, 500);
                } else reply.error = (waitingHeaders && failure instanceof java.io.InterruptedIOException
                        ? "响应头等待超时：" : "") + failure.getClass().getSimpleName() + ": " + failure.getMessage();
                if (e instanceof NetworkRouting.Failure) {
                    reply.userMessage = ((NetworkRouting.Failure) e).getMessage();
                    mine.networkDiagnostic = ((NetworkRouting.Failure) e).diagnostic;
                } else reply.userMessage = notification(mine);
                reply.diagnostic = diagnostic(mine, e);
            }
        } finally {
            reply.finishText();
            if (reply.error != null && reply.diagnostic == null) reply.diagnostic = diagnostic(mine, null);
            if (reply.error != null && reply.userMessage == null) reply.userMessage = notification(mine);
            if (stream != null) try { stream.close(); } catch (RuntimeException closeFailure) { }
            if (response != null) try { response.close(); } catch (RuntimeException closeFailure) { }
            if (sdk != null) try { sdk.close(); } catch (RuntimeException closeFailure) { }
            mine.call = null; mine.source = null; mine.status = 0; mine.errorDetail = null;
        }
        if (mine.dead) {
            discardCancelledReply(reply);
        }
        return reply;
    }

    private static void discardCancelledReply(Reply reply) {
        reply.error = null;
        reply.content = "";
        reply.toolCalls = null;
        reply.reasoning = null;
        reply.displayParts = null;
        reply.diagnostic = null;
        reply.userMessage = null;
    }

    private String notification(Attempt mine) {
        if (mine.status == 401) return "AI 密钥无效，请检查供应商配置。";
        if (mine.status == 403) return "AI 服务拒绝访问，请检查账户权限。";
        if (mine.status == 404) return "AI 接口或模型不存在，请检查配置。";
        if (mine.status == 429) return "AI 服务限流，请稍后手动继续。";
        if (mine.status >= 500) return "AI 服务器暂时无法处理请求。";
        if (mine.status >= 400) return "AI 请求参数不受支持，请检查模型配置。";
        if ("validation".equals(mine.stage)) return "模型返回的工具参数无效，未执行。";
        if (mine.status == 0 && mine.call != null && mine.route != null && !mine.dead) {
            try {
                String message = mine.route.failureMessage(new RequestValidity() {
                    @Override public boolean isCurrent() { return !mine.dead; }
                });
                mine.networkDiagnostic = mine.route.diagnostic();
                return message;
            } catch (RuntimeException unavailableProbe) { return "AI 服务器连接失败，请检查网络或服务地址。"; }
        }
        return mine.responseStarted ? "模型响应中断，请稍后手动继续。" : "模型请求失败，详细原因已记录。";
    }

    private JSONObject diagnostic(Attempt mine, Throwable failure) {
        JSONObject result = new JSONObject();
        try {
            long now = System.nanoTime();
            result.put("provider", config.providerId == null ? "" : trim(config.providerId, 128));
            result.put("model", config.model == null ? "" : trim(config.model, 128));
            // Request bodies, credentials, URL query strings and userinfo are deliberately absent.
            try {
                java.net.URI endpoint = new java.net.URI("GET".equals(mine.requestMethod) ? config.modelsUrl() : config.baseUrl);
                result.put("endpoint", trim(new java.net.URI(endpoint.getScheme(), null, endpoint.getHost(),
                        endpoint.getPort(), endpoint.getPath(), null, null).toString(), 512));
            } catch (Exception invalidEndpoint) { result.put("endpoint", "invalid"); }
            result.put("stage", mine.stage);
            result.put("http_method", mine.requestMethod);
            result.put("http_status", mine.status);
            result.put("response_started", mine.responseStarted);
            result.put("has_progress", mine.lastProgressNanos > 0L);
            result.put("elapsed_ms", Math.max(0L, TimeUnit.NANOSECONDS.toMillis(now - mine.startedNanos)));
            result.put("quiet_ms", Math.max(0L, TimeUnit.NANOSECONDS.toMillis(now
                    - (mine.lastProgressNanos == 0L ? mine.startedNanos : mine.lastProgressNanos))));
            if (mine.networkDiagnostic != null) result.put("network", mine.networkDiagnostic);
            else if (mine.route != null) result.put("network", mine.route.diagnostic());
            if (mine.requestId != null) result.put("request_id", trim(mine.requestId, 256));
            if (mine.contentType != null) result.put("content_type", trim(mine.contentType, 256));
            if (mine.errorDetail != null) result.put("provider_error", trim(mine.errorDetail, 4096));
            if (failure != null) {
                result.put("exception_class", failure.getClass().getName());
                result.put("cause_class", rootCause(failure).getClass().getName());
                StringBuilder frames = new StringBuilder();
                java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<Throwable, Boolean>());
                int count = 0;
                while (failure != null && seen.add(failure) && count < 256 && frames.length() < 3072) {
                    frames.append(failure.getClass().getName()).append('\n');
                    for (StackTraceElement frame : failure.getStackTrace()) {
                        if (++count > 256 || frames.length() >= 3072) break;
                        frames.append(" at ").append(frame).append('\n');
                    }
                    failure = failure.getCause();
                }
                result.put("stack", trim(frames.toString(), 3072));
            }
        } catch (Exception unavailableMetadata) {
            // Diagnostics must not replace the request's actual result.
        }
        return result;
    }

    private void sealDiagnostic(Reply reply, Attempt mine, List<Message> messages) {
        try {
            if (reply.diagnostic == null) reply.diagnostic = diagnostic(mine, null);
            String providerError = reply.diagnostic.optString("provider_error", "");
            if (incompleteDiagnosticBody(providerError))
                reply.diagnostic.put("provider_error", "供应商错误正文不完整，已省略");
            List<String> secrets = new ArrayList<String>();
            secrets.add(config.apiKey);
            for (Message message : messages) if (message != null) {
                secrets.add(message.content); secrets.add(message.reasoning);
                diagnosticArguments(message.toolCalls, secrets);
            }
            secrets.add(reply.content); secrets.add(reply.reasoning);
            diagnosticArguments(reply.toolCalls, secrets);
            reply.diagnostic = new JSONObject(Diagnostics.boundedJson(reply.diagnostic,
                    secrets.toArray(new String[secrets.size()])));
        } catch (Exception unavailableDiagnostics) {
            reply.diagnostic = null;
        }
    }

    /** A clipped echo cannot be safely matched against the full request's sensitive strings. */
    private static boolean incompleteDiagnosticBody(String body) {
        if (body == null || body.length() == 0) return false;
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 4096) return true;
        String text = body.trim();
        if (!text.startsWith("{") && !text.startsWith("[")) return false;
        try {
            JSONTokener parser = new JSONTokener(text);
            Object value = parser.nextValue();
            return (!(value instanceof JSONObject) && !(value instanceof JSONArray)) || parser.nextClean() != 0;
        } catch (Exception incomplete) {
            return true;
        }
    }

    private static void diagnosticArguments(JSONArray calls, List<String> secrets) {
        if (calls == null) return;
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            JSONObject function = call == null ? null : call.optJSONObject("function");
            if (function != null) secrets.add(function.optString("arguments", ""));
        }
    }

    /** HTTP failure details are optional: cap both memory and time before applying request policy. */
    private static String readErrorDetail(Response response) throws java.io.IOException {
        if (response.body() == null) return "";
        BufferedSource source = response.body().source();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        if (source.timeout().hasDeadline()) deadline = Math.min(deadline, source.timeout().deadlineNanoTime());
        source.timeout().timeout(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                .deadlineNanoTime(deadline);
        Buffer detail = new Buffer();
        try {
            while (detail.size() < 4096L) {
                if (source.read(detail, 4096L - detail.size()) < 0L) break;
            }
        } catch (java.io.IOException missingDetail) {
            // Preserve received detail if a server stalls, without losing the known HTTP status.
        }
        return detail.readUtf8();
    }

    private static Response limitBody(Response response, final long limit) {
        final ResponseBody original = response.body();
        final BufferedSource bounded = Okio.buffer(new okio.ForwardingSource(original.source()) {
            long received;
            @Override public long read(Buffer sink, long byteCount) throws java.io.IOException {
                long count = super.read(sink, Math.min(byteCount, Math.max(1L, limit - received)));
                if (count > 0L) {
                    received += count;
                    if (received > limit) throw new java.io.IOException("Response size limit exceeded");
                }
                return count;
            }
        });
        return response.newBuilder().body(new ResponseBody() {
            @Override public MediaType contentType() { return original.contentType(); }
            @Override public long contentLength() { return -1L; }
            @Override public BufferedSource source() { return bounded; }
        }).build();
    }

    private int headerTimeout(Attempt mine) throws java.net.SocketTimeoutException {
        long budget = Math.max(1000L, config.timeoutMs);
        if (mine.totalDeadlineNanos > 0L) budget = Math.min(budget, remainingTotal(mine));
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, budget));
    }

    private static long remainingTotal(Attempt mine) throws java.net.SocketTimeoutException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(mine.totalDeadlineNanos - System.nanoTime());
        if (remaining <= 0) throw new java.net.SocketTimeoutException("Request deadline exceeded");
        return Math.min(Integer.MAX_VALUE, remaining);
    }

    private static Throwable rootCause(Throwable failure) {
        // A call deadline may wrap the socket close that it caused; keep the timeout as the cause.
        while (failure.getCause() != null && failure.getCause() != failure) {
            if (failure instanceof java.io.InterruptedIOException) return failure;
            failure = failure.getCause();
        }
        return failure;
    }

    private static void configureDeadline(Attempt mine, long finishedDeadline) {
        BufferedSource source = mine.source;
        if (source == null) return;
        long deadline = finishedDeadline > 0L ? finishedDeadline : mine.idleDeadlineNanos;
        if (mine.totalDeadlineNanos > 0L) deadline = Math.min(deadline, mine.totalDeadlineNanos);
        source.timeout().timeout(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                .deadlineNanoTime(deadline);
    }

    /** 一路工具调用的拼装。流式里名字和参数是分段到的。 */
    private static class CallAcc {
        String id = "";
        String name = "";
        StringBuilder args = new StringBuilder();
        JSONObject displayPart;
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

    private static void absorbEvent(String data, Reply reply, List<CallAcc> calls, Sink sink)
            throws Exception {
        JSONObject root = new JSONObject(data);
        if (reply.finishReason != null) {
            reply.applyUsage(root.optJSONObject("usage"));
            JSONArray tailChoices = root.optJSONArray("choices");
            if (tailChoices == null || tailChoices.length() == 0) {
                if (root.optJSONObject("usage") != null) reply.finalUsage = true;
            } else {
                JSONObject tailChoice = tailChoices.optJSONObject(0);
                if (tailChoice != null) reply.applyUsage(tailChoice.optJSONObject("usage"));
            }
            return;
        }
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
        String finish = choice.optString("finish_reason", "");
        if (finish.length() > 0 && !"null".equals(finish)) reply.finishReason = finish;
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
        public JSONObject diagnostic;
        public String userMessage;
    }

    /** 请求 /v1/models，返回可用模型 id 列表。 */
    public static ModelsResult fetchModels(String baseUrl, String apiKey, String providerId) {
        ModelsResult result = new ModelsResult();
        SdkSession sdk = null;
        LlmClient owner = null;
        Attempt mine = new Attempt(); mine.startedNanos = System.nanoTime();
        mine.totalDeadlineNanos = mine.startedNanos + TimeUnit.SECONDS.toNanos(30L);
        mine.stage = "models_headers";
        mine.requestMethod = "GET";
        try {
            Config cfg = new Config(baseUrl, apiKey, "");
            cfg.timeoutMs = 30000; cfg.totalTimeoutMs = 30000;
            cfg.providerId = providerId;
            owner = new LlmClient(cfg);
            sdk = owner.new SdkSession(mine);
            for (Model model : sdk.client.models().list().data()) {
                String id = model.id();
                if (id.length() > 0) {
                    result.models.add(id);
                }
            }
            if (result.models.isEmpty()) {
                result.error = "模型列表为空。";
            }
        } catch (Exception e) {
            result.error = e instanceof com.openai.errors.OpenAIServiceException
                    ? "HTTP " + ((com.openai.errors.OpenAIServiceException) e).statusCode() + ": " + trim(e.getMessage(), 300)
                    : rootCause(e).getClass().getSimpleName() + ": " + rootCause(e).getMessage();
            if (owner != null) {
                if (e instanceof NetworkRouting.Failure) {
                    result.userMessage = e.getMessage();
                    mine.networkDiagnostic = ((NetworkRouting.Failure) e).diagnostic;
                } else result.userMessage = owner.notification(mine);
                Reply failure = new Reply(); failure.error = result.error; failure.diagnostic = owner.diagnostic(mine, e);
                owner.sealDiagnostic(failure, mine, java.util.Collections.<Message>emptyList());
                result.diagnostic = failure.diagnostic;
            }
        } finally {
            if (sdk != null) try { sdk.close(); } catch (RuntimeException closeFailure) { }
        }
        return result;
    }

    static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
