import com.mkei.backcast.agent.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Executes privacy bounds and independent loop/checkpoint diagnostic channels. */
public final class DiagnosticsRegressionTest {
    private static int passed;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }

    private static class Store implements AgentLoop.Recorder, AgentLoop.DetailedRequestRecorder, AgentLoop.ErrorRecorder {
        final List<Message> messages = new ArrayList<Message>();
        final List<String> requests = new ArrayList<String>(), errors = new ArrayList<String>();
        boolean fail;
        @Override public void record(long sid, Message message) { messages.add(message); }
        @Override public void replace(long sid, List<Message> messages) { }
        @Override public void recordRequest(long sid, String p, long ms, String o, String r, int retry, String detail) {
            if (fail) throw new IllegalStateException("full store");
            requests.add(detail);
        }
        @Override public void recordDiagnostic(long sid, String s, String summary, String detail) {
            if (fail) throw new IllegalStateException("full store");
            errors.add(s + ":" + detail);
        }
    }
    private static AgentLoop loop(Store store, ToolRegistry tools, LlmClient.Reply... replies) {
        LlmClient client = new LlmClient(new LlmClient.Config("http://localhost", "secret", "fixture")) {
            int at;
            @Override public Reply send(List<Message> messages, JSONArray schema, Sink sink) {
                if (at >= replies.length) throw new AssertionError("Unexpected repeated request");
                return replies[at++];
            }
        };
        AgentLoop loop = new AgentLoop(client, tools, new AgentLoop.Quiet());
        loop.bindSession(7); loop.reset("private system prompt"); loop.setRecorder(store); return loop;
    }
    private static LlmClient.Reply reply(String content, String error) {
        LlmClient.Reply r = new LlmClient.Reply(); r.content = content; r.error = error; return r;
    }
    private static void scrubsExactSecretsAndCredentials() {
        String prompt = "中文需求\n带\"引号\"";
        String raw = "Bearer abc api_key=hidden xai-abcdefgh123 https://user:pass@example.com/v1?key=leak "
                + prompt + " " + JSONObject.quote(prompt);
        String safe = Diagnostics.scrub(raw, prompt);
        check(!safe.contains("hidden") && !safe.contains("abc") && !safe.contains("abcdefgh")
                && !safe.contains("user:pass") && !safe.contains("leak") && !safe.contains("中文需求"), "Credential/prompt escaped redaction failed");
        check(safe.contains("https://example.com/v1"), "Redaction removed useful endpoint");
        pass("scrubsExactSecretsAndCredentials");
    }
    private static void structuredProviderErrorsStayUseful() throws Exception {
        JSONObject error = new JSONObject().put("error", new JSONObject().put("code", "invalid_parameter")
                .put("param", "reasoning_effort").put("message", "unsupported").put("api_key", "hidden")
                .put("messages", new JSONArray().put("private prompt")));
        JSONObject safe = new JSONObject(Diagnostics.boundedJson(new JSONObject().put("http_status", 400)
                .put("provider_error", error.toString()).put("request_id", "req123")));
        check(safe.getInt("http_status") == 400 && safe.getString("request_id").equals("req123")
                && safe.getJSONObject("provider_error").getJSONObject("error").getString("param").equals("reasoning_effort"), "Useful diagnosis lost");
        check(!safe.toString().contains("private prompt") && !safe.toString().contains("hidden"), "Structured response leaked secrets");
        pass("structuredProviderErrorsStayUseful");
    }
    private static void providerObjectKeysAreAlsoRedacted() throws Exception {
        String key = "arbitrary-provider-credential", prompt = "用户的私有需求";
        JSONObject response = new JSONObject().put("error", new JSONObject().put("details",
                new JSONObject().put(key, "value").put(prompt, "echo")));
        String safe = Diagnostics.boundedJson(new JSONObject().put("http_status", 400)
                .put("provider_error", response.toString()), key, prompt);
        check(!safe.contains(key) && !safe.contains(prompt) && new JSONObject(safe).getInt("http_status") == 400,
                "Provider object key exposed a secret or discarded status evidence");
        safe = Diagnostics.boundedJson(new JSONObject().put("provider_error", new JSONArray().put(response).toString()), key, prompt);
        check(!safe.contains(key) && !safe.contains(prompt) && new JSONObject(safe).getJSONArray("provider_error").length() == 1,
                "Provider array wrapper bypassed structured redaction");
        pass("providerObjectKeysAreAlsoRedacted");
    }
    private static void hugeDiagnosticsRemainValidAndBounded() throws Exception {
        JSONObject enormous = new JSONObject(); JSONArray frames = new JSONArray();
        String text = new String(new char[20000]).replace('\0', '\u0001');
        for (int i = 0; i < 300; i++) frames.put(text);
        enormous.put("stack", frames).put("provider_error", text);
        String bounded = Diagnostics.boundedJson(enormous);
        check(bounded.length() <= 8192 && new JSONObject(bounded).length() > 0, "Log cap corrupted JSON or failed on escaping");
        check(Diagnostics.detail("plain provider failure").contains("plain provider failure"), "Plain tool evidence disappeared");
        pass("hugeDiagnosticsRemainValidAndBounded");
    }
    private static void throwableEvidenceOmitsExceptionPayloads() {
        Throwable error = new IllegalStateException("Bearer top-secret private request", new java.io.IOException("secret body"));
        String safe = Diagnostics.boundedJson(Diagnostics.failure(error));
        check(safe.contains("IllegalStateException") && safe.contains("IOException") && safe.contains("stack")
                && !safe.contains("top-secret") && !safe.contains("secret body"), "Exception diagnostic dumped provider/request text");
        pass("throwableEvidenceOmitsExceptionPayloads");
    }
    private static void detailedRequestIsRecordedOnceOutsideHistory() throws Exception {
        Store store = new Store(); LlmClient.Reply error = reply("", "HTTP 400: bad input");
        error.diagnostic = new JSONObject().put("http_status", 400).put("stage", "http_error");
        AgentLoop loop = loop(store, new ToolRegistry(), error); loop.submit("inspect", 7, loop.generation(), 1);
        check(store.requests.size() == 1 && new JSONObject(store.requests.get(0)).getInt("http_status") == 400
                && store.errors.size() == 1 && store.messages.size() == 1, "Request diagnostic duplicated/lost/polluted history");
        pass("detailedRequestIsRecordedOnceOutsideHistory");
    }
    private static void diagnosticFailuresKeepValidReplies() {
        Store store = new Store(); store.fail = true; AgentLoop loop = loop(store, new ToolRegistry(), reply("done", null));
        loop.submit("inspect", 7, loop.generation(), 1);
        check(store.messages.size() == 2 && store.messages.get(1).content.equals("done"), "Unavailable diagnostic database discarded response");
        pass("diagnosticFailuresKeepValidReplies");
    }
    private static void childCheckpointAndDiagnosticsAreIndependent() {
        Store checkpoint = new Store(), diagnostics = new Store();
        AgentLoop loop = loop(checkpoint, new ToolRegistry(), reply("done", null));
        loop.setDiagnosticRecorder(diagnostics, diagnostics);
        loop.submit("inspect", 7, loop.generation(), 1);
        check(checkpoint.messages.size() == 2 && checkpoint.requests.isEmpty() && diagnostics.requests.size() == 1
                && diagnostics.messages.isEmpty(), "Child checkpoint displaced request diagnostics");
        pass("childCheckpointAndDiagnosticsAreIndependent");
    }
    private static void toolFailuresEnterDedicatedDiagnostics() throws Exception {
        Store store = new Store(); ToolRegistry tools = new ToolRegistry();
        tools.register(new Tool() {
            public String name() { return "shell"; }
            public String description() { return "fixture"; }
            public JSONObject parameters() { return new JSONObject(); }
            public String run(JSONObject args) { return "exit=1\nmissing executable"; }
            public void abort() { }
        });
        LlmClient.Reply call = reply("", null);
        call.toolCalls = new JSONArray().put(new JSONObject().put("id", "c1").put("type", "function")
                .put("function", new JSONObject().put("name", "shell").put("arguments", "{}")));
        AgentLoop loop = loop(store, tools, call, reply("failed", null)); loop.submit("inspect", 7, loop.generation(), 1);
        check(store.errors.size() == 1 && store.errors.get(0).contains("tool:shell:")
                && store.errors.get(0).contains("missing executable") && store.errors.get(0).contains("c1"), "Tool failure has no queryable local evidence");
        check(store.messages.size() == 4 && Message.TOOL.equals(store.messages.get(2).role), "Diagnostics changed model tool protocol");
        pass("toolFailuresEnterDedicatedDiagnostics");
    }
    private static void structuredToolFailuresEnterDedicatedDiagnostics() throws Exception {
        String[] names = {"toolkit", "toolkit", "toolkit", "mcp_fixture_tool"};
        String[] outputs = {"{\"state\":\"error\",\"error\":\"missing executable\"}",
                "{\"state\":\"cancelled\"}", "{\"success\":false,\"output\":\"failed\"}",
                "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"failed\"}]}"};
        for (int i = 0; i < names.length; i++) {
            final String name = names[i], output = outputs[i];
            check(ToolOutcome.failed(name, output), "Structured error was treated as successful progress");
            Store store = new Store(); ToolRegistry tools = new ToolRegistry();
            tools.register(new Tool() {
                public String name() { return name; }
                public String description() { return "fixture"; }
                public JSONObject parameters() { return new JSONObject(); }
                public String run(JSONObject args) { return output; }
                public void abort() { }
            });
            LlmClient.Reply call = reply("", null);
            call.toolCalls = new JSONArray().put(new JSONObject().put("id", "structured")
                    .put("type", "function").put("function", new JSONObject().put("name", name).put("arguments", "{}")));
            AgentLoop loop = loop(store, tools, call, reply("failed", null)); loop.submit("inspect", 7, loop.generation(), 1);
            check(store.errors.size() == 1 && store.errors.get(0).contains("tool:" + name)
                    && store.errors.get(0).contains("structured"), "Structured failure missing queryable database diagnostic");
            check(store.messages.get(2).content.equals(output), "Model lost exact structured tool feedback");
        }
        check(!ToolOutcome.failed("read", "{\"state\":\"error\"}")
                && !ToolOutcome.failed("find_files", "{\"complete\":false,\"matches\":[]}")
                && !ToolOutcome.failed("toolkit", "{\"state\":\"ready\",\"success\":true}")
                && !ToolOutcome.failed("mcp_fixture_tool", "{\"isError\":false,\"content\":[]}"),
                "Normal file/search/success JSON mistaken for execution failure");
        pass("structuredToolFailuresEnterDedicatedDiagnostics");
    }
    public static void main(String[] args) throws Exception {
        scrubsExactSecretsAndCredentials(); structuredProviderErrorsStayUseful(); providerObjectKeysAreAlsoRedacted(); hugeDiagnosticsRemainValidAndBounded();
        throwableEvidenceOmitsExceptionPayloads(); detailedRequestIsRecordedOnceOutsideHistory(); diagnosticFailuresKeepValidReplies();
        childCheckpointAndDiagnosticsAreIndependent(); toolFailuresEnterDedicatedDiagnostics(); structuredToolFailuresEnterDedicatedDiagnostics();
        System.out.println(passed + " diagnostic storage tests passed");
    }
}
