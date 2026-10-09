package com.mkei.backcast;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.mkei.backcast.tool.EmbeddedToolchain;
import com.mkei.backcast.tool.ToolCatalog;
import com.mkei.backcast.tool.ToolBatchProbe;
import com.mkei.backcast.tool.ToolkitOperationManager;
import com.mkei.backcast.ui.Icons;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Package installation and one tool's detail are separate navigation pages. */
public final class ToolConfigActivity extends AppCompatActivity {
    public static final String EXTRA_TOOL_ID = "tool_id";
    public static final String EXTRA_PROBE_RESULT = "probe_result";
    public static final String EXTRA_PROBE_CONTEXT = "probe_context";
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean activityStarted;
    private ToolkitOperationManager operations;
    private ToolkitOperationManager.Snapshot active;
    private long renderedRevision = -1, renderedOperation;
    private String renderedInventory = "";
    private final ToolkitOperationManager.Listener operationListener = new ToolkitOperationManager.Listener() {
        public void onState(final ToolkitOperationManager.Snapshot state) {
            main.post(new Runnable() { public void run() { if (activityStarted && !isFinishing()) renderOperation(state); } });
        }
    };
    private Settings settings;
    private String toolId;
    private CheckBox useRoot;
    private TextView packageStatus, detailName, detailInfo, detailOutput, operationStatus, installProgressText;
    private View installProgressContainer;
    private ProgressBar installProgress;
    private String probeContext = "";
    private View batchProgressContainer;
    private ProgressBar batchProgress;
    private TextView batchProgressText;
    private Button batchProbe;
    private final Map<String, JSONObject> probeResults = new LinkedHashMap<String, JSONObject>();
    private final Map<String, TextView> toolRows = new LinkedHashMap<String, TextView>();
    private LinearLayout toolList;
    private Button install, remove, probe, cancel;
    private JSONObject selected = new JSONObject();
    private JSONObject bundle = new JSONObject();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool_config);
        settings = new Settings(this);
        operations = RunHub.get(this).toolkitOperations();
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
        packageStatus = (TextView) findViewById(R.id.tool_package_status);
        detailName = (TextView) findViewById(R.id.tool_detail_name);
        detailInfo = (TextView) findViewById(R.id.tool_detail_info);
        detailOutput = (TextView) findViewById(R.id.tool_detail_output);
        operationStatus = (TextView) findViewById(R.id.tool_operation_status);
        installProgressContainer = findViewById(R.id.tool_install_progress_container);
        installProgress = (ProgressBar) findViewById(R.id.tool_install_progress);
        installProgressText = (TextView) findViewById(R.id.tool_install_progress_text);
        toolList = (LinearLayout) findViewById(R.id.tool_config_list);
        install = (Button) findViewById(R.id.tool_package_install);
        remove = (Button) findViewById(R.id.tool_package_remove);
        probe = (Button) findViewById(R.id.tool_detail_probe);
        cancel = (Button) findViewById(R.id.tool_operation_cancel);
        batchProbe = (Button) findViewById(R.id.tool_batch_probe);
        batchProgressContainer = findViewById(R.id.tool_batch_progress_container);
        batchProgress = (ProgressBar) findViewById(R.id.tool_batch_progress);
        batchProgressText = (TextView) findViewById(R.id.tool_batch_progress_text);
        Icons.left(install, Icons.FOLDER, 0xFF0D0D0D, dp(18));
        Icons.left(remove, Icons.STOP, 0xFF0D0D0D, dp(18));
        Icons.left(probe, Icons.PLAY, 0xFF0D0D0D, dp(18));
        Icons.left(cancel, Icons.STOP, 0xFF0D0D0D, dp(18));
        Icons.left(batchProbe, Icons.PLAY, 0xFF0D0D0D, dp(18));
        useRoot.setChecked(settings.useRoot());
        useRoot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) { updateRoot(checked); }
        });
        install.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { manage("package_install", R.string.toolkit_installing); }
        });
        remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { manage("package_remove", R.string.toolkit_removing); }
        });
        probe.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (operations.snapshot().busy || operations.snapshot().refreshing) return;
                begin(getString(R.string.toolkit_loading));
                startOperation("status", toolId);
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { cancelActiveToolkit(); }
        });
        batchProbe.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { probeAllTools(); }
        });
        restoreProbeResults(savedInstanceState);
        if (toolId.length() > 0) {
            batchProbe.setVisibility(View.GONE);
            findViewById(R.id.tool_config_controls).setVisibility(View.GONE);
            toolList.setVisibility(View.GONE);
            findViewById(R.id.tool_detail_content).setVisibility(View.VISIBLE);
            detailName.setText(ToolCatalog.get(toolId).name);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        activityStarted = true;
        operations.subscribe(operationListener);
        operations.refreshInventory();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        JSONArray results = new JSONArray();
        for (JSONObject result : probeResults.values()) results.put(result);
        state.putString("probe_results", results.toString());
        state.putString("probe_context", probeContext);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStop() {
        activityStarted = false;
        operations.unsubscribe(operationListener);
        super.onStop();
    }

    private void manage(String action, int status) {
        if (operations.snapshot().busy || operations.snapshot().refreshing) return;
        probeResults.clear(); probeContext = "";
        begin(getString(status));
        beginInstallProgress("package_remove".equals(action));
        startOperation(action, "");
    }

    private void begin(String text) {
        installProgressContainer.setVisibility(View.GONE);
        installProgressText.setText("");
        batchProgressContainer.setVisibility(View.GONE);
        operationStatus.setText(text); operationStatus.setVisibility(View.VISIBLE); setBusy(true);
    }

    private void startOperation(String action, String id) {
        try { operations.start(action, id); }
        catch (IllegalStateException changedState) { renderOperation(operations.snapshot()); }
    }

    private void renderOperation(ToolkitOperationManager.Snapshot state) {
        if (state.revision < renderedRevision) return;
        renderedRevision = state.revision;
        active = state;
        try {
            if (!state.busy) { invalidateProbeContext(); useRoot.setChecked(settings.useRoot()); }
            JSONObject inventory = state.inventory();
            if (inventory != null && !inventory.toString().equals(renderedInventory)) {
                renderedInventory = inventory.toString(); renderTools(inventory);
            }
            boolean contextMatches = state.context.equals(currentProbeContext());
            if (state.id != 0 && (state.busy || contextMatches)) {
                if (renderedOperation != state.id) {
                    renderedOperation = state.id;
                    if (!"status".equals(state.action)) { probeResults.clear(); probeContext = state.context; }
                    if ("batch_status".equals(state.action)) { batchProgress.setProgress(0); resetProbeRows(); }
                    else if ("package_install".equals(state.action) || "package_remove".equals(state.action)) beginInstallProgress("package_remove".equals(state.action));
                }
                if ("batch_status".equals(state.action)) {
                    batchProgressContainer.setVisibility(View.VISIBLE);
                    installProgressContainer.setVisibility(View.GONE);
                    for (ToolBatchProbe.Progress progress : state.probes) applyBatchProgress(progress);
                    if (!state.busy) finishBatchProbe(state.result());
                } else if ("package_install".equals(state.action) || "package_remove".equals(state.action)) {
                    installProgressContainer.setVisibility(View.VISIBLE);
                    if (state.installProgress != null) applyInstallProgress(state.installProgress);
                    if (!state.busy) finishInstallProgress(state.result(), "package_remove".equals(state.action));
                } else if (!state.busy && state.result() != null) {
                    JSONObject result = state.result();
                    probeResults.put(state.tool, result); probeContext = state.context;
                    if (toolId.equals(state.tool)) showProbe(result);
                    String error = result.optString("error");
                    operationStatus.setText(error.length() > 0 ? error : toolkitState(result.optString("state")));
                    operationStatus.setVisibility(error.length() > 0 || "cancelled".equals(state.state) ? View.VISIBLE : View.GONE);
                }
            }
            if (state.id != 0 && !state.busy && !contextMatches) operations.refreshInventory();
        } catch (Exception invalid) { operationStatus.setText(R.string.toolkit_failed); operationStatus.setVisibility(View.VISIBLE); }
        setBusy(state.busy || state.refreshing);
        cancel.setVisibility(state.busy ? View.VISIBLE : View.GONE);
    }

    private void beginInstallProgress(boolean removing) {
        installProgress.setMax(100); installProgress.setProgress(0); installProgress.setIndeterminate(true);
        installProgressText.setText(removing ? R.string.toolkit_progress_remove_preparing : R.string.toolkit_progress_preparing);
        installProgressContainer.setVisibility(View.VISIBLE);
    }

    private void applyInstallProgress(EmbeddedToolchain.Progress progress) {
        if (progress.total <= 0) {
            installProgress.setIndeterminate(true);
            installProgressText.setText("removing".equals(progress.stage) ? R.string.toolkit_progress_remove_preparing : R.string.toolkit_progress_preparing);
            return;
        }
        // Final publication still has to return successfully and close its UI session.
        int percent = Math.max(installProgress.getProgress(), Math.min(99, Math.max(0, progress.percent())));
        installProgress.setIndeterminate(false); installProgress.setProgress(percent);
        boolean removing = active != null && "package_remove".equals(active.action);
        String phase = installProgressPhase(removing ? "removing" : progress.stage);
        if (progress.artifact.length() > 0) {
            String artifact = "any".equals(progress.artifact) ? getString(R.string.toolkit_progress_common)
                    : getString(R.string.toolkit_progress_device);
            phase = getString(R.string.toolkit_progress_phase_artifact, phase, artifact);
        }
        installProgressText.setText(getString(R.string.toolkit_progress_value, percent, phase)
                + "\n" + (removing ? getString(R.string.toolkit_progress_files, progress.completed, progress.total)
                    : getString(R.string.toolkit_progress_bytes,
                        android.text.format.Formatter.formatFileSize(this, progress.completed),
                        android.text.format.Formatter.formatFileSize(this, progress.total))));
    }

    private String installProgressPhase(String stage) {
        int resource = "probing".equals(stage) ? R.string.toolkit_progress_probing
                : "downloading".equals(stage) ? R.string.toolkit_progress_downloading
                : "resuming".equals(stage) ? R.string.toolkit_progress_resuming
                : "switching".equals(stage) ? R.string.toolkit_progress_switching
                : "verifying".equals(stage) ? R.string.toolkit_progress_verifying
                : "unpacking".equals(stage) ? R.string.toolkit_progress_unpacking
                : "publishing".equals(stage) ? R.string.toolkit_progress_publishing
                : "removing".equals(stage) ? R.string.toolkit_progress_removing
                : "registering".equals(stage) || "complete".equals(stage) ? R.string.toolkit_progress_registering
                : R.string.toolkit_progress_checking;
        return getString(resource);
    }

    private void finishInstallProgress(JSONObject result, boolean removing) {
        boolean success = result != null && result.optString("error").length() == 0
                && (removing ? ("removed".equals(result.optString("state")) || "not_installed".equals(result.optString("state")))
                    && !result.optBoolean("installed") && result.optLong("installed_bytes") == 0
                    : "installed".equals(result.optString("state")) && result.optBoolean("installed"));
        installProgress.setIndeterminate(false);
        installProgress.setProgress(success ? 100 : Math.min(99, installProgress.getProgress()));
        int resource = success ? removing ? R.string.toolkit_progress_removed : R.string.toolkit_progress_complete
                : result == null || "cancelled".equals(result.optString("state")) ? R.string.toolkit_cancelled : R.string.toolkit_failed;
        installProgressText.setText(getString(R.string.toolkit_progress_value, installProgress.getProgress(), getString(resource)));
        String error = result == null ? "" : result.optString("error");
        operationStatus.setText(error.length() > 0 ? error : getString(resource));
        operationStatus.setVisibility(success ? View.GONE : View.VISIBLE);
    }

    private void restoreProbeResults(Bundle state) {
        try {
            String saved = state == null ? "" : state.getString("probe_results", "");
            probeContext = state == null ? "" : state.getString("probe_context", "");
            JSONArray results = saved.length() == 0 ? new JSONArray() : new JSONArray(saved);
            for (int i = 0; i < results.length(); i++) {
                JSONObject result = results.getJSONObject(i); String id = result.optString("id");
                ToolCatalog.get(id); probeResults.put(id, result);
            }
            String selectedResult = getIntent().getStringExtra(EXTRA_PROBE_RESULT);
            if (toolId.length() > 0 && selectedResult != null && !probeResults.containsKey(toolId)) {
                JSONObject result = new JSONObject(selectedResult);
                if (toolId.equals(result.optString("id"))) {
                    probeResults.put(toolId, result);
                    probeContext = getIntent().getStringExtra(EXTRA_PROBE_CONTEXT);
                    if (probeContext == null) probeContext = "";
                }
            }
        } catch (Exception ignored) { }
    }

    private void probeAllTools() {
        if (operations.snapshot().busy || operations.snapshot().refreshing) return;
        begin(getString(R.string.toolkit_batch_preparing));
        probeResults.clear();
        probeContext = currentProbeContext();
        try { batchProgress.setMax(ToolCatalog.list().length()); }
        catch (Exception failure) { setBusy(false); operationStatus.setText(String.valueOf(failure.getMessage())); return; }
        batchProgress.setProgress(0); batchProgress.setIndeterminate(false);
        batchProgressText.setText(getString(R.string.toolkit_batch_preparing));
        batchProgressContainer.setVisibility(View.VISIBLE);
        startOperation("batch_status", "");
        resetProbeRows();
    }

    private void resetProbeRows() {
        for (Map.Entry<String, TextView> row : toolRows.entrySet()) row.getValue().setText(ToolCatalog.get(row.getKey()).name
                + "\n" + getString(R.string.toolkit_batch_pending));
    }

    private void applyBatchProgress(ToolBatchProbe.Progress progress) {
        int previous = batchProgress.getProgress();
        batchProgress.setMax(progress.total); batchProgress.setProgress(Math.max(previous, progress.completed));
        if (progress.completed >= previous) batchProgressText.setText(getString(R.string.toolkit_batch_progress, progress.completed, progress.total)
                + "\n" + getString("running".equals(progress.stage) ? R.string.toolkit_batch_current : R.string.toolkit_batch_checked, progress.name));
        TextView row = toolRows.get(progress.id);
        if ("running".equals(progress.stage)) {
            if (row != null) row.setText(progress.name + "\n" + getString(R.string.toolkit_batch_current, progress.name));
            return;
        }
        try {
            JSONObject result = progress.result();
            if (result != null) {
                probeResults.put(progress.id, result);
                if (row != null) row.setText(progress.name + "\n" + probeRowStatus(result));
                if (toolId.equals(progress.id)) showProbe(result);
            }
        } catch (Exception failure) { operationStatus.setText(String.valueOf(failure.getMessage())); }
    }

    private String probeRowStatus(JSONObject result) {
        String state = toolkitState(result.optString("state"));
        String error = result.optString("error");
        if (error.length() == 0 && "unavailable".equals(result.optString("state"))) error = result.optString("probe_output");
        return error.length() == 0 ? state : state + "\n" + shortText(error.replace('\n', ' ').replace('\r', ' '), 180);
    }

    private String currentProbeContext() {
        return operations.configurationContext();
    }

    private void invalidateProbeContext() {
        if (!probeResults.isEmpty() && (!bundle.optBoolean("installed") || !currentProbeContext().equals(probeContext))) {
            probeResults.clear(); probeContext = "";
            renderedInventory = "";
            detailOutput.setText(""); detailOutput.setVisibility(View.GONE);
            batchProgressContainer.setVisibility(View.GONE);
            operationStatus.setVisibility(View.GONE);
        }
    }

    private void finishBatchProbe(JSONObject result) {
        boolean finished = result != null && "batch_complete".equals(result.optString("state")) && result.optString("error").length() == 0;
        if (finished) {
            batchProgress.setMax(result.optInt("total")); batchProgress.setProgress(result.optInt("completed"));
            batchProgressText.setText(getString(R.string.toolkit_batch_summary, result.optInt("completed"), result.optInt("total"),
                    result.optInt("ready_count"), result.optInt("failed_count")));
        } else {
            int label = result == null || "cancelled".equals(result.optString("state")) ? R.string.toolkit_cancelled : R.string.toolkit_failed;
            batchProgressText.setText(getString(R.string.toolkit_batch_progress, batchProgress.getProgress(), batchProgress.getMax())
                    + "\n" + getString(label));
            for (Map.Entry<String, TextView> row : toolRows.entrySet()) if (!probeResults.containsKey(row.getKey()))
                row.getValue().setText(ToolCatalog.get(row.getKey()).name + "\n" + getString(R.string.toolkit_batch_not_checked));
        }
        String error = result == null ? "" : result.optString("error");
        operationStatus.setText(error.length() > 0 ? error : batchProgressText.getText());
        operationStatus.setVisibility(View.VISIBLE);
    }

    private void cancelActiveToolkit() {
        ToolkitOperationManager.Snapshot state = operations.snapshot();
        if (state.busy) operations.cancel(state.id);
    }

    private void setBusy(boolean busy) {
        useRoot.setEnabled(!busy);
        install.setEnabled(!busy && !"unsupported".equals(bundle.optString("state")));
        remove.setEnabled(!busy && (bundle.optBoolean("installed") || bundle.optBoolean("can_remove")));
        probe.setEnabled(!busy && bundle.optBoolean("installed"));
        batchProbe.setEnabled(!busy && bundle.optBoolean("installed"));
        cancel.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void renderTools(JSONObject result) {
        JSONObject packageInfo = result.optJSONObject("package");
        if (packageInfo != null) {
            bundle = packageInfo;
            invalidateProbeContext();
            String state = bundle.optString("state");
            String storage = getString(R.string.toolkit_storage, result.optString("storage"), result.optString("abi"));
            packageStatus.setText(packageSummary(bundle, storage));
            install.setEnabled(!"unsupported".equals(state));
            remove.setEnabled(bundle.optBoolean("installed") || bundle.optBoolean("can_remove"));
            probe.setEnabled(bundle.optBoolean("installed"));
            batchProbe.setEnabled(bundle.optBoolean("installed"));
        }
        JSONArray tools = result.optJSONArray("tools");
        if (tools == null) return;
        toolList.removeAllViews();
        toolRows.clear();
        for (int i = 0; i < tools.length(); i++) {
            final JSONObject entry = tools.optJSONObject(i);
            if (entry == null) continue;
            JSONObject manifest = bundle == null ? null : bundle.optJSONObject("manifest");
            try { entry.put("version", toolVersion(entry.optString("id"), manifest)); }
            catch (Exception ignored) { }
            if (entry.optString("id").equals(toolId)) {
                selected = entry; detailInfo.setText(toolkitDetails(entry));
                JSONObject cached = probeResults.get(toolId); if (cached != null) showProbe(cached);
                continue;
            }
            if (toolId.length() > 0) continue;
            TextView row = new TextView(this);
            toolRows.put(entry.optString("id"), row);
            JSONObject cached = probeResults.get(entry.optString("id"));
            row.setText(entry.optString("name") + "\n" + probeRowStatus(cached == null ? entry : cached));
            row.setTextColor(getResources().getColor(R.color.text_primary)); row.setTextSize(14);
            row.setPadding(dp(8), dp(12), dp(8), dp(12)); row.setMinHeight(dp(64));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            Icons.right(row, Icons.CHEVRON_RIGHT, 0xFF6F6F6F, dp(18));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    Intent intent = new Intent(ToolConfigActivity.this, ToolConfigActivity.class);
                    String id = entry.optString("id"); intent.putExtra(EXTRA_TOOL_ID, id);
                    JSONObject result = probeResults.get(id);
                    if (result != null) {
                        intent.putExtra(EXTRA_PROBE_RESULT, result.toString());
                        intent.putExtra(EXTRA_PROBE_CONTEXT, probeContext);
                    }
                    startActivity(intent);
                }
            });
            toolList.addView(row, new LinearLayout.LayoutParams(-1, -2));
            View divider = new View(this); divider.setBackgroundColor(getResources().getColor(R.color.divider));
            toolList.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private String packageSummary(JSONObject packageInfo, String storage) {
        long bytes = packageInfo.optBoolean("installed") ? Math.max(0, packageInfo.optLong("installed_bytes")) : 0;
        return toolkitState(packageInfo.optString("state")) + "\n" + storage
                + (packageInfo.optBoolean("installed_size_unknown") ? "\n" + getString(R.string.toolkit_bundle_size_unavailable)
                    : bytes > 0 ? "\n" + getString(R.string.toolkit_bundle_bytes, android.text.format.Formatter.formatFileSize(this, bytes)) : "")
                + (packageInfo.optBoolean("busy") ? "\n" + getString(R.string.toolkit_busy) : "");
    }

    private void showProbe(JSONObject result) {
        try { if (toolId.length() > 0) probeResults.put(toolId, new JSONObject(result.toString())); }
        catch (Exception ignored) { }
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

    private void updateRoot(boolean enabled) {
        if (settings.useRoot() == enabled) return;
        if (operations.busy() || operations.snapshot().refreshing) { useRoot.setChecked(settings.useRoot()); return; }
        probeResults.clear();
        probeContext = "";
        settings.setUseRoot(enabled);
        RunHub.get(this).retargetTools();
        operations.refreshInventory();
    }
    private String toolkitState(String state) {
        if ("ready".equals(state)) return getString(R.string.toolkit_ready);
        if ("installed".equals(state)) return getString(R.string.toolkit_installed);
        if ("removed".equals(state) || "not_installed".equals(state)) return getString(R.string.toolkit_not_installed);
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

}
