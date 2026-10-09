package com.mkei.backcast;

import android.content.Intent;

import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.ApprovalGate;
import com.mkei.backcast.agent.Goal;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.ToolRegistry;
import com.mkei.backcast.agent.SubAgentManager;
import com.mkei.backcast.tool.EditTool;
import com.mkei.backcast.tool.GoalTool;
import com.mkei.backcast.tool.GetGoalTool;
import com.mkei.backcast.tool.ReadTool;
import com.mkei.backcast.tool.FindFilesTool;
import com.mkei.backcast.tool.ShellTool;
import com.mkei.backcast.tool.WriteTool;
import com.mkei.backcast.tool.TemporaryTool;
import com.mkei.backcast.tool.TemporaryWorkspace;
import com.mkei.backcast.tool.SubAgentTools;
import com.mkei.backcast.tool.ToolkitTool;
import com.mkei.backcast.tool.ToolchainStore;
import com.mkei.backcast.tool.EmbeddedToolchain;
import com.mkei.backcast.mcp.McpStore;
import com.mkei.backcast.mcp.McpCatalog;
import com.mkei.backcast.mcp.McpSelection;
import com.mkei.backcast.mcp.McpTools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 每个会话一个循环，跟界面当前看哪一个会话无关。
 * 换会话只换界面上的监听，不取消还在跑的那一个。
 */
public final class RunHub {

    private static RunHub instance;

    private final android.content.Context app;
    private final ChatStore store;
    private final Settings settings;
    private final Map<Long, AgentLoop> loops = new HashMap<Long, AgentLoop>();
    private final Map<AgentLoop, TemporaryWorkspace> temporary = new ConcurrentHashMap<AgentLoop, TemporaryWorkspace>();
    private final Map<AgentLoop, SubAgentManager> children = new ConcurrentHashMap<AgentLoop, SubAgentManager>();
    private final Map<AgentLoop, SQLiteSubAgentStore> childStores = new ConcurrentHashMap<AgentLoop, SQLiteSubAgentStore>();
    private final Map<AgentLoop, ChildOwner> childOwners = new ConcurrentHashMap<AgentLoop, ChildOwner>();
    private final Map<ToolRegistry, ToolContext> toolContexts = java.util.Collections.synchronizedMap(
            new java.util.WeakHashMap<ToolRegistry, ToolContext>());
    private final ConcurrentHashMap<AgentLoop, Boolean> childWakes = new ConcurrentHashMap<AgentLoop, Boolean>();
    /** Credentials stay only in memory or Settings, never in child checkpoints. */
    private final ConcurrentHashMap<String, String> capturedCredentials = new ConcurrentHashMap<String, String>();
    private final ToolchainStore toolchains;
    private final McpStore mcp;
    private final TemporaryWorkspace uiMaterials;
    private final ConcurrentHashMap<Long, Object> sessionPreparations = new ConcurrentHashMap<Long, Object>();
    private final AgentLoop.Recorder recorder;
    private final AgentLoop.Durability durability;
    private final AgentLoop.Listener quiet = new AgentLoop.Quiet();

    private AgentLoop draft;
    private String applied = "";
    private boolean recovered;
    private final Object legacyMigrationLock = new Object();
    private boolean legacyMigrationChecked;

    private static final class ChildOwner {
        final AgentLoop root;
        final SubAgentManager manager;
        final String id;
        ChildOwner(AgentLoop root, SubAgentManager manager, String id) {
            this.root = root; this.manager = manager; this.id = id;
        }
    }

    private static final class ToolContext {
        final List<String> roots;
        final boolean root;
        final McpTools remote;
        ToolContext(List<String> roots, boolean root, McpTools remote) {
            this.roots = new ArrayList<String>(roots); this.root = root; this.remote = remote;
        }
        JSONObject toJson() {
            try {
                return new JSONObject().put("roots", new JSONArray(roots)).put("useRoot", root)
                        .put("mcp", remote == null ? new JSONObject().put("servers", new JSONArray()) : remote.contextSnapshot());
            } catch (Exception invalid) { throw new IllegalStateException("无法捕获父 agent 工具配置。", invalid); }
        }
    }

    private final class StoredRecorder implements AgentLoop.Recorder, AgentLoop.DetailedRequestRecorder, AgentLoop.ErrorRecorder {
        @Override public void record(long sessionId, Message message) { store.append(sessionId, message); }
        @Override public void replace(long sessionId, List<Message> messages) { store.replaceAll(sessionId, messages); }
        @Override public void recordRequest(long sessionId, String purpose, long elapsedMs,
                String outcome, String reason, int retryCount, String diagnostic) {
            store.recordRequest(sessionId, purpose, elapsedMs, outcome, reason, retryCount, diagnostic);
        }
        @Override public void recordDiagnostic(long sessionId, String source, String summary, String detail) {
            store.recordDiagnostic(sessionId, source, summary, detail);
        }
    }

    private final class ChildDiagnostics implements AgentLoop.DetailedRequestRecorder, AgentLoop.ErrorRecorder {
        private final AgentLoop parent;
        private final String id;
        ChildDiagnostics(AgentLoop parent, String id) { this.parent = parent; this.id = id; }
        @Override public void recordRequest(long sid, String purpose, long elapsedMs,
                String outcome, String reason, int retryCount, String detail) {
            org.json.JSONObject evidence;
            try { evidence = new org.json.JSONObject(detail); }
            catch (Exception empty) { evidence = new org.json.JSONObject(); }
            try { evidence.put("agent_id", id); } catch (Exception ignored) { }
            store.recordRequest(parent.sessionKey(), purpose, elapsedMs, outcome, reason, retryCount,
                    com.mkei.backcast.agent.Diagnostics.boundedJson(evidence));
        }
        @Override public void recordDiagnostic(long sid, String source, String summary, String detail) {
            store.recordDiagnostic(parent.sessionKey(), "agent:" + id, summary, detail);
        }
    }

    private RunHub(android.content.Context context) {
        app = context.getApplicationContext();
        store = new ChatStore(app);
        settings = new Settings(app);
        mcp = new McpStore(new java.io.File(app.getFilesDir(), "mcp"));
        toolchains = new ToolchainStore(new java.io.File(app.getFilesDir(), "toolchains"),
                new EmbeddedToolchain.Assets() {
                    @Override public java.io.InputStream open(String name) throws Exception {
                        return app.getAssets().open(name);
                    }
                }, android.os.Build.CPU_ABI, android.os.Build.VERSION.SDK_INT);
        uiMaterials = new TemporaryWorkspace(settings.workDir(), settings.useRoot(),
                new java.io.File(app.getFilesDir(), "temporary-workspaces/tool-ui"), 0);
        recorder = new StoredRecorder();
        durability = new AgentLoop.Durability() {
            @Override
            public void save(long sessionId, boolean running, String goal, String status, long elapsedMs,
                        long turnElapsedMs, Long turnThinkMs, long tokensUsed, long tokenBudget,
                        boolean budgetWrapFinished) {
                store.saveRun(sessionId, running, goal, status, elapsedMs, turnElapsedMs, turnThinkMs,
                        tokensUsed, tokenBudget, budgetWrapFinished);
                syncService(running);
            }
            @Override
            public void saveClock(long sessionId, long elapsedMs, long turnElapsedMs, Long turnThinkMs) {
                store.saveClock(sessionId, elapsedMs, turnElapsedMs, turnThinkMs);
            }
        };
    }

    public static synchronized RunHub get(android.content.Context context) {
        if (instance == null) {
            instance = new RunHub(context);
        }
        return instance;
    }

    /** 打开一个已经落库的会话。已有循环就接着用，不重新装历史。 */
    public AgentLoop bind(long sessionId, AgentLoop.Listener ui) {
        AgentLoop loop = existingSession(sessionId);
        if (loop == null) {
            prepareSession(sessionId);
            loop = existingSession(sessionId);
        }
        synchronized (this) {
            quietOthers(loop, ui);
            arm(loop);
            loop.setListener(ui == null ? quiet : ui);
        }
        return loop;
    }

    /** Returns only a fully restored loop; performs no database or filesystem work. */
    public synchronized AgentLoop existingSession(long sessionId) {
        return loops.get(Long.valueOf(sessionId));
    }

    public SubAgentManager subAgents(AgentLoop loop) { return children.get(loop); }

    public List<McpCatalog.Server> cachedMcpCatalog() { return McpCatalog.cached(mcp); }

    public McpCatalog.Refresh newMcpRefresh(String serverId) { return new McpCatalog.Refresh(mcp, serverId); }

    public void validateMcpSelection(McpSelection selection) { mcp.validateSelection(selection); }

    /** UI probes use their own runner so they cannot cancel a model's active command. */
    public ToolkitSession newToolkitSession() {
        List<String> roots = settings.authorizedWorkDirs();
        String dir = roots.get(0);
        boolean root = settings.useRoot();
        synchronized (uiMaterials) {
            uiMaterials.configure(dir, root);
            uiMaterials.configureWorkDirs(roots);
            String cleanup = uiMaterials.cleanupRecovered();
            if (cleanup != null) throw new IllegalStateException("工具临时材料清理失败：" + cleanup);
            uiMaterials.beginTurn();
        }
        ShellTool shell = new ShellTool(root, dir, uiMaterials);
        return new ToolkitSession(new ToolkitTool(shell, toolchains, dir, uiMaterials,
                android.os.Build.CPU_ABI, root), uiMaterials);
    }

    public static final class ToolkitSession {
        public final ToolkitTool toolkit;
        private final TemporaryWorkspace materials;
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        private ToolkitSession(ToolkitTool toolkit, TemporaryWorkspace materials) {
            this.toolkit = toolkit;
            this.materials = materials;
        }

        public void close() {
            toolkit.abort();
            // Cancellation can come from the UI; the worker owns the temporary lease.
            if (Thread.currentThread() != owner) return;
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            String cleanup = materials.finishTurn();
            if (cleanup != null) throw new IllegalStateException("工具临时材料清理失败：" + cleanup);
        }
    }

    public String agentName(AgentLoop loop) {
        ChildOwner child = childOwners.get(loop);
        if (child == null) return "主 agent";
        try { return child.manager.find(child.id).name; }
        catch (Exception missing) { return child.id; }
    }

    /** Preload persisted context off the UI thread without changing listener ownership. */
    public void prepareSession(long sessionId) {
        AgentLoop known = existingSession(sessionId);
        if (known == null) {
            Long key = Long.valueOf(sessionId);
            Object candidate = new Object();
            Object previous = sessionPreparations.putIfAbsent(key, candidate);
            Object preparation = previous == null ? candidate : previous;
            synchronized (preparation) {
                known = existingSession(sessionId);
                if (known == null) {
                    ChatStore.Run run = store.readRun(sessionId);
                    List<Message> context = stripSteer(store.contextMessages(sessionId));
                    known = create(sessionId);
                    try {
                        restoreSession(known, sessionId, run, context);
                    } catch (RuntimeException error) {
                        temporary.remove(known);
                        throw error;
                    }
                    synchronized (this) { loops.put(key, known); }
                }
            }
        }
        TemporaryWorkspace materials = temporary.get(known);
        if (materials != null) materials.cleanupRecovered();
    }

    private void restoreSession(AgentLoop loop, long sessionId, ChatStore.Run run, List<Message> context) {
        loop.bindSession(sessionId);
        loop.restoreGoal(run.goal, run.status, run.elapsedMs, run.tokensUsed, run.tokenBudget,
                run.budgetWrapFinished);
        loop.loadHistory(settings.fullSystemPrompt(), context);
        if (run.running) loop.restoreTurnClock(run.turnElapsedMs, run.turnThinkMs);
    }

    /** 还没落库的新会话。不碰别的会话上正在跑的循环。 */
    public synchronized AgentLoop freshDraft(AgentLoop.Listener ui) {
        quietOthers(null, ui);
        if (draft != null) {
            draft.setListener(quiet);
        }
        draft = create(-1);
        draft.bindSession(-1);
        draft.reset(settings.fullSystemPrompt());
        arm(draft);
        draft.setListener(ui == null ? quiet : ui);
        return draft;
    }

    /** 第一条消息落库后，草稿循环改挂到这个会话上。 */
    public synchronized void adopt(AgentLoop loop, long sessionId) {
        if (loop == null || sessionId < 0) {
            return;
        }
        loop.bindSession(sessionId);
        TemporaryWorkspace materials = temporary.get(loop);
        if (materials != null) materials.bindSession(sessionId);
        SQLiteSubAgentStore childStore = childStores.get(loop);
        if (childStore != null) childStore.bindSession(sessionId);
        loops.put(Long.valueOf(sessionId), loop);
        if (draft == loop) {
            draft = null;
        }
        arm(loop);
    }

    public synchronized void detach(AgentLoop.Listener ui) {
        if (ui == null) {
            return;
        }
        for (AgentLoop loop : all()) {
            if (loop.listener() == ui) {
                loop.setListener(quiet);
            }
        }
    }

    /** 权限改了，正在跑的会话也要跟着走。完全访问不挂确认框。 */
    public synchronized void broadcastAccess(String level, ApprovalGate gate) {
        ApprovalGate use = ApprovalGate.ACCESS_FULL.equals(level) ? null : gate;
        for (AgentLoop loop : all()) {
            loop.setAccessLevel(level);
            loop.setApprovalGate(use);
            SubAgentManager manager = children.get(loop);
            if (manager != null) for (AgentLoop child : manager.runtimeLoops()) {
                child.setAccessLevel(level);
                child.setApprovalGate(use);
            }
        }
    }

    public synchronized void drop(long sessionId) {
        AgentLoop loop = loops.remove(Long.valueOf(sessionId));
        if (loop != null) {
            boolean running = loop.busy();
            loop.clearGoal();
            loop.cancel();
            SubAgentManager manager = children.remove(loop);
            SQLiteSubAgentStore checkpoint = childStores.remove(loop);
            if (checkpoint != null) checkpoint.remove();
            if (manager != null) for (AgentLoop child : manager.runtimeLoops()) {
                childOwners.remove(child);
                temporary.remove(child);
            }
            final TemporaryWorkspace materials = temporary.remove(loop);
            if (!running && materials != null) {
                new Thread(new Runnable() {
                    @Override public void run() { materials.cleanupRecovered(); }
                }).start();
            }
        }
    }

    /** 配置变了才换客户端和工具，避免把正在跑的请求换掉。 */
    public synchronized void retargetIfNeeded() {
        if (!settings.isConfigured()) {
            return;
        }
        Settings.AiProfile profile = settings.activeAiProfile();
        String sig = profile.id + "\n" + profile.baseUrl + "\n" + profile.apiKey + "\n" + profile.model
                + "\n" + settings.effectiveReasoningEffort() + "\n" + settings.useRoot()
                + "\n" + settings.workDir() + "\n" + settings.authorizedWorkDirs() + "\n" + settings.outputVerbosity()
                + "\n" + settings.outputLanguage() + "\n" + settings.agentMode()
                + "\n" + settings.agentConcurrency();
        if (sig.equals(applied)) {
            return;
        }
        applied = sig;
        for (AgentLoop loop : all()) {
            arm(loop);
            loop.retarget(newClient(), tools(loop));
        }
    }

    /** 工作目录变了，下一轮工具按新目录来。正在执行的那一次不中途拆掉。 */
    public synchronized void retargetTools() {
        applied = "";
        retargetIfNeeded();
    }

    public synchronized boolean hasWork() {
        if (store.runningIds().size() > 0) {
            return true;
        }
        for (AgentLoop loop : all()) {
            if (loop.busy()) {
                return true;
            }
        }
        for (SubAgentManager manager : children.values()) if (manager.hasLiveWork()) return true;
        return false;
    }

    private synchronized void syncService(boolean parentRunning) {
        if (parentRunning || hasWork()) AgentService.start(app);
        else app.stopService(new Intent(app, AgentService.class));
    }

    public synchronized String noteText() {
        for (AgentLoop loop : all()) {
            if (loop.busy() && loop.goalText().length() > 0) {
                return loop.goalText();
            }
        }
        return "正在执行任务";
    }

    /** 进程被系统拉起来之后，把没跑完的会话接上。只做一次。 */
    public void recover() {
        List<Long> ids;
        synchronized (legacyMigrationLock) {
            if (!legacyMigrationChecked) {
                SQLiteSubAgentStore.migrateLegacySessions(store, app.getFilesDir());
                legacyMigrationChecked = true;
            }
        }
        synchronized (this) {
            if (recovered || !settings.isConfigured()) {
                return;
            }
            recovered = true;
        }
        ids = store.runningIds();
        for (Long sid : store.subAgentWorkSessionIds()) if (!ids.contains(sid)) ids.add(sid);
        for (int i = 0; i < ids.size(); i++) {
            final long sid = ids.get(i).longValue();
            final AgentLoop loop;
            final ChatStore.Run savedRun = store.readRun(sid);
            final boolean parentRunning = savedRun.running;
            final boolean childRestartOnly;
            try { childRestartOnly = store.subAgentUserRestartOnly(sid); }
            catch (RuntimeException failure) {
                collaborationFailure(sid, "子任务恢复权限损坏，原记录已保留", failure);
                continue;
            }
            if (!childRestartOnly && !parentRunning && Goal.isClosed(savedRun.status)) continue;
            try { prepareSession(sid); }
            catch (RuntimeException failure) {
                collaborationFailure(sid, "子任务会话恢复失败，原记录已保留", failure);
                continue;
            }
            synchronized (this) {
                loop = loops.get(Long.valueOf(sid));
            }
            if (loop.busy()) {
                continue;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        SubAgentManager manager = children.get(loop);
                        if (manager != null && manager.find(SubAgentManager.ROOT).managerCancelled) return;
                        if (childRestartOnly) {
                            if (manager != null) manager.resumeUserRestartOnlyPending();
                        } else if (parentRunning) loop.resume(sid, 0);
                        else {
                            if (manager != null) {
                                loop.armRecoveredChildEvents();
                                manager.resumePending();
                                wakeForChildEvents(loop);
                            }
                        }
                    } catch (Exception failure) { collaborationFailure(sid, "子任务恢复失败", failure); }
                }
            }).start();
        }
    }

    private void arm(AgentLoop loop) {
        loop.setEnvironment(settings.fullSystemPrompt(), settings.workDir());
        loop.setRecorder(recorder);
        loop.setDurability(durability);
        loop.setContextBudget(AgentLoop.DEFAULT_CONTEXT_LIMIT, settings.compactRatio());
        loop.setAccessLevel(settings.accessLevel());
        loop.setAutomaticDelegation(Settings.EFFORT_ULTRA.equals(settings.effectiveReasoningEffort()));
    }

    /** 换到另一个会话时，旧循环继续跑，只是不再往这个界面上画。 */
    private void quietOthers(AgentLoop keep, AgentLoop.Listener ui) {
        if (ui == null || ui == quiet) {
            return;
        }
        for (AgentLoop loop : all()) {
            if (loop != keep && loop.listener() == ui) {
                loop.setListener(quiet);
            }
        }
    }

    private AgentLoop create(long sessionId) {
        AgentLoop loop = new AgentLoop(newClient(), new ToolRegistry(), quiet);
        loop.bindSession(sessionId);
        temporary.put(loop, new TemporaryWorkspace(settings.workDir(), settings.useRoot(),
                new java.io.File(app.getFilesDir(), "temporary-workspaces"), sessionId));
        arm(loop);
        loop.retarget(newClient(), tools(loop));
        return loop;
    }

    private LlmClient newClient() {
        Settings.AiProfile profile = settings.activeAiProfile();
        LlmClient.Config config = new LlmClient.Config(
                profile.baseUrl, profile.apiKey, profile.model,
                settings.effectiveReasoningEffort());
        config.providerId = profile.id;
        config.verbosity = settings.outputVerbosity();
        config.responseInstructions = settings.responseInstructions();
        capturedCredentials.put(credentialIdentity(config.providerId, config.baseUrl,
                LlmClient.credentialFingerprint(config.apiKey)), config.apiKey);
        return new LlmClient(config);
    }

    private ToolRegistry tools(AgentLoop loop) {
        List<String> roots = settings.authorizedWorkDirs();
        return tools(loop, roots, settings.useRoot(), null);
    }

    private ToolRegistry tools(AgentLoop loop, List<String> roots, boolean root, JSONObject mcpSnapshot) {
        ToolRegistry next = new ToolRegistry();
        String dir = roots.get(0);
        TemporaryWorkspace materials = temporary.get(loop);
        materials.configure(dir, root);
        materials.configureWorkDirs(roots);
        next.register(new ReadTool(dir, root, materials));
        next.register(new FindFilesTool(dir, root, materials));
        ShellTool shell = new ShellTool(root, dir, materials);
        next.register(shell);
        next.register(new EditTool(dir, root, materials));
        next.register(new WriteTool(dir, root, materials));
        next.register(new TemporaryTool(materials, dir, roots));
        next.register(new ToolkitTool(shell, toolchains, dir, materials, android.os.Build.CPU_ABI, root));
        McpTools remote = null;
        try { remote = mcpSnapshot == null ? McpTools.register(next, mcp) : McpTools.register(next, mcp, mcpSnapshot); }
        catch (RuntimeException invalid) {
            store.recordDiagnostic(loop.sessionKey(), "mcp", "MCP 配置读取失败", "请检查 MCP 私有配置或重新保存连接");
            if (mcpSnapshot != null) throw invalid;
        }
        toolContexts.put(next, new ToolContext(roots, root, remote));
        ChildOwner owner = childOwners.get(loop);
        if (owner != null) {
            loop.setDelegationParent(owner.root);
            SubAgentTools.register(next, owner.manager, owner.id);
            return next;
        }
        next.register(new GoalTool(loop));
        next.register(new GetGoalTool(loop));
        SubAgentManager manager = manager(loop);
        manager.setMaxParallel(settings.agentConcurrency());
        loop.setSubAgents(manager);
        SubAgentTools.register(next, manager, SubAgentManager.ROOT);
        return next;
    }

    private java.io.File childDirectory(long sessionId) {
        return new java.io.File(app.getFilesDir(), "sub-agents/session-" + sessionId);
    }

    private SubAgentManager manager(final AgentLoop parent) {
        SubAgentManager known = children.get(parent);
        if (known != null) return known;
        try {
            final SQLiteSubAgentStore checkpoint = new SQLiteSubAgentStore(store, parent.sessionKey(),
                    parent.sessionKey() < 0 ? null : childDirectory(parent.sessionKey()));
            SubAgentManager created = new SubAgentManager(settings.agentConcurrency(), new SubAgentManager.ContextFactory() {
                @Override public JSONObject capture(AgentLoop source) throws Exception {
                    JSONObject snapshot = source.captureChildContext();
                    ToolContext tools = toolContexts.get(source.childSourceTools());
                    if (tools == null) throw new IllegalStateException("父 agent 工具配置无法确认。");
                    try { snapshot.put("tools", tools.toJson()); return snapshot; }
                    catch (Exception invalid) { throw new IllegalStateException("无法捕获父 agent 运行配置。", invalid); }
                }
                @Override public AgentLoop create(final SubAgentManager.Record task, AgentLoop.Listener listener,
                        final SubAgentManager manager) throws Exception {
                    JSONObject snapshot = task.forkConfig;
                    if (snapshot == null || snapshot.length() == 0) {
                        // Legacy checkpoints never captured their source config.
                        // Use the restored parent, never unrelated current settings.
                        snapshot = capture(parent);
                    }
                    JSONObject toolSnapshot = snapshot.getJSONObject("tools");
                    List<String> roots = new ArrayList<String>();
                    JSONArray directories = toolSnapshot.getJSONArray("roots");
                    for (int i = 0; i < directories.length(); i++) roots.add(directories.getString(i));
                    if (roots.isEmpty()) throw new IllegalStateException("父 agent 工作目录快照为空。");
                    String system = snapshot.getString("system"), directory = snapshot.getString("workspace");
                    AgentLoop child = new AgentLoop(clientFromSnapshot(snapshot.getJSONObject("client")), new ToolRegistry(), listener);
                    ChildDiagnostics diagnostics = new ChildDiagnostics(parent, task.id);
                    child.setDiagnosticRecorder(diagnostics, diagnostics);
                    childOwners.put(child, new ChildOwner(parent, manager, task.id));
                    temporary.put(child, new TemporaryWorkspace(roots.get(0), toolSnapshot.getBoolean("useRoot"),
                            new java.io.File(app.getFilesDir(), "temporary-workspaces/children/" + task.id), task.sessionId));
                    child.retarget(clientFromSnapshot(snapshot.getJSONObject("client")),
                            tools(child, roots, toolSnapshot.getBoolean("useRoot"), toolSnapshot.getJSONObject("mcp")));
                    child.reset(system);
                    child.setEnvironment(system, directory);
                    child.setContextBudget(snapshot.getInt("contextLimit"), (float) snapshot.getDouble("compactRatio"));
                    String capturedAccess = snapshot.getString("access"), currentAccess = parent.accessLevel();
                    child.setAccessLevel(accessRank(currentAccess) > accessRank(capturedAccess) ? currentAccess : capturedAccess);
                    child.setApprovalGate(parent.approvalGate());
                    child.setUsageObserver(new AgentLoop.UsageObserver() {
                        @Override public void onUsage(long tokens) {
                            manager.accountUsage(task.id, tokens);
                            parent.accountExternalUsage(tokens, manager.usageLease(task.id));
                        }
                    });
                    return child;
                }
            }, checkpoint);
            created.attachRoot(parent);
            childStores.put(parent, checkpoint);
            children.put(parent, created);
            created.setWorkObserver(new SubAgentManager.WorkObserver() {
                @Override public void onWorkChanged() {
                    syncService(false);
                    wakeForChildEvents(parent);
                }
            });
            return created;
        } catch (Exception failure) {
            throw new IllegalStateException("无法初始化子 agent：" + failure.getMessage(), failure);
        }
    }

    private LlmClient clientFromSnapshot(JSONObject value) throws Exception {
        String provider = value.optString("providerId", ""), url = value.getString("baseUrl");
        String fingerprint = value.getString("credentialFingerprint");
        String credential = capturedCredentials.get(credentialIdentity(provider, url, fingerprint));
        if (credential == null) {
            for (Settings.AiProfile profile : settings.aiProfiles()) {
                LlmClient.Config candidate = new LlmClient.Config(profile.baseUrl, profile.apiKey, profile.model);
                if (provider.equals(profile.id) && url.equals(candidate.baseUrl)
                        && fingerprint.equals(LlmClient.credentialFingerprint(profile.apiKey))) {
                    credential = profile.apiKey; break;
                }
            }
        }
        if (credential == null) throw new IllegalStateException("父 agent 模型授权已变化，不能恢复旧任务。");
        LlmClient.Config config = new LlmClient.Config(url, credential,
                value.getString("model"), value.optString("reasoningEffort", ""));
        config.providerId = value.optString("providerId", ""); config.verbosity = value.optString("verbosity", "");
        config.responseInstructions = value.optString("responseInstructions", "");
        config.timeoutMs = value.optInt("timeoutMs", 120000); config.maxTokens = value.optInt("maxTokens", 0);
        config.totalTimeoutMs = value.optInt("totalTimeoutMs", 0); config.maxResponseChars = value.optInt("maxResponseChars", 0);
        return new LlmClient(config);
    }

    private static String credentialIdentity(String provider, String url, String fingerprint) {
        return (provider == null ? "" : provider) + "\n" + url + "\n" + fingerprint;
    }

    private static int accessRank(String access) {
        return ApprovalGate.ACCESS_STRICT.equals(access) ? 2 : ApprovalGate.ACCESS_GUARDED.equals(access) ? 1 : 0;
    }

    private void wakeForChildEvents(final AgentLoop parent) {
        final SubAgentManager manager = children.get(parent);
        if (manager == null || parent.sessionKey() < 0 || parent.busy() || !manager.shouldWakeRoot()) return;
        if (childWakes.putIfAbsent(parent, Boolean.TRUE) != null) return;
        Thread wake = new Thread(new Runnable() {
            @Override public void run() {
                boolean resumed = false;
                try { resumed = parent.resumeForChildEvents(parent.sessionKey(), -1); }
                catch (RuntimeException failure) {
                    collaborationFailure(parent.sessionKey(), "子任务消息唤醒失败", failure);
                } finally {
                    childWakes.remove(parent);
                    // A durable event may arrive between the worker's last drain
                    // and its idle notification while this single-flight lease
                    // is still held. Recheck after release so it is not stranded.
                    if (resumed) wakeForChildEvents(parent);
                }
            }
        }, "agent-child-events");
        wake.setDaemon(true); wake.start();
    }

    private void collaborationFailure(long sessionId, String summary, Throwable failure) {
        try { store.recordDiagnostic(sessionId, "sub_agent_wake", summary,
                com.mkei.backcast.agent.Diagnostics.boundedJson(com.mkei.backcast.agent.Diagnostics.failure(failure))); }
        catch (RuntimeException unavailable) { }
    }

    private List<AgentLoop> all() {
        List<AgentLoop> out = new ArrayList<AgentLoop>(loops.values());
        if (draft != null) {
            out.add(draft);
        }
        return out;
    }

    /**
     * 装回内存时去掉续跑说明和界面分隔。
     * 分隔留在库里给重开时画，不能当成用户说过的话发给模型。
     */
    private static List<Message> stripSteer(List<Message> messages) {
        List<Message> out = new ArrayList<Message>();
        if (messages == null) {
            return out;
        }
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message != null
                    && !Message.isCoordination(message.content)
                    && (Goal.isSteer(message.content) || Goal.isNote(message.content))) {
                continue;
            }
            out.add(message);
        }
        return out;
    }
}
