package com.mkei.backcast;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.mkei.backcast.mcp.McpClient;
import com.mkei.backcast.mcp.McpServer;
import com.mkei.backcast.mcp.McpStore;
import com.mkei.backcast.mcp.McpToolInfo;
import com.mkei.backcast.ui.Icons;
import com.mkei.backcast.agent.Diagnostics;
import java.io.File;
import java.util.List;
import java.util.UUID;

/** Server list and a separate editor; probing never silently commits a draft. */
public final class McpConfigActivity extends AppCompatActivity {
    private static final String EXTRA_ID = "mcp_server_id";
    private final Handler main = new Handler(Looper.getMainLooper());
    private McpStore store;
    private LinearLayout content, rows;
    private String id;
    private EditText name, endpoint, token, timeout;
    private CheckBox enabled;
    private TextView status, tools;
    private Button probe, cancel;
    private ProgressBar progress;
    private volatile McpClient probing;
    private int generation;
    private boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new McpStore(new File(getFilesDir(), "mcp"));
        id = getIntent().getStringExtra(EXTRA_ID);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFFFFFFFF);
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.settings_mcp_title);
        toolbar.setNavigationIcon(Icons.tinted(this, Icons.BACK, 0xFF0D0D0D, dp(22)));
        toolbar.setNavigationOnClickListener(view -> finish());
        page.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(page);
        setSupportActionBar(toolbar);
        if (id == null) {
            text("连接 MCP 服务后，主会话和子任务都可调用服务提供的工具。点击服务查看配置和工具。", 14);
            button("添加服务", view -> open(UUID.randomUUID().toString()));
            rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            content.addView(rows);
        } else {
            McpServer server;
            try { server = current(); }
            catch (Exception storage) { report(storage, "MCP 配置读取失败"); finish(); return; }
            name = input("服务名称", server == null ? "" : server.name, InputType.TYPE_CLASS_TEXT);
            endpoint = input("MCP 地址（Streamable HTTP）", server == null ? "" : server.endpoint,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            endpoint.setHint("https://example.com/mcp");
            token = input("Bearer Token（可选）", server == null ? "" : server.bearerToken,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            timeout = input("请求超时（秒，5–300）", Integer.toString(server == null ? 60 : server.timeoutSeconds),
                    InputType.TYPE_CLASS_NUMBER);
            enabled = new CheckBox(this);
            enabled.setText("启用此服务");
            enabled.setChecked(server == null || server.enabled);
            content.addView(enabled);
            text("保存后生效。连接检测会获取工具列表；检测本身不会执行远程工具。", 14);
            button("保存", view -> save());
            probe = button("连接检测 / 刷新工具", view -> discover());
            cancel = button("取消检测", view -> cancelProbe());
            cancel.setVisibility(View.GONE);
            progress = new ProgressBar(this);
            progress.setVisibility(View.GONE);
            content.addView(progress, new LinearLayout.LayoutParams(-1, dp(32)));
            status = text("", 14);
            tools = text("检测连接后显示工具列表。", 14);
            button("删除服务", view -> {
                cancelProbe();
                try {
                    store.remove(id);
                    RunHub.get(this).retargetTools();
                    finish();
                } catch (Exception error) { report(error, "删除失败，请稍后再试"); }
            });
            if (state != null) {
                name.setText(state.getString("name", ""));
                endpoint.setText(state.getString("endpoint", ""));
                token.setText(state.getString("token", ""));
                timeout.setText(state.getString("timeout", "60"));
                enabled.setChecked(state.getBoolean("enabled", true));
            }
            TextWatcher changes = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable text) { invalidateProbe(); }
            };
            name.addTextChangedListener(changes);
            endpoint.addTextChangedListener(changes);
            token.addTextChangedListener(changes);
            timeout.addTextChangedListener(changes);
            enabled.setOnCheckedChangeListener((button, checked) -> invalidateProbe());
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (rows == null) return;
        rows.removeAllViews();
        List<McpServer> servers;
        try { servers = store.servers(); }
        catch (Exception storage) { report(storage, "MCP 配置读取失败"); return; }
        for (McpServer server : servers) {
            TextView row = new TextView(this);
            row.setText(server.name + "  ›\n" + (server.enabled ? "已启用" : "已停用") + " · " + Diagnostics.scrub(server.endpoint));
            row.setTextSize(16);
            row.setTextColor(0xFF0D0D0D);
            row.setPadding(dp(4), dp(18), dp(4), dp(18));
            row.setFocusable(true);
            row.setOnClickListener(view -> open(server.id));
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        if (servers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("尚未添加 MCP 服务");
            empty.setPadding(0, dp(20), 0, dp(20));
            rows.addView(empty);
        }
    }

    private void open(String serverId) {
        startActivity(new Intent(this, McpConfigActivity.class).putExtra(EXTRA_ID, serverId));
    }

    private McpServer current() {
        for (McpServer server : store.servers()) if (server.id.equals(id)) return server;
        return null;
    }

    private McpServer draft() {
        int seconds;
        try { seconds = Integer.parseInt(timeout.getText().toString().trim()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("超时必须是 5–300 秒的整数"); }
        return new McpServer(id, name.getText().toString().trim(), endpoint.getText().toString().trim(),
                token.getText().toString().trim(), enabled.isChecked(), seconds);
    }

    private void save() {
        cancelProbe();
        try {
            store.save(draft());
            RunHub.get(this).retargetTools();
            Toast.makeText(this, "MCP 配置已保存", Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, "请检查服务名称、HTTP 地址和超时设置", Toast.LENGTH_SHORT).show();
        } catch (Exception error) { report(error, "保存失败，请稍后再试"); }
    }

    private void discover() {
        final McpServer server;
        try { server = draft(); }
        catch (IllegalArgumentException invalid) {
            Toast.makeText(this, "请检查服务名称、HTTP 地址和超时设置", Toast.LENGTH_SHORT).show();
            return;
        }
        cancelProbe();
        final int mine = generation;
        final McpClient client = new McpClient(server);
        probing = client;
        probe.setEnabled(false);
        cancel.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        status.setText("正在连接并获取工具…");
        new Thread(() -> {
            List<McpToolInfo> result = null;
            Exception failure = null;
            try { result = client.discover(); }
            catch (Exception error) { failure = error; }
            finally { client.close(); }
            final List<McpToolInfo> found = result;
            final Exception error = failure;
            main.post(() -> {
                if (destroyed || mine != generation || probing != client) return;
                probing = null;
                probe.setEnabled(true);
                cancel.setVisibility(View.GONE);
                progress.setVisibility(View.GONE);
                if (error != null) { status.setText("连接失败"); report(error, "MCP 连接失败，请检查地址或授权"); return; }
                StringBuilder output = new StringBuilder();
                for (McpToolInfo tool : found) {
                    output.append(tool.name).append('\n').append(tool.description).append("\n\n");
                }
                tools.setText(output.length() == 0 ? "此服务没有提供工具" : output.toString());
                status.setText("连接成功 · " + found.size() + " 个工具");
                try {
                    if (sameConnection(current(), server) && store.cacheTools(server, found))
                        RunHub.get(this).retargetTools();
                } catch (Exception storage) { report(storage, "工具列表保存失败，请稍后再试"); }
            });
        }, "backcast-mcp-probe").start();
    }

    private static boolean sameConnection(McpServer saved, McpServer probed) {
        return saved != null && saved.name.equals(probed.name) && saved.endpoint.equals(probed.endpoint)
                && saved.bearerToken.equals(probed.bearerToken) && saved.enabled == probed.enabled
                && saved.timeoutSeconds == probed.timeoutSeconds;
    }

    private void cancelProbe() {
        generation++;
        McpClient client = probing;
        probing = null;
        if (client != null) client.close();
        if (probe != null) {
            probe.setEnabled(true);
            cancel.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
            status.setText(client == null ? status.getText() : "检测已取消");
        }
    }

    private void invalidateProbe() {
        cancelProbe();
        status.setText("");
        tools.setText("配置已修改，请重新检测连接。保存后生效。");
    }

    private void report(Exception error, String message) {
        String detail = error.getClass().getName() + ": " + String.valueOf(error.getMessage());
        String secret = token == null ? "" : token.getText().toString();
        if (secret.length() > 0) detail = detail.replace(secret, "[已隐藏]");
        final String safe = detail;
        new Thread(() -> {
            try (ChatStore database = new ChatStore(getApplicationContext())) {
                database.recordDiagnostic(-1L, "mcp:" + id, "MCP 配置操作失败", safe);
            }
            catch (Exception ignored) { }
        }, "backcast-mcp-diagnostic").start();
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (name != null) {
            state.putString("name", name.getText().toString());
            state.putString("endpoint", endpoint.getText().toString());
            state.putString("token", token.getText().toString());
            state.putString("timeout", timeout.getText().toString());
            state.putBoolean("enabled", enabled.isChecked());
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onStop() { cancelProbe(); super.onStop(); }
    @Override protected void onDestroy() { destroyed = true; cancelProbe(); super.onDestroy(); }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(0xFF3C3C43);
        view.setPadding(0, dp(8), 0, dp(8));
        content.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private EditText input(String label, String value, int type) {
        text(label, 14);
        EditText view = new EditText(this);
        view.setInputType(type);
        view.setSingleLine(true);
        view.setText(value);
        content.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private Button button(String title, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        content.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
