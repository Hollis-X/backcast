package com.mkei.backcast.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.mkei.backcast.ChatStore;
import com.mkei.backcast.McpConfigActivity;
import com.mkei.backcast.RunHub;
import com.mkei.backcast.agent.Diagnostics;
import com.mkei.backcast.mcp.McpCatalog;
import com.mkei.backcast.mcp.McpSelection;
import com.mkei.backcast.mcp.McpServer;
import com.mkei.backcast.mcp.McpStore;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** User-selected MCP tools become editable drafts; listing never executes a remote tool. */
public final class McpToolPicker {
    public interface Host {
        long context();
        long session();
        boolean current(long context);
        String text();
        void draft(String text);
        void hideMenu();
        void refreshMenu();
    }
    private final Context context;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService REFRESH_WORKERS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "backcast-mcp-menu-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private int generation;
    private boolean menuOpen;
    private RefreshBatch refreshBatch;
    private AlertDialog dialog;
    private McpSelection selection;
    private String selectedText;
    private List<McpCatalog.Server> catalog;

    public McpToolPicker(Context context, Host host) { this.context = context; this.host = host; }

    public void append(List<SlashMenuPopup.Item> items, String prefix) {
        final long owner = host.context();
        final int mine = generation;
        if (catalog == null) {
            items.add(new SlashMenuPopup.Item("正在读取 MCP 工具列表…", "", null));
            return;
        }
        List<McpCatalog.Server> servers = catalog;
        String query = prefix.toLowerCase(Locale.ROOT);
        if (query.equals("mcp")) query = "";
        else if (query.startsWith("mcp ")) query = query.substring(4).trim();
        int shown = 0, omitted = 0;
        for (final McpCatalog.Server server : servers) {
            boolean serverMatches = matches(server.name, query) || matches(server.id, query);
            boolean matchingTool = false;
            for (McpSelection tool : server.tools) if (serverMatches || matches(tool.toolName, query)
                    || matches(tool.description, query)) { matchingTool = true; break; }
            if (!serverMatches && !matchingTool) continue;
            items.add(new SlashMenuPopup.Item("MCP · " + server.name,
                    server.tools.isEmpty() ? "暂无工具" : server.tools.size() + " 个工具", null));
            for (final McpSelection tool : server.tools) {
                if (!serverMatches && !matches(tool.toolName, query) && !matches(tool.description, query)) continue;
                if (shown >= 80) { omitted++; continue; }
                shown++;
                items.add(new SlashMenuPopup.Item(tool.toolName, tool.description, () -> {
                    if (current(mine, owner)) edit(tool);
                }));
            }

        }
        if (omitted > 0) items.add(new SlashMenuPopup.Item("还有 " + omitted + " 个匹配工具", "继续输入工具名或服务名筛选", null));
        if (servers.isEmpty() && (query.length() == 0 || matches("mcp", query)))
            items.add(new SlashMenuPopup.Item("配置 MCP 服务", "尚无已启用服务", () -> {
                if (!current(mine, owner)) return;
                host.hideMenu(); context.startActivity(new Intent(context, McpConfigActivity.class));
            }));
    }

    private static boolean matches(String value, String query) {
        return value.toLowerCase(Locale.ROOT).contains(query);
    }

    /** Only a hidden-to-visible transition starts a new cache read and refresh round. */
    public void menuOpened() {
        if (menuOpen) return;
        menuOpen = true;
        final int mine = ++generation;
        final long owner = host.context(), session = host.session();
        new Thread(() -> {
            List<McpCatalog.Server> found = Collections.emptyList();
            Exception failure = null;
            try { found = RunHub.get(context).cachedMcpCatalog(); }
            catch (Exception invalid) { failure = invalid; }
            final List<McpCatalog.Server> loaded = found;
            final Exception error = failure;
            main.post(() -> {
                if (!current(mine, owner)) return;
                if (error == null) catalog = loaded;
                else {
                    if (catalog == null) catalog = Collections.emptyList();
                    report(error, session, "MCP 工具列表读取失败");
                }
                host.refreshMenu();
                if (error != null || !current(mine, owner) || loaded.isEmpty()) return;
                RefreshBatch batch = new RefreshBatch(mine, owner, session, loaded);
                refreshBatch = batch;
                batch.start();
            });
        }, "backcast-mcp-menu-cache").start();
    }

    private boolean current(int mine, long owner) {
        return menuOpen && generation == mine && host.current(owner);
    }

    /** Dismissal cancels only this menu's independent clients and queued refresh work. */
    public void menuClosed() {
        if (!menuOpen && refreshBatch == null) return;
        menuOpen = false;
        generation++;
        RefreshBatch batch = refreshBatch;
        refreshBatch = null;
        if (batch != null) batch.close();
    }

    private final class RefreshBatch implements Runnable {
        private final int mine;
        private final long owner, session;
        private final ArrayDeque<McpCatalog.Server> pending;
        private final List<McpCatalog.Refresh> clients = new ArrayList<McpCatalog.Refresh>();
        private final List<Future<?>> workers = new ArrayList<Future<?>>();
        private boolean closed, notified;
        private int remaining;

        RefreshBatch(int mine, long owner, long session, List<McpCatalog.Server> servers) {
            this.mine = mine; this.owner = owner; this.session = session;
            pending = new ArrayDeque<McpCatalog.Server>(servers);
        }
        void start() {
            final int count = Math.min(2, pending.size());
            remaining = count;
            for (int i = 0; i < count; i++) {
                Future<?> worker = REFRESH_WORKERS.submit(this);
                synchronized (this) {
                    if (closed) worker.cancel(true);
                    else workers.add(worker);
                }
            }
        }
        @Override public void run() {
            try {
                while (true) {
                    McpCatalog.Server server;
                    synchronized (this) { server = closed ? null : pending.poll(); }
                    if (server == null) return;
                    McpCatalog.Refresh client = null;
                    try {
                        client = RunHub.get(context).newMcpRefresh(server.id);
                        boolean accepted;
                        synchronized (this) {
                            accepted = !closed;
                            if (accepted) clients.add(client);
                        }
                        if (!accepted) return;
                        client.run();
                    } catch (Exception error) {
                        main.post(() -> {
                            if (!current(mine, owner)) return;
                            report(error, session, "MCP 工具列表更新失败", !notified);
                            notified = true;
                        });
                    } finally {
                        if (client != null) {
                            synchronized (this) { clients.remove(client); }
                            client.close();
                        }
                    }
                }
            } finally {
                boolean finished;
                synchronized (this) { finished = --remaining == 0 && !closed; }
                if (finished) publish();
            }
        }
        private void publish() {
            try {
                final List<McpCatalog.Server> loaded = RunHub.get(context).cachedMcpCatalog();
                main.post(() -> {
                    if (!current(mine, owner)) return;
                    catalog = loaded;
                    host.refreshMenu();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!current(mine, owner)) return;
                    report(error, session, "MCP 工具列表读取失败", !notified);
                    notified = true;
                });
            }
        }
        void close() {
            List<McpCatalog.Refresh> active;
            List<Future<?>> tasks;
            synchronized (this) {
                closed = true; pending.clear();
                active = new ArrayList<McpCatalog.Refresh>(clients);
                tasks = new ArrayList<Future<?>>(workers);
                clients.clear(); workers.clear();
            }
            for (McpCatalog.Refresh client : active) client.close();
            for (Future<?> worker : tasks) worker.cancel(true);
        }
    }

    private void edit(final McpSelection tool) {
        final long owner = host.context();
        final String original = host.text();
        host.hideMenu();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        addText(content, tool.serverName + " · " + tool.toolName, 16);
        if (tool.description.length() > 0) addText(content, tool.description, 14);
        addText(content, parameterDescription(tool.inputSchema()), 13);
        final TextView schema = addText(content, "查看完整参数格式", 12);
        final boolean[] expanded = {false};
        schema.setOnClickListener(view -> {
            expanded[0] = !expanded[0];
            String detail = tool.inputSchema().toString();
            try { detail = tool.inputSchema().toString(2); } catch (Exception ignored) { }
            schema.setText(expanded[0] ? detail + "\n收起参数格式" : "查看完整参数格式");
        });
        final EditText arguments = new EditText(context);
        arguments.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        arguments.setMinLines(3); arguments.setMaxLines(8); arguments.setText("{}");
        content.addView(arguments, new LinearLayout.LayoutParams(-1, -2));
        final TextView error = addText(content, "仅填入草稿；发送后由模型调用，继续遵守现有工具权限。", 13);
        ScrollView scroll = new ScrollView(context); scroll.addView(content);
        final AlertDialog panel = new AlertDialog.Builder(context).setTitle("选择 MCP 工具")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("填入草稿", null).create();
        dialog = panel;
        panel.setOnDismissListener(ignored -> { if (dialog == panel) dialog = null; });
        panel.setOnShowListener(ignored -> panel.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!host.current(owner) || !host.text().equals(original)) { panel.dismiss(); return; }
            try {
                JSONObject values = parseArguments(arguments.getText().toString(), tool.inputSchema());
                RunHub.get(context).validateMcpSelection(tool);
                String draft = "请使用 MCP 服务「" + tool.serverName + "」的工具「" + tool.toolName
                        + "」（" + tool.mappedName + "），参数如下：\n```json\n" + values.toString(2) + "\n```";
                selection = tool; selectedText = draft;
                host.draft(draft); panel.dismiss();
            } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
            catch (Exception invalid) {
                error.setText("工具配置已变化，请重新选择工具。");
                report(invalid, host.session(), "MCP 工具选择已失效");
            }
        }));
        panel.show();
    }

    /** Validate the editable JSON envelope and ordinary schema types, without rewriting values. */
    static JSONObject parseArguments(String text, JSONObject schema) {
        final JSONObject values;
        try {
            JSONTokener parser = new JSONTokener(text);
            Object decoded = parser.nextValue();
            if (!(decoded instanceof JSONObject) || parser.nextClean() != 0) throw new IllegalArgumentException();
            values = (JSONObject) decoded;
        } catch (Exception invalid) { throw new IllegalArgumentException("参数必须是完整的 JSON 对象。"); }
        JSONArray required = schema.optJSONArray("required");
        if (required != null) for (int i = 0; i < required.length(); i++) {
            String name = required.optString(i);
            if (!values.has(name)) throw new IllegalArgumentException("缺少必填参数：" + name);
        }
        JSONObject properties = schema.optJSONObject("properties");
        if (properties != null) for (java.util.Iterator<String> names = values.keys(); names.hasNext();) {
            String name = names.next(); JSONObject property = properties.optJSONObject(name);
            if (property == null || !(property.opt("type") instanceof String)) continue;
            String type = property.optString("type"); Object value = values.opt(name);
            boolean valid = "string".equals(type) ? value instanceof String : "boolean".equals(type) ? value instanceof Boolean
                    : "object".equals(type) ? value instanceof JSONObject : "array".equals(type) ? value instanceof JSONArray
                    : "null".equals(type) ? value == JSONObject.NULL : "number".equals(type) ? value instanceof Number
                    : !"integer".equals(type) || value instanceof Number && ((Number) value).doubleValue() == Math.rint(((Number) value).doubleValue());
            if (!valid) throw new IllegalArgumentException("参数类型不符：" + name + "（需要 " + type + "）");
        }
        return values;
    }

    private static String parameterDescription(JSONObject schema) {
        JSONObject properties = schema.optJSONObject("properties");
        if (properties == null || properties.length() == 0) return "参数为 JSON 对象；无参数时填写 {}。";
        JSONArray required = schema.optJSONArray("required");
        StringBuilder text = new StringBuilder("参数\n");
        for (java.util.Iterator<String> names = properties.keys(); names.hasNext();) {
            String name = names.next(); JSONObject field = properties.optJSONObject(name);
            boolean needed = false;
            if (required != null) for (int i = 0; i < required.length(); i++) if (name.equals(required.optString(i))) needed = true;
            text.append(name).append(needed ? " · 必填" : " · 可选");
            if (field != null) {
                text.append(" · ").append(field.optString("type", "见完整格式"));
                String description = field.optString("description", "");
                if (description.length() > 0) text.append('\n').append(description);
            }
            text.append('\n');
        }
        return text.toString();
    }

    private void report(final Exception error, final long session, final String summary) {
        report(error, session, summary, true);
    }
    private void report(final Exception error, final long session, final String summary, boolean notify) {
        if (notify) Toast.makeText(context, summary + "，请稍后重试", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                java.util.List<String> secrets = new java.util.ArrayList<String>();
                try {
                    McpStore store = new McpStore(new java.io.File(context.getFilesDir(), "mcp"));
                    for (McpServer server : store.servers()) if (!server.bearerToken.isEmpty()) secrets.add(server.bearerToken);
                } catch (Exception unavailable) { }
                String detail = Diagnostics.boundedJson(Diagnostics.failure(error), secrets.toArray(new String[secrets.size()]));
                try (ChatStore database = new ChatStore(context.getApplicationContext())) {
                    database.recordDiagnostic(session, "ui:mcp_picker", summary, detail);
                }
            } catch (Exception unavailable) { }
        }, "backcast-mcp-menu-diagnostic").start();
    }

    public void selectionUnavailable(Exception error) { report(error, host.session(), "MCP 工具当前不可用，草稿已保留"); }
    public McpSelection selection(String text) { return selectedText != null && selectedText.equals(text) ? selection : null; }
    public void textChanged(String text) { if (selectedText != null && !selectedText.equals(text)) { selection = null; selectedText = null; } }
    public String saveDraft() {
        if (selection == null || selectedText == null || !selectedText.equals(host.text())) return null;
        try { return new JSONObject().put("selection", selection.toJson()).put("text", selectedText).toString(); }
        catch (Exception invalid) { return null; }
    }
    public void restoreDraft(String saved) {
        if (saved == null) return;
        try {
            JSONObject value = new JSONObject(saved);
            selection = McpSelection.fromJson(value.getJSONObject("selection"));
            selectedText = value.getString("text");
            host.draft(selectedText);
        } catch (Exception invalid) { selection = null; selectedText = null; }
    }
    public boolean editing() { return dialog != null; }
    public void reset() {
        cancelUi();
        if (selectedText != null && selectedText.equals(host.text())) host.draft("");
        selection = null; selectedText = null; catalog = null;
    }
    public void reloadCatalog() { cancelUi(); catalog = null; }
    public void cancelUi() {
        menuClosed();
        if (dialog != null) { AlertDialog previous = dialog; dialog = null; previous.dismiss(); }
    }
    private TextView addText(LinearLayout content, String value, int size) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(0xFF3C3C43);
        text.setPadding(0, dp(6), 0, dp(6)); content.addView(text, new LinearLayout.LayoutParams(-1, -2)); return text;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
