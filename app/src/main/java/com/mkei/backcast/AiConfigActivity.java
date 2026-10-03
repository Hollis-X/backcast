package com.mkei.backcast;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.ui.Icons;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provider drafts are local to this page until the user presses Save. */
public class AiConfigActivity extends AppCompatActivity {
    private EditText baseUrl, apiKey, model;
    private Spinner provider;
    private Button fetchModels;
    private TextView fetchStatus;
    private LinearLayout modelList;
    private ArrayList<String> previewModels = new ArrayList<String>();
    private final Map<String, Settings.AiProfile> drafts = new LinkedHashMap<String, Settings.AiProfile>();
    private final Set<String> editedProfiles = new LinkedHashSet<String>();
    private final ArrayList<String> providerIds = new ArrayList<String>();
    private String selectedProvider;
    private int requestGeneration;
    private boolean destroyed, bindingDraft;
    private Thread activeFetch;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_ai_config);
        androidx.appcompat.widget.Toolbar toolbar = (androidx.appcompat.widget.Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        int mark = (int) (22 * getResources().getDisplayMetrics().density);
        toolbar.setNavigationIcon(Icons.tinted(this, Icons.BACK, 0xFF0D0D0D, mark));
        toolbar.setNavigationOnClickListener(v -> finish());
        baseUrl = (EditText) findViewById(R.id.base_url);
        apiKey = (EditText) findViewById(R.id.api_key);
        model = (EditText) findViewById(R.id.model);
        provider = (Spinner) findViewById(R.id.ai_provider);
        fetchModels = (Button) findViewById(R.id.fetch_models);
        fetchStatus = (TextView) findViewById(R.id.fetch_status);
        modelList = (LinearLayout) findViewById(R.id.model_list);
        final Settings settings = new Settings(this);
        restoreDrafts(settings, state);
        ArrayList<String> names = new ArrayList<String>();
        for (Settings.AiProfile profile : drafts.values()) { providerIds.add(profile.id); names.add(profile.name); }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        provider.setAdapter(adapter);
        loadDraft(selectedProvider);
        provider.setSelection(providerIds.indexOf(selectedProvider));
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switchProvider(providerIds.get(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        TextWatcher connectionWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { onConnectionChanged(); }
        };
        baseUrl.addTextChangedListener(connectionWatcher); apiKey.addTextChangedListener(connectionWatcher);
        model.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { if (!bindingDraft) syncChecks(); }
        });
        fetchModels.setOnClickListener(v -> doFetch());
        findViewById(R.id.add_saved_model).setOnClickListener(v -> addSavedModel());
        findViewById(R.id.save).setOnClickListener(v -> {
            saveSettings(settings);
            Toast.makeText(AiConfigActivity.this, R.string.toast_saved, Toast.LENGTH_SHORT).show(); finish();
        });
    }

    private String draft(Bundle state, String key, String fallback) {
        String value = state == null ? null : state.getString(key); return value == null ? fallback : value;
    }
    private void restoreDrafts(Settings settings, Bundle state) {
        for (Settings.AiProfile profile : settings.aiProfiles()) {
            Bundle saved = state == null ? null : state.getBundle("draft_" + profile.id);
            ArrayList<String> models = saved == null ? null : saved.getStringArrayList("models");
            drafts.put(profile.id, new Settings.AiProfile(profile.id, draft(saved, "url", profile.baseUrl),
                    draft(saved, "key", profile.apiKey), draft(saved, "model", profile.model), models == null ? profile.modelList : models));
        }
        selectedProvider = draft(state, "provider", settings.activeProviderId());
        if (!drafts.containsKey(selectedProvider)) selectedProvider = settings.activeProviderId();
        ArrayList<String> edited = state == null ? null : state.getStringArrayList("edited");
        if (edited != null) for (String id : edited) if (drafts.containsKey(id)) editedProfiles.add(id);
    }
    private void captureDraft() {
        if (selectedProvider == null) return;
        Settings.AiProfile old = drafts.get(selectedProvider);
        Settings.AiProfile current = new Settings.AiProfile(selectedProvider, baseUrl.getText().toString(),
                apiKey.getText().toString(), model.getText().toString(), previewModels);
        if (!old.baseUrl.equals(current.baseUrl) || !old.apiKey.equals(current.apiKey) || !old.model.equals(current.model)
                || !old.modelList.equals(current.modelList)) editedProfiles.add(selectedProvider);
        drafts.put(selectedProvider, current);
    }
    private void loadDraft(String id) {
        Settings.AiProfile value = drafts.get(id);
        bindingDraft = true;
        try {
            selectedProvider = id; baseUrl.setText(value.baseUrl); apiKey.setText(value.apiKey); model.setText(value.model);
            previewModels = new ArrayList<String>(value.modelList); renderModels(previewModels);
            fetchModels.setEnabled(true); fetchStatus.setVisibility(View.GONE);
        } finally { bindingDraft = false; }
    }
    private void switchProvider(String id) {
        if (id.equals(selectedProvider)) return;
        captureDraft(); invalidateFetch(); loadDraft(id);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        captureDraft(); state.putString("provider", selectedProvider);
        state.putStringArrayList("edited", new ArrayList<String>(editedProfiles));
        for (Settings.AiProfile value : drafts.values()) {
            Bundle saved = new Bundle(); saved.putString("url", value.baseUrl); saved.putString("key", value.apiKey);
            saved.putString("model", value.model); saved.putStringArrayList("models", new ArrayList<String>(value.modelList));
            state.putBundle("draft_" + value.id, saved);
        }
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { destroyed = true; invalidateFetch(); super.onDestroy(); }
    @Override public void finish() { invalidateFetch(); super.finish(); }
    private void invalidateFetch() {
        requestGeneration++; if (activeFetch != null) { activeFetch.interrupt(); activeFetch = null; }
    }
    private void onConnectionChanged() {
        if (bindingDraft) return;
        invalidateFetch(); previewModels.clear(); renderModels(previewModels);
        fetchModels.setEnabled(true); fetchStatus.setVisibility(View.GONE);
    }
    private void saveSettings(Settings settings) {
        invalidateFetch(); captureDraft(); ArrayList<Settings.AiProfile> edited = new ArrayList<Settings.AiProfile>();
        for (String id : editedProfiles) edited.add(drafts.get(id));
        settings.saveAiProfiles(edited, selectedProvider);
    }
    private boolean isCurrentFetch(int generation, String providerId, String url, String key) {
        return !destroyed && !isFinishing() && generation == requestGeneration && providerId.equals(selectedProvider)
                && url.equals(baseUrl.getText().toString().trim()) && key.equals(apiKey.getText().toString().trim());
    }
    private void doFetch() {
        final String url = baseUrl.getText().toString().trim(), key = apiKey.getText().toString().trim();
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) {
            Toast.makeText(this, R.string.toast_need_url_key, Toast.LENGTH_SHORT).show(); return;
        }
        invalidateFetch(); final int generation = requestGeneration; final String providerId = selectedProvider;
        fetchModels.setEnabled(false); showStatus(getString(R.string.fetching));
        activeFetch = new Thread(() -> {
            final LlmClient.ModelsResult result = LlmClient.fetchModels(url, key, providerId);
            if (result.error != null) {
                ChatStore store = null;
                try {
                    store = new ChatStore(getApplicationContext());
                    store.recordDiagnostic(-1L, "models:" + providerId, "模型列表获取失败",
                            result.diagnostic != null ? result.diagnostic.toString() : result.error);
                } catch (RuntimeException loggingFailure) { /* Keep the failed fetch visible if storage is unavailable. */ }
                finally { if (store != null) try { store.close(); } catch (RuntimeException loggingFailure) { } }
            }
            runOnUiThread(() -> {
                if (!isCurrentFetch(generation, providerId, url, key)) return;
                activeFetch = null; fetchModels.setEnabled(true);
                if (result.error != null) { showStatus(getString(R.string.fetch_failed_summary)); return; }
                previewModels = new ArrayList<String>(result.models);
                showStatus(getString(R.string.fetch_count, previewModels.size())); renderModels(previewModels);
            });
        }, "backcast-model-list");
        activeFetch.start();
    }
    private void addSavedModel() {
        String name = model.getText().toString().trim();
        if (name.length() == 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
            Toast.makeText(this, R.string.toast_need_model, Toast.LENGTH_SHORT).show(); return;
        }
        if (!previewModels.contains(name)) previewModels.add(name); renderModels(previewModels);
    }
    private void showStatus(String text) { fetchStatus.setText(text); fetchStatus.setVisibility(View.VISIBLE); }
    private void renderModels(List<String> models) {
        modelList.removeAllViews();
        if (models == null || models.isEmpty()) {
            TextView empty = new TextView(this); empty.setText(R.string.model_empty); empty.setTextSize(12);
            empty.setTextColor(getResources().getColor(R.color.text_secondary)); empty.setPadding(8, 8, 8, 8);
            modelList.addView(empty); return;
        }
        for (final String name : new ArrayList<String>(models)) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            final CheckBox choice = new CheckBox(this); choice.setText(name); choice.setTextSize(13);
            choice.setTextColor(getResources().getColor(R.color.text_primary)); choice.setChecked(name.equals(model.getText().toString().trim()));
            choice.setPadding(8, 8, 8, 8); choice.setOnClickListener(v -> { model.setText(name); syncChecks(); });
            row.addView(choice, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button remove = new Button(this); remove.setText(R.string.action_remove_saved_model);
            remove.setOnClickListener(v -> {
                previewModels.remove(name); if (name.equals(model.getText().toString().trim())) model.setText("");
                renderModels(previewModels);
            });
            row.addView(remove); modelList.addView(row);
        }
    }
    private void syncChecks() {
        String selected = model.getText().toString().trim();
        for (int i = 0; i < modelList.getChildCount(); i++) {
            View child = modelList.getChildAt(i);
            if (child instanceof LinearLayout) {
                View first = ((LinearLayout) child).getChildAt(0);
                if (first instanceof CheckBox) ((CheckBox) first).setChecked(selected.equals(((CheckBox) first).getText().toString()));
            }
        }
    }
}
