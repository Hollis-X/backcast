package com.mkei.backcast;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.mkei.backcast.tool.ToolCatalog;
import com.mkei.backcast.ui.Icons;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline package management and one tool's detail are separate navigation pages. */
public final class ToolConfigActivity extends AppCompatActivity {
    public static final String EXTRA_TOOL_ID = "tool_id";
    private final ExecutorService toolkitReader = Executors.newSingleThreadExecutor();
    private final ExecutorService toolkitCancellation = Executors.newSingleThreadExecutor();
    private final List<ToolkitOperation> toolkitOperations = new ArrayList<ToolkitOperation>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean activityDestroyed;
    private ToolkitOperation active;
    private Settings settings;
    private String toolId;
    private CheckBox useRoot;
    private RadioGroup permission;
    private TextView permissionNote, packageStatus, detailName, detailInfo, detailOutput, operationStatus;
    private LinearLayout toolList;
    private Button install, remove, probe, cancel;
    private JSONObject selected = new JSONObject();
    private JSONObject bundle = new JSONObject();

    private interface ToolkitResult { void apply(JSONObject result); }
    private static final class ToolkitOperation {
        volatile boolean cancelled;
        volatile java.util.concurrent.Future<?> future;
        RunHub.ToolkitSession session;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool_config);
        settings = new Settings(this);
        toolId = getIntent().getStringExtra(EXTRA_TOOL_ID);
        if (toolId == null) toolId = "";
        try { if (toolId.length() > 0) ToolCatalog.get(toolId); }
        catch (IllegalArgumentException invalid) { finish(); return; }
        androidx.appcompat.widget.Toolbar toolbar = (androidx.appcompat.widget.Toolbar) findViewById(R.id.tool_toolbar);
        setSupportActionBar(toolbar);
        toolbar.setTitle(toolId.length() > 0 ? R.string.settings_tool_detail_title : R.string.settings_tools_title);
        toolbar.setNavigationIcon(Icons.tinted(this, Icons.BACK, 0xFF0D0D0D, dp(22)));
        toolbar.setNavigationOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { finish(); } });
        useRoot = (CheckBox) findViewById(R.id.tool_use_root);
        permission = (RadioGroup) findViewById(R.id.tool_permission);
        permissionNote = (TextView) findViewById(R.id.tool_permission_note);
        packageStatus = (TextView) findViewById(R.id.tool_package_status);
        detailName = (TextView) findViewById(R.id.tool_detail_name);
        detailInfo = (TextView) findViewById(R.id.tool_detail_info);
        detailOutput = (TextView) findViewById(R.id.tool_detail_output);
        operationStatus = (TextView) findViewById(R.id.tool_operation_status);
        toolList = (LinearLayout) findViewById(R.id.tool_config_list);
        install = (Button) findViewById(R.id.tool_package_install);
        remove = (Button) findViewById(R.id.tool_package_remove);
        probe = (Button) findViewById(R.id.tool_detail_probe);
        cancel = (Button) findViewById(R.id.tool_operation_cancel);
        Button save = (Button) findViewById(R.id.tool_save_permissions);
        Icons.left(save, Icons.SHIELD, 0xFF0D0D0D, dp(18));
        Icons.left(install, Icons.FOLDER, 0xFF0D0D0D, dp(18));
        Icons.left(remove, Icons.STOP, 0xFF0D0D0D, dp(18));
        Icons.left(probe, Icons.PLAY, 0xFF0D0D0D, dp(18));
        Icons.left(cancel, Icons.STOP, 0xFF0D0D0D, dp(18));
        useRoot.setChecked(savedInstanceState == null ? settings.useRoot()
                : savedInstanceState.getBoolean("draft_root", settings.useRoot()));
        permission.check(permissionId(savedInstanceState == null ? settings.accessLevel()
                : savedInstanceState.getString("draft_permission", settings.accessLevel())));
        refreshPermissionNote();
        permission.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup group, int id) { refreshPermissionNote(); }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                settings.setUseRoot(useRoot.isChecked());
                settings.setAccessLevel(permissionValue());
                RunHub.get(ToolConfigActivity.this).retargetTools();
                Toast.makeText(ToolConfigActivity.this, R.string.toolkit_permission_saved, Toast.LENGTH_SHORT).show();
            }
        });
        install.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { manage("package_install", R.string.toolkit_installing_offline); }
        });
        remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { manage("package_remove", R.string.toolkit_removing); }
        });
        probe.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                begin(getString(R.string.toolkit_loading));
                active = requestToolkit(toolkitArguments("status", toolId), new ToolkitResult() {
                    @Override public void apply(JSONObject result) { finishOperation(result); showProbe(result); }
                });
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (active != null) cancelToolkitOperation(active);
                active = null; setBusy(false);
                operationStatus.setText(R.string.toolkit_cancelled);
            }
        });
        if (toolId.length() > 0) {
            findViewById(R.id.tool_config_controls).setVisibility(View.GONE);
            toolList.setVisibility(View.GONE);
            findViewById(R.id.tool_detail_content).setVisibility(View.VISIBLE);
            detailName.setText(ToolCatalog.get(toolId).name);
        }
    }

    @Override protected void onResume() { super.onResume(); if (active == null) loadTools(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("draft_root", useRoot.isChecked());
        state.putString("draft_permission", permissionValue());
        super.onSaveInstanceState(state);
    }

    @Override protected void onStop() {
        for (ToolkitOperation operation : pendingOperations()) cancelToolkitOperation(operation);
        active = null;
        super.onStop();
    }

    @Override protected void onDestroy() {
        activityDestroyed = true;
        for (ToolkitOperation operation : pendingOperations()) cancelToolkitOperation(operation);
        toolkitReader.shutdownNow(); toolkitCancellation.shutdown();
        super.onDestroy();
    }

    private List<ToolkitOperation> pendingOperations() {
        synchronized (toolkitOperations) { return new ArrayList<ToolkitOperation>(toolkitOperations); }
    }

    private void loadTools() {
        begin(getString(R.string.toolkit_loading));
        active = requestToolkit(toolkitArguments("list", ""), new ToolkitResult() {
            @Override public void apply(JSONObject result) { finishOperation(result); renderTools(result); }
        });
    }

    private void manage(String action, int status) {
        begin(getString(status));
        active = requestToolkit(toolkitArguments(action, ""), new ToolkitResult() {
            @Override public void apply(JSONObject result) {
                finishOperation(result);
                if (!"error".equals(result.optString("state")) && !"cancelled".equals(result.optString("state"))) loadTools();
            }
        });
    }

    private void begin(String text) {
        if (active != null) cancelToolkitOperation(active);
        operationStatus.setText(text); operationStatus.setVisibility(View.VISIBLE); setBusy(true);
    }

    private void finishOperation(JSONObject result) {
        active = null; setBusy(false);
        String error = result.optString("error");
        operationStatus.setText(error.length() > 0 ? error : toolkitState(result.optString("state")));
        operationStatus.setVisibility(error.length() > 0 || "cancelled".equals(result.optString("state")) ? View.VISIBLE : View.GONE);
    }

    private void setBusy(boolean busy) {
        install.setEnabled(!busy && !"unsupported".equals(bundle.optString("state")));
        remove.setEnabled(!busy && (bundle.optBoolean("installed") || "removed".equals(bundle.optString("state"))));
        probe.setEnabled(!busy && bundle.optBoolean("installed"));
        cancel.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void renderTools(JSONObject result) {
        JSONObject packageInfo = result.optJSONObject("package");
        if (packageInfo != null) {
            bundle = packageInfo;
            String state = bundle.optString("state");
            String storage = getString(R.string.toolkit_storage, result.optString("storage"), result.optString("abi"));
            long bytes = bundle.optLong("installed_bytes");
            packageStatus.setText(toolkitState(state) + "\n" + storage
                    + (bytes > 0 ? "\n" + getString(R.string.toolkit_bundle_bytes, android.text.format.Formatter.formatFileSize(this, bytes)) : "")
                    + (bundle.optBoolean("busy") ? "\n" + getString(R.string.toolkit_busy) : ""));
            install.setEnabled(!"unsupported".equals(state));
            remove.setEnabled(bundle.optBoolean("installed") || "removed".equals(state));
            probe.setEnabled(bundle.optBoolean("installed"));
        }
        JSONArray tools = result.optJSONArray("tools");
        if (tools == null) return;
        toolList.removeAllViews();
        for (int i = 0; i < tools.length(); i++) {
            final JSONObject entry = tools.optJSONObject(i);
            if (entry == null) continue;
            JSONObject manifest = bundle == null ? null : bundle.optJSONObject("manifest");
            try { entry.put("version", toolVersion(entry.optString("id"), manifest)); }
            catch (Exception ignored) { }
            if (entry.optString("id").equals(toolId)) {
                selected = entry; detailInfo.setText(toolkitDetails(entry)); continue;
            }
            if (toolId.length() > 0) continue;
            TextView row = new TextView(this);
            row.setText(entry.optString("name") + "\n" + toolkitState(entry.optString("state")));
            row.setTextColor(getResources().getColor(R.color.text_primary)); row.setTextSize(14);
            row.setPadding(dp(8), dp(12), dp(8), dp(12)); row.setMinHeight(dp(64));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            Icons.right(row, Icons.CHEVRON_RIGHT, 0xFF6F6F6F, dp(18));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    Intent intent = new Intent(ToolConfigActivity.this, ToolConfigActivity.class);
                    intent.putExtra(EXTRA_TOOL_ID, entry.optString("id")); startActivity(intent);
                }
            });
            toolList.addView(row, new LinearLayout.LayoutParams(-1, -2));
            View divider = new View(this); divider.setBackgroundColor(getResources().getColor(R.color.divider));
            toolList.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private void showProbe(JSONObject result) {
        try {
            selected.put("state", result.optString("state"));
            if (result.has("configuration")) selected.put("configuration", result.getJSONObject("configuration"));
        } catch (Exception ignored) { }
        detailInfo.setText(toolkitDetails(selected));
        String output = result.optString("probe_output", result.optString("error"));
        detailOutput.setText(getString(R.string.toolkit_probe_output) + "\n" + shortText(output, 12000));
        detailOutput.setVisibility(output.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private static String toolVersion(String id, JSONObject manifest) {
        if (manifest == null) return "";
        if ("apktool".equals(id) || "objection".equals(id)) return manifest.optString(id);
        if ("radare2".equals(id) || "rabin2".equals(id)) return manifest.optString("radare2");
        JSONArray sources = manifest.optJSONArray("sources");
        if (sources != null) for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            if (source != null && "binutils".equals(source.optString("package"))) return source.optString("version");
        }
        return "";
    }

    private void refreshPermissionNote() {
        int resource = permission.getCheckedRadioButtonId() == R.id.tool_access_guarded ? R.string.access_guarded_note
                : permission.getCheckedRadioButtonId() == R.id.tool_access_strict ? R.string.access_strict_note : R.string.access_full_note;
        permissionNote.setText(resource);
    }
    private static int permissionId(String value) {
        return Settings.ACCESS_GUARDED.equals(value) ? R.id.tool_access_guarded
                : Settings.ACCESS_STRICT.equals(value) ? R.id.tool_access_strict : R.id.tool_access_full;
    }
    private String permissionValue() {
        return permission.getCheckedRadioButtonId() == R.id.tool_access_guarded ? Settings.ACCESS_GUARDED
                : permission.getCheckedRadioButtonId() == R.id.tool_access_strict ? Settings.ACCESS_STRICT : Settings.ACCESS_FULL;
    }
    private static JSONObject toolkitArguments(String action, String id) {
        JSONObject args = new JSONObject();
        try { args.put("action", action); if (id.length() > 0) args.put("tool", id); }
        catch (Exception ignored) { }
        return args;
    }
    private String toolkitState(String state) {
        if ("ready".equals(state)) return getString(R.string.toolkit_ready);
        if ("installed".equals(state)) return getString(R.string.toolkit_installed);
        if ("removed".equals(state)) return getString(R.string.toolkit_removed);
        if ("not_installed".equals(state) || "bundled_not_probed".equals(state)) return getString(R.string.toolkit_not_installed);
        if ("unsupported".equals(state)) return getString(R.string.toolkit_unsupported);
        if ("configured_not_probed".equals(state)) return getString(R.string.toolkit_configured);
        if ("needs_runtime".equals(state)) return getString(R.string.toolkit_needs_runtime);
        if ("unconfigured".equals(state)) return getString(R.string.toolkit_unconfigured);
        if ("unavailable".equals(state)) return getString(R.string.toolkit_unavailable);
        if ("cancelled".equals(state)) return getString(R.string.toolkit_cancelled);
        return getString(R.string.toolkit_failed);
    }
    private String toolkitDetails(JSONObject entry) {
        String version = entry.optString("version");
        return toolkitState(entry.optString("state")) + "\n\n" + getString(R.string.toolkit_version) + "\n" + version
                + "\n\n" + getString(R.string.toolkit_source) + "\n" + entry.optString("source")
                + "\n\n" + getString(R.string.toolkit_requirements) + "\n" + entry.optString("requirements");
    }
    private static String shortText(String text, int limit) { return text.length() <= limit ? text : text.substring(0, limit); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private ToolkitOperation requestToolkit(final JSONObject args, final ToolkitResult callback) {
        final ToolkitOperation operation = new ToolkitOperation();
        synchronized (toolkitOperations) { toolkitOperations.add(operation); }
        operation.future = toolkitReader.submit(new Runnable() {
            @Override public void run() {
                JSONObject result = new JSONObject();
                try {
                    if (operation.cancelled) return;
                    RunHub.ToolkitSession session = RunHub.get(ToolConfigActivity.this).newToolkitSession();
                    synchronized (operation) { operation.session = session; }
                    if (operation.cancelled) return;
                    String action = args.optString("action");
                    if ("package_install".equals(action)) result = session.toolkit.installBundled();
                    else if ("package_remove".equals(action)) result = session.toolkit.removeBundled();
                    else result = new JSONObject(session.toolkit.run(args));
                } catch (InterruptedException cancellation) {
                    Thread.currentThread().interrupt();
                    try { result.put("state", "cancelled").put("error", String.valueOf(cancellation.getMessage())); }
                    catch (Exception ignored) { }
                } catch (Exception failure) {
                    try { result.put("state", "error").put("error", String.valueOf(failure.getMessage())); }
                    catch (Exception ignored) { }
                } finally {
                    try { closeToolkitSession(operation); }
                    catch (Exception cleanup) {
                        try { result.put("state", "error").put("error", String.valueOf(cleanup.getMessage())); }
                        catch (Exception ignored) { }
                    }
                    synchronized (toolkitOperations) { toolkitOperations.remove(operation); }
                }
                final JSONObject response = result;
                ui(new Runnable() {
                    @Override public void run() {
                        if (!operation.cancelled && !activityDestroyed && !isFinishing() && active == operation) callback.apply(response);
                    }
                });
            }
        });
        return operation;
    }
    private void cancelToolkitOperation(final ToolkitOperation operation) {
        if (operation.cancelled) return;
        operation.cancelled = true;
        java.util.concurrent.Future<?> future = operation.future;
        if (future != null) future.cancel(true);
        synchronized (toolkitOperations) { toolkitOperations.remove(operation); }
        if (!toolkitCancellation.isShutdown()) toolkitCancellation.execute(new Runnable() {
            @Override public void run() { closeToolkitSession(operation); }
        });
    }
    private static void closeToolkitSession(ToolkitOperation operation) {
        RunHub.ToolkitSession session;
        synchronized (operation) { if (operation.session == null) return; session = operation.session; }
        session.close();
    }
    private void ui(Runnable callback) { main.post(callback); }
}
