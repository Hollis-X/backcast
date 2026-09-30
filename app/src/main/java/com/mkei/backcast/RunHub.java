package com.mkei.backcast;

import android.content.Intent;

import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.ApprovalGate;
import com.mkei.backcast.agent.Goal;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.ToolRegistry;
import com.mkei.backcast.tool.EditTool;
import com.mkei.backcast.tool.GoalTool;
import com.mkei.backcast.tool.GetGoalTool;
import com.mkei.backcast.tool.ReadTool;
import com.mkei.backcast.tool.ShellTool;
import com.mkei.backcast.tool.WriteTool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final AgentLoop.Recorder recorder;
    private final AgentLoop.Durability durability;
    private final AgentLoop.Listener quiet = new AgentLoop.Quiet();

    private AgentLoop draft;
    private String applied = "";
    private boolean recovered;

    private RunHub(android.content.Context context) {
        app = context.getApplicationContext();
        store = new ChatStore(app);
        settings = new Settings(app);
        recorder = new AgentLoop.Recorder() {
            @Override
            public void record(long sessionId, Message message) {
                store.append(sessionId, message);
            }

            @Override
            public void replace(long sessionId, List<Message> messages) {
                store.replaceAll(sessionId, messages);
            }
        };
        durability = new AgentLoop.Durability() {
            @Override
            public void save(long sessionId, boolean running, String goal, String status, long elapsedMs,
                        long turnAt, long turnWall, long seenAt, long tokensUsed, long tokenBudget,
                        boolean budgetWrapFinished) {
                store.saveRun(sessionId, running, goal, status, elapsedMs, turnAt, turnWall, seenAt,
                        tokensUsed, tokenBudget, budgetWrapFinished);
                if (running || hasWork()) {
                    AgentService.start(app);
                } else {
                    app.stopService(new Intent(app, AgentService.class));
                }
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
    public synchronized AgentLoop bind(long sessionId, AgentLoop.Listener ui) {
        AgentLoop loop = obtainSession(sessionId);
        quietOthers(loop, ui);
        arm(loop);
        loop.setListener(ui == null ? quiet : ui);
        return loop;
    }

    /** 调用方持有 hub 锁。恢复循环不改变已有监听的归属。 */
    private AgentLoop obtainSession(long sessionId) {
        AgentLoop loop = loops.get(Long.valueOf(sessionId));
        if (loop == null) {
            loop = create();
            loops.put(Long.valueOf(sessionId), loop);
            loop.bindSession(sessionId);
            ChatStore.Run run = store.readRun(sessionId);
            loop.restoreGoal(run.goal, run.status, run.elapsedMs, run.tokensUsed, run.tokenBudget,
                    run.budgetWrapFinished);
            loop.loadHistory(settings.fullSystemPrompt(),
                    stripSteer(store.contextMessages(sessionId)));
            if (run.running) {
                loop.restoreTurnClock(run.turnAt, run.turnWall, run.seenAt);
            }
        }
        return loop;
    }

    /** 还没落库的新会话。不碰别的会话上正在跑的循环。 */
    public synchronized AgentLoop freshDraft(AgentLoop.Listener ui) {
        quietOthers(null, ui);
        if (draft != null) {
            draft.setListener(quiet);
        }
        draft = create();
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
        }
    }

    public synchronized void clearGate(ApprovalGate gate) {
        if (gate == null) {
            return;
        }
        for (AgentLoop loop : all()) {
            loop.clearGate(gate);
        }
    }

    public synchronized void drop(long sessionId) {
        AgentLoop loop = loops.remove(Long.valueOf(sessionId));
        if (loop != null) {
            loop.clearGoal();
            loop.cancel();
        }
    }

    /** 配置变了才换客户端和工具，避免把正在跑的请求换掉。 */
    public synchronized void retargetIfNeeded() {
        if (!settings.isConfigured()) {
            return;
        }
        String sig = settings.baseUrl() + "\n" + settings.apiKey() + "\n" + settings.model()
                + "\n" + settings.reasoningEffort() + "\n" + settings.useRoot()
                + "\n" + settings.workDir();
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
        return false;
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
        synchronized (this) {
            if (recovered || !settings.isConfigured()) {
                return;
            }
            recovered = true;
            ids = store.runningIds();
        }
        for (int i = 0; i < ids.size(); i++) {
            final long sid = ids.get(i).longValue();
            final AgentLoop loop;
            synchronized (this) {
                loop = obtainSession(sid);
            }
            if (loop.busy()) {
                continue;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    loop.resume(sid, 0);
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

    private AgentLoop create() {
        AgentLoop loop = new AgentLoop(newClient(), new ToolRegistry(), quiet);
        loop.retarget(newClient(), tools(loop));
        arm(loop);
        return loop;
    }

    private LlmClient newClient() {
        return new LlmClient(new LlmClient.Config(
                settings.baseUrl(), settings.apiKey(), settings.model(),
                settings.reasoningEffort()));
    }

    private ToolRegistry tools(AgentLoop loop) {
        ToolRegistry next = new ToolRegistry();
        String dir = settings.workDir();
        boolean root = settings.useRoot();
        next.register(new ReadTool(dir, root));
        next.register(new ShellTool(root, dir));
        next.register(new EditTool(dir, root));
        next.register(new WriteTool(dir, root));
        next.register(new GoalTool(loop));
        next.register(new GetGoalTool(loop));
        return next;
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
                    && (Goal.isSteer(message.content) || Goal.isNote(message.content))) {
                continue;
            }
            out.add(message);
        }
        return out;
    }
}
