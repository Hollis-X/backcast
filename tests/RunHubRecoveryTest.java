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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.json.JSONObject;

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
        add(files, "android.content.Context", "public class Context { public int serviceStops; public Context getApplicationContext() { return this; } public java.io.File getFilesDir() { return new java.io.File(System.getProperty(\"java.io.tmpdir\")); } public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(); } public boolean stopService(Intent i) { serviceStops++;return true; } }");
        add(files, "android.content.res.AssetManager", "public class AssetManager {public static String lastName;public java.io.InputStream open(String name) {lastName=name;return new java.io.ByteArrayInputStream(new byte[]{42});}}");
        add(files, "android.content.Intent", "public class Intent { public Intent(Context c, Class<?> cls) {} }");
        add(files, "android.os.Build", "public class Build {public static final String CPU_ABI=\"arm64-v8a\";public static final String[] SUPPORTED_ABIS={CPU_ABI};public static class VERSION {public static final int SDK_INT=30;}}");
        add(files, "com.mkei.backcast.Settings", "public class Settings {"
                + "public static final String AGENT_OFF=\"off\",EFFORT_ULTRA=\"ultra\";public static String mode=\"manual\",effort=\"off\",directory=\".\";"
                + "public static String provider=\"deepseek\",url=\"http://localhost\",key=\"fixture\",selectedModel=\"fixture\";public static boolean failLegacyProfileReads;"
                + "public static final class AiProfile {public final String id,name,baseUrl,apiKey,model;public final java.util.List<String> modelList;"
                + "public AiProfile(String i,String n,String u,String k,String m,java.util.List<String> l){id=i;name=n;baseUrl=u;apiKey=k;model=m;modelList=java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(l));}}"
                + "public static java.util.List<String> additional=new java.util.ArrayList<String>();"
                + "public static int concurrency=3;public static boolean root;public static void reset(){mode=\"manual\";effort=\"off\";directory=\".\";additional.clear();concurrency=3;root=false;provider=\"deepseek\";url=\"http://localhost\";key=\"fixture\";selectedModel=\"fixture\";failLegacyProfileReads=false;}"
                + "public Settings(android.content.Context c) {} public boolean isConfigured() { return true; }"
                + "public String fullSystemPrompt() { return \"system/\"+mode; } public String baseUrl() { legacyRead();return url; }"
                + "public String apiKey() {legacyRead();return key; } public String model() {legacyRead();return selectedModel; }"
                + "private void legacyRead(){if(failLegacyProfileReads)throw new AssertionError(\"Client configuration used independent credential reads\");}"
                + "public AiProfile activeAiProfile(){return new AiProfile(provider,provider,url,key,selectedModel,java.util.Collections.singletonList(selectedModel));}public java.util.List<AiProfile> aiProfiles(){return java.util.Collections.singletonList(activeAiProfile());}"
                + "public String reasoningEffort() {return effort;}public String effectiveReasoningEffort(){return effort;}"
                + "public String agentMode(){return mode;}public int agentConcurrency(){return concurrency;}"
                + "public String outputVerbosity() { return \"default\"; } public String outputLanguage() { return \"zh-CN\"; }"
                + "public String responseInstructions() { return \"language fixture\"; } public boolean useRoot() { return root; }"
                + "public String workDir() { return directory; }public java.util.List<String> authorizedWorkDirs(){java.util.List<String> result=new java.util.ArrayList<String>();result.add(directory);result.addAll(additional);return result;} public float compactRatio() { return .9f; } public String accessLevel() { return \"full\"; } }");
        add(files, "com.mkei.backcast.AgentService", "public class AgentService {public static int starts;public static void start(android.content.Context c) {starts++;} }");
        add(files, "com.mkei.backcast.ChatStore",
                "public class ChatStore {"
                + "public static class Run { public String goal=\"\", status=\"\"; public long elapsedMs,tokensUsed,tokenBudget; public Long turnElapsedMs,turnThinkMs; public boolean running; public Boolean budgetWrapFinished; }"
                + "private static final java.util.Map<Long,Run> runs=new java.util.HashMap<Long,Run>();"
                + "public static long pausedRead=-1; public static java.util.concurrent.CountDownLatch readStarted,readRelease;"
                + "public static int runSaves,clockSaves;public static java.util.Set<Long> restartOnly=new java.util.HashSet<Long>();public boolean subAgentUserRestartOnly(long sid){return restartOnly.contains(sid);}public static java.util.List<Long> childWork=new java.util.ArrayList<Long>();public java.util.List<Long> subAgentWorkSessionIds(){return new java.util.ArrayList<Long>(childWork);}"
                + "public static long requestSession,requestElapsed,diagnosticSession;public static String requestPurpose,requestOutcome,requestReason,requestDiagnostic,diagnosticSource,diagnosticSummary,diagnosticDetail;public static int requestRetry,diagnosticCalls;"
                + "public static void pauseRead(long sid) { pausedRead=sid; readStarted=new java.util.concurrent.CountDownLatch(1); readRelease=new java.util.concurrent.CountDownLatch(1); }"
                + "public ChatStore(android.content.Context c) {}"
                + "public static void reset() { runs.clear();childWork.clear();restartOnly.clear(); pausedRead=-1;runSaves=clockSaves=0;requestSession=requestElapsed=diagnosticSession=0;requestRetry=diagnosticCalls=0;requestPurpose=requestOutcome=requestReason=requestDiagnostic=diagnosticSource=diagnosticSummary=diagnosticDetail=null; }"
                + "public static void pending(long sid) { checkpoint(sid,true,5000L,null); }"
                + "public static void checkpoint(long sid,boolean running,Long elapsed,Long think) { Run r=new Run();r.running=running;r.turnElapsedMs=elapsed;r.turnThinkMs=think;runs.put(sid,r); }"
                + "public static void pendingBudget(long sid,Boolean finished) { pending(sid); Run r=runs.get(sid); r.goal=\"spent goal\"; r.status=\"budget_limited\"; r.budgetWrapFinished=finished; }"
                + "public Run readRun(long sid) { Run r=runs.get(sid); return r==null?new Run():r; }"
                + "public java.util.List<Long> runningIds() { java.util.List<Long> out=new java.util.ArrayList<Long>(); for(java.util.Map.Entry<Long,Run> e:runs.entrySet()) if(e.getValue().running) out.add(e.getKey()); return out; }"
                + "public java.util.List<com.mkei.backcast.agent.Message> contextMessages(long sid) { if(pausedRead==sid) { readStarted.countDown(); try { readRelease.await(5,java.util.concurrent.TimeUnit.SECONDS); } catch(InterruptedException e) { throw new RuntimeException(e); } } return new java.util.ArrayList<com.mkei.backcast.agent.Message>(); }"
                + "public void append(long sid,com.mkei.backcast.agent.Message m) {}"
                + "public void replaceAll(long sid,java.util.List<com.mkei.backcast.agent.Message> m) {}"
                + "public void recordRequest(long sid,String purpose,long elapsed,String outcome,String reason,int retry,String detail){requestSession=sid;requestPurpose=purpose;requestElapsed=elapsed;requestOutcome=outcome;requestReason=reason;requestRetry=retry;requestDiagnostic=detail;}"
                + "public void recordDiagnostic(long sid,String source,String summary,String detail){diagnosticSession=sid;diagnosticSource=source;diagnosticSummary=summary;diagnosticDetail=detail;diagnosticCalls++;}"
                + "public void saveRun(long sid,boolean running,String goal,String status,long ms,long elapsed,Long think,long used,long budget,boolean budgetWrapFinished) {"
                + "runSaves++;Run r=new Run();r.running=running;r.goal=goal;r.status=status;r.elapsedMs=ms;r.turnElapsedMs=elapsed;r.turnThinkMs=think;r.tokensUsed=used;r.tokenBudget=budget;r.budgetWrapFinished=budgetWrapFinished;runs.put(sid,r);}"
                + "public void saveClock(long sid,long ms,long elapsed,Long think){clockSaves++;Run r=runs.get(sid);if(r!=null&&r.running){r.elapsedMs=ms;r.turnElapsedMs=elapsed;r.turnThinkMs=think;}} }");
        add(files, "com.mkei.backcast.agent.AgentLoop",
                "public class AgentLoop {"
                + "public static final int DEFAULT_CONTEXT_LIMIT=456000;"
                + "public interface Listener {} public static class Quiet implements Listener {}"
                + "public interface Recorder { void record(long sid,Message m); void replace(long sid,java.util.List<Message> m); }"
                + "public interface DetailedRequestRecorder {void recordRequest(long sid,String purpose,long elapsed,String outcome,String reason,int retry,String diagnostic);}"
                + "public interface ErrorRecorder {void recordDiagnostic(long sid,String source,String summary,String detail);}"
                + "public interface Durability { void save(long sid,boolean running,String goal,String status,long ms,long elapsed,Long think,long used,long budget,boolean budgetWrapFinished);default void saveClock(long sid,long ms,long elapsed,Long think){} }"
                + "public interface UsageObserver{void onUsage(long tokens);}public UsageObserver usageObserver;"
                + "public LlmClient client;public ToolRegistry registry;public SubAgentManager children;public String access=\"full\",environment,directory,resetPrompt;"
                + "public int limit=DEFAULT_CONTEXT_LIMIT,retargets,externalUsage;public float ratio;public ApprovalGate gate;"
                + "private Listener listener; private long sid;"
                + "public volatile boolean busyState; public volatile int resumes; public int loads, clockRestores, cancellations;"
                + "public static long pausedLoad=-1; public static java.util.concurrent.CountDownLatch loadStarted,loadRelease;"
                + "public static void reset() { pausedLoad=-1; }"
                + "public static void pauseLoad(long sid) { pausedLoad=sid; loadStarted=new java.util.concurrent.CountDownLatch(1); loadRelease=new java.util.concurrent.CountDownLatch(1); }"
                + "public Boolean restoredBudgetWrapFinished;public Long restoredTurnElapsedMs,restoredTurnThinkMs;"
                + "public AgentLoop(LlmClient c,ToolRegistry r,Listener l) { client=c;registry=r;listener=l; }"
                + "public void bindSession(long id) { sid=id; } public long sessionKey() { return sid; }"
                + "public boolean busy() { return busyState; }"
                + "public Listener listener() { return listener; } public void setListener(Listener l) { listener=l; }"
                + "public void loadHistory(String s,java.util.List<Message> m) { loads++; if(pausedLoad==sid) { loadStarted.countDown(); try { loadRelease.await(5,java.util.concurrent.TimeUnit.SECONDS); } catch(InterruptedException e) { throw new RuntimeException(e); } } }"
                + "public void restoreGoal(String g,String s,long ms) {} public void restoreGoal(String g,String s,long ms,long used,long budget,Boolean budgetWrapFinished) { restoredBudgetWrapFinished=budgetWrapFinished; }"
                + "public void restoreTurnClock(Long elapsed,Long think) {if(loads!=1)throw new AssertionError(\"Clock restored before history\");clockRestores++;restoredTurnElapsedMs=elapsed;restoredTurnThinkMs=think;}"
                + "public void resume(long id,int token) { resumes++; }"
                + "public Recorder recorder;public Durability durability;public void setRecorder(Recorder r) {recorder=r;} public void setDurability(Durability d) {durability=d;}"
                + "public DetailedRequestRecorder diagnosticRecorder;public ErrorRecorder errorRecorder;public void setDiagnosticRecorder(DetailedRequestRecorder r,ErrorRecorder e){diagnosticRecorder=r;errorRecorder=e;}"
                + "public void setContextBudget(int l,float r) {limit=l;ratio=r;}public int contextLimit(){return limit;}"
                + "public void setAccessLevel(String level) {access=level;}public String accessLevel(){return access;}"
                + "public void setApprovalGate(ApprovalGate g) {gate=g;}public ApprovalGate approvalGate(){return gate;}"
                + "public void clearGate(ApprovalGate g) {if(gate==g)gate=null;}"
                + "public void setUsageObserver(UsageObserver o){usageObserver=o;}public long goalUsageLease(){return 7L;}"
                + "public void accountExternalUsage(long t){externalUsage+=t;}public void accountExternalUsage(long t,long lease){externalUsage+=t;}"
                + "public void setSubAgents(SubAgentManager m){children=m;}"
                + "public org.json.JSONObject captureChildContext(){return new org.json.JSONObject().put(\"client\",client.configSnapshot()).put(\"system\",environment).put(\"workspace\",directory).put(\"access\",access).put(\"contextLimit\",limit).put(\"compactRatio\",ratio);}public ToolRegistry childSourceTools(){return registry;}"
                + "public void armRecoveredChildEvents(){}public volatile boolean wakeBlocked;public java.util.concurrent.CountDownLatch wakeEntered,wakeRelease;public boolean resumeForChildEvents(long sid,int token){if(busyState||wakeBlocked)return false;busyState=true;resumes++;if(wakeEntered!=null){wakeEntered.countDown();try{wakeRelease.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}}if(children!=null)children.wakePending=false;busyState=false;return true;}"
                + "public boolean automaticDelegation;public AgentLoop delegationParent;public void setAutomaticDelegation(boolean b){automaticDelegation=b;}public void setDelegationParent(AgentLoop p){delegationParent=p;}"
                + "public void retarget(LlmClient c,ToolRegistry r) {client=c;registry=r;retargets++;} public void reset(String s) {resetPrompt=s;}"
                + "public void setEnvironment(String prompt,String dir) {environment=prompt;directory=dir;}"
                + "public void clearGoal() {} public void cancel() { cancellations++;if(children!=null)children.cancelAll(); } public String goalText() { return \"\"; } }");
        add(files, "com.mkei.backcast.agent.ApprovalGate", "public class ApprovalGate { public static final String ACCESS_FULL=\"full\",ACCESS_STRICT=\"strict\",ACCESS_GUARDED=\"guarded\"; }");
        add(files, "com.mkei.backcast.agent.Goal", "public class Goal { public static boolean isClosed(String s){return \"complete\".equals(s)||\"blocked\".equals(s);}public static boolean isSteer(String s) { return false; } public static boolean isNote(String s) { return false; } }");
        add(files, "com.mkei.backcast.agent.Message", "public class Message { public String content;public static boolean isCoordination(String content){return false;} }");
        add(files, "com.mkei.backcast.agent.LlmClient", "public class LlmClient {public Config config; public static class Config { public String verbosity,responseInstructions,effort,model,baseUrl,apiKey,providerId;public int timeoutMs=120000,maxTokens,totalTimeoutMs,maxResponseChars; public Config(String a,String b,String c){this(a,b,c,\"\");}public Config(String a,String b,String c,String d) {baseUrl=a;apiKey=b;model=c;effort=d;} } public LlmClient(Config c) {config=c;} public static String credentialFingerprint(String value){try{byte[] digest=java.security.MessageDigest.getInstance(\"SHA-256\").digest(value.getBytes(\"UTF-8\"));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(java.util.Locale.US,\"%02x\",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}public org.json.JSONObject configSnapshot(){return new org.json.JSONObject().put(\"baseUrl\",config.baseUrl).put(\"credentialFingerprint\",credentialFingerprint(config.apiKey)).put(\"model\",config.model).put(\"providerId\",config.providerId).put(\"reasoningEffort\",config.effort).put(\"verbosity\",config.verbosity).put(\"responseInstructions\",config.responseInstructions).put(\"timeoutMs\",config.timeoutMs);} }");
        add(files, "com.mkei.backcast.agent.SubAgentManager", "public class SubAgentManager {"
                + "public static final String ROOT=\"main\";public static class Record{public String id,name=\"child fixture\";public long sessionId;public boolean managerCancelled;public org.json.JSONObject forkConfig=new org.json.JSONObject();}"
                + "public interface Factory{AgentLoop create(Record task,AgentLoop.Listener listener,SubAgentManager manager) throws Exception;}"
                + "public interface ContextFactory extends Factory{org.json.JSONObject capture(AgentLoop parent) throws Exception;}"
                + "public boolean wakeAllowed,wakePending;public boolean shouldWakeRoot(){return wakeAllowed&&wakePending;}"
                + "public interface Store{}public final Factory factory;public final Store store;public AgentLoop parent;public int parallel,cancellations,resumeCalls;public void resumePending(){resumeCalls++;}"
                + "public int restrictedResumeCalls;public boolean resumeUserRestartOnlyPending(){restrictedResumeCalls++;return true;}"
                + "public interface WorkObserver{void onWorkChanged();}public WorkObserver observer;public boolean live;public void setWorkObserver(WorkObserver o){observer=o;}public boolean hasLiveWork(){return live;}public void live(boolean value){live=value;if(observer!=null)observer.onWorkChanged();}"
                + "public java.util.List<AgentLoop> loops=new java.util.ArrayList<AgentLoop>();public long usage;public String accountedId;"
                + "public SubAgentManager(int p,Factory f,Store s){parallel=p;factory=f;store=s;}"
                + "public void attachRoot(AgentLoop l){parent=l;}public void setMaxParallel(int p){parallel=p;}"
                + "public volatile int findCalls;public boolean stopped;public Record find(String id){Record r=new Record();r.id=id;r.managerCancelled=stopped;findCalls++;return r;}"
                + "public java.util.List<AgentLoop> runtimeLoops(){return new java.util.ArrayList<AgentLoop>(loops);}"
                + "public void cancelAll(){cancellations++;for(AgentLoop l:loops)l.cancel();}"
                + "public void accountUsage(String id,long t){accountedId=id;usage+=t;}"
                + "public long usageLease(String id){return 7L;}"
                + "public AgentLoop createChild(String id,long sid,AgentLoop.Listener listener)throws Exception{"
                + "Record r=new Record();r.id=id;r.sessionId=sid;r.forkConfig=((ContextFactory)factory).capture(parent);return createTaskChild(r,listener);}public AgentLoop createTaskChild(Record r,AgentLoop.Listener listener)throws Exception{AgentLoop l=factory.create(r,listener,this);l.bindSession(r.sessionId);loops.add(l);return l;} }");
        add(files, "com.mkei.backcast.SQLiteSubAgentStore", "public class SQLiteSubAgentStore implements com.mkei.backcast.agent.SubAgentManager.Store {"
                + "public static void migrateLegacySessions(ChatStore s,java.io.File d){}"
                + "public java.io.File directory;public String owner;public long sessionId;public int binds,removes;public SQLiteSubAgentStore(ChatStore store,long sid,java.io.File d){directory=d;sessionId=sid;owner=sid<0?\"draft-fixture\":\"session-\"+sid;}"
                + "public void bindSession(long sid){sessionId=sid;owner=\"session-\"+sid;binds++;}public void remove(){removes++;} }");
        add(files, "com.mkei.backcast.agent.ToolRegistry", "public class ToolRegistry { public java.util.List<String> names=new java.util.ArrayList<String>();public java.util.List<Object> tools=new java.util.ArrayList<Object>(); public void register(Object tool) { names.add(tool.getClass().getSimpleName());tools.add(tool); } }");
        for (String name : Arrays.asList("EditTool", "GoalTool", "GetGoalTool", "ReadTool", "FindFilesTool", "ShellTool", "WriteTool", "TemporaryTool", "ToolkitTool")) {
            add(files, "com.mkei.backcast.tool." + name, "public class " + name + " {public Object[] args;public int aborts; public " + name + "(Object... args) {this.args=args;}public void abort(){aborts++;} }");
        }
        add(files, "com.mkei.backcast.tool.SubAgentTools", "public class SubAgentTools {public static void register(com.mkei.backcast.agent.ToolRegistry r,com.mkei.backcast.agent.SubAgentManager m,String owner){r.register(new Coordination(m,owner));}public static class Coordination{public Object manager;public String owner;Coordination(Object m,String o){manager=m;owner=o;}} }");
        add(files, "com.mkei.backcast.mcp.McpSelection", "public final class McpSelection{}");
        add(files, "com.mkei.backcast.mcp.McpCatalog", "public class McpCatalog{public static class Server{}public static class Refresh{public Refresh(McpStore store,String id){}}public static java.util.List<Server> cached(McpStore store){return java.util.Collections.emptyList();}}");
        add(files, "com.mkei.backcast.mcp.McpStore", "public class McpStore{public java.io.File directory;public McpStore(java.io.File directory){this.directory=directory;}public void validateSelection(McpSelection selection){}}");
        add(files, "com.mkei.backcast.mcp.McpTools", "public class McpTools{public org.json.JSONObject contextSnapshot(){return new org.json.JSONObject().put(\"servers\",new org.json.JSONArray());}public static McpTools register(com.mkei.backcast.agent.ToolRegistry registry,McpStore store){McpTools source=new McpTools();registry.register(source);return source;}public static McpTools register(com.mkei.backcast.agent.ToolRegistry registry,McpStore store,org.json.JSONObject snapshot){return register(registry,store);}}");
        add(files, "android.util.Log", "public class Log {public static int w(String tag,String message){return 0;}}");
        add(files, "com.mkei.backcast.tool.ToolchainDownloader", "public class ToolchainDownloader {public static class Failure extends Exception{public String diagnostic(){return \"{}\";}}}");
        add(files, "com.mkei.backcast.tool.ToolBatchProbe", "public class ToolBatchProbe {public interface Session {ToolkitTool toolkit();void abort();void close() throws Exception;}public interface SessionFactory {Session open() throws Exception;}}");
        add(files, "com.mkei.backcast.tool.ToolkitOperationManager", "public class ToolkitOperationManager {public interface FactorySource {ToolBatchProbe.SessionFactory capture() throws Exception;}public interface Diagnostics {void onFailure(String action,String tool,String stage,Throwable failure,EmbeddedToolchain.Progress progress);}public interface WorkListener {void onWorkChanged();}public final WorkListener workListener;public boolean busyState;public ToolkitOperationManager(FactorySource source,Diagnostics diagnostics,WorkListener listener){workListener=listener;}public boolean busy(){return busyState;}}");
        add(files, "com.mkei.backcast.tool.EmbeddedToolchain", "public class EmbeddedToolchain {public static class Progress {public String stage,artifact;public long completed,total;}public interface Assets {java.io.InputStream open(String name) throws Exception;}}");
        add(files, "com.mkei.backcast.tool.ToolchainStore", "public class ToolchainStore {public java.io.File directory;public EmbeddedToolchain.Assets assets;public String abi;public int sdk;public ToolchainStore(java.io.File d,EmbeddedToolchain.Assets a,String b,int s){directory=d;assets=a;abi=b;sdk=s;}}");
        add(files, "com.mkei.backcast.tool.TemporaryWorkspace", "public class TemporaryWorkspace {public Object[] args;public long sessionId;public String directory;public boolean root;"
                + "public TemporaryWorkspace(Object... args) {this.args=args;sessionId=((Number)args[3]).longValue();}"
                + "public boolean requireTransaction;private void checkTransaction(){if(requireTransaction&&!Thread.holdsLock(this))throw new AssertionError(\"UI workspace configuration was not atomic\");}"
                + "public int begins,finishes;public void beginTurn(){checkTransaction();begins++;}public String finishTurn(){finishes++;return null;}public String finishTurn(long deadline){return finishTurn();}"
                + "public java.util.List<String> roots;public void configureWorkDirs(java.util.List<String> dirs){checkTransaction();roots=new java.util.ArrayList<String>(dirs);}"
                + "public void configure(String dir,boolean r) {checkTransaction();directory=dir;root=r;}public void bindSession(long sid) {sessionId=sid;}public String cleanupRecovered() {checkTransaction(); return null; }public String cleanupRecovered(long deadline){return cleanupRecovered();} }");
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method=target.getClass().getMethod(name, types);method.setAccessible(true);return method.invoke(target, args);
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
        hubType.getClassLoader().loadClass("com.mkei.backcast.Settings").getMethod("reset").invoke(null);
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
    private static Object field(Object target, String name) throws Exception {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass()) {
            try {Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(target);}
            catch(NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void setting(String name,Object value) throws Exception {
        hubType.getClassLoader().loadClass("com.mkei.backcast.Settings").getField(name).set(null,value);
    }
    private static Object children(Object hub,Object root) throws Exception {
        return call(hub,"subAgents",new Class[]{loopType},root);
    }
    private static Object child(Object manager,String id,long sid) throws Exception {
        return call(manager,"createChild",new Class[]{String.class,long.class,listenerType},id,sid,listener());
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
        check(count(loop, "resumes") == 1 && count(loop, "loads") == 1 && count(loop, "clockRestores") == 1,
                "Recovery duplicated work, reloaded history, or reapplied its duration checkpoint");
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
        check(names.equals(Arrays.asList("ReadTool", "FindFilesTool", "ShellTool", "EditTool", "WriteTool", "TemporaryTool", "ToolkitTool", "McpTools", "GoalTool", "GetGoalTool", "Coordination")),
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

    private static void durationCheckpointsAreRestoredAfterHistoryExactlyOnce() throws Exception {
        for (Long[] checkpoint : new Long[][]{{null, null}, {0L, null}, {0L, 0L}, {7200L, 350L}}) {
            Object hub = freshHub(), ui = listener();
            storeType.getMethod("checkpoint", long.class, boolean.class, Long.class, Long.class)
                    .invoke(null, 8L, true, checkpoint[0], checkpoint[1]);
            Object loop = bind(hub, 8L, ui);
            check(java.util.Objects.equals(checkpoint[0], field(loop, "restoredTurnElapsedMs"))
                            && java.util.Objects.equals(checkpoint[1], field(loop, "restoredTurnThinkMs"))
                            && count(loop, "loads") == 1 && count(loop, "clockRestores") == 1,
                    "Recovery lost a legacy/null, zero, or populated duration checkpoint");
            check(bind(hub, 8L, ui) == loop, "Binding replaced a restored loop");
            recover(hub); awaitResume(loop); recover(hub);
            check(count(loop, "loads") == 1 && count(loop, "clockRestores") == 1 && count(loop, "resumes") == 1,
                    "Repeated recovery reapplied durations or resumed twice");
        }
        Object hub = freshHub();
        storeType.getMethod("checkpoint", long.class, boolean.class, Long.class, Long.class)
                .invoke(null, 8L, false, 9000L, 300L);
        Object idle = bind(hub, 8L, listener());
        recover(hub);
        check(count(idle, "loads") == 1 && count(idle, "clockRestores") == 0 && count(idle, "resumes") == 0,
                "An idle run's old duration was restored into a live turn");
    }

    private static void heartbeatPersistsDurationWithoutRestartingForegroundService() throws Exception {
        Object hub = freshHub(), root = bind(hub, 9L, listener()), durability = field(root, "durability");
        Class<?> contract = loopType.getClassLoader().loadClass("com.mkei.backcast.agent.AgentLoop$Durability");
        Class<?> service = hubType.getClassLoader().loadClass("com.mkei.backcast.AgentService");
        Object app = field(hub, "app"), store = field(hub, "store");
        service.getField("starts").setInt(null, 0);
        app.getClass().getField("serviceStops").setInt(app, 0);
        Method save = contract.getMethod("save", long.class, boolean.class, String.class, String.class,
                long.class, long.class, Long.class, long.class, long.class, boolean.class);
        Method saveClock = contract.getMethod("saveClock", long.class, long.class, long.class, Long.class);
        save.invoke(durability, 9L, true, "goal", "active", 10000L, 5000L, null, 42L, 200L, true);
        for (long elapsed : new long[]{7000L, 9000L, 11000L}) saveClock.invoke(durability, 9L, elapsed + 5000L, elapsed, 0L);
        Object saved = storeType.getMethod("readRun", long.class).invoke(store, 9L);
        check(service.getField("starts").getInt(null) == 1 && app.getClass().getField("serviceStops").getInt(app) == 0
                        && storeType.getField("runSaves").getInt(null) == 1 && storeType.getField("clockSaves").getInt(null) == 3
                        && Long.valueOf(11000L).equals(field(saved, "turnElapsedMs"))
                        && Long.valueOf(0L).equals(field(saved, "turnThinkMs")) && (Long) field(saved, "elapsedMs") == 16000L
                        && (Long) field(saved, "tokensUsed") == 42L && (Long) field(saved, "tokenBudget") == 200L
                        && Boolean.TRUE.equals(field(saved, "budgetWrapFinished")) && "goal".equals(field(saved, "goal")),
                "Heartbeat restarted/stopped the service or failed to preserve run metadata");
        save.invoke(durability, 9L, false, "goal", "stopped", 17000L, 12000L, 0L, 42L, 200L, true);
        saveClock.invoke(durability, 9L, 18000L, 13000L, null);
        saved = storeType.getMethod("readRun", long.class).invoke(store, 9L);
        check(service.getField("starts").getInt(null) == 1 && app.getClass().getField("serviceStops").getInt(app) == 1
                        && Long.valueOf(12000L).equals(field(saved, "turnElapsedMs")) && !(Boolean) field(saved, "running"),
                "Late heartbeat restarted stopped work or changed its final duration");
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

    private static void managersUsePrivateSessionPathsAndFollowDraftAdoption() throws Exception {
        Object hub=freshHub(),first=bind(hub,21L,listener()),second=bind(hub,22L,listener());
        Object manager=children(hub,first),other=children(hub,second);
        check(manager!=other && field(manager,"parent")==first,"Two root sessions shared child manager ownership");
        java.io.File directory=(java.io.File)field(field(manager,"store"),"directory");
        java.io.File appFiles=(java.io.File)call(field(hub,"app"),"getFilesDir",new Class[0]);
        check(directory.equals(new java.io.File(appFiles,"sub-agents/session-21")),"Child persistence escaped the app's private session path");
        Object draft=call(hub,"freshDraft",new Class[]{listenerType},listener()),draftManager=children(hub,draft);
        Object checkpoint=field(draftManager,"store");
        check(((String)field(checkpoint,"owner")).startsWith("draft-") && field(checkpoint,"directory")==null,"A draft used another session's child checkpoint");
        call(hub,"adopt",new Class[]{loopType,long.class},draft,23L);
        check(children(hub,draft)==draftManager && (Long)call(draft,"sessionKey",new Class[0])==23L
                && "session-23".equals(field(checkpoint,"owner"))
                && (Integer)field(checkpoint,"binds")==1,"Draft adoption lost or misbound its existing child history");
        Object workspace=((Map<?,?>)field(hub,"temporary")).get(draft);
        check((Long)field(workspace,"sessionId")==23L,"Draft adoption did not rebind its temporary ledger");
    }
    private static void realChildFactoryInheritsGateConfigContextAndOwnTemporaryLedger() throws Exception {
        Object hub=freshHub();setting("mode","ultra");setting("effort","ultra");setting("directory","/work/project");setting("root",true);
        setting("provider", "grok"); setting("url", "https://provider.test/v1"); setting("key", "provider-key");
        setting("selectedModel", "provider-model"); setting("failLegacyProfileReads", true);
        Object root=bind(hub,24L,listener()),manager=children(hub,root);
        Class<?> gateType=hubType.getClassLoader().loadClass("com.mkei.backcast.agent.ApprovalGate");
        Object gate=gateType.getConstructor().newInstance();
        call(hub,"broadcastAccess",new Class[]{String.class,gateType},"strict",gate);
        call(root,"setContextBudget",new Class[]{int.class,float.class},12345,.7f);
        Object child=child(manager,"child_fixture",991L);
        check("strict".equals(field(child,"access")) && field(child,"gate")==gate,"Child factory weakened the root's approval policy");
        check((Integer)field(child,"limit")==12345 && "system/ultra".equals(field(child,"environment"))
                && "/work/project".equals(field(child,"directory")) && "system/ultra".equals(field(child,"resetPrompt")),
                "Child factory lost the parent's context limit or current environment");
        Object config=field(field(child,"client"),"config");
        check("ultra".equals(field(config,"effort")) && "language fixture".equals(field(config,"responseInstructions"))
                && field(child,"delegationParent")==root && (Boolean)field(root,"automaticDelegation"),
                "Child API config or inherited root authorization was lost");
        Object parentConfig = field(field(root, "client"), "config");
        for (Object selected : new Object[]{parentConfig, config}) check("grok".equals(field(selected, "providerId"))
                        && "https://provider.test/v1".equals(field(selected, "baseUrl"))
                        && "provider-key".equals(field(selected, "apiKey")) && "provider-model".equals(field(selected, "model")),
                "Parent/child client did not capture one coherent immutable provider profile");
        check(parentConfig != config, "Parent and child unexpectedly share mutable request configuration");
        Object registry=field(child,"registry");
        @SuppressWarnings("unchecked") List<String> names=(List<String>)field(registry,"names");
        check(!names.contains("GoalTool") && !names.contains("GetGoalTool") && names.contains("Coordination")
                && names.contains("ToolkitTool") && names.contains("FindFilesTool") && names.contains("McpTools"),
                "Child tools exposed parent goal controls or lost coordination/toolkit/search/MCP");
        @SuppressWarnings("unchecked") List<Object> tools=(List<Object>)field(registry,"tools");
        Object coordination=tools.get(tools.size()-1);
        check(field(coordination,"manager")==manager && "child_fixture".equals(field(coordination,"owner")),
                "Child coordination was registered as the root or another manager");
        Object workspace=((Map<?,?>)field(hub,"temporary")).get(child);
        Object[] args=(Object[])field(workspace,"args");
        java.io.File appFiles=(java.io.File)call(field(hub,"app"),"getFilesDir",new Class[0]);
        check(args[2].equals(new java.io.File(appFiles,"temporary-workspaces/children/child_fixture"))
                && (Long)field(workspace,"sessionId")==991L && (Boolean)field(workspace,"root"),
                "Child temporary files reused a parent/sibling ledger or lost root configuration");
        call(field(child,"usageObserver"),"onUsage",new Class[]{long.class},17L);
        check((Integer)field(root,"externalUsage")==17 && (Long)field(manager,"usage")==17L
                && "child_fixture".equals(field(manager,"accountedId")),"Child token usage was not attributed to both manager and parent budget");
    }
    private static void modeAndConcurrencyChangesRetargetTheExistingManager() throws Exception {
        Object hub=freshHub(),root=bind(hub,25L,listener()),manager=children(hub,root),child=child(manager,"live_child",992L);
        call(hub,"retargetIfNeeded",new Class[0]);
        int before=count(root,"retargets");
        setting("concurrency",4);call(hub,"retargetIfNeeded",new Class[0]);
        check(children(hub,root)==manager && (Integer)field(manager,"parallel")==4 && count(root,"retargets")==before+1,
                "Concurrency change did not update the running session's existing manager");
        setting("mode","ultra");setting("effort","ultra");call(hub,"retargetIfNeeded",new Class[0]);
        check("ultra".equals(field(field(field(root,"client"),"config"),"effort"))
                && "off".equals(field(field(field(child,"client"),"config"),"effort"))
                && (Boolean)field(root,"automaticDelegation"),"Ultra did not retarget API effort and authorization");
        setting("mode","manual");setting("effort","max");call(hub,"retargetIfNeeded",new Class[0]);
        check(field(root,"children")==manager && !(Boolean)field(root,"automaticDelegation")
                && "max".equals(field(field(field(root,"client"),"config"),"effort")),
                "Max kept automatic delegation or discarded reusable child history");
        @SuppressWarnings("unchecked") List<String> names=(List<String>)field(field(root,"registry"),"names");
        @SuppressWarnings("unchecked") List<String> childNames=(List<String>)field(field(child,"registry"),"names");
        check(names.contains("Coordination") && childNames.contains("Coordination") && names.contains("GoalTool"),
                "Explicit delegation management or parent goal control was removed");
        Object oldParent = field(field(root, "client"), "config"), oldChild = field(field(child, "client"), "config");
        setting("provider", "openai"); setting("url", "https://openai.test/v1"); setting("key", "new-key");
        setting("selectedModel", "new-model"); setting("failLegacyProfileReads", true);
        call(hub, "retargetIfNeeded", new Class[0]);
        for (Object loop : new Object[]{root}) {
            Object next = field(field(loop, "client"), "config");
            check("openai".equals(field(next, "providerId")) && "https://openai.test/v1".equals(field(next, "baseUrl"))
                            && "new-key".equals(field(next, "apiKey")) && "new-model".equals(field(next, "model")),
                    "Retargeting did not update the parent provider/model");
        }
        check("deepseek".equals(field(oldParent, "providerId")) && "fixture".equals(field(oldParent, "apiKey"))
                        && "fixture".equals(field(oldChild, "model")) && children(hub, root) == manager,
                "Provider switching rewrote an old request configuration or discarded the existing child manager");
    }
    private static void accessChangesAndDroppingRootCloseOwnedChildren() throws Exception {
        Object hub=freshHub(),root=bind(hub,26L,listener()),manager=children(hub,root),child=child(manager,"owned",993L);
        Class<?> gateType=hubType.getClassLoader().loadClass("com.mkei.backcast.agent.ApprovalGate");
        Object gate=gateType.getConstructor().newInstance();
        call(hub,"broadcastAccess",new Class[]{String.class,gateType},"strict",gate);
        check(field(child,"gate")==gate && "strict".equals(field(child,"access")),"Permission change did not reach a running child");
        call(hub,"broadcastAccess",new Class[]{String.class,gateType},"full",gate);
        check(field(child,"gate")==null && "full".equals(field(child,"access")),"Full access left an approval gate reachable from a child");
        Object checkpoint=field(manager,"store");
        call(hub,"drop",new Class[]{long.class},26L);
        check(count(root,"cancellations")>0 && count(child,"cancellations")>0 && (Integer)field(checkpoint,"removes")==1,
                "Dropping a root session kept its child process or private checkpoint alive");
        check(children(hub,root)==null && !((Map<?,?>)field(hub,"temporary")).containsKey(child)
                && !((Map<?,?>)field(hub,"childOwners")).containsKey(child),"Dropping the root leaked child ownership or temporary ledgers");
    }
    private static Object toolkitFactory(Object hub) throws Exception {
        Method method=hubType.getDeclaredMethod("captureToolkitSessions");method.setAccessible(true);return method.invoke(hub);
    }
    private static void toolkitUiSessionsUseOwnRunnerAndOwnerThreadCleanup() throws Exception {
        Object hub=freshHub(),root=bind(hub,27L,listener());
        final Object factory=toolkitFactory(hub),session=call(factory,"open",new Class[0]);
        final Object second=call(factory,"open",new Class[0]);
        Object toolkit=field(session,"toolkit"),materials=field(session,"materials");
        Object[] args=(Object[])field(toolkit,"args"),materialArgs=(Object[])field(materials,"args");
        java.io.File appFiles=(java.io.File)call(field(hub,"app"),"getFilesDir",new Class[0]);
        check(((java.io.File)materialArgs[2]).getParentFile().equals(new java.io.File(appFiles,"temporary-workspaces/tool-ui/jobs"))
                && (Long)field(materials,"sessionId")==0L && (Integer)field(materials,"begins")==1,
                "Toolkit UI reused a model's temporary session or failed to acquire its own lease");
        @SuppressWarnings("unchecked") List<Object> rootTools=(List<Object>)field(field(root,"registry"),"tools");
        check(args[0]!=rootTools.get(1) && args[3]==materials && args[1]==field(hub,"toolchains"),
                "Toolkit UI shares an agent shell or lost the persistent software registry");
        check(field(second,"materials")!=materials && field(second,"toolkit")!=toolkit
                && !((Object[])field(field(second,"materials"),"args"))[2].equals(materialArgs[2]),
                "Concurrent UI probes shared runner/workspace ledger");
        final Throwable[] error={null};
        Thread cancelling=new Thread(new Runnable(){@Override public void run(){
            try {call(session,"close",new Class[0]);}catch(Throwable failure){error[0]=failure;}
        }});
        cancelling.start();cancelling.join(2000);
        check(!cancelling.isAlive() && error[0]==null && (Integer)field(toolkit,"aborts")==1
                && (Integer)field(materials,"finishes")==0,"Non-owner cancellation tried to finish another thread's temporary lease");
        call(session,"close",new Class[0]);call(session,"close",new Class[0]);
        check((Integer)field(materials,"finishes")==1 && count(root,"cancellations")==0,
                "Owner cleanup was skipped/duplicated or cancelled a running model session");
        call(second,"close",new Class[0]);
        check((Integer)field(field(second,"toolkit"),"aborts")==0,"Normal owner cleanup advanced the runner cancellation epoch");
    }

    private static void toolkitOnlyWorkOwnsForegroundService() throws Exception {
        Object hub=freshHub(),manager=call(hub,"toolkitOperations",new Class[0]);
        manager.getClass().getField("busyState").setBoolean(manager,true);
        check((Boolean)call(hub,"hasWork",new Class[0]) && "正在管理工具".equals(call(hub,"noteText",new Class[0])),
                "Independent toolkit operation did not participate in foreground ownership");
        call(field(manager,"workListener"),"onWorkChanged",new Class[0]);
        Class<?> service=hubType.getClassLoader().loadClass("com.mkei.backcast.AgentService");
        check(service.getField("starts").getInt(null)>0,"Toolkit-only work did not start service protection");
        manager.getClass().getField("busyState").setBoolean(manager,false);
        call(field(manager,"workListener"),"onWorkChanged",new Class[0]);
        check(!(Boolean)call(hub,"hasWork",new Class[0])&&(Integer)field(field(hub,"app"),"serviceStops")>0,
                "Completed toolkit-only operation stranded foreground service");
    }

    private static void embeddedToolsUseAppAssetsAndDeviceRuntime() throws Exception {
        Object hub=freshHub(),tools=field(hub,"toolchains");
        java.io.File files=(java.io.File)call(field(hub,"app"),"getFilesDir",new Class[0]);
        check(new java.io.File(files,"toolchains").equals(field(tools,"directory"))
                && "arm64-v8a".equals(field(tools,"abi")) && (Integer)field(tools,"sdk")==30,
                "Embedded tools lost the app-private path or device runtime");
        Object assets=field(tools,"assets");
        Class<?> type=hubType.getClassLoader().loadClass("com.mkei.backcast.tool.EmbeddedToolchain$Assets");
        java.io.InputStream input=(java.io.InputStream)type.getMethod("open",String.class)
                .invoke(assets,"toolchain/manifest.json");
        try {check(input.read()==42,"Embedded tools did not open APK assets");}
        finally {input.close();}
        check("toolchain/manifest.json".equals(hubType.getClassLoader()
                .loadClass("android.content.res.AssetManager").getField("lastName").get(null)),
                "Embedded tool asset name was rewritten");
    }

    private static void multipleRootsRetargetParentChildAndIndependentUiTools() throws Exception {
        Object hub = freshHub(), root = bind(hub, 31L, listener()), manager = children(hub, root), child = child(manager, "scope", 995L);
        call(hub, "retargetIfNeeded", new Class[0]);
        Class<?> settings = hubType.getClassLoader().loadClass("com.mkei.backcast.Settings");
        @SuppressWarnings("unchecked") List<String> extra = (List<String>) settings.getField("additional").get(null);
        int previous = count(root, "retargets"); extra.add("/project/additional");
        call(hub, "retargetIfNeeded", new Class[0]);
        check(count(root, "retargets") == previous + 1, "Additional directory change did not retarget without changing primary");
        Map<?, ?> temporary = (Map<?, ?>) field(hub, "temporary");
        for (Object loop : new Object[]{root}) {
            @SuppressWarnings("unchecked") List<String> roots = (List<String>) field(temporary.get(loop), "roots");
            check(roots.equals(Arrays.asList(".", "/project/additional")), "Parent/child tools lost the complete authorized snapshot");
        }
        check(((List<?>)field(temporary.get(child),"roots")).equals(Collections.singletonList(".")), "Existing child workspace changed with global settings");
        Object session = call(toolkitFactory(hub), "open", new Class[0]);
        @SuppressWarnings("unchecked") List<String> roots = (List<String>) field(field(session, "materials"), "roots");
        check(roots.equals(Arrays.asList(".", "/project/additional")), "Independent UI tool session lost additional directories");
        call(session, "close", new Class[0]);
        extra.clear(); call(hub, "retargetIfNeeded", new Class[0]);
        check(((List<?>) field(temporary.get(root), "roots")).equals(Collections.singletonList(".")), "Revoked directory remained in next tool configuration");
    }

    private static void childOnlyWorkOwnsForegroundServiceWhileParentRemainsIdle() throws Exception {
        Object hub = freshHub(), root = bind(hub, 29, listener()), manager = children(hub, root);
        Object app = field(hub, "app"); Class<?> service = hubType.getClassLoader().loadClass("com.mkei.backcast.AgentService");
        service.getField("starts").setInt(null, 0); app.getClass().getField("serviceStops").setInt(app, 0);
        check(!(Boolean) call(hub, "hasWork", new Class<?>[0]) && !loopType.getField("busyState").getBoolean(root), "Idle fixture incorrectly starts with active work");
        call(manager, "live", new Class<?>[]{boolean.class}, true);
        check((Boolean) call(hub, "hasWork", new Class<?>[0]) && service.getField("starts").getInt(null) == 1,
                "Child-only work did not start the foreground service");
        check(!loopType.getField("busyState").getBoolean(root) && count(root, "resumes") == 0,
                "User child work resumed the idle parent");
        Object durability = field(root, "durability");
        Class<?> durabilityType = loopType.getClassLoader().loadClass("com.mkei.backcast.agent.AgentLoop$Durability");
        durabilityType.getMethod("save", long.class, boolean.class, String.class, String.class, long.class,
                long.class, Long.class, long.class, long.class, boolean.class)
                .invoke(durability, 29L, false, "", "", 0L, 0L, null, 0L, 0L, false);
        check(app.getClass().getField("serviceStops").getInt(app) == 0,
                "Idle parent persistence stopped the service while its child was active");
        call(manager, "live", new Class<?>[]{boolean.class}, false);
        check(!(Boolean) call(hub, "hasWork", new Class<?>[0]) && app.getClass().getField("serviceStops").getInt(app) == 1,
                "Child completion did not stop its foreground service");
        recover(hub); check(count(root, "resumes") == 0, "Service recovery restarted an idle or stopped parent");
    }

    private static void queuedChildUsesCapturedParentConfigAfterGlobalSettingsChange() throws Exception {
        Object hub=freshHub(); setting("directory","/original/project");setting("root",true);
        setting("provider","original-provider");setting("url","https://original.test/v1");setting("key","original-key");setting("selectedModel","original-model");
        Object parent=bind(hub,41L,listener()),manager=children(hub,parent),factory=field(manager,"factory");
        Class<?> recordType=hubType.getClassLoader().loadClass("com.mkei.backcast.agent.SubAgentManager$Record");
        Object task=recordType.getConstructor().newInstance();recordType.getField("id").set(task,"captured");recordType.getField("sessionId").setLong(task,1001L);
        JSONObject snapshot=(JSONObject)call(factory,"capture",new Class<?>[]{loopType},parent);recordType.getField("forkConfig").set(task,snapshot);
        check(!snapshot.toString().contains("original-key")&&!snapshot.getJSONObject("client").has("apiKey")
                &&snapshot.getJSONObject("client").has("credentialFingerprint"),"Child checkpoint leaked its model credential");
        setting("directory","/new/project");setting("root",false);setting("provider","new-provider");setting("url","https://new.test/v1");setting("key","new-key");setting("selectedModel","new-model");
        call(hub,"retargetIfNeeded",new Class<?>[0]);
        Class<?> gateType=hubType.getClassLoader().loadClass("com.mkei.backcast.agent.ApprovalGate");Object gate=gateType.getConstructor().newInstance();
        call(hub,"broadcastAccess",new Class<?>[]{String.class,gateType},"strict",gate);
        Object child=call(manager,"createTaskChild",new Class<?>[]{recordType,listenerType},task,listener());
        Object config=field(field(child,"client"),"config"),materials=((Map<?,?>)field(hub,"temporary")).get(child);
        check("original-model".equals(field(config,"model"))&&"original-provider".equals(field(config,"providerId"))
                &&"original-key".equals(field(config,"apiKey"))&&"/original/project".equals(field(child,"directory"))
                &&(Boolean)field(materials,"root")&&((List<?>)field(materials,"roots")).equals(Collections.singletonList("/original/project")),
                "Queued child used unrelated global model/root/workspace settings");
        check("strict".equals(field(child,"access"))&&field(child,"gate")==gate,
                "Queued full-access snapshot bypassed a later permission restriction");
        Object next=child(manager,"next",1002L);
        check("new-model".equals(field(field(field(next,"client"),"config"),"model"))
                &&"/new/project".equals(field(next,"directory")),"A new task failed to capture its current parent config");
    }

    private static void childEventsWakeIdleParentOnceAndRespectBusyAndStoppedGuards() throws Exception {
        Object hub=freshHub(),parent=bind(hub,42L,listener()),manager=children(hub,parent);
        manager.getClass().getField("wakeAllowed").setBoolean(manager,true);manager.getClass().getField("wakePending").setBoolean(manager,true);
        busy(parent);call(manager,"live",new Class<?>[]{boolean.class},true);
        check(count(parent,"resumes")==0,"Child event reentered a busy parent");
        loopType.getField("busyState").setBoolean(parent,false);
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        loopType.getField("wakeEntered").set(parent,entered);loopType.getField("wakeRelease").set(parent,release);
        try {
            call(manager,"live",new Class<?>[]{boolean.class},true);
            check(entered.await(3,java.util.concurrent.TimeUnit.SECONDS),"Persisted child event did not wake idle parent");
            for(int i=0;i<12;i++)call(manager,"live",new Class<?>[]{boolean.class},true);
            check(count(parent,"resumes")==1,"Repeated work notifications started overlapping parent workers");
        } finally { release.countDown(); }
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(!((Map<?,?>)field(hub,"childWakes")).isEmpty()&&System.nanoTime()<deadline)Thread.yield();
        manager.getClass().getField("wakeAllowed").setBoolean(manager,false);
        loopType.getField("wakeBlocked").setBoolean(parent,true);
        call(manager,"live",new Class<?>[]{boolean.class},true);
        check(count(parent,"resumes")==1,"Stopped/completed parent was revived by a late child event");
    }

    private static void restoredCapturedModelRequiresMatchingPrivateCredentials() throws Exception {
        Object hub=freshHub(),parent=bind(hub,43L,listener()),manager=children(hub,parent),factory=field(manager,"factory");
        Class<?> recordType=hubType.getClassLoader().loadClass("com.mkei.backcast.agent.SubAgentManager$Record");
        Object task=recordType.getConstructor().newInstance();recordType.getField("id").set(task,"restore_key");recordType.getField("sessionId").setLong(task,1003L);
        recordType.getField("forkConfig").set(task,call(factory,"capture",new Class<?>[]{loopType},parent));
        ((Map<?,?>)field(hub,"capturedCredentials")).clear();setting("selectedModel","different-global-model");
        Object restored=call(manager,"createTaskChild",new Class<?>[]{recordType,listenerType},task,listener());
        check("fixture".equals(field(field(field(restored,"client"),"config"),"model")),"Credential resolution changed the captured model");
        ((Map<?,?>)field(hub,"capturedCredentials")).clear();setting("key","rotated-key");recordType.getField("id").set(task,"reject_key");
        boolean rejected=false;try{call(manager,"createTaskChild",new Class<?>[]{recordType,listenerType},task,listener());}
        catch(java.lang.reflect.InvocationTargetException expected){rejected=expected.getCause() instanceof IllegalStateException;}
        check(rejected,"Restored child silently used changed global credentials");
    }

    private static void childOnlyRecoveryResumesQueuesWithoutResendingIdleParent() throws Exception {
        Object hub=freshHub();@SuppressWarnings("unchecked") List<Long> work=(List<Long>)storeType.getField("childWork").get(null);work.add(44L);
        Object parent=bind(hub,44L,listener()),manager=children(hub,parent);
        recover(hub);long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while((Integer)field(manager,"resumeCalls")==0&&System.nanoTime()<deadline)Thread.yield();
        check((Integer)field(manager,"resumeCalls")==1&&count(parent,"resumes")==0,
                "Recovering child-only work lost its queue or resent the idle parent's old API request");
    }

    private static void durableChildStopOutranksStaleParentRunningRow() throws Exception {
        Object hub=freshHub();pending(45L);Object parent=bind(hub,45L,listener()),manager=children(hub,parent);
        manager.getClass().getField("stopped").setBoolean(manager,true);
        int before=(Integer)field(manager,"findCalls");recover(hub);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while((Integer)field(manager,"findCalls")==before&&System.nanoTime()<deadline)Thread.yield();
        check((Integer)field(manager,"findCalls")>before&&count(parent,"resumes")==0
                &&(Integer)field(manager,"resumeCalls")==0,"Stale running flag revived an explicitly stopped legacy parent or its children");
    }
    private static void explicitChildRestartRecoveryKeepsClosedParentAndOldQueuesIdle() throws Exception {
        Object hub=freshHub();storeType.getMethod("checkpoint",long.class,boolean.class,Long.class,Long.class).invoke(null,46L,false,0L,null);
        Object run=call(field(hub,"store"),"readRun",new Class<?>[]{long.class},46L);run.getClass().getField("status").set(run,"complete");
        @SuppressWarnings("unchecked") List<Long> work=(List<Long>)storeType.getField("childWork").get(null);work.add(46L);
        @SuppressWarnings("unchecked") java.util.Set<Long> selected=(java.util.Set<Long>)storeType.getField("restartOnly").get(null);selected.add(46L);
        Object parent=bind(hub,46L,listener()),manager=children(hub,parent);recover(hub);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while((Integer)field(manager,"restrictedResumeCalls")==0&&System.nanoTime()<deadline)Thread.yield();
        check((Integer)field(manager,"restrictedResumeCalls")==1&&(Integer)field(manager,"resumeCalls")==0
                &&count(parent,"resumes")==0,"Explicit child restart recovered the old parent or ordinary queued work");
    }

    private static void modelDiagnosticsUseTheConversationRecorder() throws Exception {
        Object hub = freshHub(), root = bind(hub, 31, listener());
        Object recorder = field(root, "recorder");
        Class<?> contract = hubType.getClassLoader().loadClass("com.mkei.backcast.agent.AgentLoop$DetailedRequestRecorder");
        check(contract.isInstance(recorder), "Production recorder drops request diagnostics");
        contract.getMethod("recordRequest", long.class, String.class, long.class, String.class, String.class, int.class, String.class)
                .invoke(recorder, 31L, "review", 14234L, "retryable_error", "接口返回 HTTP 503", 2, "");
        check(storeType.getField("requestSession").getLong(null) == 31L
                        && storeType.getField("requestElapsed").getLong(null) == 14234L
                        && storeType.getField("requestRetry").getInt(null) == 2
                        && "review".equals(storeType.getField("requestPurpose").get(null))
                        && "retryable_error".equals(storeType.getField("requestOutcome").get(null))
                        && "接口返回 HTTP 503".equals(storeType.getField("requestReason").get(null)),
                "RunHub changed request diagnostics or routed them to a different session");
        ClassLoader loader = hubType.getClassLoader();
        Class<?> detailed = loader.loadClass("com.mkei.backcast.agent.AgentLoop$DetailedRequestRecorder");
        Class<?> errors = loader.loadClass("com.mkei.backcast.agent.AgentLoop$ErrorRecorder");
        check(detailed.isInstance(recorder) && errors.isInstance(recorder), "Root recorder lacks structured request and local error recording");
        String evidence = "{\"provider\":\"grok\",\"error\":{\"code\":\"invalid_model\"}}";
        Method recordDetailed = detailed.getMethod("recordRequest", long.class, String.class, long.class,
                String.class, String.class, int.class, String.class);
        recordDetailed.invoke(recorder, 31L, "model", 58L, "error", "invalid model", 0, evidence);
        check(evidence.equals(storeType.getField("requestDiagnostic").get(null))
                        && storeType.getField("requestElapsed").getLong(null) == 58L
                        && storeType.getField("requestSession").getLong(null) == 31L,
                "Structured model failure evidence was dropped or changed before ChatStore");
        Method recordError = errors.getMethod("recordDiagnostic", long.class, String.class, String.class, String.class);
        recordError.invoke(recorder, -1L, "configuration", "model list failure", evidence);
        check(storeType.getField("diagnosticSession").getLong(null) == -1L
                        && "configuration".equals(storeType.getField("diagnosticSource").get(null))
                        && "model list failure".equals(storeType.getField("diagnosticSummary").get(null))
                        && evidence.equals(storeType.getField("diagnosticDetail").get(null)),
                "Configuration failure did not remain a global diagnostic with full safe detail");
        Object manager = children(hub, root), child = child(manager, "worker_a", 910L);
        Object childRecorder = field(child, "diagnosticRecorder"), childErrors = field(child, "errorRecorder");
        Class<?> checkpoint = loader.loadClass("com.mkei.backcast.agent.AgentLoop$Recorder");
        Object replacement = Proxy.newProxyInstance(loader, new Class<?>[]{checkpoint}, (p, m, a) -> null);
        call(child, "setRecorder", new Class<?>[]{checkpoint}, replacement);
        check(field(child, "recorder") == replacement && field(child, "diagnosticRecorder") == childRecorder,
                "Manager checkpoint attachment overwrote child diagnostic recording");
        recordDetailed.invoke(childRecorder, 910L, "compact", 137000L, "retryable_error", "HTTP 503", 1, evidence);
        JSONObject childEvidence = new JSONObject((String) storeType.getField("requestDiagnostic").get(null));
        check(storeType.getField("requestSession").getLong(null) == 31L
                        && storeType.getField("requestElapsed").getLong(null) == 137000L
                        && "worker_a".equals(childEvidence.getString("agent_id"))
                        && "invalid_model".equals(childEvidence.getJSONObject("error").getString("code")),
                "Child request diagnostic was filed under its hidden session or lost child/evidence attribution");
        recordError.invoke(childErrors, 910L, "toolkit", "worker failure", evidence);
        check(storeType.getField("diagnosticSession").getLong(null) == 31L
                        && "agent:worker_a".equals(storeType.getField("diagnosticSource").get(null))
                        && "worker failure".equals(storeType.getField("diagnosticSummary").get(null))
                        && evidence.equals(storeType.getField("diagnosticDetail").get(null)),
                "Child runtime error did not reach the parent's visible diagnostic stream");
        call(hub, "adopt", new Class<?>[]{loopType, long.class}, root, 32L);
        recordDetailed.invoke(childRecorder, 910L, "review", 2L, "success", "", 0, "");
        check(storeType.getField("requestSession").getLong(null) == 32L
                        && "worker_a".equals(new JSONObject((String) storeType.getField("requestDiagnostic").get(null)).getString("agent_id")),
                "Child diagnostics captured an old parent session instead of following session adoption");
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
                Path runHub = Paths.get(args[0]);
                for (JavaFileObject file : manager.getJavaFileObjects(runHub.toFile(),
                        runHub.getParent().resolve("agent/Diagnostics.java").toFile())) files.add(file);
                boolean compiled = compiler.getTask(null, manager, null,
                        Arrays.asList("-encoding", "UTF-8", "-d", output.toString(), "-classpath", System.getProperty("java.class.path")), null, files).call();
                check(compiled, "RunHub fixture compilation failed");
            }
            try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()})) {
                hubType = loader.loadClass("com.mkei.backcast.RunHub");
                contextType = loader.loadClass("android.content.Context");
                loopType = loader.loadClass("com.mkei.backcast.agent.AgentLoop");
                listenerType = loader.loadClass("com.mkei.backcast.agent.AgentLoop$Listener");
                storeType = loader.loadClass("com.mkei.backcast.ChatStore");
                String[] tests = {"activeListenerSurvives", "idleListenerSurvives", "newRecoveryIsQuiet", "switchedSessionsKeepOwnership", "registeredToolsMatchCurrentSet", "budgetWrapStateSurvivesRecovery", "durationCheckpointsAreRestoredAfterHistoryExactlyOnce", "heartbeatPersistsDurationWithoutRestartingForegroundService", "preparedSessionPreservesCurrentListenerAndLoadsOnce", "sessionPreparationReleasesHubDuringDatabaseRead", "sessionPreparationReleasesHubDuringFullRestore", "concurrentBindingUsesOneRestoredSession", "managersUsePrivateSessionPathsAndFollowDraftAdoption", "realChildFactoryInheritsGateConfigContextAndOwnTemporaryLedger", "modeAndConcurrencyChangesRetargetTheExistingManager", "accessChangesAndDroppingRootCloseOwnedChildren", "toolkitUiSessionsUseOwnRunnerAndOwnerThreadCleanup", "toolkitOnlyWorkOwnsForegroundService", "embeddedToolsUseAppAssetsAndDeviceRuntime", "childOnlyWorkOwnsForegroundServiceWhileParentRemainsIdle", "multipleRootsRetargetParentChildAndIndependentUiTools", "modelDiagnosticsUseTheConversationRecorder", "queuedChildUsesCapturedParentConfigAfterGlobalSettingsChange", "childEventsWakeIdleParentOnceAndRespectBusyAndStoppedGuards", "restoredCapturedModelRequiresMatchingPrivateCredentials", "childOnlyRecoveryResumesQueuesWithoutResendingIdleParent","durableChildStopOutranksStaleParentRunningRow","explicitChildRestartRecoveryKeepsClosedParentAndOldQueuesIdle"};
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
