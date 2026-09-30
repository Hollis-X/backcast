import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.ReasoningSummary;
import com.mkei.backcast.ui.TurnTrace;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises summary preferences while preserving the transport's original reasoning. */
public final class SummaryPreferencesRegressionTest {
    private static int passed;
    private static ClassLoader fixtures;
    private static Class<?> notesType, settingsType, storeType, pieceType, clientType, handlerType;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String repeat(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append('x');
        return text.toString();
    }

    private static void modesChangePromptAndLimits() throws Exception {
        JSONArray input = new JSONArray();
        for (int i = 0; i < 6; i++) {
            JSONObject item = new JSONObject();
            item.put("title", repeat(90));
            item.put("text", repeat(900));
            input.put(item);
        }
        JSONArray concise = new JSONArray(ReasoningSummary.validate(input.toString(), "concise"));
        JSONArray detailed = new JSONArray(ReasoningSummary.validate(input.toString(), "detailed"));
        JSONArray automatic = new JSONArray(ReasoningSummary.validate(input.toString(), "auto"));
        check(concise.length() == 1 && detailed.length() == 5 && automatic.length() == 3,
                "Summary mode did not bound the number of entries");
        check(concise.getJSONObject(0).getString("text").length() < automatic.getJSONObject(0).getString("text").length()
                && automatic.getJSONObject(0).getString("text").length() < detailed.getJSONObject(0).getString("text").length(),
                "Detailed summaries are no longer than concise summaries");
        check(ReasoningSummary.tokenLimit("concise") < ReasoningSummary.tokenLimit("auto")
                && ReasoningSummary.tokenLimit("auto") < ReasoningSummary.tokenLimit("detailed"),
                "Summary request budgets do not follow the selected detail");
        check(ReasoningSummary.prompt("concise", "en").contains("one short object")
                && ReasoningSummary.prompt("auto", "en").contains("Choose an appropriate level"),
                "Selected summary mode is missing from the model's prompt");
    }

    private static void languageReachesSummaryRequest() {
        List<Message> request = ReasoningSummary.request("untrusted source", true, "detailed", "ja");
        check(request.get(0).content.contains("Japanese (ja)")
                && request.get(0).content.contains("hard constraint"), "Summary language was not strongly constrained");
        check(request.get(0).content.contains("private reasoning steps"), "Detailed mode requests private reasoning");
        check(request.get(1).content.contains("<source>\nuntrusted source\n</source>"),
                "Summary source was promoted to instructions");
        check(ReasoningSummary.PROMPT.equals(ReasoningSummary.prompt("auto", "zh-CN")),
                "Legacy summary prompt no longer uses the default preferences");
    }

    private static void noneProducesNoSummaryRequest() {
        check(ReasoningSummary.request("original reasoning", true, "none", "en").isEmpty(),
                "None mode still constructs a summary request");
    }

    private static void hiddenReasoningPreservesToolsAndRawText() {
        TurnTrace trace = new TurnTrace();
        trace.appendThink("original reasoning");
        trace.sealThink();
        TurnTrace.Range thought = new TurnTrace.Range(trace, 0);
        thought.end = 1;
        trace.addStep("read_1", "read", "{}");
        TurnTrace.Range mixed = new TurnTrace.Range(trace, 0);
        mixed.end = trace.order.size();
        trace.showReasoning = false;
        check(!thought.hasDetail() && thought.caption().length() == 0,
                "None mode leaves a visible reasoning detail row");
        check(mixed.hasDetail() && mixed.caption().contains("工具") && !mixed.caption().contains("思考"),
                "None mode hides tools or exposes a reasoning caption");
        check("original reasoning".equals(trace.reasoning.toString()) && trace.order.size() == 2,
                "UI preferences removed the original reasoning or chronology");
        trace.showReasoning = true;
        check(thought.hasDetail() && thought.caption().contains("思考"), "Reasoning cannot be shown again");
    }

    private static Object get(Object object, String name) throws Exception {
        return object.getClass().getField(name).get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        object.getClass().getField(name).set(object, value);
    }

    private static Object[] state(String mode, String language) throws Exception {
        Object settings = settingsType.getConstructor().newInstance();
        set(settings, "mode", mode);
        set(settings, "language", language);
        Object store = storeType.getConstructor().newInstance();
        Object notes = notesType.getConstructor(settingsType, storeType).newInstance(settings, store);
        Object piece = pieceType.getConstructor().newInstance();
        set(piece, "think", new StringBuilder("transport reasoning"));
        set(piece, "sealed", Boolean.TRUE);
        return new Object[]{settings, store, notes, piece};
    }

    private static void request(Object[] state) throws Exception {
        notesType.getMethod("request", pieceType, Runnable.class).invoke(state[2], state[3], new Runnable() {
            public void run() { }
        });
    }

    private static void waitAndDrain() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Method drain = handlerType.getMethod("drain");
        while (System.nanoTime() < deadline) {
            if (((Integer) handlerType.getMethod("pending").invoke(null)).intValue() > 0) {
                drain.invoke(null);
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Summary callback did not finish");
    }

    private static void notesNoneDoesNotSend() throws Exception {
        int before = clientType.getField("calls").getInt(null);
        Object[] state = state("none", "en");
        request(state);
        check(clientType.getField("calls").getInt(null) == before, "None mode sent a summary API request");
        check(!(Boolean) get(state[3], "summaryPending"), "None mode is stuck waiting for a summary");
    }

    private static void preferenceChangeClearsOldSummaryAndError() throws Exception {
        Object[] state = state("auto", "zh-CN");
        Method refresh = notesType.getMethod("refreshPreference", pieceType);
        refresh.invoke(state[2], state[3]);
        set(state[3], "summary", "old summary");
        set(state[3], "summaryError", "old error");
        set(state[3], "summaryComplete", Boolean.TRUE);
        set(state[3], "summaryPending", Boolean.TRUE);
        int version = ((Integer) get(state[3], "summaryVersion")).intValue();
        set(state[0], "mode", "detailed");
        set(state[0], "language", "en");
        check((Boolean) refresh.invoke(state[2], state[3]), "Preference change was not detected");
        check("".equals(get(state[3], "summary")) && "".equals(get(state[3], "summaryError"))
                && !(Boolean) get(state[3], "summaryComplete") && !(Boolean) get(state[3], "summaryPending"),
                "Old summary, error or completion status survived the preference change");
        check(((Integer) get(state[3], "summaryVersion")).intValue() > version
                && "transport reasoning".contentEquals((StringBuilder) get(state[3], "think")),
                "Preference change failed to invalidate callbacks or removed original reasoning");
    }

    private static void asyncResultCannotRestoreHiddenSummary() throws Exception {
        Object[] state = state("auto", "zh-CN");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        clientType.getField("started").set(null, started);
        clientType.getField("release").set(null, release);
        request(state);
        check(started.await(5, TimeUnit.SECONDS), "Summary request did not start");
        set(state[0], "mode", "none");
        notesType.getMethod("refreshPreference", pieceType).invoke(state[2], state[3]);
        release.countDown();
        waitAndDrain();
        clientType.getField("started").set(null, null);
        clientType.getField("release").set(null, null);
        check("".equals(get(state[3], "summary")) && !(Boolean) get(state[3], "summaryPending"),
                "A late result restored a summary after None was selected");
    }

    private static void cacheSeparatesModeAndLanguage() throws Exception {
        Object[] state = state("concise", "en");
        int before = clientType.getField("calls").getInt(null);
        request(state);
        waitAndDrain();
        check(((String) clientType.getField("lastPrompt").get(null)).contains("English (en)"),
                "Summary API request lost its configured output language");
        check(clientType.getField("lastTokens").getInt(null) == ReasoningSummary.tokenLimit("concise"),
                "Concise API request lost its token budget");
        set(state[0], "mode", "detailed");
        request(state);
        waitAndDrain();
        set(state[0], "language", "ja");
        request(state);
        waitAndDrain();
        check(clientType.getField("calls").getInt(null) == before + 3,
                "Cache reused a summary from another mode or language");
        set(state[0], "mode", "concise");
        set(state[0], "language", "en");
        request(state);
        waitAndDrain();
        check(clientType.getField("calls").getInt(null) == before + 3,
                "Matching summary preferences could not reuse their cache");
    }

    private static void add(List<File> files, File dir, String type, String source) throws Exception {
        File file = new File(dir, type.replace('.', '/') + ".java");
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), source.getBytes(StandardCharsets.UTF_8));
        files.add(file);
    }

    private static File compileFixtures(String root) throws Exception {
        File build = Files.createTempDirectory("backcast-summary-test").toFile();
        List<File> sources = new ArrayList<File>();
        sources.add(new File(root, "app/src/main/java/com/mkei/backcast/ReasoningNotes.java"));
        add(sources, build, "android.os.Looper", "package android.os; public class Looper { public static Looper getMainLooper(){ return new Looper(); } }");
        add(sources, build, "android.os.Handler", "package android.os; import java.util.concurrent.*; public class Handler { static final ConcurrentLinkedQueue<Runnable> queue=new ConcurrentLinkedQueue<Runnable>(); public Handler(Looper l){} public void post(Runnable r){queue.add(r);} public static int pending(){return queue.size();} public static void drain(){Runnable r;while((r=queue.poll())!=null)r.run();} }");
        add(sources, build, "com.mkei.backcast.Settings", "package com.mkei.backcast; public class Settings { public String mode=\"auto\",language=\"zh-CN\"; public String reasoningSummary(){return mode;} public String outputLanguage(){return language;} public String baseUrl(){return \"http://fixture\";} public String apiKey(){return \"fixture\";} public String model(){return \"fixture\";} public String systemPrompt(){return \"\";} public String environmentContext(){return \"\";} }");
        add(sources, build, "com.mkei.backcast.ChatStore", "package com.mkei.backcast; import java.util.*; public class ChatStore { Map<String,String> cache=new HashMap<String,String>(); public synchronized String reasoningNote(String key){return cache.containsKey(key)?cache.get(key):\"\";} public synchronized void saveReasoningNote(String key,String value){cache.put(key,value);} }");
        add(sources, build, "com.mkei.backcast.agent.LlmClient", "package com.mkei.backcast.agent; import java.util.*; import java.util.concurrent.*; public class LlmClient { public static int calls,lastTokens; public static String lastPrompt; public static CountDownLatch started,release; public static class Config { public String baseUrl,model,responseInstructions; public int maxTokens,timeoutMs,totalTimeoutMs,maxResponseChars; public Config(String b,String k,String m){baseUrl=b;model=m;} } public static class Reply { public String error,content=\"[{\\\"title\\\":\\\"Progress\\\",\\\"text\\\":\\\"Inspected the project\\\"}]\"; } private final Config config; public LlmClient(Config c){config=c;} public Reply send(List<Message> messages,Object tools,Object sink) throws Exception {calls++;lastTokens=config.maxTokens;lastPrompt=messages.get(0).content+config.responseInstructions; if(started!=null)started.countDown();if(release!=null&&!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException(\"Timeout\");return new Reply();} }");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> arguments = new ArrayList<String>();
        arguments.add("-proc:none"); arguments.add("-encoding"); arguments.add("UTF-8");
        arguments.add("-cp"); arguments.add(System.getProperty("java.class.path"));
        arguments.add("-d"); arguments.add(build.getAbsolutePath());
        for (File source : sources) arguments.add(source.getAbsolutePath());
        check(compiler.run(null, null, null, arguments.toArray(new String[0])) == 0, "Real ReasoningNotes fixture compilation failed");
        List<URL> paths = new ArrayList<URL>();
        paths.add(build.toURI().toURL());
        for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) paths.add(new File(path).toURI().toURL());
        fixtures = new URLClassLoader(paths.toArray(new URL[0]), null);
        notesType = fixtures.loadClass("com.mkei.backcast.ReasoningNotes");
        settingsType = fixtures.loadClass("com.mkei.backcast.Settings");
        storeType = fixtures.loadClass("com.mkei.backcast.ChatStore");
        pieceType = fixtures.loadClass("com.mkei.backcast.ui.TurnTrace$Piece");
        clientType = fixtures.loadClass("com.mkei.backcast.agent.LlmClient");
        handlerType = fixtures.loadClass("android.os.Handler");
        return build;
    }

    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        file.delete();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the absolute project root");
        File build = compileFixtures(args[0]);
        try {
            for (String name : new String[]{"modesChangePromptAndLimits", "languageReachesSummaryRequest",
                    "noneProducesNoSummaryRequest", "hiddenReasoningPreservesToolsAndRawText", "notesNoneDoesNotSend",
                    "preferenceChangeClearsOldSummaryAndError", "asyncResultCannotRestoreHiddenSummary", "cacheSeparatesModeAndLanguage"}) {
                SummaryPreferencesRegressionTest.class.getDeclaredMethod(name).invoke(null);
                passed++;
            }
            System.out.println("Summary preferences regression tests passed: " + passed);
        } finally {
            ((URLClassLoader) fixtures).close();
            remove(build);
        }
    }
}
