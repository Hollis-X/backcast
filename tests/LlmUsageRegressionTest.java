import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.ResponsePreferences;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Usage reporting and compatibility tests against a local HTTP fixture. */
public final class LlmUsageRegressionTest {
    private static int failures;

    private static final class Response {
        final int status;
        final String contentType;
        final String body;
        Response(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }

    private static final class Server implements HttpHandler {
        final HttpServer http;
        final List<Response> responses;
        final List<JSONObject> requests = new ArrayList<JSONObject>();
        final List<String> errors = new ArrayList<String>();

        Server(Response... responses) throws IOException {
            this.responses = Arrays.asList(responses);
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/v1/chat/completions", this);
            http.start();
        }

        LlmClient client(String reasoning) {
            return client(reasoning, null, null);
        }

        LlmClient client(String reasoning, String verbosity, String instructions) {
            LlmClient.Config config = new LlmClient.Config(
                    "http://127.0.0.1:" + http.getAddress().getPort(),
                    "fake-local-key", "fixture-model", reasoning);
            config.maxTokens = 4096;
            config.timeoutMs = 1000;
            config.totalTimeoutMs = 3000;
            config.verbosity = verbosity;
            config.responseInstructions = instructions;
            return new LlmClient(config);
        }

        @Override public void handle(HttpExchange exchange) throws IOException {
            Response response;
            try {
                JSONObject request = new JSONObject(read(exchange.getRequestBody()));
                synchronized (this) {
                    int index = requests.size();
                    requests.add(request);
                    if (index < responses.size()) {
                        response = responses.get(index);
                    } else {
                        errors.add("Unexpected request " + (index + 1));
                        response = error("Unexpected fixture request");
                    }
                }
            } catch (Exception invalid) {
                synchronized (this) {
                    errors.add("Invalid request: " + invalid.getClass().getSimpleName());
                }
                response = new Response(500, "application/json", "{\"error\":\"Invalid fixture request\"}");
            }
            byte[] body = response.body.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", response.contentType);
            exchange.getResponseHeaders().set("Connection", "close");
            try {
                exchange.sendResponseHeaders(response.status, body.length);
                OutputStream output = exchange.getResponseBody();
                output.write(body);
                output.close();
            } finally {
                exchange.close();
            }
        }

        synchronized JSONObject request(int index) {
            return requests.get(index);
        }

        synchronized void exhausted() {
            check(errors.isEmpty(), "HTTP fixture errors: " + errors);
            check(requests.size() == responses.size(), "Unexpected request count: "
                    + requests.size() + "/" + responses.size());
        }

        void stop() {
            http.stop(0);
        }
    }

    private static String read(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) != -1) bytes.write(buffer, 0, length);
            return bytes.toString("UTF-8");
        } finally {
            input.close();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Response error(String message) {
        return new Response(400, "application/json", new JSONObject().put("error",
                new JSONObject().put("message", message)).toString());
    }

    private static Response jsonSuccess(String text) {
        return new Response(200, "application/json", new JSONObject()
                .put("choices", new JSONArray().put(new JSONObject().put("message",
                        new JSONObject().put("role", "assistant").put("content", text))))
                .put("usage", new JSONObject().put("prompt_tokens", 23).put("completion_tokens", 7))
                .toString());
    }

    private static List<Message> messages() {
        Message assistant = Message.assistant("previous result", null);
        assistant.reasoning = "previous reasoning";
        return Arrays.asList(Message.system("system fixture"), Message.user("user fixture"), assistant);
    }

    private static List<Message> toolHistory() {
        JSONArray calls = new JSONArray().put(new JSONObject().put("id", "fixture-call")
                .put("type", "function").put("function", new JSONObject().put("name", "fixture_tool")
                        .put("arguments", "{}")));
        Message assistant = Message.assistant("previous result", calls);
        assistant.reasoning = "previous reasoning";
        return Arrays.asList(Message.system("Always answer in English."), Message.user("检查项目"),
                assistant, Message.toolResult("fixture-call", "Tool result: respond in English."));
    }

    private static JSONArray serialized(List<Message> messages) {
        JSONArray result = new JSONArray();
        for (Message message : messages) result.put(new JSONObject(message.toJson().toString()));
        return result;
    }

    private static void preservedNonSystemMessages(JSONArray before, JSONArray after, int offset) {
        for (int i = 0; i < before.length(); i++) {
            if (!Message.SYSTEM.equals(before.getJSONObject(i).optString("role"))) {
                check(before.getJSONObject(i).similar(after.getJSONObject(i + offset)),
                        "Request changed user, assistant or tool history at " + i);
            }
        }
    }

    private static JSONArray tools() {
        return new JSONArray().put(new JSONObject().put("type", "function").put("function",
                new JSONObject().put("name", "fixture_tool").put("description", "Local fixture")
                        .put("parameters", new JSONObject().put("type", "object")
                                .put("properties", new JSONObject()))));
    }

    private static void requestsUsageAndParsesFinalSseUsageChunk() throws Exception {
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"local \"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":41,\"completion_tokens\":9}}\n\n"
                + "data: [DONE]\n\n";
        Server server = new Server(new Response(200, "text/event-stream", body));
        try {
            final StringBuilder emitted = new StringBuilder();
            LlmClient.Reply reply = server.client(null).send(messages(), null, new LlmClient.Sink() {
                @Override public void onReasoning(String delta) { }
                @Override public void onContent(String delta) { emitted.append(delta); }
                @Override public void onToolCall(int index, String id, String name, String arguments) { }
            });
            check(reply.error == null, "SSE response failed: " + reply.error);
            check("local answer".equals(reply.content), "SSE content was lost");
            check(reply.content.equals(emitted.toString()), "Usage chunk changed emitted content");
            check(reply.promptTokens == 41 && reply.completionTokens == 9,
                    "Final choices[] usage chunk was ignored");
            JSONObject options = server.request(0).optJSONObject("stream_options");
            check(options != null && options.optBoolean("include_usage"),
                    "Default stream request does not ask for usage");
            server.exhausted();
        } finally {
            server.stop();
        }
    }

    private static void fallbackPreservesRequestAndCachesUnsupportedOption(String rejection)
            throws Exception {
        Server server = new Server(error(rejection), jsonSuccess("fallback"), jsonSuccess("cached"));
        try {
            LlmClient client = server.client("high");
            List<Message> messages = messages();
            JSONArray tools = tools();
            LlmClient.Reply first = client.send(messages, tools, null);
            check(first.error == null && "fallback".equals(first.content),
                    "Unsupported usage option did not recover: " + first.error);
            check(first.promptTokens == 23 && first.completionTokens == 7,
                    "Fallback JSON usage was ignored");
            JSONObject original = server.request(0);
            JSONObject fallback = server.request(1);
            check(original.optJSONObject("stream_options").optBoolean("include_usage"),
                    "Initial request did not include usage");
            check(!fallback.has("stream_options"), "Fallback still includes stream_options");
            check("fixture-model".equals(fallback.optString("model")), "Fallback lost model");
            check(fallback.optBoolean("stream"), "Fallback stopped streaming");
            check(fallback.optInt("max_tokens") == 4096, "Fallback lost max_tokens");
            check("high".equals(fallback.optString("reasoning_effort")), "Fallback lost reasoning effort");
            check("auto".equals(fallback.optString("tool_choice")), "Fallback lost tool choice");
            check(original.getJSONArray("messages").similar(fallback.getJSONArray("messages")),
                    "Fallback changed messages or prior reasoning");
            check(original.getJSONArray("tools").similar(fallback.getJSONArray("tools")),
                    "Fallback changed tool definitions");
            LlmClient.Reply second = client.send(messages, tools, null);
            check(second.error == null && "cached".equals(second.content), "Cached request failed");
            check(!server.request(2).has("stream_options"), "Unsupported usage capability was not cached");
            server.exhausted();
        } finally {
            server.stop();
        }
    }

    private static void unsupportedStreamOptionsFallsBackAndCaches() throws Exception {
        fallbackPreservesRequestAndCachesUnsupportedOption("stream_options is unsupported");
    }

    private static void unknownIncludeUsageFallsBackAndCaches() throws Exception {
        fallbackPreservesRequestAndCachesUnsupportedOption("Unknown parameter: include_usage");
    }

    private static void fallbackRetriesAtMostOnce() throws Exception {
        Server server = new Server(error("stream_options is unsupported"),
                error("stream_options is unsupported"));
        try {
            LlmClient.Reply reply = server.client(null).send(messages(), null, null);
            check(reply.error != null && reply.error.startsWith("HTTP 400:"),
                    "Failed fallback error was lost");
            check(!server.request(1).has("stream_options"), "Retry retained rejected option");
            server.exhausted();
        } finally {
            server.stop();
        }
    }

    private static void ordinaryBadRequestDoesNotRetry() throws Exception {
        Server server = new Server(error("invalid_api_key"));
        try {
            LlmClient.Reply reply = server.client(null).send(messages(), null, null);
            check(reply.error != null && reply.error.contains("invalid_api_key"),
                    "Ordinary HTTP 400 error was lost");
            server.exhausted();
        } finally {
            server.stop();
        }
    }

    private static void splitUsageKeepsIndependentMaximums() {
        LlmClient.Reply reply = new LlmClient.Reply();
        reply.applyUsage(null);
        reply.applyUsage(new JSONObject().put("prompt_tokens", 120));
        reply.applyUsage(new JSONObject().put("completion_tokens", 17));
        reply.applyUsage(new JSONObject().put("prompt_tokens", 90).put("completion_tokens", 8));
        check(reply.promptTokens == 120 && reply.completionTokens == 17,
                "Partial or smaller usage reduced previous counts");
        reply.applyUsage(new JSONObject().put("prompt_tokens", 130));
        reply.applyUsage(new JSONObject().put("completion_tokens", 20));
        check(reply.promptTokens == 130 && reply.completionTokens == 20,
                "Split usage did not independently advance counts");
    }

    private static void defaultAndInvalidVerbosityAreOmitted() throws Exception {
        Server server = new Server(jsonSuccess("unset"), jsonSuccess("default"), jsonSuccess("invalid"));
        try {
            for (String verbosity : new String[]{null, "default", "not-a-detail"}) {
                LlmClient.Reply reply = server.client(null, verbosity, null).send(messages(), null, null);
                check(reply.error == null, "Default verbosity request failed: " + reply.error);
            }
            for (int i = 0; i < 3; i++) {
                check(!server.request(i).has("verbosity"), "Default or invalid verbosity was sent");
                check(server.request(i).optJSONObject("stream_options").optBoolean("include_usage"),
                        "Default detail request lost usage reporting");
            }
            server.exhausted();
        } finally { server.stop(); }
    }

    private static void chosenVerbosityAndMandatoryLanguageReachWire() throws Exception {
        Server server = new Server(jsonSuccess("low"), jsonSuccess("medium"), jsonSuccess("high"));
        try {
            List<Message> history = toolHistory();
            JSONArray before = serialized(history);
            String[] details = {"low", "medium", "high"};
            for (int i = 0; i < details.length; i++) {
                String rules = ResponsePreferences.instructions(details[i], "zh-CN");
                LlmClient.Reply reply = server.client("high", details[i], rules).send(history, tools(), null);
                check(reply.error == null, "Chosen verbosity request failed: " + reply.error);
                JSONObject request = server.request(i);
                check(details[i].equals(request.optString("verbosity")), "Chosen verbosity was not transmitted");
                JSONArray wire = request.getJSONArray("messages");
                String system = wire.getJSONObject(0).getString("content");
                check(system.startsWith(history.get(0).content) && system.endsWith(rules),
                        "Strong language policy does not follow the conflicting editable prompt");
                preservedNonSystemMessages(before, wire, 0);
                check(before.similar(serialized(history)), "Policy injection mutated caller's history");
                check(request.getJSONArray("tools").similar(tools()), "Preference injection changed tools");
            }
            server.exhausted();
        } finally { server.stop(); }
    }

    private static void languageRulesAreAddedWhenHistoryHasNoSystemMessage() throws Exception {
        Server server = new Server(jsonSuccess("中文"));
        try {
            List<Message> history = Arrays.asList(Message.user("request without system"),
                    Message.assistant("older answer", null));
            JSONArray before = serialized(history);
            String rules = ResponsePreferences.instructions("default", "zh-CN");
            LlmClient.Reply reply = server.client(null, "default", rules).send(history, null, null);
            check(reply.error == null, "No-system policy request failed");
            JSONArray wire = server.request(0).getJSONArray("messages");
            check(wire.length() == before.length() + 1, "Missing synthesized system policy message");
            check(Message.SYSTEM.equals(wire.getJSONObject(0).getString("role"))
                    && rules.equals(wire.getJSONObject(0).getString("content")), "Policy system message is incorrect");
            preservedNonSystemMessages(before, wire, 1);
            check(before.similar(serialized(history)), "Synthesized policy changed caller history");
            server.exhausted();
        } finally { server.stop(); }
    }

    private static void existingPolicyIsNotDuplicated() throws Exception {
        Server server = new Server(jsonSuccess("answer"));
        try {
            String rules = ResponsePreferences.instructions("medium", "en");
            List<Message> history = Arrays.asList(Message.system("system fixture\n\n" + rules), Message.user("request"));
            JSONArray before = serialized(history);
            LlmClient.Reply reply = server.client(null, "medium", rules).send(history, null, null);
            check(reply.error == null, "Existing-policy request failed");
            check(before.similar(server.request(0).getJSONArray("messages")), "Existing policy was duplicated");
            server.exhausted();
        } finally { server.stop(); }
    }

    private static void unsupportedVerbosityFallsBackAndCachesWithoutLosingPolicy() throws Exception {
        Server server = new Server(error("Unknown parameter: verbosity"), jsonSuccess("fallback"), jsonSuccess("cached"));
        try {
            String rules = ResponsePreferences.instructions("low", "zh-CN");
            LlmClient client = server.client("high", "low", rules);
            List<Message> history = toolHistory();
            JSONArray before = serialized(history);
            LlmClient.Reply first = client.send(history, tools(), null);
            check(first.error == null && first.promptTokens == 23 && first.completionTokens == 7,
                    "Verbosity fallback lost response or usage");
            check("low".equals(server.request(0).optString("verbosity")), "Initial request omitted chosen detail");
            check(!server.request(1).has("verbosity"), "Fallback retained rejected verbosity");
            check(server.request(1).optJSONObject("stream_options").optBoolean("include_usage"),
                    "Verbosity fallback disabled supported usage option");
            check("high".equals(server.request(1).optString("reasoning_effort")), "Fallback lost reasoning effort");
            check(server.request(0).getJSONArray("messages").similar(server.request(1).getJSONArray("messages")),
                    "Fallback changed language rules or history");
            check(server.request(0).getJSONArray("tools").similar(server.request(1).getJSONArray("tools")),
                    "Fallback changed tool definitions");
            check(server.request(1).getJSONArray("messages").getJSONObject(0).getString("content").endsWith(rules),
                    "Language and prompt detail rules disappeared in fallback");
            LlmClient.Reply second = client.send(history, tools(), null);
            check(second.error == null, "Cached verbosity request failed");
            check(!server.request(2).has("verbosity"), "Unsupported verbosity capability was not cached");
            check(server.request(2).getJSONArray("messages").similar(server.request(1).getJSONArray("messages")),
                    "Cached request dropped response policy");
            check(before.similar(serialized(history)), "Fallback or cached request mutated original history");
            server.exhausted();
        } finally { server.stop(); }
    }

    private static void sequentialFallbacks(boolean usageFirst) throws Exception {
        Server server = new Server(error(usageFirst ? "stream_options is unsupported" : "verbosity is unsupported"),
                error(usageFirst ? "verbosity is unsupported" : "Unknown parameter: include_usage"),
                jsonSuccess("fallback"), jsonSuccess("cached"));
        try {
            String rules = ResponsePreferences.instructions("high", "ja");
            LlmClient client = server.client("medium", "high", rules);
            LlmClient.Reply reply = client.send(toolHistory(), tools(), null);
            check(reply.error == null && reply.promptTokens == 23, "Sequential optional fallback failed");
            check(server.request(0).has("verbosity") && server.request(0).has("stream_options"),
                    "Original request did not carry both optional features");
            check(server.request(1).has("verbosity") == usageFirst
                    && server.request(1).has("stream_options") != usageFirst, "First fallback removed the wrong feature");
            check(!server.request(2).has("verbosity") && !server.request(2).has("stream_options"),
                    "Second fallback retained a rejected feature");
            for (int i = 1; i <= 2; i++) {
                check(server.request(0).getJSONArray("messages").similar(server.request(i).getJSONArray("messages")),
                        "Sequential fallback changed required language policy");
                check(server.request(0).getJSONArray("tools").similar(server.request(i).getJSONArray("tools")),
                        "Sequential fallback changed tool definitions");
            }
            check(client.send(toolHistory(), tools(), null).error == null, "Both-feature cached request failed");
            check(!server.request(3).has("verbosity") && !server.request(3).has("stream_options"),
                    "Both unsupported features were not cached");
            server.exhausted();
        } finally { server.stop(); }
    }

    private static void usageThenVerbosityFallsBackWithinThreeRequests() throws Exception {
        sequentialFallbacks(true);
    }

    private static void verbosityThenUsageFallsBackWithinThreeRequests() throws Exception {
        sequentialFallbacks(false);
    }

    private static void rejectedVerbosityRetriesAtMostOnce() throws Exception {
        Server server = new Server(error("verbosity is unsupported"), error("verbosity is unsupported"));
        try {
            LlmClient.Reply reply = server.client(null, "high", ResponsePreferences.instructions("high", "en"))
                    .send(messages(), null, null);
            check(reply.error != null && reply.error.startsWith("HTTP 400:"), "Repeated rejection error disappeared");
            check(!server.request(1).has("verbosity"), "Verbosity retry retained unsupported field");
            server.exhausted();
        } finally { server.stop(); }
    }

    private static void unrelatedVerbosityErrorDoesNotRetry() throws Exception {
        Server server = new Server(error("invalid_api_key for request with verbosity"));
        try {
            LlmClient.Reply reply = server.client(null, "medium", ResponsePreferences.instructions("medium", "en"))
                    .send(messages(), null, null);
            check(reply.error != null && reply.error.contains("invalid_api_key"), "Unrelated error disappeared");
            server.exhausted();
        } finally { server.stop(); }
    }

    private static void cancellationBeforeAttemptDoesNotStartHttp() throws Exception {
        Server server = new Server(jsonSuccess("next valid request"));
        try {
            LlmClient client = server.client(null);
            final int[] checks = new int[1];
            LlmClient.Reply cancelled = client.sendIfCurrent(messages(), null, null, new LlmClient.RequestValidity() {
                @Override public boolean isCurrent() { return ++checks[0] == 1; }
            });
            check(checks[0] == 2, "Request validity was not checked after registering its attempt");
            check(cancelled.content.length() == 0 && cancelled.error == null,
                    "Cancelled request produced a reply or transport error");
            check(server.requests.isEmpty(), "Cancellation before attempt registration still started HTTP");
            LlmClient.Reply next = client.send(messages(), null, null);
            check(next.error == null && "next valid request".equals(next.content),
                    "Cancelled request validity leaked into the next request");
            server.exhausted();
        } finally { server.stop(); }
    }

    private static void run(String name) {
        try {
            LlmUsageRegressionTest.class.getDeclaredMethod(name).invoke(null);
            System.out.println("PASS " + name);
        } catch (Exception error) {
            failures++;
            Throwable cause = error.getCause() == null ? error : error.getCause();
            System.out.println("FAIL " + name + ": " + cause);
        }
    }

    public static void main(String[] args) {
        String[] tests = { "requestsUsageAndParsesFinalSseUsageChunk",
                "unsupportedStreamOptionsFallsBackAndCaches", "unknownIncludeUsageFallsBackAndCaches",
                "fallbackRetriesAtMostOnce", "ordinaryBadRequestDoesNotRetry",
                "splitUsageKeepsIndependentMaximums", "defaultAndInvalidVerbosityAreOmitted",
                "chosenVerbosityAndMandatoryLanguageReachWire", "languageRulesAreAddedWhenHistoryHasNoSystemMessage",
                "existingPolicyIsNotDuplicated", "unsupportedVerbosityFallsBackAndCachesWithoutLosingPolicy",
                "usageThenVerbosityFallsBackWithinThreeRequests", "verbosityThenUsageFallsBackWithinThreeRequests",
                "rejectedVerbosityRetriesAtMostOnce", "unrelatedVerbosityErrorDoesNotRetry",
                "cancellationBeforeAttemptDoesNotStartHttp" };
        for (String name : tests) run(name);
        if (failures != 0) throw new AssertionError(failures + " usage tests failed");
        System.out.println(tests.length + " usage tests passed");
    }
}
