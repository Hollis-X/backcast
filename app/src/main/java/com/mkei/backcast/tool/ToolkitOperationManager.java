package com.mkei.backcast.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Application-owned operations. Views subscribe; detaching a view never cancels work. */
public final class ToolkitOperationManager {
    public interface FactorySource { ToolBatchProbe.SessionFactory capture() throws Exception; }
    public interface Listener { void onState(Snapshot state); }
    public interface WorkListener { void onWorkChanged(); }
    public interface Diagnostics {
        void onFailure(String action, String tool, String stage, Throwable failure, EmbeddedToolchain.Progress progress);
    }
    public static final class Snapshot {
        public final long id;
        public final long revision;
        public final String action, tool, state, context;
        public final boolean busy;
        public final boolean refreshing;
        public final EmbeddedToolchain.Progress installProgress;
        public final List<ToolBatchProbe.Progress> probes;
        private final String response, inventory;
        private Snapshot(Operation operation, JSONObject inventory, long revision, boolean refreshing) {
            this.refreshing = refreshing;
            this.revision = revision;
            id = operation == null ? 0 : operation.id;
            action = operation == null ? "" : operation.action;
            tool = operation == null ? "" : operation.tool;
            state = operation == null ? "idle" : operation.state;
            context = operation == null ? "" : operation.context;
            busy = operation != null && !operation.terminal;
            installProgress = operation == null ? null : operation.installProgress;
            probes = java.util.Collections.unmodifiableList(operation == null ? new ArrayList<ToolBatchProbe.Progress>()
                    : new ArrayList<ToolBatchProbe.Progress>(operation.probes.values()));
            response = operation == null || operation.result == null ? "" : operation.result.toString();
            this.inventory = inventory == null ? "" : inventory.toString();
        }
        public JSONObject result() throws Exception { return response.length() == 0 ? null : new JSONObject(response); }
        public JSONObject inventory() throws Exception { return inventory.length() == 0 ? null : new JSONObject(inventory); }
    }
    private static final class Operation {
        final long id; final String action, tool, context;
        volatile boolean cancelled;
        boolean terminal;
        String state = "running";
        JSONObject result;
        EmbeddedToolchain.Progress installProgress;
        final Map<String, ToolBatchProbe.Progress> probes = new LinkedHashMap<String, ToolBatchProbe.Progress>();
        final Map<ToolBatchProbe.Session, Thread> sessions = new LinkedHashMap<ToolBatchProbe.Session, Thread>();
        Operation(long id, String action, String tool, String context) { this.id = id; this.action = action; this.tool = tool; this.context = context; }
    }
    private final FactorySource source;
    private final Diagnostics diagnostics;
    private final WorkListener workListener;
    private static final java.util.concurrent.ThreadFactory WORKERS = new java.util.concurrent.ThreadFactory() {
        public Thread newThread(Runnable task) { Thread thread = new Thread(task, "backcast-tool-configuration"); thread.setDaemon(true); return thread; }
    };
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(WORKERS);
    private final ExecutorService cancellation = Executors.newFixedThreadPool(ToolBatchProbe.PARALLELISM, WORKERS);
    private final List<Listener> listeners = new ArrayList<Listener>();
    private Operation operation;
    private JSONObject inventory;
    private long nextId;
    private long revision;
    private boolean refreshing;

    public ToolkitOperationManager(FactorySource source, Diagnostics diagnostics, WorkListener workListener) {
        this.source = source; this.diagnostics = diagnostics; this.workListener = workListener;
    }
    public synchronized Snapshot snapshot() { return new Snapshot(operation, inventory, revision, refreshing); }
    public synchronized boolean busy() { return operation != null && !operation.terminal; }
    public String configurationContext() {
        try { synchronized (this) { return source.capture().context() + "|" + packageIdentity(inventory); } }
        catch (Exception unavailable) { return ""; }
    }
    public void subscribe(Listener listener) {
        Snapshot initial;
        synchronized (this) { if (!listeners.contains(listener)) listeners.add(listener); initial = snapshot(); }
        try { listener.onState(initial); } catch (RuntimeException invalidView) { report("observer", "", "subscribe", invalidView, null); }
    }
    public synchronized void unsubscribe(Listener listener) { listeners.remove(listener); }

    public void refreshInventory() {
        synchronized (this) { if (busy() || refreshing) return; refreshing = true; }
        publish();
        coordinator.execute(new Runnable() { @Override public void run() {
            ToolBatchProbe.Session session = null;
            try {
                ToolBatchProbe.SessionFactory factory = source.capture();
                session = factory.open();
                JSONObject loaded = session.toolkit().listing();
                synchronized (ToolkitOperationManager.this) {
                    inventory = loaded;
                    String context = factory.context() + "|" + packageIdentity(loaded);
                    if (operation != null && operation.terminal && !context.equals(operation.context)) operation = null;
                }
            } catch (Exception failure) { report("list", "", "inventory", failure, null); }
            finally {
                if (session != null) try { session.close(); }
                catch (Exception failure) { report("list", "", "cleanup", failure, null); }
                synchronized (ToolkitOperationManager.this) { refreshing = false; }
                publish();
            }
        } });
    }

    public long start(String action, String tool) {
        if (!("batch_status".equals(action) || "status".equals(action) || "package_install".equals(action)
                || "package_remove".equals(action))) throw new IllegalArgumentException("未知工具配置操作：" + action);
        final Operation owner;
        final ToolBatchProbe.SessionFactory frozen;
        synchronized (this) {
            if (busy() || refreshing) throw new IllegalStateException("工具配置操作仍在进行中。");
            try { frozen = source.capture(); }
            catch (Exception failure) { throw new IllegalStateException("无法捕获工具配置。", failure); }
            owner = new Operation(++nextId, action, tool, frozen.context() + "|" + packageIdentity(inventory));
            operation = owner;
        }
        publish(); changed();
        coordinator.execute(new Runnable() { @Override public void run() { execute(owner, frozen); } });
        return owner.id;
    }

    /** Only the explicit cancel control calls this; keep busy until owned cleanup settles. */
    public void cancel(long id) {
        final Operation owner;
        Map<ToolBatchProbe.Session, Thread> running;
        synchronized (this) {
            owner = operation;
            if (owner == null || owner.id != id || owner.terminal || owner.cancelled) return;
            owner.cancelled = true; owner.state = "cancelling";
            running = new LinkedHashMap<ToolBatchProbe.Session, Thread>(owner.sessions);
        }
        for (final Map.Entry<ToolBatchProbe.Session, Thread> entry : running.entrySet()) {
            entry.getValue().interrupt();
            cancellation.execute(new Runnable() { @Override public void run() {
                try { entry.getKey().abort(); }
                catch (RuntimeException failure) { report(owner.action, owner.tool, "cancel", failure, owner.installProgress); }
            } });
        }
        publish();
    }

    private void execute(final Operation owner, final ToolBatchProbe.SessionFactory frozen) {
        JSONObject result = new JSONObject();
        final ToolBatchProbe.SessionFactory owned = new ToolBatchProbe.SessionFactory() {
            @Override public ToolBatchProbe.Session open() throws Exception {
                check(owner);
                final ToolBatchProbe.Session session = frozen.open();
                synchronized (ToolkitOperationManager.this) { owner.sessions.put(session, Thread.currentThread()); }
                return new ToolBatchProbe.Session() {
                    public ToolkitTool toolkit() { return session.toolkit(); }
                    public void abort() { session.abort(); }
                    public void close() throws Exception {
                        try { session.close(); }
                        finally { synchronized (ToolkitOperationManager.this) { owner.sessions.remove(session); } }
                    }
                };
            }
        };
        ToolBatchProbe.Session single = null;
        try {
            check(owner);
            if ("batch_status".equals(owner.action)) result = ToolBatchProbe.run(owned, new ToolchainInstaller.Cancellation() {
                public void check() throws Exception { ToolkitOperationManager.this.check(owner); }
            }, new ToolBatchProbe.Listener() { public void onProgress(ToolBatchProbe.Progress progress) {
                synchronized (ToolkitOperationManager.this) { if (owner.terminal) return; owner.probes.put(progress.id, progress); }
                publish();
            } }, new ToolBatchProbe.FailureListener() { public void onFailure(String id, String stage, Throwable failure) {
                report(owner.action, id, stage, failure, null);
            } });
            else {
                single = owned.open(); check(owner);
                EmbeddedToolchain.ProgressListener progress = new EmbeddedToolchain.ProgressListener() {
                    public void onProgress(EmbeddedToolchain.Progress value) {
                        synchronized (ToolkitOperationManager.this) { if (owner.terminal) return; owner.installProgress = value; }
                        publish();
                    }
                };
                if ("package_install".equals(owner.action)) result = single.toolkit().installBundled(progress);
                else if ("package_remove".equals(owner.action)) result = single.toolkit().removeBundled(progress);
                else result = single.toolkit().status(owner.tool);
            }
        } catch (InterruptedException interrupted) {
            Thread.interrupted();
            result = terminalResult(owner.cancelled ? "cancelled" : "interrupted", "");
            if (!owner.cancelled) report(owner.action, owner.tool, "interrupted", interrupted, owner.installProgress);
        } catch (Exception failure) {
            report(owner.action, owner.tool, "execute", failure, owner.installProgress);
            result = terminalResult(owner.cancelled ? "cancelled" : "error", owner.cancelled ? "" : "工具配置操作失败。");
        } finally {
            if (single != null) try { single.close(); }
            catch (Exception cleanup) {
                report(owner.action, owner.tool, "cleanup", cleanup, owner.installProgress);
                try { result.put("cleanup_pending", true); } catch (Exception ignored) { }
            }
            Thread.interrupted();
        }
        synchronized (this) {
            if (owner.terminal) return;
            if (owner.cancelled) result = terminalResult("cancelled", "");
            owner.result = result; owner.state = result.optString("state", "error"); owner.terminal = true;
        }
        publish(); changed();
        if ("package_install".equals(owner.action) || "package_remove".equals(owner.action)) refreshInventory();
    }
    private void check(Operation owner) throws InterruptedException {
        if (owner.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException("工具操作已中断。");
    }
    private static JSONObject terminalResult(String state, String error) {
        try { return new JSONObject().put("state", state).put("error", error); }
        catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }
    private static String packageIdentity(JSONObject listing) {
        JSONObject bundle = listing == null ? null : listing.optJSONObject("package");
        return bundle == null ? "" : bundle.optBoolean("installed") + "|" + bundle.optString("version")
                + "|" + String.valueOf(bundle.optJSONObject("versions"));
    }
    private void report(String action, String tool, String stage, Throwable failure, EmbeddedToolchain.Progress progress) {
        if (diagnostics != null) try { diagnostics.onFailure(action, tool, stage, failure, progress); }
        catch (RuntimeException unavailable) { /* Diagnostic failures cannot change an operation's outcome. */ }
    }
    private void changed() { if (workListener != null) workListener.onWorkChanged(); }
    private void publish() {
        Snapshot state; List<Listener> subscribers;
        synchronized (this) { revision++; state = snapshot(); subscribers = new ArrayList<Listener>(listeners); }
        for (Listener listener : subscribers) try { listener.onState(state); }
        catch (RuntimeException invalidView) { report("observer", "", "publish", invalidView, null); }
    }
}
