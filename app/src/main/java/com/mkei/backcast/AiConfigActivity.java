package com.mkei.backcast;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.ui.Icons;

import java.util.ArrayList;
import java.util.List;

public class AiConfigActivity extends AppCompatActivity {
    private EditText baseUrl;
    private EditText apiKey;
    private EditText model;
    private Button fetchModels;
    private TextView fetchStatus;
    private LinearLayout modelList;
    private ArrayList<String> previewModels = new ArrayList<String>();
    private int requestGeneration;
    private boolean destroyed;
    private Thread activeFetch;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_ai_config);
        androidx.appcompat.widget.Toolbar toolbar = (androidx.appcompat.widget.Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        int mark = (int) (22 * getResources().getDisplayMetrics().density);
        toolbar.setNavigationIcon(Icons.tinted(this, Icons.BACK, 0xFF0D0D0D, mark));
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        baseUrl = (EditText) findViewById(R.id.base_url);
        apiKey = (EditText) findViewById(R.id.api_key);
        model = (EditText) findViewById(R.id.model);
        fetchModels = (Button) findViewById(R.id.fetch_models);
        fetchStatus = (TextView) findViewById(R.id.fetch_status);
        modelList = (LinearLayout) findViewById(R.id.model_list);
        final Settings settings = new Settings(this);
        baseUrl.setText(draft(state, "url", settings.baseUrl()));
        apiKey.setText(draft(state, "key", settings.apiKey()));
        model.setText(draft(state, "model", settings.model()));
        ArrayList<String> restored = state == null ? null : state.getStringArrayList("models");
        previewModels = restored == null ? new ArrayList<String>(settings.modelList()) : restored;
        renderModels(previewModels);
        TextWatcher connectionWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { onConnectionChanged(); }
        };
        baseUrl.addTextChangedListener(connectionWatcher);
        apiKey.addTextChangedListener(connectionWatcher);
        model.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { syncChecks(); }
        });
        fetchModels.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doFetch(); }
        });
        Button save = (Button) findViewById(R.id.save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveSettings(settings);
                Toast.makeText(AiConfigActivity.this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private String draft(Bundle state, String key, String fallback) {
        String value = state == null ? null : state.getString(key);
        return value == null ? fallback : value;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("url", baseUrl.getText().toString());
        state.putString("key", apiKey.getText().toString());
        state.putString("model", model.getText().toString());
        state.putStringArrayList("models", new ArrayList<String>(previewModels));
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        invalidateFetch();
        super.onDestroy();
    }

    @Override public void finish() {
        invalidateFetch();
        super.finish();
    }

    private void invalidateFetch() {
        requestGeneration++;
        if (activeFetch != null) { activeFetch.interrupt(); activeFetch = null; }
    }

    private void onConnectionChanged() {
        invalidateFetch();
        previewModels.clear();
        renderModels(previewModels);
        fetchModels.setEnabled(true);
        fetchStatus.setVisibility(View.GONE);
    }

    private void saveSettings(Settings settings) {
        invalidateFetch();
        settings.saveAiConfiguration(baseUrl.getText().toString(), apiKey.getText().toString(),
                model.getText().toString(), previewModels);
    }

    private boolean isCurrentFetch(int generation, String url, String key) {
        return !destroyed && !isFinishing() && generation == requestGeneration
                && url.equals(baseUrl.getText().toString().trim())
                && key.equals(apiKey.getText().toString().trim());
    }

    private void doFetch() {
        final String url = baseUrl.getText().toString().trim();
        final String key = apiKey.getText().toString().trim();
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) {
            Toast.makeText(this, R.string.toast_need_url_key, Toast.LENGTH_SHORT).show();
            return;
        }
        invalidateFetch();
        final int generation = requestGeneration;
        fetchModels.setEnabled(false);
        showStatus(getString(R.string.fetching));
        activeFetch = new Thread(new Runnable() {
            @Override public void run() {
                final LlmClient.ModelsResult result = LlmClient.fetchModels(url, key);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!isCurrentFetch(generation, url, key)) return;
                        activeFetch = null;
                        fetchModels.setEnabled(true);
                        if (result.error != null) {
                            showStatus(getString(R.string.fetch_failed, result.error));
                            return;
                        }
                        previewModels = new ArrayList<String>(result.models);
                        showStatus(getString(R.string.fetch_count, previewModels.size()));
                        renderModels(previewModels);
                    }
                });
            }
        }, "backcast-model-list");
        activeFetch.start();
    }

    private void showStatus(String text) {
        fetchStatus.setText(text);
        fetchStatus.setVisibility(View.VISIBLE);
    }

    private void renderModels(List<String> models) {
        modelList.removeAllViews();
        if (models == null || models.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.model_empty);
            empty.setTextSize(12);
            empty.setTextColor(getResources().getColor(R.color.text_secondary));
            empty.setPadding(8, 8, 8, 8);
            modelList.addView(empty);
            return;
        }
        for (final String name : models) {
            final CheckBox choice = new CheckBox(this);
            choice.setText(name);
            choice.setTextSize(13);
            choice.setTextColor(getResources().getColor(R.color.text_primary));
            choice.setChecked(name.equals(model.getText().toString().trim()));
            choice.setPadding(8, 8, 8, 8);
            choice.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    model.setText(name);
                    syncChecks();
                }
            });
            modelList.addView(choice);
        }
    }

    private void syncChecks() {
        String selected = model.getText().toString().trim();
        for (int i = 0; i < modelList.getChildCount(); i++) {
            View child = modelList.getChildAt(i);
            if (child instanceof CheckBox) {
                CheckBox choice = (CheckBox) child;
                choice.setChecked(selected.equals(choice.getText().toString()));
            }
        }
    }
}
