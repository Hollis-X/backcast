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
import com.mkei.backcast.tool.ToolchainInstaller;
import com.mkei.backcast.tool.ToolchainDownloader;
import com.mkei.backcast.agent.Diagnostics;
import com.mkei.backcast.ui.Icons;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Package installation and one tool's detail are separate navigation pages. */
public final class ToolConfigActivity extends AppCompatActivity {
    public static final String EXTRA_TOOL_ID = "tool_id";
    public static final String EXTRA_PROBE_RESULT = "probe_result";
    public static final String EXTRA_PROBE_CONTEXT = "probe_context";
    private final ExecutorService toolkitReader = Executors.newSingleThreadExecutor();
    private final ExecutorService toolkitCancellation = Executors.newSingleThreadExecutor();
    private final List<ToolkitOperation> toolkitOperations = new ArrayList<ToolkitOperation>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean activityDestroyed;
    private ToolkitOperation active;
    private Settings settings;
    private String toolId;
    private CheckBox useRoot;
    private TextView packageStatus, detailName, detailInfo, detailOutput, operationStatus, installProgressText;
    private View installProgressContainer;
    private ProgressBar installProgress;
    private boolean progressTerminal;
    private boolean batchTerminal;
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

    private interface ToolkitResult { void apply(JSONObject result); }
    private static final class ToolkitOperation {
        volatile boolean cancelled;
        volatile java.util.concurrent.Future<?> future;
        RunHub.ToolkitSession session;
        boolean installing, probingAll, progressQueued;
        EmbeddedToolchain.Progress pendingProgress;
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
                begin(getString(R.string.toolkit_loading));
                active = requestToolkit(toolkitArguments("status", toolId), new ToolkitResult() {
                    @Override public void apply(JSONObject result) { finishOperation(result); showProbe(result); }
                });
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

    @Override protected void onResume() { super.onResume(); if (active == null) loadTools(progressTerminal || batchTerminal); }

    @Override protected void onSaveInstanceState(Bundle state) {
        JSONArray results = new JSONArray();
        for (JSONObject result : probeResults.values()) results.put(result);
        state.putString("probe_results", results.toString());
        state.putString("probe_context", probeContext);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStop() {
        boolean installing = active != null && active.installing;
        boolean probingAll = active != null && active.probingAll;
        for (ToolkitOperation operation : pendingOperations()) cancelToolkitOperation(operation);
        active = null;
        if (installing) finishInstallProgress(null);
        if (probingAll) finishBatchProbe(null);
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

    private void loadTools(final boolean preserveStatus) {
        if (!preserveStatus) begin(getString(R.string.toolkit_loading));
        else setBusy(true);
        active = requestToolkit(toolkitArguments("list", ""), new ToolkitResult() {
            @Override public void apply(JSONObject result) {
                if (!preserveStatus) finishOperation(result);
                else {
                    active = null; setBusy(false);
                    String error = result.optString("error");
                    if (error.length() > 0) operationStatus.setText(getString(R.string.toolkit_progress_refresh_failed, error));
                }
                renderTools(result);
            }
        });
    }

    private void manage(String action, int status) {
        probeResults.clear(); probeContext = "";
        begin(getString(status));
        if ("package_install".equals(action)) beginInstallProgress();
        active = requestToolkit(toolkitArguments(action, ""), new ToolkitResult() {
            @Override public void apply(JSONObject result) {
                finishOperation(result);
                if (!"error".equals(result.optString("state")) && !"cancelled".equals(result.optString("state"))) loadTools(progressTerminal);
            }
        });
    }

    private void begin(String text) {
        if (active != null) cancelToolkitOperation(active);
        progressTerminal = false;
        batchTerminal = false;
        installProgressContainer.setVisibility(View.GONE);
        batchProgressContainer.setVisibility(View.GONE);
        operationStatus.setText(text); operationStatus.setVisibility(View.VISIBLE); setBusy(true);
    }

    private void finishOperation(JSONObject result) {
        boolean installing = active != null && active.installing;
        boolean probingAll = active != null && active.probingAll;
        active = null; setBusy(false);
        if (installing) { finishInstallProgress(result); return; }
        if (probingAll) { finishBatchProbe(result); return; }
        String error = result.optString("error");
        operationStatus.setText(error.length() > 0 ? error : toolkitState(result.optString("state")));
        operationStatus.setVisibility(error.length() > 0 || "cancelled".equals(result.optString("state")) ? View.VISIBLE : View.GONE);
    }

    private void beginInstallProgress() {
        installProgress.setMax(100); installProgress.setProgress(0); installProgress.setIndeterminate(true);
        installProgressText.setText(R.string.toolkit_progress_preparing);
        installProgressContainer.setVisibility(View.VISIBLE);
    }

    private void queueInstallProgress(final ToolkitOperation operation, EmbeddedToolchain.Progress progress) {
        synchronized (operation) {
            if (operation.cancelled) return;
            operation.pendingProgress = progress;
            if (operation.progressQueued) return;
            operation.progressQueued = true;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                EmbeddedToolchain.Progress latest;
                synchronized (operation) {
                    latest = operation.pendingProgress;
                    operation.pendingProgress = null; operation.progressQueued = false;
                }
                if (latest != null && !operation.cancelled && !activityDestroyed && !isFinishing() && active == operation)
                    applyInstallProgress(latest);
            }
        }, 100);
    }

    private void applyInstallProgress(EmbeddedToolchain.Progress progress) {
        if (progress.total <= 0) {
            installProgress.setIndeterminate(true);
            installProgressText.setText(R.string.toolkit_progress_preparing);
            return;
        }
        // Final publication still has to return successfully and close its UI session.
        int percent = Math.max(installProgress.getProgress(), Math.min(99, Math.max(0, progress.percent())));
        installProgress.setIndeterminate(false); installProgress.setProgress(percent);
        String phase = installProgressPhase(progress.stage);
        if (progress.artifact.length() > 0) {
            String artifact = "any".equals(progress.artifact) ? getString(R.string.toolkit_progress_common)
                    : getString(R.string.toolkit_progress_device);
            phase = getString(R.string.toolkit_progress_phase_artifact, phase, artifact);
        }
        installProgressText.setText(getString(R.string.toolkit_progress_value, percent, phase)
                + "\n" + getString(R.string.toolkit_progress_bytes,
                        android.text.format.Formatter.formatFileSize(this, progress.completed),
                        android.text.format.Formatter.formatFileSize(this, progress.total)));
    }

    private String installProgressPhase(String stage) {
        int resource = "probing".equals(stage) ? R.string.toolkit_progress_probing
                : "downloading".equals(stage) ? R.string.toolkit_progress_downloading
                : "resuming".equals(stage) ? R.string.toolkit_progress_resuming
                : "switching".equals(stage) ? R.string.toolkit_progress_switching
                : "verifying".equals(stage) ? R.string.toolkit_progress_verifying
                : "unpacking".equals(stage) ? R.string.toolkit_progress_unpacking
                : "publishing".equals(stage) ? R.string.toolkit_progress_publishing
                : "registering".equals(stage) || "complete".equals(stage) ? R.string.toolkit_progress_registering
                : R.string.toolkit_progress_checking;
        return getString(resource);
    }

    private void finishInstallProgress(JSONObject result) {
        boolean success = result != null && "installed".equals(result.optString("state")) && result.optBoolean("installed")
                && result.optString("error").length() == 0;
        progressTerminal = true;
        installProgress.setIndeterminate(false);
        installProgress.setProgress(success ? 100 : Math.min(99, installProgress.getProgress()));
        int resource = success ? R.string.toolkit_progress_complete
                : result == null || "cancelled".equals(result.optString("state")) ? R.string.toolkit_cancelled : R.string.toolkit_failed;
        installProgressText.setText(getString(R.string.toolkit_progress_value, installProgress.getProgress(), getString(resource)));
        String error = result == null ? "" : result.optString("error");
        operationStatus.setText(error.length() > 0 ? error : getString(resource));
        operationStatus.setVisibility(View.VISIBLE);
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
        begin(getString(R.string.toolkit_batch_preparing));
        probeResults.clear();
        probeContext = currentProbeContext();
        try { batchProgress.setMax(ToolCatalog.list().length()); }
        catch (Exception failure) { active = null; setBusy(false); operationStatus.setText(String.valueOf(failure.getMessage())); return; }
        batchProgress.setProgress(0); batchProgress.setIndeterminate(false);
        batchProgressText.setText(getString(R.string.toolkit_batch_preparing));
        batchProgressContainer.setVisibility(View.VISIBLE);
        active = requestToolkit(toolkitArguments("batch_status", ""), new ToolkitResult() {
            @Override public void apply(JSONObject result) { finishOperation(result); }
        });
        resetProbeRows();
    }

    private void resetProbeRows() {
        for (Map.Entry<String, TextView> row : toolRows.entrySet()) row.getValue().setText(ToolCatalog.get(row.getKey()).name
                + "\n" + getString(R.string.toolkit_batch_pending));
    }

    private void queueBatchProgress(final ToolkitOperation operation, final ToolBatchProbe.Progress progress) {
        ui(new Runnable() { @Override public void run() {
            if (!operation.cancelled && !activityDestroyed && !isFinishing() && active == operation) applyBatchProgress(progress);
        } });
    }

    private void applyBatchProgress(ToolBatchProbe.Progress progress) {
        batchProgress.setMax(progress.total); batchProgress.setProgress(progress.completed);
        batchProgressText.setText(getString(R.string.toolkit_batch_progress, progress.completed, progress.total)
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
        JSONObject manifest = bundle.optJSONObject("manifest");
        return String.valueOf(settings.useRoot()) + "|" + settings.accessLevel() + "|"
                + new JSONArray(settings.authorizedWorkDirs()).toString() + "|" + bundle.optString("version") + "|"
                + (manifest == null ? "" : String.valueOf(manifest.optJSONArray("artifacts")));
    }

    private void invalidateProbeContext() {
        if (!probeResults.isEmpty() && (!bundle.optBoolean("installed") || !currentProbeContext().equals(probeContext))) {
            probeResults.clear(); probeContext = "";
            detailOutput.setText(""); detailOutput.setVisibility(View.GONE);
        }
    }

    private void finishBatchProbe(JSONObject result) {
        batchTerminal = true;
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
        boolean installing = active != null && active.installing;
        boolean probingAll = active != null && active.probingAll;
        if (active != null) cancelToolkitOperation(active);
        active = null; setBusy(false);
        operationStatus.setText(R.string.toolkit_cancelled);
        operationStatus.setVisibility(View.VISIBLE);
        if (installing) finishInstallProgress(null);
        if (probingAll) finishBatchProbe(null);
    }

    private void setBusy(boolean busy) {
        install.setEnabled(!busy && !"unsupported".equals(bundle.optString("state")));
        remove.setEnabled(!busy && (bundle.optBoolean("installed") || "removed".equals(bundle.optString("state"))));
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
            long bytes = bundle.optLong("installed_bytes");
            packageStatus.setText(toolkitState(state) + "\n" + storage
                    + (bytes > 0 ? "\n" + getString(R.string.toolkit_bundle_bytes, android.text.format.Formatter.formatFileSize(this, bytes)) : "")
                    + (bundle.optBoolean("busy") ? "\n" + getString(R.string.toolkit_busy) : ""));
            install.setEnabled(!"unsupported".equals(state));
            remove.setEnabled(bundle.optBoolean("installed") || "removed".equals(state));
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

    private void showProbe(JSONObject result) {
        try { if (toolId.length() > 0) { probeResults.put(toolId, new JSONObject(result.toString())); probeContext = currentProbeContext(); } }
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
        if (active != null) cancelActiveToolkit();
        probeResults.clear();
        probeContext = "";
        settings.setUseRoot(enabled);
        RunHub.get(this).retargetTools();
        loadTools(false);
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
        if ("not_installed".equals(state)) return getString(R.string.toolkit_not_installed);
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
        operation.installing = "package_install".equals(args.optString("action"));
        operation.probingAll = "batch_status".equals(args.optString("action"));
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
                    if ("package_install".equals(action)) result = session.toolkit.installBundled(new EmbeddedToolchain.ProgressListener() {
                        @Override public void onProgress(EmbeddedToolchain.Progress progress) { queueInstallProgress(operation, progress); }
                    });
                    else if ("package_remove".equals(action)) result = session.toolkit.removeBundled();
                    else if ("batch_status".equals(action)) result = ToolBatchProbe.run(session.toolkit,
                            new ToolchainInstaller.Cancellation() { public void check() throws Exception {
                                if (operation.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException("批量检测已取消。");
                            } }, new ToolBatchProbe.Listener() {
                                @Override public void onProgress(ToolBatchProbe.Progress progress) { queueBatchProgress(operation, progress); }
                            });
                    else result = new JSONObject(session.toolkit.run(args));
                } catch (InterruptedException cancellation) {
                    Thread.currentThread().interrupt();
                    try { result.put("state", "cancelled").put("error", String.valueOf(cancellation.getMessage())); }
                    catch (Exception ignored) { }
                } catch (Exception failure) {
                    recordToolkitFailure(args, failure);
                    try { result.put("state", "error").put("error", getString(R.string.toolkit_failed)); }
                    catch (Exception ignored) { }
                } finally {
                    try { closeToolkitSession(operation); }
                    catch (Exception cleanup) {
                        recordToolkitFailure(args, cleanup);
                        try { result.put("state", "error").put("error", getString(R.string.toolkit_failed)); }
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

    private void recordToolkitFailure(JSONObject args, Throwable failure) {
        ChatStore diagnostics = null;
        try {
            List<String> secrets = new ArrayList<String>();
            for (Settings.AiProfile profile : settings.aiProfiles()) secrets.add(profile.apiKey);
            JSONObject evidence = Diagnostics.failure(failure);
            evidence.put("reason", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            evidence.put("action", args.optString("action")).put("tool", args.optString("tool"));
            if (failure instanceof ToolchainDownloader.Failure) evidence.put("download", new JSONObject(((ToolchainDownloader.Failure) failure).diagnostic()));
            diagnostics = new ChatStore(getApplicationContext());
            diagnostics.recordDiagnostic(-1L, "toolkit", "工具配置操作失败", Diagnostics.boundedJson(evidence, secrets.toArray(new String[secrets.size()])));
        } catch (Exception unavailable) {
            android.util.Log.w("Backcast", "Unable to persist toolkit configuration diagnostic");
        } finally { if (diagnostics != null) diagnostics.close(); }
    }
    private void ui(Runnable callback) { main.post(callback); }
}
