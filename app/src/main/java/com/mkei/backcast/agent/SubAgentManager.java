package com.mkei.backcast.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Session-owned child loops, mailboxes, and a bounded cooperative scheduler. */
public final class SubAgentManager {
    public static final String ROOT = "main";
    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String WAITING = "waiting";
    public static final String IDLE = "idle";
    public static final String FAILED = "failed";
    public static final String CLOSED = "closed";
    public static final String STOPPED = "stopped";
    /** Explicit child-only work after stopping the parent must not charge or restart its former goal. */
    public static final long DETACHED_USAGE_LEASE = Long.MIN_VALUE;
    private static final int MAX_AGENTS = 16;
    private static final int MAX_MAIL = 64;
    private static final int MAX_TEXT = 32000;
    private static final int RESULT_BATCH_CHARS = 60000;
    private static final long MAX_WAIT_MS = 60000L;

    public interface Factory {
        AgentLoop create(Record task, AgentLoop.Listener listener, SubAgentManager manager) throws Exception;
    }

    /** Captured synchronously at dispatch, before preferences or parent configuration can change. */
    public interface ContextFactory extends Factory {
        JSONObject capture(AgentLoop parent) throws Exception;
    }

    public interface Store {
        List<Record> load() throws Exception;
        void save(Record task) throws Exception;
    }

    public interface WorkObserver {
        void onWorkChanged();
    }

    public static final class Record {
        public String id, parentId, name, task, status, result = "", error = "", inFlight = "";
        public String phase = QUEUED, activeTool = "", progress = "";
        public String inFlightRequest = "", inFlightReference = "";
        public long sessionId, revision, tokensUsed, collectedRevision, acknowledgedRevision;
        public long lastActivityAt, progressRevision, inboxRevision;
        public JSONArray history = new JSONArray();
        public JSONArray pending = new JSONArray();
        public JSONArray inbox = new JSONArray();
        public JSONObject deliveredChildren = new JSONObject();
        public JSONObject acknowledgedChildren = new JSONObject();
        public JSONArray deliveredInbox = new JSONArray();
        public String currentTaskId = "";
        public JSONArray tasks = new JSONArray(), results = new JSONArray(), events = new JSONArray();
        public JSONArray forkHistory = new JSONArray();
        public JSONObject forkConfig = new JSONObject();
        public JSONObject deliveredResults = new JSONObject(), acknowledgedResults = new JSONObject();
        public boolean rootWakeAllowed, userRestartOnly;
        public boolean resume, inFlightStarted, managerCancelled;

        public JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("parentId", parentId).put("name", name)
                    .put("task", task).put("status", status).put("result", result).put("error", error)
                    .put("inFlight", inFlight).put("sessionId", sessionId).put("revision", revision)
                    .put("tokensUsed", tokensUsed).put("collectedRevision", collectedRevision)
                    .put("acknowledgedRevision", acknowledgedRevision)
                    .put("history", history).put("pending", pending).put("inbox", inbox).put("resume", resume)
                    .put("inFlightStarted", inFlightStarted).put("deliveredChildren", deliveredChildren)
                    .put("acknowledgedChildren", acknowledgedChildren).put("deliveredInbox", deliveredInbox)
                    .put("managerCancelled", managerCancelled).put("phase", phase).put("activeTool", activeTool)
                    .put("progress", progress).put("lastActivityAt", lastActivityAt).put("progressRevision", progressRevision)
                    .put("inboxRevision", inboxRevision)
                    .put("inFlightRequest", inFlightRequest).put("inFlightReference", inFlightReference)
                    .put("currentTaskId", currentTaskId).put("tasks", tasks).put("results", results).put("events", events)
                    .put("forkHistory", forkHistory).put("forkConfig", forkConfig)
                    .put("deliveredResults", deliveredResults).put("acknowledgedResults", acknowledgedResults)
                    .put("rootWakeAllowed", rootWakeAllowed).put("userRestartOnly", userRestartOnly);
        }

        public static Record fromJson(JSONObject json) throws Exception {
            Record task = new Record();
            task.id = json.getString("id"); task.parentId = json.optString("parentId", ROOT);
            task.name = json.optString("name", task.id); task.task = json.optString("task", "");
            task.status = json.optString("status", IDLE); task.result = json.optString("result", "");
            task.error = json.optString("error", ""); task.inFlight = json.optString("inFlight", "");
            task.sessionId = json.optLong("sessionId", 1L); task.revision = json.optLong("revision", 0L);
            task.tokensUsed = json.optLong("tokensUsed", 0L); task.collectedRevision = json.optLong("collectedRevision", 0L);
            task.acknowledgedRevision = json.optLong("acknowledgedRevision", 0L);
            task.history = json.optJSONArray("history"); if (task.history == null) task.history = new JSONArray();
            task.pending = json.optJSONArray("pending"); if (task.pending == null) task.pending = new JSONArray();
            task.inbox = json.optJSONArray("inbox"); if (task.inbox == null) task.inbox = new JSONArray();
            task.resume = json.optBoolean("resume", false);
            task.inFlightStarted = json.optBoolean("inFlightStarted", false);
            task.managerCancelled = json.optBoolean("managerCancelled", false);
            task.phase = json.optString("phase", task.status); task.activeTool = json.optString("activeTool", "");
            task.progress = json.optString("progress", ""); task.lastActivityAt = json.optLong("lastActivityAt", 0L);
            task.progressRevision = json.optLong("progressRevision", 0L);
            task.inboxRevision = json.optLong("inboxRevision", 0L);
            task.inFlightRequest = json.optString("inFlightRequest", "");
            task.inFlightReference = json.optString("inFlightReference", "");
            task.deliveredChildren = json.optJSONObject("deliveredChildren");
            if (task.deliveredChildren == null) task.deliveredChildren = new JSONObject();
            task.acknowledgedChildren = json.optJSONObject("acknowledgedChildren");
            if (task.acknowledgedChildren == null) task.acknowledgedChildren = new JSONObject();
            task.deliveredInbox = json.optJSONArray("deliveredInbox");
            if (task.deliveredInbox == null) task.deliveredInbox = new JSONArray();
            if (IDLE.equals(task.status) && task.error.length() == 0 && PromptGuard.REFUSAL.equals(task.result)) {
                task.status = FAILED; task.phase = FAILED; task.activeTool = "";
                task.error = PromptGuard.REFUSAL; task.result = ""; task.revision++;
            }
            task.currentTaskId = json.optString("currentTaskId", "");
            task.tasks = array(json, "tasks"); task.results = array(json, "results"); task.events = array(json, "events");
            task.forkHistory = array(json, "forkHistory"); task.forkConfig = object(json, "forkConfig");
            task.deliveredResults = object(json, "deliveredResults");
            task.acknowledgedResults = object(json, "acknowledgedResults");
            task.rootWakeAllowed = json.optBoolean("rootWakeAllowed", false);
            task.userRestartOnly = json.optBoolean("userRestartOnly", false);
            // Preserve the one legacy outcome without fabricating unavailable earlier attempts.
            if (!ROOT.equals(task.id) && task.tasks.length() == 0
                    && (task.task.length() > 0 || task.pending.length() > 0 || task.inFlight.length() > 0)) {
                task.currentTaskId = "task_" + task.id + "_legacy";
                JSONObject attempt = new JSONObject().put("taskId", task.currentTaskId).put("request", task.task)
                        .put("status", task.status).put("phase", task.phase).put("progress", task.progress)
                        .put("partial", "").put("error", task.error).put("createdAt", task.lastActivityAt);
                task.tasks.put(attempt);
                for (int i = 0; i < task.pending.length(); i++) {
                    JSONObject pending = task.pending.getJSONObject(i);
                    // A queued legacy message is its own assignment, never another outcome for the in-flight task.
                    boolean firstUnstarted = i == 0 && task.inFlight.length() == 0
                            && task.result.length() == 0 && task.error.length() == 0;
                    String pendingId = firstUnstarted ? task.currentTaskId : "task_" + task.id + "_legacy_pending_" + i;
                    pending.put("kind", "task").put("taskId", pendingId);
                    if (firstUnstarted) attempt.put("request", pending.optString("text", task.task)).put("status", QUEUED).put("phase", QUEUED);
                    else task.tasks.put(new JSONObject().put("taskId", pendingId).put("request", pending.optString("text", ""))
                            .put("status", QUEUED).put("phase", QUEUED).put("progress", "").put("partial", "")
                            .put("error", "").put("createdAt", task.lastActivityAt));
                }
                if (task.result.length() > 0 || task.error.length() > 0) {
                    String resultId = "result_" + task.id + "_legacy";
                    attempt.put("resultId", resultId);
                    task.results.put(new JSONObject().put("resultId", resultId).put("taskId", task.currentTaskId)
                            .put("status", task.error.length() == 0 ? "completed" : task.status)
                            .put("content", task.result).put("error", task.error).put("partial", "")
                            .put("createdAt", task.lastActivityAt));
                }
            }
            return task;
        }

        private static JSONArray array(JSONObject json, String name) {
            JSONArray value = json.optJSONArray(name); return value == null ? new JSONArray() : value;
        }
        private static JSONObject object(JSONObject json, String name) {
            JSONObject value = json.optJSONObject(name); return value == null ? new JSONObject() : value;
        }
    }

    private static final class Slot {
        final Record task;
        AgentLoop loop;
        boolean executing, suspended, ready = true;
        volatile boolean stop;
        int uiToken;
        long usageLease = -1L;
        long lastProgressPersist;
        final Map<String, Long> observedProgress = new LinkedHashMap<String, Long>();
        Slot(Record task) { this.task = task; }
    }

    private final Object lock = new Object(), persistence = new Object();
    private final Map<String, Slot> slots = new LinkedHashMap<String, Slot>();
    private final Factory factory;
    private final Store store;
    private int maxParallel;
    private int active;
    private boolean schedulingEnabled;
    private volatile boolean cancelled;
    private String persistenceError = "";
    private volatile WorkObserver workObserver;

    public SubAgentManager(int maxParallel, Factory factory, Store store) throws Exception {
        if (factory == null) throw new IllegalArgumentException("Child factory is required");
        this.maxParallel = Math.max(1, Math.min(8, maxParallel));
        this.factory = factory; this.store = store;
        Record root = new Record(); root.id = ROOT; root.parentId = ""; root.name = ROOT;
        root.task = ""; root.status = IDLE; root.sessionId = 0;
        slots.put(ROOT, new Slot(root));
        if (store != null) {
            List<Record> saved = store.load();
            if (saved != null) for (Record record : saved) {
                Record copy = copy(record);
                // Goal leases belong to this process; recovered work binds to the restored root goal.
                for (int i = 0; i < copy.pending.length(); i++) copy.pending.getJSONObject(i).remove("usageLease");
                copy.collectedRevision = copy.acknowledgedRevision;
                copy.deliveredResults = new JSONObject(copy.acknowledgedResults.toString());
                copy.deliveredChildren = new JSONObject(copy.acknowledgedChildren.toString());
                if (copy.deliveredInbox.length() > 0) {
                    JSONArray restoredMail = new JSONArray(copy.deliveredInbox.toString());
                    for (int i = 0; i < copy.inbox.length(); i++) restoredMail.put(copy.inbox.get(i));
                    copy.inbox = restoredMail; copy.deliveredInbox = new JSONArray();
                }
                if (ROOT.equals(copy.id)) { slots.put(ROOT, new Slot(copy)); continue; }
                if (copy.id.length() == 0 || slots.containsKey(copy.id)) throw new IllegalStateException("重复或无效子任务登记。");
                if (RUNNING.equals(copy.status) || WAITING.equals(copy.status)) {
                    copy.status = QUEUED; copy.resume = copy.history.length() > 0;
                    copy.phase = QUEUED; copy.activeTool = "";
                }
                slots.put(copy.id, new Slot(copy));
            }
            int open = 0;
            for (Slot slot : slots.values()) if (!ROOT.equals(slot.task.id) && !CLOSED.equals(slot.task.status)) open++;
            if (open > MAX_AGENTS) throw new IllegalStateException("已登记子任务超过并发管理上限，不能静默忽略任务。");
        }
        cancelled = slots.get(ROOT).task.managerCancelled;
    }

    public void attachRoot(AgentLoop root) {
        synchronized (lock) { slots.get(ROOT).loop = root; }
    }

    public void setWorkObserver(WorkObserver observer) {
        workObserver = observer;
    }

    /** Restored queues remain dormant until an explicit resume or new user task. */
    public boolean hasLiveWork() {
        synchronized (lock) {
            for (Slot slot : slots.values()) if (!ROOT.equals(slot.task.id)) {
                if (slot.executing || !cancelled && schedulingEnabled && !slot.stop
                        && QUEUED.equals(slot.task.status)) return true;
            }
            return false;
        }
    }

    /** Invoke outside lock: hub observers may query this manager under their own lock. */
    private void notifyWorkChanged() {
        WorkObserver observer = workObserver;
        if (observer != null) try { observer.onWorkChanged(); }
        catch (RuntimeException ignored) { }
    }

    public void onParentIdle() { notifyWorkChanged(); }

    public void resumePending() {
        synchronized (lock) {
            cancelled = false;
            Record root = slots.get(ROOT).task;
            root.managerCancelled = false; root.userRestartOnly = false; root.revision++;
        }
        persistQuietly(ROOT);
        synchronized (lock) { schedulingEnabled = true; scheduleLocked(); }
        notifyWorkChanged();
    }

    /** Recover explicit child-only assignments while leaving the stopped parent and old sibling queues dormant. */
    public boolean resumeUserRestartOnlyPending() throws Exception {
        List<String> changed = new ArrayList<String>(); boolean found = false;
        synchronized (lock) {
            Record root = requireSlot(ROOT).task;
            if (!root.userRestartOnly || root.managerCancelled || root.rootWakeAllowed) return false;
            cancelled = false;
            for (Slot slot : slots.values()) if (!ROOT.equals(slot.task.id) && QUEUED.equals(slot.task.status)) {
                JSONObject current = currentTaskLocked(slot.task);
                boolean authorized = slot.task.inFlight.length() > 0 && current != null && current.optBoolean("userRestartOnly");
                if (slot.task.inFlight.length() > 0 && !authorized) {
                    finishAttemptLocked(slot.task, current, STOPPED, "", "主会话已停止，旧任务不会自动恢复。");
                    slot.task.inFlight = ""; slot.task.inFlightRequest = ""; slot.task.inFlightReference = "";
                    slot.task.inFlightStarted = false; slot.task.resume = false;
                }
                JSONArray requests = new JSONArray();
                for (int i = 0; i < slot.task.pending.length(); i++) {
                    JSONObject request = slot.task.pending.getJSONObject(i);
                    if (request.optBoolean("userRestartOnly")) requests.put(request);
                    else for (int j = 0; j < slot.task.tasks.length(); j++) {
                        JSONObject attempt = slot.task.tasks.getJSONObject(j);
                        if (request.optString("taskId").equals(attempt.optString("taskId")))
                            finishAttemptLocked(slot.task, attempt, STOPPED, "", "主会话已停止，旧任务不会自动恢复。");
                    }
                }
                slot.task.pending = requests;
                authorized = authorized || requests.length() > 0;
                if (authorized) { slot.stop = false; slot.ready = false; found = true; }
                else stopSlotLocked(slot, "主会话已停止，旧任务不会自动恢复。", new ArrayList<AgentLoop>());
                slot.task.revision++; changed.add(slot.task.id);
            }
        }
        for (String id : changed) persist(id);
        synchronized (lock) {
            for (String id : changed) { Slot slot = requireSlot(id); if (!slot.stop) slot.ready = true; }
            schedulingEnabled = true; scheduleLocked(); lock.notifyAll();
        }
        notifyWorkChanged(); return found;
    }

    public void setMaxParallel(int count) {
        synchronized (lock) { maxParallel = Math.max(1, Math.min(8, count)); scheduleLocked(); lock.notifyAll(); }
        notifyWorkChanged();
    }

    public JSONObject spawn(String owner, String name, String task, boolean fork) throws Exception {
        requireText(task);
        long lease = taskUsageLease(owner, false);
        AgentLoop source;
        synchronized (lock) { source = requireOwner(owner).loop; }
        JSONObject captured = factory instanceof ContextFactory
                ? ((ContextFactory) factory).capture(source) : new JSONObject();
        JSONArray forked = fork && source != null ? checkpoints(source.forkHistorySnapshot()) : new JSONArray();
        if (captured.has("system")) for (int i = 0; i < forked.length(); i++) {
            JSONObject message = forked.getJSONObject(i);
            if (Message.SYSTEM.equals(message.optString("role"))) message.put("content", captured.getString("system"));
        }
        Record record = new Record();
        synchronized (lock) {
            requireOwner(owner);
            int open = 0;
            for (Slot slot : slots.values()) if (!ROOT.equals(slot.task.id) && !CLOSED.equals(slot.task.status)) open++;
            if (open >= MAX_AGENTS) throw new IllegalStateException("子 agent 数量已达上限，请复用空闲子任务或关闭不再需要的子任务。");
            record.id = "agent_" + UUID.randomUUID().toString(); record.parentId = owner;
            record.name = name == null || name.trim().length() == 0 ? record.id : name.trim();
            record.task = task; record.status = QUEUED;
            record.phase = QUEUED; record.lastActivityAt = System.currentTimeMillis(); record.progressRevision = 1L;
            record.sessionId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
            if (record.sessionId == 0) record.sessionId = 1;
            record.forkHistory = forked;
            record.forkConfig = new JSONObject(captured.toString());
            JSONObject request = newTaskLocked(record, owner, task);
            record.pending.put(request.put("usageLease", lease)); record.revision++;
            armRootLocked();
            Slot created = new Slot(record); created.ready = false; slots.put(record.id, created);
        }
        try { persist(record.id); }
        catch (Exception error) { synchronized (lock) { slots.remove(record.id); } throw error; }
        persist(ROOT);
        JSONObject result;
        synchronized (lock) { requireSlot(record.id).ready = true; schedulingEnabled = true; scheduleLocked(); result = view(requireSlot(record.id)); }
        notifyWorkChanged();
        return result;
    }

    public JSONObject send(String owner, String target, String message, String kind) throws Exception {
        return send(owner, target, message, kind, false);
    }

    /** Called only by the user-facing child conversation; never exposed as a model tool. */
    public JSONObject sendFromUser(String target, String message, String kind) throws Exception {
        if (ROOT.equals(target)) throw new IllegalArgumentException("请选择一个子 agent。");
        return send(ROOT, target, message, kind, true);
    }

    private JSONObject send(String owner, String target, String message, String kind, boolean fromUser) throws Exception {
        requireText(message);
        if (!"message".equals(kind) && !"task".equals(kind)) throw new IllegalArgumentException("kind 必须是 message 或 task。");
        boolean assignment = "task".equals(kind);
        if (assignment && ROOT.equals(target)) throw new IllegalArgumentException("主会话只能接收协作消息，不能隐式追加用户任务。");
        long lease = taskUsageLease(owner, !assignment, fromUser);
        synchronized (lock) {
            Record rootState = requireSlot(ROOT).task;
            boolean trustedRestart = fromUser && ROOT.equals(owner) && assignment && (cancelled || rootState.userRestartOnly);
            Slot slot = requireSlot(target);
            boolean trustedRunningMessage = fromUser && ROOT.equals(owner) && !assignment && rootState.userRestartOnly
                    && slot.executing && !slot.stop;
            if (trustedRestart || trustedRunningMessage) requireSlot(owner); else requireOwner(owner);
            if (CLOSED.equals(slot.task.status)) throw new IllegalStateException("子任务已关闭。");
            if (slot.stop && slot.executing) throw new IllegalStateException("子任务仍在停止，请等待清理完成后复用。");
            if (!assignment && (slot.stop || FAILED.equals(slot.task.status) || STOPPED.equals(slot.task.status)))
                throw new IllegalStateException("任务已停止或失败。普通消息不会重新启动它；需明确派发新的 task。");
            JSONArray queue = assignment ? slot.task.pending : slot.task.inbox;
            if (queue.length() >= MAX_MAIL) throw new IllegalStateException("待处理消息已达上限，请先等待现有任务完成。");
            JSONObject entry = assignment ? newTaskLocked(slot.task, owner, message) : mail(owner, message).put("kind", "message");
            if (trustedRestart) {
                entry.put("userRestartOnly", true);
                for (int i = 0; i < slot.task.tasks.length(); i++) {
                    JSONObject attempt = slot.task.tasks.getJSONObject(i);
                    if (entry.getString("taskId").equals(attempt.optString("taskId"))) attempt.put("userRestartOnly", true);
                }
            }
            queue.put(entry.put("usageLease", trustedRestart ? DETACHED_USAGE_LEASE : lease)); slot.task.revision++;
            if (queue == slot.task.inbox) slot.task.inboxRevision++;
            if (!assignment) eventLocked(slot.task, "message", message, owner, target);
            if (assignment) {
                slot.stop = false;
                if (trustedRestart) {
                    cancelled = false; rootState.managerCancelled = false; rootState.userRestartOnly = true;
                    rootState.rootWakeAllowed = false; rootState.revision++;
                } else armRootLocked();
            }
            if (assignment && !slot.executing && !QUEUED.equals(slot.task.status)) {
                slot.task.status = QUEUED; slot.task.phase = QUEUED; slot.task.activeTool = ""; slot.ready = false;
            }
            if (!ROOT.equals(owner)) eventLocked(requireSlot(owner).task, "message", message, owner, target);
            lock.notifyAll();
        }
        try { persist(target); }
        catch (Exception error) {
            synchronized (lock) {
                Slot slot = requireSlot(target);
                if (!slot.executing && !ROOT.equals(target)) {
                    slot.task.status = FAILED; slot.task.error = "子任务消息保存失败：" + error.getMessage();
                    slot.task.revision++;
                }
            }
            throw error;
        }
        if (!ROOT.equals(owner) && !owner.equals(target)) persistQuietly(owner);
        if (assignment) persist(ROOT);
        JSONObject result;
        synchronized (lock) { requireSlot(target).ready = true; schedulingEnabled = true; scheduleLocked(); result = view(requireSlot(target)); }
        notifyWorkChanged();
        return result;
    }

    private void armRootLocked() {
        Record root = requireSlot(ROOT).task;
        root.rootWakeAllowed = true; root.revision++;
    }

    public boolean shouldWakeRoot() {
        synchronized (lock) {
            return !cancelled && requireSlot(ROOT).task.rootWakeAllowed && hasUncollectedResults(ROOT);
        }
    }

    public void disarmRootWake() {
        synchronized (lock) { Record root = requireSlot(ROOT).task; root.rootWakeAllowed = false; root.revision++; }
        persistQuietly(ROOT);
    }

    private JSONObject newTaskLocked(Record record, String owner, String request) throws Exception {
        String id = "task_" + UUID.randomUUID().toString();
        record.tasks.put(new JSONObject().put("taskId", id).put("request", request).put("status", QUEUED)
                .put("phase", QUEUED).put("progress", "").put("partial", "").put("error", "")
                .put("createdAt", System.currentTimeMillis()));
        if (record.currentTaskId.length() == 0) record.currentTaskId = id;
        eventLocked(record, "task", request, owner, record.id);
        record.events.getJSONObject(record.events.length() - 1).put("taskId", id);
        return mail(owner, request).put("kind", "task").put("taskId", id);
    }

    private void eventLocked(Record record, String kind, String text, String from, String to) throws Exception {
        record.events.put(new JSONObject().put("id", "event_" + UUID.randomUUID().toString())
                .put("taskId", record.currentTaskId).put("kind", kind).put("text", text)
                .put("from", from).put("to", to).put("createdAt", System.currentTimeMillis()));
        record.revision++;
    }

    /** Explicit child reports do not replace the final answer or create another task. */
    public JSONObject report(String owner, String kind, String text, String phase) throws Exception {
        if (ROOT.equals(owner)) throw new IllegalArgumentException("只有子 agent 可提交任务阶段汇报。");
        requireText(text);
        if (!"progress".equals(kind) && !"partial".equals(kind) && !"error".equals(kind))
            throw new IllegalArgumentException("汇报类型必须是 progress、partial 或 error，最终成果由任务完成时保存。");
        synchronized (lock) {
            Slot slot = requireOwner(owner); JSONObject task = currentTaskLocked(slot.task);
            if (task == null || !slot.executing) throw new IllegalStateException("当前没有运行中的任务。");
            if ("progress".equals(kind)) {
                slot.task.progress = clipped(text, 600); task.put("progress", text);
                if (phase != null && phase.length() > 0) { slot.task.phase = phase; task.put("phase", phase); }
            } else task.put(kind, text);
            eventLocked(slot.task, kind, text, owner, slot.task.parentId); lock.notifyAll();
        }
        persist(owner);
        String parent;
        synchronized (lock) { parent = requireSlot(owner).task.parentId; }
        send(owner, parent, "子任务阶段汇报（" + kind + "）" + (phase == null || phase.length() == 0 ? "" : "，阶段 " + phase)
                + "：\n" + text, "message");
        notifyWorkChanged();
        return new JSONObject().put("saved", true).put("kind", kind);
    }

    private JSONObject currentTaskLocked(Record record) {
        for (int i = record.tasks.length() - 1; i >= 0; i--) {
            JSONObject task = record.tasks.optJSONObject(i);
            if (task != null && record.currentTaskId.equals(task.optString("taskId"))) return task;
        }
        return null;
    }

    public boolean hasInbox(String owner) {
        synchronized (lock) { return requireSlot(owner).task.inbox.length() > 0; }
    }

    /** Keep messages queued until their complete note has reached the receiver's checkpoint. */
    public JSONObject peekInbox(String owner) throws Exception {
        synchronized (lock) {
            JSONArray messages = new JSONArray(); int chars = 0;
            Record receiver = requireSlot(owner).task;
            for (int i = 0; i < receiver.inbox.length(); i++) {
                JSONObject entry = new JSONObject(receiver.inbox.getJSONObject(i).toString()); entry.remove("usageLease");
                int size = entry.toString().length();
                if (messages.length() > 0 && chars + size > RESULT_BATCH_CHARS) break;
                messages.put(entry); chars += size;
            }
            return new JSONObject().put("messages", messages).put("moreMessages", messages.length() < receiver.inbox.length());
        }
    }

    public void acknowledgeInbox(String owner, JSONArray messageIds) throws Exception {
        synchronized (lock) {
            Record receiver = requireSlot(owner).task;
            JSONArray retained = new JSONArray(); boolean changed = false;
            for (int i = 0; i < receiver.inbox.length(); i++) {
                JSONObject entry = receiver.inbox.getJSONObject(i); boolean acknowledged = false;
                for (int j = 0; j < messageIds.length(); j++) if (entry.optString("id").equals(messageIds.optString(j))) {
                    acknowledged = true; break;
                }
                if (acknowledged) changed = true; else retained.put(entry);
            }
            if (!changed) return;
            receiver.inbox = retained; receiver.revision++; lock.notifyAll();
        }
        persist(owner);
    }

    public JSONObject list(String owner, int cursor) throws Exception {
        synchronized (lock) {
            requireSlot(owner);
            JSONArray agents = new JSONArray(); int index = 0, bytes = 0, next = -1;
            int start = Math.max(0, cursor);
            for (Slot slot : slots.values()) {
                if (ROOT.equals(slot.task.id)) continue;
                if (index++ < start) continue;
                JSONObject view = view(slot); int size = view.toString().length();
                if (bytes + size > RESULT_BATCH_CHARS) { next = index - 1; break; }
                agents.put(view); bytes += size;
            }
            return new JSONObject().put("agents", agents).put("inbox", inboxPreview(requireSlot(owner).task.inbox))
                    .put("pending", pendingLocked(owner, null)).put("cancelled", cancelled)
                    .put("nextCursor", next < 0 ? JSONObject.NULL : Integer.valueOf(next))
                    .put("totalAgents", slots.size() - 1);
        }
    }

    /** Wait for descendants to finish, yielding the caller's execution slot. */
    public JSONObject waitFor(String owner, String target, long timeoutMs) throws Exception {
        return waitInternal(owner, target, timeoutMs, false, -1L);
    }

    /** Wait for task progress or completion, yielding a child's execution slot. */
    public JSONObject waitForUpdate(String owner, String target, long timeoutMs, long cursor) throws Exception {
        return waitInternal(owner, target, timeoutMs, true, cursor);
    }

    private JSONObject waitInternal(String owner, String target, long timeoutMs, boolean updates, long cursor) throws Exception {
        long deadline = System.currentTimeMillis() + Math.min(MAX_WAIT_MS, Math.max(0, timeoutMs));
        Slot caller;
        synchronized (lock) {
            caller = ROOT.equals(owner) ? requireSlot(owner) : requireOwner(owner);
            if (owner.equals(target)) throw new IllegalArgumentException("子任务不能等待自身。");
            if (target != null && target.length() > 0) requireSlot(target);
            if (!ROOT.equals(owner) && target != null && target.length() > 0
                    && !descendantLocked(target, owner)) throw new IllegalArgumentException("子任务只可等待自己的后代，不能等待父任务或互相等待。");
            String key = target == null ? "" : target;
            long observed = cursor >= 0 ? cursor : caller.observedProgress.containsKey(key)
                    ? caller.observedProgress.get(key).longValue() : progressCursorLocked(owner, target);
            if (caller.executing && !caller.suspended) {
                caller.suspended = true; caller.task.status = WAITING; active--; scheduleLocked();
                updateProgressLocked(caller, "waiting", "", null);
            }
            try {
                while (!cancelled && !caller.stop && pendingLocked(owner, target)
                        && (!updates || (!hasInbox(owner) && progressCursorLocked(owner, target) <= observed))
                        && System.currentTimeMillis() < deadline) {
                    lock.wait(Math.max(1L, deadline - System.currentTimeMillis()));
                }
            } finally {
                if (caller.suspended) {
                    while (!cancelled && !caller.stop && active >= maxParallel) lock.wait(100L);
                    caller.suspended = false;
                    if (caller.executing) {
                        active++;
                        if (!caller.stop && !cancelled) {
                            caller.task.status = RUNNING;
                            updateProgressLocked(caller, "model", "", null);
                        }
                    }
                }
            }
            long next = progressCursorLocked(owner, target);
            caller.observedProgress.put(key, Long.valueOf(next));
            return snapshotLocked(owner, target).put("cursor", next);
        }
    }

    private long progressCursorLocked(String owner, String target) {
        // Waiting changes the caller's phase; only received mail and other tasks may wake it.
        long cursor = requireSlot(owner).task.inboxRevision;
        for (Slot slot : slots.values()) if (!ROOT.equals(slot.task.id) && !owner.equals(slot.task.id)
                && (target == null || target.length() == 0 || target.equals(slot.task.id))
                && (ROOT.equals(owner) || descendantLocked(slot.task.id, owner))) cursor += slot.task.revision;
        return cursor;
    }

    public String awaitSettled(long timeoutMs, LlmClient.RequestValidity validity) {
        try {
            synchronized (lock) {
                // A provisional final waits locally. Timed polls must not create repeated model requests.
                while (pendingLocked(ROOT, null) && (cancelled || !hasUncollectedResults(ROOT))
                        && (validity == null || validity.isCurrent())) lock.wait(250L);
            }
            if (validity != null && !validity.isCurrent()) return "{}";
            return collectResults();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    public boolean hasUncollectedResults() {
        return hasUncollectedResults(ROOT);
    }

    public boolean hasUncollectedResults(String owner) {
        synchronized (lock) {
            Slot caller = requireSlot(owner);
            if (caller.task.inbox.length() > 0) return true;
            for (Slot slot : slots.values()) if (!ROOT.equals(slot.task.id)
                    && (ROOT.equals(owner) || descendantLocked(slot.task.id, owner))) {
                for (int i = 0; i < slot.task.results.length(); i++) {
                    JSONObject result = slot.task.results.optJSONObject(i);
                    if (result != null && caller.task.deliveredResults.optInt(result.optString("resultId"), 0)
                            < deliveryLength(result)) return true;
                }
            }
            return false;
        }
    }

    public String collectResults() {
        try { return collectResults(ROOT, null).toString(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    public JSONObject collectResults(String owner, String target) throws Exception {
        JSONArray agents = new JSONArray(), inbox;
        List<String> changed = new ArrayList<String>();
        boolean pending;
        int bytes = 0;
        synchronized (lock) {
            Slot root = requireSlot(owner);
            inbox = new JSONArray(); JSONArray retained = new JSONArray();
            for (int i = 0; i < root.task.inbox.length(); i++) {
                JSONObject mail = new JSONObject(root.task.inbox.getJSONObject(i).toString());
                mail.remove("usageLease");
                int size = mail.toString().length();
                if (inbox.length() == 0 || bytes + size <= RESULT_BATCH_CHARS) {
                    inbox.put(mail); root.task.deliveredInbox.put(root.task.inbox.get(i)); bytes += size;
                }
                else retained.put(root.task.inbox.get(i));
            }
            root.task.inbox = retained;
            if (inbox.length() > 0) { root.task.revision++; changed.add(owner); }
            for (Slot slot : slots.values()) if (!ROOT.equals(slot.task.id)
                    && (target == null || target.length() == 0 || target.equals(slot.task.id))
                    && (ROOT.equals(owner) || descendantLocked(slot.task.id, owner))) {
                for (int i = 0; i < slot.task.results.length(); i++) {
                    JSONObject result = slot.task.results.getJSONObject(i);
                    String resultId = result.getString("resultId");
                    int offset = root.task.deliveredResults.optInt(resultId, 0);
                    if (offset >= deliveryLength(result)) continue;
                    int limit = 8000;
                    JSONObject chunk = resultChunk(slot.task.id, result, offset, limit);
                    int size = chunk.toString().length();
                    while (size > RESULT_BATCH_CHARS && limit > 1) {
                        limit = Math.max(1, limit / 2); chunk = resultChunk(slot.task.id, result, offset, limit);
                        size = chunk.toString().length();
                    }
                    if (bytes + size > RESULT_BATCH_CHARS) continue;
                    agents.put(chunk); bytes += size;
                    int end = chunk.getInt("endOffset");
                    root.task.deliveredResults.put(resultId, end == resultLength(result)
                            ? deliveryLength(result) : end);
                    root.task.revision++; changed.add(owner);
                }
            }
            pending = pendingLocked(owner, target);
        }
        for (String id : changed) persist(id);
        return new JSONObject().put("agents", agents).put("inbox", inbox).put("pending", pending)
                .put("moreResults", hasUncollectedResults(owner));
    }

    public List<AgentLoop> runtimeLoops() {
        synchronized (lock) {
            List<AgentLoop> loops = new ArrayList<AgentLoop>();
            for (Slot slot : slots.values()) if (!ROOT.equals(slot.task.id) && slot.loop != null) loops.add(slot.loop);
            return loops;
        }
    }

    /** Confirm delivery only after the owner's final answer has reached durable history. */
    public void acknowledgeResults() { acknowledgeResults(ROOT); }

    public void acknowledgeResults(String owner) {
        List<String> changed = new ArrayList<String>();
        try {
            synchronized (lock) {
                Slot caller = requireSlot(owner);
                caller.task.deliveredInbox = new JSONArray();
                caller.task.acknowledgedChildren = new JSONObject(caller.task.deliveredChildren.toString());
                // A truncated chunk is not confirmation of the unseen remainder.
                for (Slot slot : slots.values()) for (int i = 0; i < slot.task.results.length(); i++) {
                    JSONObject result = slot.task.results.getJSONObject(i);
                    String resultId = result.getString("resultId"); int length = deliveryLength(result);
                    if (caller.task.deliveredResults.optInt(resultId, 0) >= length)
                        caller.task.acknowledgedResults.put(resultId, length);
                }
                changed.add(owner);
                if (ROOT.equals(owner)) for (Slot slot : slots.values()) if (!ROOT.equals(slot.task.id)) {
                    slot.task.acknowledgedRevision = slot.task.collectedRevision; changed.add(slot.task.id);
                }
            }
            for (String id : changed) persist(id);
        } catch (Exception error) {
            synchronized (lock) { persistenceError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
        }
    }

    /** Failed parent persistence keeps all unconfirmed evidence eligible for the next valid turn. */
    public void replayUnconfirmed(String owner) throws Exception {
        synchronized (lock) {
            Record caller = requireSlot(owner).task;
            caller.deliveredResults = new JSONObject(caller.acknowledgedResults.toString());
            JSONArray mail = new JSONArray(caller.deliveredInbox.toString());
            for (int i = 0; i < caller.inbox.length(); i++) mail.put(caller.inbox.get(i));
            caller.inbox = mail; caller.deliveredInbox = new JSONArray(); caller.inboxRevision++; caller.revision++;
            lock.notifyAll();
        }
        persist(owner);
    }

    public Record find(String id) throws Exception {
        synchronized (lock) { return copy(requireSlot(id).task); }
    }

    public JSONObject readResult(String owner, String target, String resultId, int offset, int limit) throws Exception {
        JSONObject chunk;
        synchronized (lock) {
            Record receiver = requireSlot(owner).task, task = requireSlot(target).task;
            JSONObject result = null;
            for (int i = task.results.length() - 1; i >= 0; i--) {
                JSONObject candidate = task.results.getJSONObject(i);
                if (resultId.length() == 0 || resultId.equals(candidate.optString("resultId"))) { result = candidate; break; }
            }
            if (result == null) throw new IllegalArgumentException("成果不存在，运行中的任务尚未提交最终成果。");
            chunk = resultChunk(target, result, offset, limit);
            String id = result.getString("resultId");
            int delivered = receiver.deliveredResults.optInt(id, 0);
            if (chunk.getInt("offset") <= delivered) {
                int end = Math.max(delivered, chunk.getInt("endOffset"));
                receiver.deliveredResults.put(id, end >= resultLength(result)
                        ? deliveryLength(result) : end);
                receiver.revision++;
            }
        }
        persist(owner); return chunk;
    }

    /** Stable archived identities remain discoverable after model context compaction. Indexing is not delivery. */
    public JSONObject resultIndex(String owner, String target, int cursor) throws Exception {
        synchronized (lock) {
            requireSlot(owner); Record record = requireSlot(target).task;
            int start = Math.min(record.results.length(), Math.max(0, cursor));
            int end = Math.min(record.results.length(), start + 20); JSONArray rows = new JSONArray();
            for (int i = start; i < end; i++) {
                JSONObject result = record.results.getJSONObject(i); String taskId = result.getString("taskId"), request = "";
                for (int j = 0; j < record.tasks.length(); j++) {
                    JSONObject task = record.tasks.getJSONObject(j);
                    if (taskId.equals(task.optString("taskId"))) { request = task.optString("request"); break; }
                }
                rows.put(new JSONObject().put("taskId", taskId).put("resultId", result.getString("resultId"))
                        .put("status", result.optString("status")).put("request", clipped(request, 160))
                        .put("contentLength", result.optString("content").length()).put("partialLength", partialStream(result).length())
                        .put("errorLength", result.optString("error").length()).put("createdAt", result.optLong("createdAt")));
            }
            return new JSONObject().put("id", target).put("results", rows).put("totalResults", record.results.length())
                    .put("nextCursor", end < record.results.length() ? Integer.valueOf(end) : JSONObject.NULL);
        }
    }

    private static String partialStream(JSONObject result) {
        String partial = result.optString("partial");
        return partial.equals(result.optString("content")) ? "" : partial;
    }

    private static int resultLength(JSONObject result) { return result.optString("content").length() + partialStream(result).length(); }
    private static int deliveryLength(JSONObject result) { return Math.max(1, resultLength(result)); }

    private static JSONObject resultChunk(String agentId, JSONObject result, int offset, int limit) throws Exception {
        String content = result.optString("content"), partial = partialStream(result);
        int total = content.length() + partial.length();
        int start = Math.min(total, Math.max(0, offset));
        boolean partialPart = start >= content.length() && partial.length() > 0;
        String text = partialPart ? partial : content;
        int base = partialPart ? content.length() : 0;
        int local = start - base;
        if (local > 0 && local < text.length() && Character.isLowSurrogate(text.charAt(local))
                && Character.isHighSurrogate(text.charAt(local - 1))) { local--; start--; }
        int endLocal = Math.min(text.length(), local + Math.max(1, Math.min(8000, limit)));
        if (endLocal < text.length() && endLocal > local && Character.isHighSurrogate(text.charAt(endLocal - 1))
                && Character.isLowSurrogate(text.charAt(endLocal))) endLocal--;
        if (endLocal == local && local < text.length()) endLocal = Math.min(text.length(), local + 2);
        int end = base + endLocal;
        return new JSONObject().put("id", agentId).put("taskId", result.getString("taskId"))
                .put("resultId", result.getString("resultId")).put("status", result.optString("status"))
                .put("stream", partialPart ? "partial" : "content")
                .put("result", partialPart ? "" : text.substring(local, endLocal)).put("error", clipped(result.optString("error"), 2000))
                .put("errorTruncated", result.optString("error").length() > 2000)
                .put("partial", partialPart ? text.substring(local, endLocal) : "").put("offset", start).put("endOffset", end)
                .put("totalLength", total).put("contentLength", content.length()).put("partialLength", partial.length())
                .put("resultTruncated", end < total).put("nextOffset", end < total ? Integer.valueOf(end) : JSONObject.NULL);
    }

    public void accountUsage(String id, long tokens) {
        if (tokens <= 0) return;
        synchronized (lock) {
            Slot slot = requireSlot(id); slot.task.tokensUsed += tokens; slot.task.revision++;
        }
        persistQuietly(id);
    }

    public long usageLease(String id) {
        synchronized (lock) { return requireSlot(id).usageLease; }
    }

    private long taskUsageLease(String owner, boolean reportOnly) {
        return taskUsageLease(owner, reportOnly, false);
    }

    private long taskUsageLease(String owner, boolean reportOnly, boolean fromUser) {
        AgentLoop root;
        long childLease;
        boolean detached;
        synchronized (lock) {
            Slot caller = requireSlot(owner);
            childLease = caller.usageLease;
            root = slots.get(ROOT).loop;
            if (!reportOnly && !fromUser && requireSlot(ROOT).task.userRestartOnly)
                throw new IllegalStateException("主会话已停止，仅允许用户明确派发的新子任务；模型不能继续派活。");
            detached = requireSlot(ROOT).task.userRestartOnly && (reportOnly || fromUser);
        }
        if (!reportOnly && root != null && !(fromUser ? root.delegationBudgetAllowsWork() : root.delegationAllowed()))
            throw new IllegalStateException(fromUser ? "主任务预算已用尽，不能追加子任务。请先在主会话补充预算。"
                    : "当前没有派发子任务的授权：只有 ultra 可以主动派活，其他思考程度需要用户明确要求；目标预算用尽时也不能继续派活。");
        return detached ? DETACHED_USAGE_LEASE : ROOT.equals(owner) ? (root == null ? -1L : root.goalUsageLease()) : childLease;
    }

    public boolean hasPendingWork() {
        synchronized (lock) { return pendingLocked(ROOT, null); }
    }

    public boolean needsSettlement() {
        synchronized (lock) { return !cancelled && (pendingLocked(ROOT, null) || hasUncollectedResults()); }
    }

    public JSONObject close(String owner, String target) throws Exception {
        AgentLoop loop;
        synchronized (lock) {
            if (ROOT.equals(owner)) requireSlot(owner); else requireOwner(owner);
            Slot slot = requireSlot(target);
            if (ROOT.equals(target) || (!ROOT.equals(owner) && !owner.equals(slot.task.parentId)
                    && !owner.equals(target))) throw new IllegalArgumentException("只能关闭自己的子任务。");
            if (slot.executing || QUEUED.equals(slot.task.status) || RUNNING.equals(slot.task.status)
                    || WAITING.equals(slot.task.status) || pendingLocked(target, null))
                throw new IllegalStateException("任务或后代仍在运行，不能用关闭跳过等待和成果收集。请先完成任务，或由用户明确停止。");
            slot.stop = true; slot.task.status = CLOSED; slot.task.pending = new JSONArray();
            slot.task.phase = CLOSED; slot.task.activeTool = ""; slot.task.progressRevision++;
            slot.task.revision++; loop = slot.loop; lock.notifyAll();
        }
        if (loop != null) loop.cancel();
        closeDescendants(target);
        persist(target);
        JSONObject result;
        synchronized (lock) { result = view(requireSlot(target)); }
        notifyWorkChanged();
        return result;
    }

    /** Trusted UI operation. Model close cannot silently cancel unfinished delegated work. */
    public JSONObject stopFromUser(String target) throws Exception {
        if (ROOT.equals(target)) throw new IllegalArgumentException("请使用主会话停止按钮。");
        List<AgentLoop> loops = new ArrayList<AgentLoop>(); List<String> changed = new ArrayList<String>();
        synchronized (lock) {
            requireSlot(target);
            for (Slot slot : slots.values()) if (target.equals(slot.task.id) || descendantLocked(slot.task.id, target)) {
                if (CLOSED.equals(slot.task.status)) continue;
                stopSlotLocked(slot, "用户已停止任务。", loops); changed.add(slot.task.id);
            }
            lock.notifyAll();
        }
        for (AgentLoop loop : loops) loop.cancel();
        for (String id : changed) persist(id);
        notifyWorkChanged();
        synchronized (lock) { return view(requireSlot(target)); }
    }

    public void cancelAll() {
        stopWork(true);
    }

    public void cancelForObjectiveChange() {
        stopWork(false);
    }

    private void stopWork(boolean stopManager) {
        List<AgentLoop> loops = new ArrayList<AgentLoop>();
        List<String> changed = new ArrayList<String>();
        synchronized (lock) {
            if (cancelled) return;
            if (stopManager) {
                cancelled = true; schedulingEnabled = false;
                Record root = slots.get(ROOT).task;
                root.managerCancelled = true; root.rootWakeAllowed = false; root.revision++; changed.add(ROOT);
            }
            for (Slot slot : slots.values()) {
                if (ROOT.equals(slot.task.id) || slot.stop) continue;
                if (!slot.executing && !QUEUED.equals(slot.task.status)) continue;
                stopSlotLocked(slot, stopManager ? "子任务已取消。" : "目标已更新，旧任务已停止。", loops);
                changed.add(slot.task.id);
            }
            lock.notifyAll();
        }
        if (stopManager) persistQuietly(ROOT);
        for (AgentLoop loop : loops) loop.cancel();
        for (String id : changed) if (!ROOT.equals(id)) persistQuietly(id);
        notifyWorkChanged();
    }

    /** A child's own cancellation affects descendants without cancelling its peers or parent. */
    public void abortOwned(String owner) {
        if (ROOT.equals(owner)) { cancelAll(); return; }
        List<AgentLoop> loops = new ArrayList<AgentLoop>(); List<String> changed = new ArrayList<String>();
        synchronized (lock) {
            for (Slot slot : slots.values()) if (descendantLocked(slot.task.id, owner) && !slot.stop
                    && (slot.executing || QUEUED.equals(slot.task.status))) {
                stopSlotLocked(slot, "父子任务已停止。", loops); changed.add(slot.task.id);
            }
            lock.notifyAll();
        }
        for (AgentLoop loop : loops) loop.cancel();
        for (String id : changed) persistQuietly(id);
        notifyWorkChanged();
    }

    private void stopSlotLocked(Slot slot, String reason, List<AgentLoop> loops) {
        slot.stop = true; slot.task.pending = new JSONArray(); slot.task.inbox = new JSONArray(); slot.task.inFlight = "";
        slot.task.status = STOPPED; slot.task.error = reason; slot.task.phase = STOPPED; slot.task.activeTool = "";
        slot.task.progressRevision++; slot.task.resume = false;
        try {
            for (int i = 0; i < slot.task.tasks.length(); i++) {
                JSONObject attempt = slot.task.tasks.getJSONObject(i);
                if (!attempt.has("resultId") && !"completed".equals(attempt.optString("status")))
                    finishAttemptLocked(slot.task, attempt, STOPPED, "", reason);
            }
        } catch (Exception error) { persistenceError = error.toString(); }
        slot.task.revision++; if (slot.loop != null) loops.add(slot.loop);
    }

    private void finishAttemptLocked(Record record, JSONObject attempt, String status, String content, String error) throws Exception {
        if (attempt == null || attempt.has("resultId")) return;
        String resultId = "result_" + UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        attempt.put("status", status).put("phase", status).put("error", error).put("resultId", resultId).put("completedAt", now);
        record.results.put(new JSONObject().put("resultId", resultId).put("taskId", attempt.getString("taskId"))
                .put("status", status).put("content", content).put("error", error)
                .put("partial", attempt.optString("partial")).put("createdAt", now));
        eventLocked(record, "completed".equals(status) ? "final" : "error", "completed".equals(status) ? content : error,
                record.id, record.parentId);
        record.events.getJSONObject(record.events.length() - 1).put("taskId", attempt.getString("taskId"));
    }

    private void closeDescendants(String parent) throws Exception {
        List<String> children = new ArrayList<String>();
        synchronized (lock) {
            for (Slot slot : slots.values()) if (parent.equals(slot.task.parentId)
                    && !CLOSED.equals(slot.task.status)) children.add(slot.task.id);
        }
        for (String id : children) close(ROOT, id);
    }

    private void scheduleLocked() {
        if (cancelled || !schedulingEnabled) return;
        for (final Slot slot : slots.values()) {
            if (active >= maxParallel) return;
            if (ROOT.equals(slot.task.id) || slot.executing || slot.stop || !slot.ready || !QUEUED.equals(slot.task.status)) continue;
            if (slot.task.pending.length() == 0 && slot.task.inFlight.length() == 0) continue;
            slot.executing = true; slot.task.status = RUNNING; slot.task.error = ""; active++;
            updateProgressLocked(slot, "starting", "", "");
            new Thread(new Runnable() {
                @Override public void run() { runChild(slot); }
            }, "backcast-" + slot.task.id).start();
        }
    }

    private void runChild(final Slot slot) {
        try {
            notifyWorkChanged();
            long recoveredLease = taskUsageLease(ROOT, true);
            if (slot.loop == null) {
                Record config;
                synchronized (lock) { config = copy(slot.task); }
                final AgentLoop loop = factory.create(config, new AgentLoop.Quiet() {
                    @Override public void onRequestStart(int gen) {
                        reportProgress(slot, "model", "", null);
                        AgentLoop target;
                        synchronized (lock) { target = slot.stop || cancelled ? slot.loop : null; }
                        if (target != null) target.cancel();
                    }
                    @Override public void onError(int gen, String error) {
                        synchronized (lock) {
                            if (slot.stop || cancelled || CLOSED.equals(slot.task.status)) return;
                            slot.task.error = error == null ? "" : error;
                        }
                        reportProgress(slot, FAILED, "", error);
                    }
                    @Override public void onReasoning(int gen, String delta) { reportProgress(slot, "thinking", "", null); }
                    @Override public void onAssistantText(int gen, String delta) {
                        synchronized (lock) {
                            if (!slot.stop && !cancelled) try {
                                JSONObject task = currentTaskLocked(slot.task);
                                if (task != null) task.put("partial", task.optString("partial") + (delta == null ? "" : delta));
                            } catch (Exception invalid) { throw new IllegalStateException(invalid); }
                        }
                        reportProgress(slot, "responding", "", delta);
                    }
                    @Override public void onToolPreview(int gen, int index, String id, String name, String args) {
                        reportProgress(slot, "generating_tool", name == null ? "" : name, null);
                    }
                    @Override public void onToolStart(int gen, String name, String args) {
                        reportProgress(slot, "wait_agent".equals(name) || "wait_agents".equals(name) ? "waiting" : "tool", name, null);
                    }
                    @Override public void onToolEnd(int gen, String name, String result) { reportProgress(slot, "reviewing", "", null); }
                    @Override public void onCompactStart(int gen) { reportProgress(slot, "compacting", "", null); }
                    @Override public void onCompacted(int gen, boolean followup) { reportProgress(slot, "model", "", null); }
                    @Override public void onProgress(int gen, String phase, String name, String detail) {
                        reportStage(slot, phase, name);
                    }
                }, this);
                if (loop == null) throw new IllegalStateException("Child factory returned no loop");
                loop.bindSession(slot.task.sessionId);
                loop.setCoordinationMailbox(this, slot.task.id);
                if (slot.task.forkConfig.has("taskPaths")) loop.setCapturedTaskPaths(slot.task.forkConfig.optJSONArray("taskPaths"));
                synchronized (lock) { loop.setDelegationParent(slots.get(ROOT).loop); }
                if (slot.task.history.length() > 0) {
                    List<Message> history = messages(slot.task.history);
                    String prompt = "";
                    for (Message message : loop.historySnapshot()) if (Message.SYSTEM.equals(message.role)) { prompt = message.content; break; }
                    loop.loadHistory(prompt, history);
                } else if (slot.task.forkHistory.length() > 0) loop.loadForkHistory(messages(slot.task.forkHistory));
                loop.setRecorder(new AgentLoop.Recorder() {
                    @Override public void record(long sessionId, Message message) {
                        if (Message.USER.equals(message.role)) synchronized (lock) { slot.task.inFlightStarted = true; }
                        checkpoint(slot, loop);
                    }
                    @Override public void replace(long sessionId, List<Message> history) { checkpoint(slot, loop); }
                });
                synchronized (lock) { slot.loop = loop; if (slot.stop || cancelled) loop.cancel(); }
            }
            String input, request, reference;
            boolean resume;
            synchronized (lock) {
                if (slot.stop || cancelled) return;
                resume = slot.task.resume && slot.task.inFlightStarted && slot.task.inFlight.length() > 0;
                input = slot.task.inFlight;
                request = slot.task.inFlightRequest;
                reference = slot.task.inFlightReference;
                if (input.length() == 0 && slot.task.pending.length() > 0) {
                    JSONObject mail = slot.task.pending.getJSONObject(0);
                    slot.task.pending = tail(slot.task.pending);
                    input = "Message from " + mail.optString("from", ROOT) + ":\n" + mail.getString("text");
                    request = mail.getString("text");
                    slot.task.currentTaskId = mail.getString("taskId");
                    slot.task.task = request;
                    slot.task.result = ""; slot.task.error = "";
                    JSONObject attempt = currentTaskLocked(slot.task);
                    if (attempt != null) attempt.put("status", RUNNING).put("phase", "starting").put("startedAt", System.currentTimeMillis());
                    slot.task.progress = "";
                    slot.task.activeTool = "";
                    slot.task.lastActivityAt = System.currentTimeMillis();
                    slot.task.progressRevision++;
                    reference = mail.optString("reference", "");
                    slot.usageLease = mail.optLong("usageLease", recoveredLease);
                } else {
                    slot.usageLease = recoveredLease;
                }
                if (request.length() == 0) request = input;
                if (!resume) slot.task.inFlightStarted = false;
                slot.task.inFlight = input; slot.task.resume = true; slot.task.revision++;
                slot.task.inFlightRequest = request; slot.task.inFlightReference = reference;
            }
            persist(slot.task.id);
            if (slot.stop || cancelled) return;
            if (resume && slot.loop.needsResume()) slot.loop.resume(slot.task.sessionId, ++slot.uiToken);
            else if (!resume) slot.loop.submitDelegated(request, reference, slot.task.currentTaskId,
                    slot.task.sessionId, slot.loop.generation(), ++slot.uiToken);
            if (slot.loop.wasRefused()) synchronized (lock) { slot.task.error = PromptGuard.REFUSAL; }
            while (!slot.stop && !cancelled && slot.task.error.length() == 0
                    && (pendingForOwner(slot.task.id) || hasUncollectedResults(slot.task.id))) {
                waitFor(slot.task.id, null, MAX_WAIT_MS);
                if (slot.stop || cancelled) break;
                JSONObject collected = collectResults(slot.task.id, null);
                if (collected.getJSONArray("agents").length() > 0 || collected.getJSONArray("inbox").length() > 0) {
                    slot.loop.submitDelegated("Verify the delegated results and finish your assigned task before the final answer.",
                            collected.toString(), slot.task.currentTaskId,
                            slot.task.sessionId, slot.loop.generation(), ++slot.uiToken);
                }
            }
            // Ordinary mail remains mail; it never becomes an implicit follow-up assignment.
            checkpoint(slot, slot.loop);
            if (!slot.stop && !cancelled && slot.task.error.length() == 0) acknowledgeResults(slot.task.id);
            synchronized (lock) {
                if (!slot.stop && !cancelled) {
                    slot.task.result = slot.task.error.length() == 0 ? finalAnswer(slot.loop.historySnapshot(),
                            slot.task.currentTaskId, slot.task.inFlightRequest) : "";
                    if (slot.task.error.length() == 0 && slot.task.result.trim().length() == 0)
                        slot.task.error = "本次任务没有提交最终成果。";
                    finishAttemptLocked(slot.task, currentTaskLocked(slot.task), slot.task.error.length() == 0 ? "completed" : FAILED,
                            slot.task.result, slot.task.error);
                }
                slot.task.inFlight = ""; slot.task.resume = false; slot.task.inFlightStarted = false;
                slot.task.inFlightRequest = ""; slot.task.inFlightReference = "";
                if (!slot.stop && !cancelled) slot.task.status = slot.task.error.length() > 0 ? FAILED
                        : (slot.task.pending.length() > 0 ? QUEUED : IDLE);
                slot.task.revision++;
                updateProgressLocked(slot, FAILED.equals(slot.task.status) ? FAILED
                        : QUEUED.equals(slot.task.status) ? QUEUED : slot.stop ? slot.task.phase : "completed", "", null);
            }
        } catch (Throwable error) {
            synchronized (lock) {
                if (!slot.stop && !cancelled) {
                    slot.task.error = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                    slot.task.result = ""; slot.task.status = FAILED;
                    try { finishAttemptLocked(slot.task, currentTaskLocked(slot.task), FAILED, "", slot.task.error); }
                    catch (Exception invalid) { persistenceError = invalid.toString(); }
                }
                slot.task.inFlight = ""; slot.task.resume = false; slot.task.inFlightStarted = false; slot.task.revision++;
                updateProgressLocked(slot, FAILED, "", null);
            }
        } finally {
            persistQuietly(slot.task.id);
            synchronized (lock) {
                if (!slot.suspended) active--;
                slot.executing = false; slot.suspended = false; lock.notifyAll();
                scheduleLocked(); lock.notifyAll();
            }
            notifyWorkChanged();
        }
    }

    private void updateProgressLocked(Slot slot, String phase, String tool, String summary) {
        if (slot.stop || CLOSED.equals(slot.task.status)) return;
        Record task = slot.task;
        if (phase.equals(task.phase) && tool.equals(task.activeTool) && summary == null) return;
        task.phase = phase; task.activeTool = tool;
        if (summary != null) task.progress = clipped(summary, 600);
        task.lastActivityAt = System.currentTimeMillis(); task.progressRevision++; task.revision++;
        try {
            JSONObject attempt = currentTaskLocked(task);
            if (attempt != null && !attempt.has("resultId")) {
                attempt.put("status", slot.task.status).put("phase", phase).put("activeTool", tool).put("progress", task.progress);
            }
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
        lock.notifyAll();
    }

    private void reportProgress(Slot slot, String phase, String tool, String summary) {
        boolean save;
        synchronized (lock) {
            String previous = slot.task.phase;
            long now = System.currentTimeMillis();
            if (previous.equals(phase) && tool.equals(slot.task.activeTool)
                    && now - slot.task.lastActivityAt < 1000L) return;
            updateProgressLocked(slot, phase, tool, summary);
            save = !previous.equals(phase) || now - slot.lastProgressPersist >= 1000L;
            if (save) slot.lastProgressPersist = now;
        }
        if (save) persistQuietly(slot.task.id);
    }

    private void reportStage(Slot slot, String phase, String tool) {
        if (phase == null || phase.length() == 0) return;
        String name = tool == null ? "" : tool;
        synchronized (lock) {
            if (slot.stop || CLOSED.equals(slot.task.status)) return;
            updateProgressLocked(slot, phase, name, null);
            slot.lastProgressPersist = System.currentTimeMillis();
        }
        // Tool detail is often a complete script/JSON payload; it belongs to
        // history, never to the compact progress.
        persistQuietly(slot.task.id);
    }

    private void checkpoint(Slot slot, AgentLoop loop) {
        try {
            JSONArray history = checkpoints(loop.historySnapshot());
            synchronized (lock) { slot.task.history = history; slot.task.revision++; }
            persist(slot.task.id);
        } catch (Exception error) {
            synchronized (lock) { slot.task.error = "子任务状态保存失败：" + error.getMessage(); }
            loop.cancel();
        }
    }

    private boolean pendingLocked(String owner, String target) {
        for (Slot slot : slots.values()) {
            if (ROOT.equals(slot.task.id) || owner.equals(slot.task.id)) continue;
            if (target != null && target.length() > 0 && !target.equals(slot.task.id)) continue;
            if ((target == null || target.length() == 0) && !ROOT.equals(owner)
                    && !descendantLocked(slot.task.id, owner)) continue;
            if (slot.executing || QUEUED.equals(slot.task.status)) return true;
        }
        return false;
    }

    private boolean pendingForOwner(String owner) { synchronized (lock) { return pendingLocked(owner, null); } }

    private JSONObject snapshotLocked(String owner, String target) throws Exception {
        JSONArray agents = new JSONArray(); int chars = 0;
        for (Slot slot : slots.values()) {
            if (ROOT.equals(slot.task.id)) continue;
            if (target == null || target.length() == 0 || target.equals(slot.task.id)) {
                JSONObject view = view(slot); int size = view.toString().length();
                if (chars + size > RESULT_BATCH_CHARS) break;
                agents.put(view); chars += size;
            }
        }
        Slot caller = requireSlot(owner);
        JSONArray inbox = inboxPreview(caller.task.inbox);
        return new JSONObject().put("agents", agents).put("inbox", inbox)
                .put("pending", pendingLocked(owner, target)).put("cancelled", cancelled)
                .put("persistenceError", persistenceError.length() == 0 ? JSONObject.NULL : persistenceError);
    }

    private JSONObject view(Slot slot) throws Exception {
        Record task = slot.task;
        return new JSONObject().put("id", task.id).put("parentId", task.parentId).put("name", clipped(task.name, 256))
                .put("task", clipped(task.task, 2000)).put("status", task.status)
                .put("taskId", task.currentTaskId).put("taskCount", task.tasks.length()).put("resultCount", task.results.length())
                .put("phase", task.phase).put("activeTool", task.activeTool).put("progress", task.progress)
                .put("lastActivityAt", task.lastActivityAt).put("progressRevision", task.progressRevision)
                .put("result", slot.executing || QUEUED.equals(task.status) ? "" : clipped(task.result, 8000))
                .put("resultTruncated", !slot.executing && !QUEUED.equals(task.status) && task.result.length() > 8000)
                .put("taskTruncated", task.task.length() > 2000)
                .put("error", task.error.length() == 0 ? JSONObject.NULL : clipped(task.error, 2000))
                .put("pendingMessages", task.pending.length() + task.inbox.length()).put("revision", task.revision).put("tokensUsed", task.tokensUsed);
    }

    private Slot requireSlot(String id) {
        Slot slot = slots.get(id);
        if (slot == null) throw new IllegalArgumentException("子任务不存在：" + id);
        return slot;
    }

    private Slot requireOwner(String id) {
        Slot slot = requireSlot(id);
        if (cancelled || slot.stop || CLOSED.equals(slot.task.status) || FAILED.equals(slot.task.status)
                || STOPPED.equals(slot.task.status) || ROOT.equals(id) && slot.task.userRestartOnly)
            throw new IllegalStateException("当前任务已停止。普通消息或模型派活不会重新启动它。");
        return slot;
    }

    private boolean descendantLocked(String child, String ancestor) {
        int remaining = MAX_AGENTS + 1;
        Slot slot = slots.get(child);
        while (slot != null && !ROOT.equals(slot.task.id) && remaining-- > 0) {
            if (ancestor.equals(slot.task.parentId)) return true;
            slot = slots.get(slot.task.parentId);
        }
        return false;
    }

    private void persist(String id) throws Exception {
        if (store == null) return;
        synchronized (persistence) {
            Record task;
            synchronized (lock) { task = copy(requireSlot(id).task); }
            store.save(task);
        }
    }

    private void persistQuietly(String id) {
        try { persist(id); }
        catch (Exception error) { synchronized (lock) { persistenceError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); } }
    }

    private static Record copy(Record task) throws Exception { return Record.fromJson(new JSONObject(task.toJson().toString())); }
    private static String clipped(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = limit;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end);
    }
    private static JSONObject mailPreview(JSONObject original) throws Exception {
        JSONObject mail = new JSONObject(original.toString()); String text = mail.optString("text", "");
        mail.remove("usageLease");
        return mail.put("text", clipped(text, 4000)).put("truncated", text.length() > 4000);
    }
    private static JSONArray inboxPreview(JSONArray source) throws Exception {
        JSONArray preview = new JSONArray(); int chars = 0;
        for (int i = 0; i < source.length(); i++) {
            JSONObject mail = mailPreview(source.getJSONObject(i)); int size = mail.toString().length();
            if (chars + size > RESULT_BATCH_CHARS / 2) break;
            preview.put(mail); chars += size;
        }
        return preview;
    }
    private static void requireText(String text) {
        if (text == null || text.trim().length() == 0 || text.length() > MAX_TEXT) throw new IllegalArgumentException("任务消息不能为空或超过 " + MAX_TEXT + " 字符。");
    }
    private static JSONObject mail(String from, String text) throws Exception {
        return new JSONObject().put("from", from).put("text", text).put("id", UUID.randomUUID().toString());
    }
    private static JSONArray tail(JSONArray array) throws Exception {
        JSONArray result = new JSONArray(); for (int i = 1; i < array.length(); i++) result.put(array.get(i)); return result;
    }
    private static JSONArray checkpoints(List<Message> messages) throws Exception {
        JSONArray result = new JSONArray(); for (Message message : messages) result.put(message.toCheckpointJson()); return result;
    }
    private static List<Message> messages(JSONArray array) throws Exception {
        List<Message> messages = new ArrayList<Message>();
        for (int i = 0; i < array.length(); i++) messages.add(Message.fromCheckpointJson(array.getJSONObject(i)));
        return messages;
    }
    private static String finalAnswer(List<Message> history, String taskId, String request) {
        String candidate = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (Message.ASSISTANT.equals(message.role) && candidate == null
                    && (message.toolCalls == null || message.toolCalls.length() == 0))
                candidate = message.content == null ? "" : message.content;
            if (Message.USER.equals(message.role) && message.delegatedRequest != null) {
                boolean assigned = taskId.equals(message.agentTaskId)
                        || message.agentTaskId == null && request.equals(message.delegatedRequest);
                return assigned && candidate != null ? candidate : "";
            }
        }
        return "";
    }
}
