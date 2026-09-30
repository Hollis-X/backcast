import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Runs the real RunHub against isolated lifecycle and persistence fixtures. */
public final class RunHubRecoveryTest {
    private static Class<?> hubType;
    private static Class<?> contextType;
    private static Class<?> loopType;
    private static Class<?> listenerType;
    private static Class<?> storeType;

    private static final class Source extends SimpleJavaFileObject {
        private final String text;
        Source(String name, String text) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.text = text;
        }
        @Override public CharSequence getCharContent(boolean ignoreErrors) { return text; }
    }

    private static void add(List<JavaFileObject> files, String name, String body) {
        int split = name.lastIndexOf('.');
        files.add(new Source(name, "package " + name.substring(0, split) + ";\n" + body));
    }

    private static void fixtures(List<JavaFileObject> files) {
        add(files, "android.content.Context", "public class Context { public Context getApplicationContext() { return this; } public boolean stopService(Intent i) { return true; } }");
        add(files, "android.content.Intent", "public class Intent { public Intent(Context c, Class<?> cls) {} }");
        add(files, "com.mkei.backcast.Settings", "public class Settings { public Settings(android.content.Context c) {} public boolean isConfigured() { return true; } public String fullSystemPrompt() { return \"system\"; } public String baseUrl() { return \"http://localhost\"; } public String apiKey() { return \"fixture\"; } public String model() { return \"fixture\"; } public String reasoningEffort() { return \"off\"; } public boolean useRoot() { return false; } public String workDir() { return \".\"; } public float compactRatio() { return .9f; } public String accessLevel() { return \"full\"; } }");
        add(files, "com.mkei.backcast.AgentService", "public class AgentService { public static void start(android.content.Context c) {} }");
        add(files, "com.mkei.backcast.ChatStore",
                "public class ChatStore {"
                + "public static class Run { public String goal=\"\", status=\"\"; public long elapsedMs,turnAt,turnWall,seenAt,tokensUsed,tokenBudget; public boolean running; public Boolean budgetWrapFinished; }"
                + "private static final java.util.Map<Long,Run> runs=new java.util.HashMap<Long,Run>();"
                + "public ChatStore(android.content.Context c) {}"
                + "public static void reset() { runs.clear(); }"
                + "public static void pending(long sid) { Run r=new Run(); r.running=true; r.turnAt=10; r.turnWall=20; runs.put(sid,r); }"
                + "public static void pendingBudget(long sid,Boolean finished) { pending(sid); Run r=runs.get(sid); r.goal=\"spent goal\"; r.status=\"budget_limited\"; r.budgetWrapFinished=finished; }"
                + "public Run readRun(long sid) { Run r=runs.get(sid); return r==null?new Run():r; }"
                + "public java.util.List<Long> runningIds() { java.util.List<Long> out=new java.util.ArrayList<Long>(); for(java.util.Map.Entry<Long,Run> e:runs.entrySet()) if(e.getValue().running) out.add(e.getKey()); return out; }"
                + "public java.util.List<com.mkei.backcast.agent.Message> contextMessages(long sid) { return new java.util.ArrayList<com.mkei.backcast.agent.Message>(); }"
                + "public void append(long sid,com.mkei.backcast.agent.Message m) {}"
                + "public void replaceAll(long sid,java.util.List<com.mkei.backcast.agent.Message> m) {}"
                + "public void saveRun(long sid,boolean running,String goal,String status,long ms,long at,long wall,long seen,long used,long budget,boolean budgetWrapFinished) {} }");
        add(files, "com.mkei.backcast.agent.AgentLoop",
                "public class AgentLoop {"
                + "public static final int DEFAULT_CONTEXT_LIMIT=456000;"
                + "public interface Listener {} public static class Quiet implements Listener {}"
                + "public interface Recorder { void record(long sid,Message m); void replace(long sid,java.util.List<Message> m); }"
                + "public interface Durability { void save(long sid,boolean running,String goal,String status,long ms,long at,long wall,long seen,long used,long budget,boolean budgetWrapFinished); }"
                + "private Listener listener; private long sid;"
                + "public volatile boolean busyState; public volatile int resumes; public int loads, clockRestores, cancellations;"
                + "public Boolean restoredBudgetWrapFinished;"
                + "public AgentLoop(LlmClient c,ToolRegistry r,Listener l) { listener=l; }"
                + "public void bindSession(long id) { sid=id; } public long sessionKey() { return sid; }"
                + "public boolean busy() { return busyState; }"
                + "public Listener listener() { return listener; } public void setListener(Listener l) { listener=l; }"
                + "public void loadHistory(String s,java.util.List<Message> m) { loads++; }"
                + "public void restoreGoal(String g,String s,long ms) {} public void restoreGoal(String g,String s,long ms,long used,long budget,Boolean budgetWrapFinished) { restoredBudgetWrapFinished=budgetWrapFinished; } public void restoreTurnClock(long at,long wall,long seen) { clockRestores++; }"
                + "public void resume(long id,int token) { resumes++; }"
                + "public void setRecorder(Recorder r) {} public void setDurability(Durability d) {}"
                + "public void setContextBudget(int limit,float ratio) {} public void setAccessLevel(String level) {}"
                + "public void setApprovalGate(ApprovalGate g) {} public void clearGate(ApprovalGate g) {}"
                + "public void retarget(LlmClient c,ToolRegistry r) {} public void reset(String s) {}"
                + "public void setEnvironment(String prompt,String directory) {}"
                + "public void clearGoal() {} public void cancel() { cancellations++; } public String goalText() { return \"\"; } }");
        add(files, "com.mkei.backcast.agent.ApprovalGate", "public class ApprovalGate { public static final String ACCESS_FULL=\"full\"; }");
        add(files, "com.mkei.backcast.agent.Goal", "public class Goal { public static boolean isSteer(String s) { return false; } public static boolean isNote(String s) { return false; } }");
        add(files, "com.mkei.backcast.agent.Message", "public class Message { public String content; }");
        add(files, "com.mkei.backcast.agent.LlmClient", "public class LlmClient { public static class Config { public Config(String a,String b,String c,String d) {} } public LlmClient(Config c) {} }");
        add(files, "com.mkei.backcast.agent.ToolRegistry", "public class ToolRegistry { public java.util.List<String> names=new java.util.ArrayList<String>(); public void register(Object tool) { names.add(tool.getClass().getSimpleName()); } }");
        for (String name : Arrays.asList("EditTool", "GoalTool", "GetGoalTool", "ReadTool", "ShellTool", "WriteTool")) {
            add(files, "com.mkei.backcast.tool." + name, "public class " + name + " { public " + name + "(Object... args) {} }");
        }
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, args);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static Object freshHub() throws Exception {
        Field instance = hubType.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        storeType.getMethod("reset").invoke(null);
        return hubType.getMethod("get", contextType).invoke(null, contextType.getConstructor().newInstance());
    }
    private static Object listener() {
        return Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType}, (p, m, a) -> null);
    }
    private static void pending(long sid) throws Exception {
        storeType.getMethod("pending", long.class).invoke(null, sid);
    }
    private static Object bind(Object hub, long sid, Object ui) throws Exception {
        return call(hub, "bind", new Class<?>[]{long.class, listenerType}, sid, ui);
    }
    private static Object currentListener(Object loop) throws Exception {
        return call(loop, "listener", new Class<?>[0]);
    }
    private static void busy(Object loop) throws Exception {
        loopType.getField("busyState").setBoolean(loop, true);
    }
    private static int count(Object loop, String field) throws Exception {
        return loopType.getField(field).getInt(loop);
    }
    private static void recover(Object hub) throws Exception {
        call(hub, "recover", new Class<?>[0]);
    }
    private static void awaitResume(Object loop) throws Exception {
        long deadline = System.nanoTime() + 2000000000L;
        while (count(loop, "resumes") == 0 && System.nanoTime() < deadline) Thread.sleep(5);
        check(count(loop, "resumes") == 1, "pending session was not resumed exactly once");
    }
    private static Object storedLoop(Object hub, long sid) throws Exception {
        Field field = hubType.getDeclaredField("loops");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(hub)).get(Long.valueOf(sid));
    }

    private static void activeListenerSurvives() throws Exception {
        Object hub = freshHub(), ui = listener();
        pending(1);
        Object loop = bind(hub, 1, ui);
        busy(loop);
        recover(hub);
        check(currentListener(loop) == ui, "recovery discarded the foreground listener");
        check(count(loop, "resumes") == 0, "already busy session was resumed again");
        check(count(loop, "loads") == 1, "live history was reloaded");
        check(count(loop, "clockRestores") == 1, "live clock was restored again");
    }
    private static void idleListenerSurvives() throws Exception {
        Object hub = freshHub(), ui = listener();
        pending(2);
        Object loop = bind(hub, 2, ui);
        recover(hub);
        awaitResume(loop);
        check(currentListener(loop) == ui, "idle recovery discarded the attached listener");
    }
    private static void newRecoveryIsQuiet() throws Exception {
        Object hub = freshHub();
        pending(3);
        recover(hub);
        Object loop = storedLoop(hub, 3);
        awaitResume(loop);
        check(currentListener(loop).getClass().getSimpleName().equals("Quiet"), "headless recovery attached a UI");
        check(count(loop, "clockRestores") == 1, "persisted clock was not restored");
        recover(hub);
        check(count(loop, "resumes") == 1, "recovery was not idempotent");
    }
    private static void switchedSessionsKeepOwnership() throws Exception {
        Object hub = freshHub(), ui = listener();
        pending(4);
        pending(5);
        Object first = bind(hub, 4, ui);
        busy(first);
        Object second = bind(hub, 5, ui);
        busy(second);
        Object hiddenListener = currentListener(first);
        check(hiddenListener != ui, "switching sessions left both loops attached");
        recover(hub);
        check(currentListener(first) == hiddenListener, "recovery changed hidden session ownership");
        check(currentListener(second) == ui, "recovery detached the visible session");
        check(count(first, "resumes") == 0 && count(second, "resumes") == 0, "busy sessions were duplicated");
        check(count(first, "cancellations") == 0 && count(second, "cancellations") == 0, "session switching cancelled work");
        call(hub, "detach", new Class<?>[]{listenerType}, ui);
        check(currentListener(second) != ui, "destroyed UI listener was not detached");
    }

    private static void registeredToolsMatchCurrentSet() throws Exception {
        Object hub = freshHub(), loop = bind(hub, 6, listener());
        Method tools = hubType.getDeclaredMethod("tools", loopType);
        tools.setAccessible(true);
        Object registry = tools.invoke(hub, loop);
        Object names = registry.getClass().getField("names").get(registry);
        check(names.equals(Arrays.asList("ReadTool", "ShellTool", "EditTool", "WriteTool", "GoalTool", "GetGoalTool")),
                "registry contains a removed tool or is missing a current tool");
    }

    private static void budgetWrapStateSurvivesRecovery() throws Exception {
        for (Boolean finished : Arrays.asList(Boolean.TRUE, Boolean.FALSE, null)) {
            Object hub = freshHub();
            storeType.getMethod("pendingBudget", long.class, Boolean.class).invoke(null, 7L, finished);
            Object loop = bind(hub, 7L, listener());
            Object restored = loopType.getField("restoredBudgetWrapFinished").get(loop);
            check(java.util.Objects.equals(finished, restored), "Recovery lost budget wrap-up state " + finished);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the absolute RunHub.java path");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required");
        Path output = Files.createTempDirectory("backcast-recovery-test-");
        try {
            List<JavaFileObject> files = new ArrayList<>();
            fixtures(files);
            try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
                for (JavaFileObject file : manager.getJavaFileObjects(Paths.get(args[0]).toFile())) files.add(file);
                boolean compiled = compiler.getTask(null, manager, null,
                        Arrays.asList("-encoding", "UTF-8", "-d", output.toString()), null, files).call();
                check(compiled, "RunHub fixture compilation failed");
            }
            try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()})) {
                hubType = loader.loadClass("com.mkei.backcast.RunHub");
                contextType = loader.loadClass("android.content.Context");
                loopType = loader.loadClass("com.mkei.backcast.agent.AgentLoop");
                listenerType = loader.loadClass("com.mkei.backcast.agent.AgentLoop$Listener");
                storeType = loader.loadClass("com.mkei.backcast.ChatStore");
                String[] tests = {"activeListenerSurvives", "idleListenerSurvives", "newRecoveryIsQuiet", "switchedSessionsKeepOwnership", "registeredToolsMatchCurrentSet", "budgetWrapStateSurvivesRecovery"};
                int failures = 0;
                for (String name : tests) {
                    try {
                        Method test = RunHubRecoveryTest.class.getDeclaredMethod(name);
                        test.invoke(null);
                        System.out.println("PASS " + name);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        failures++;
                        System.out.println("FAIL " + name + ": " + e.getCause());
                    }
                }
                if (failures != 0) throw new AssertionError(failures + " recovery tests failed");
                System.out.println(tests.length + " recovery and registry tests passed");
            }
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(output)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
