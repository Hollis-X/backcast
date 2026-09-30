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
        add(files, "android.content.Context", "public class Context { public Context getApplicationContext() { return this; } public java.io.File getFilesDir() { return new java.io.File(System.getProperty(\"java.io.tmpdir\")); } public boolean stopService(Intent i) { return true; } }");
        add(files, "android.content.Intent", "public class Intent { public Intent(Context c, Class<?> cls) {} }");
        add(files, "com.mkei.backcast.Settings", "public class Settings { public Settings(android.content.Context c) {} public boolean isConfigured() { return true; } public String fullSystemPrompt() { return \"system\"; } public String baseUrl() { return \"http://localhost\"; } public String apiKey() { return \"fixture\"; } public String model() { return \"fixture\"; } public String reasoningEffort() { return \"off\"; } public String outputVerbosity() { return \"default\"; } public String outputLanguage() { return \"zh-CN\"; } public String responseInstructions() { return \"language fixture\"; } public boolean useRoot() { return false; } public String workDir() { return \".\"; } public float compactRatio() { return .9f; } public String accessLevel() { return \"full\"; } }");
        add(files, "com.mkei.backcast.AgentService", "public class AgentService { public static void start(android.content.Context c) {} }");
        add(files, "com.mkei.backcast.ChatStore",
                "public class ChatStore {"
                + "public static class Run { public String goal=\"\", status=\"\"; public long elapsedMs,turnAt,turnWall,seenAt,tokensUsed,tokenBudget; public boolean running; public Boolean budgetWrapFinished; }"
                + "private static final java.util.Map<Long,Run> runs=new java.util.HashMap<Long,Run>();"
                + "public static long pausedRead=-1; public static java.util.concurrent.CountDownLatch readStarted,readRelease;"
                + "public static void pauseRead(long sid) { pausedRead=sid; readStarted=new java.util.concurrent.CountDownLatch(1); readRelease=new java.util.concurrent.CountDownLatch(1); }"
                + "public ChatStore(android.content.Context c) {}"
                + "public static void reset() { runs.clear(); pausedRead=-1; }"
                + "public static void pending(long sid) { Run r=new Run(); r.running=true; r.turnAt=10; r.turnWall=20; runs.put(sid,r); }"
                + "public static void pendingBudget(long sid,Boolean finished) { pending(sid); Run r=runs.get(sid); r.goal=\"spent goal\"; r.status=\"budget_limited\"; r.budgetWrapFinished=finished; }"
                + "public Run readRun(long sid) { Run r=runs.get(sid); return r==null?new Run():r; }"
                + "public java.util.List<Long> runningIds() { java.util.List<Long> out=new java.util.ArrayList<Long>(); for(java.util.Map.Entry<Long,Run> e:runs.entrySet()) if(e.getValue().running) out.add(e.getKey()); return out; }"
                + "public java.util.List<com.mkei.backcast.agent.Message> contextMessages(long sid) { if(pausedRead==sid) { readStarted.countDown(); try { readRelease.await(5,java.util.concurrent.TimeUnit.SECONDS); } catch(InterruptedException e) { throw new RuntimeException(e); } } return new java.util.ArrayList<com.mkei.backcast.agent.Message>(); }"
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
                + "public static long pausedLoad=-1; public static java.util.concurrent.CountDownLatch loadStarted,loadRelease;"
                + "public static void reset() { pausedLoad=-1; }"
                + "public static void pauseLoad(long sid) { pausedLoad=sid; loadStarted=new java.util.concurrent.CountDownLatch(1); loadRelease=new java.util.concurrent.CountDownLatch(1); }"
                + "public Boolean restoredBudgetWrapFinished;"
                + "public AgentLoop(LlmClient c,ToolRegistry r,Listener l) { listener=l; }"
                + "public void bindSession(long id) { sid=id; } public long sessionKey() { return sid; }"
                + "public boolean busy() { return busyState; }"
                + "public Listener listener() { return listener; } public void setListener(Listener l) { listener=l; }"
                + "public void loadHistory(String s,java.util.List<Message> m) { loads++; if(pausedLoad==sid) { loadStarted.countDown(); try { loadRelease.await(5,java.util.concurrent.TimeUnit.SECONDS); } catch(InterruptedException e) { throw new RuntimeException(e); } } }"
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
        add(files, "com.mkei.backcast.agent.LlmClient", "public class LlmClient { public static class Config { public String verbosity, responseInstructions; public Config(String a,String b,String c,String d) {} } public LlmClient(Config c) {} }");
        add(files, "com.mkei.backcast.agent.ToolRegistry", "public class ToolRegistry { public java.util.List<String> names=new java.util.ArrayList<String>(); public void register(Object tool) { names.add(tool.getClass().getSimpleName()); } }");
        for (String name : Arrays.asList("EditTool", "GoalTool", "GetGoalTool", "ReadTool", "ShellTool", "WriteTool", "TemporaryTool")) {
            add(files, "com.mkei.backcast.tool." + name, "public class " + name + " { public " + name + "(Object... args) {} }");
        }
        add(files, "com.mkei.backcast.tool.TemporaryWorkspace", "public class TemporaryWorkspace { public TemporaryWorkspace(Object... args) {} public void configure(String dir,boolean root) {} public void bindSession(long sid) {} public String cleanupRecovered() { return null; } }");
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
        loopType.getMethod("reset").invoke(null);
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
        check(names.equals(Arrays.asList("ReadTool", "ShellTool", "EditTool", "WriteTool", "TemporaryTool", "GoalTool", "GetGoalTool")),
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

    private static void preparedSessionPreservesCurrentListenerAndLoadsOnce() throws Exception {
        Object hub = freshHub(), ui = listener();
        Object visible = bind(hub, 11L, ui);
        call(hub, "prepareSession", new Class<?>[]{long.class}, 12L);
        check(currentListener(visible) == ui, "Preloading stole the current listener");
        Object prepared = bind(hub, 12L, listener());
        check(count(prepared, "loads") == 1, "Binding a prepared session reloaded full context");
        check(count(prepared, "resumes") == 0, "Preloading started a new run");
    }

    private static void sessionPreparationReleasesHubDuringDatabaseRead() throws Exception {
        final Object hub = freshHub();
        storeType.getMethod("pauseRead", long.class).invoke(null, 13L);
        final Throwable[] error = new Throwable[1];
        Thread preload = new Thread(new Runnable() {
            @Override public void run() {
                try { call(hub, "prepareSession", new Class<?>[]{long.class}, 13L); }
                catch (Throwable failure) { error[0] = failure; }
            }
        });
        preload.start();
        java.util.concurrent.CountDownLatch started = (java.util.concurrent.CountDownLatch)storeType.getField("readStarted").get(null);
        java.util.concurrent.CountDownLatch release = (java.util.concurrent.CountDownLatch)storeType.getField("readRelease").get(null);
        final java.util.concurrent.CountDownLatch responsive = new java.util.concurrent.CountDownLatch(1);
        try {
            check(started.await(2, java.util.concurrent.TimeUnit.SECONDS), "Preload did not reach database");
            Thread ui = new Thread(new Runnable() {
                @Override public void run() {
                    try { call(hub, "noteText", new Class<?>[0]); responsive.countDown(); }
                    catch (Throwable failure) { error[0] = failure; }
                }
            });
            ui.start();
            check(responsive.await(2, java.util.concurrent.TimeUnit.SECONDS), "Database preload held the hub lock against UI work");
            ui.join(1000);
        } finally {
            release.countDown(); preload.join(3000);
        }
        check(error[0] == null && !preload.isAlive(), "Background preload failed: " + error[0]);
    }

    private static void sessionPreparationReleasesHubDuringFullRestore() throws Exception {
        final Object hub = freshHub(), visibleUi = listener();
        loopType.getMethod("pauseLoad", long.class).invoke(null, 14L);
        final Throwable[] error = new Throwable[1];
        Thread preload = new Thread(new Runnable() {
            @Override public void run() {
                try { call(hub, "prepareSession", new Class<?>[]{long.class}, 14L); }
                catch (Throwable failure) { error[0] = failure; }
            }
        });
        preload.start();
        java.util.concurrent.CountDownLatch started = (java.util.concurrent.CountDownLatch)loopType.getField("loadStarted").get(null);
        java.util.concurrent.CountDownLatch release = (java.util.concurrent.CountDownLatch)loopType.getField("loadRelease").get(null);
        final java.util.concurrent.CountDownLatch responsive = new java.util.concurrent.CountDownLatch(1);
        final Object[] draft = new Object[1];
        Thread ui = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    check(call(hub, "existingSession", new Class<?>[]{long.class}, 14L) == null,
                            "Published a loop before its context finished restoring");
                    draft[0] = call(hub, "freshDraft", new Class<?>[]{listenerType}, visibleUi);
                    call(hub, "retargetIfNeeded", new Class<?>[0]);
                    call(hub, "broadcastAccess", new Class<?>[]{String.class,
                            hubType.getClassLoader().loadClass("com.mkei.backcast.agent.ApprovalGate")}, "full", null);
                    responsive.countDown();
                } catch (Throwable failure) { error[0] = failure; }
            }
        });
        try {
            check(started.await(2, java.util.concurrent.TimeUnit.SECONDS), "Preload did not reach loadHistory");
            ui.start();
            check(responsive.await(2, java.util.concurrent.TimeUnit.SECONDS), "Context restore held the hub lock against UI actions");
        } finally {
            release.countDown(); preload.join(3000); ui.join(3000);
        }
        check(error[0] == null && !preload.isAlive() && !ui.isAlive(), "Context restore failed: " + error[0]);
        Object prepared = call(hub, "existingSession", new Class<?>[]{long.class}, 14L);
        check(prepared != null && count(prepared, "loads") == 1 && currentListener(draft[0]) == visibleUi,
                "Publishing a restored session reloaded it or stole the current draft listener");
    }

    private static void concurrentBindingUsesOneRestoredSession() throws Exception {
        final Object hub = freshHub(), newUi = listener();
        loopType.getMethod("pauseLoad", long.class).invoke(null, 15L);
        final Throwable[] error = new Throwable[1];
        final Object[] bound = new Object[1];
        Thread preload = new Thread(new Runnable() {
            @Override public void run() {
                try { call(hub, "prepareSession", new Class<?>[]{long.class}, 15L); }
                catch (Throwable failure) { error[0] = failure; }
            }
        });
        preload.start();
        java.util.concurrent.CountDownLatch started = (java.util.concurrent.CountDownLatch)loopType.getField("loadStarted").get(null);
        java.util.concurrent.CountDownLatch release = (java.util.concurrent.CountDownLatch)loopType.getField("loadRelease").get(null);
        Thread binding = new Thread(new Runnable() {
            @Override public void run() {
                try { bound[0] = bind(hub, 15L, newUi); }
                catch (Throwable failure) { error[0] = failure; }
            }
        });
        try {
            check(started.await(2, java.util.concurrent.TimeUnit.SECONDS), "Preload did not reach loadHistory");
            binding.start();
            check(call(hub, "existingSession", new Class<?>[]{long.class}, 15L) == null,
                    "Same-session binding exposed incomplete state");
        } finally {
            release.countDown(); preload.join(3000); binding.join(3000);
        }
        check(error[0] == null && !preload.isAlive() && !binding.isAlive(), "Concurrent bind failed: " + error[0]);
        Object prepared = call(hub, "existingSession", new Class<?>[]{long.class}, 15L);
        check(prepared == bound[0] && count(prepared, "loads") == 1 && currentListener(prepared) == newUi,
                "Parallel initialization created a second loop or replaced its listener");
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
                String[] tests = {"activeListenerSurvives", "idleListenerSurvives", "newRecoveryIsQuiet", "switchedSessionsKeepOwnership", "registeredToolsMatchCurrentSet", "budgetWrapStateSurvivesRecovery", "preparedSessionPreservesCurrentListenerAndLoadsOnce", "sessionPreparationReleasesHubDuringDatabaseRead", "sessionPreparationReleasesHubDuringFullRestore", "concurrentBindingUsesOneRestoredSession"};
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
