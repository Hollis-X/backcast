package com.mkei.backcast;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.ui.Icons;

import java.util.List;

/** 设置界面：接口、模型、回复偏好、root、系统提示词。思考强度在对话里切。 */
public class SettingsActivity extends AppCompatActivity {

    private static final String[] OUTPUT_VERBOSITY_VALUES = {
            "default", "low", "medium", "high"
    };
    private static final String[] REASONING_SUMMARY_VALUES = {
            "auto", "concise", "detailed", "none"
    };
    private static final String[] OUTPUT_LANGUAGE_VALUES = {
            "zh-CN", "zh-TW", "en", "ja", "ko", "es", "fr", "de"
    };

    private EditText baseUrl;
    private EditText apiKey;
    private EditText model;
    private CheckBox useRoot;
    private EditText systemPrompt;
    private TextView envContext;
    private Button fetchModels;
    private TextView fetchStatus;
    private LinearLayout modelList;
    private Spinner outputVerbosity;
    private Spinner reasoningSummary;
    private Spinner outputLanguage;

    /** 当前勾选生效的模型，只能有一个。 */
    private String selected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        androidx.appcompat.widget.Toolbar toolbar =
                (androidx.appcompat.widget.Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        int mark = (int) (22 * getResources().getDisplayMetrics().density);
        toolbar.setNavigationIcon(Icons.tinted(this, Icons.BACK, 0xFF0D0D0D, mark));
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        baseUrl = (EditText) findViewById(R.id.base_url);
        apiKey = (EditText) findViewById(R.id.api_key);
        model = (EditText) findViewById(R.id.model);
        useRoot = (CheckBox) findViewById(R.id.use_root);
        systemPrompt = (EditText) findViewById(R.id.system_prompt);
        envContext = (TextView) findViewById(R.id.env_context);
        fetchModels = (Button) findViewById(R.id.fetch_models);
        fetchStatus = (TextView) findViewById(R.id.fetch_status);
        modelList = (LinearLayout) findViewById(R.id.model_list);
        outputVerbosity = (Spinner) findViewById(R.id.output_verbosity);
        reasoningSummary = (Spinner) findViewById(R.id.reasoning_summary);
        outputLanguage = (Spinner) findViewById(R.id.output_language);

        final Settings settings = new Settings(this);
        baseUrl.setText(settings.baseUrl());
        apiKey.setText(settings.apiKey());
        model.setText(settings.model());
        useRoot.setChecked(settings.useRoot());
        bindChoices(outputVerbosity, R.array.output_verbosity_labels,
                R.array.output_verbosity_descriptions, R.id.output_verbosity_description,
                OUTPUT_VERBOSITY_VALUES, settings.outputVerbosity());
        bindChoices(reasoningSummary, R.array.reasoning_summary_labels,
                R.array.reasoning_summary_descriptions, R.id.reasoning_summary_description,
                REASONING_SUMMARY_VALUES, settings.reasoningSummary());
        bindChoices(outputLanguage, R.array.output_language_labels,
                R.array.output_language_descriptions, R.id.output_language_description,
                OUTPUT_LANGUAGE_VALUES, settings.outputLanguage());
        // 输入框只放静态指令，环境事实另外只读展示，不会被一起存下来。
        systemPrompt.setText(settings.systemPrompt());
        if (envContext != null) {
            envContext.setText(settings.environmentContext());
        }

        // root 变了环境里的命令执行方式就变了，勾选时同步一下预览。
        useRoot.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        if (envContext != null) {
                            envContext.setText(settings.environmentContext(on));
                        }
                    }
                });

        selected = settings.model();
        renderModels(settings.modelList());

        fetchModels.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doFetch(settings);
            }
        });

        Button save = (Button) findViewById(R.id.save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings.save(
                        baseUrl.getText().toString(),
                        apiKey.getText().toString(),
                        model.getText().toString(),
                        useRoot.isChecked(),
                        systemPrompt.getText().toString());
                settings.setOutputVerbosity(selectedValue(outputVerbosity, OUTPUT_VERBOSITY_VALUES));
                settings.setReasoningSummary(selectedValue(reasoningSummary, REASONING_SUMMARY_VALUES));
                settings.setOutputLanguage(selectedValue(outputLanguage, OUTPUT_LANGUAGE_VALUES));
                Toast.makeText(SettingsActivity.this,
                        R.string.toast_saved, Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void bindChoices(Spinner spinner, int labels, int descriptions,
            int descriptionView, String[] values, String current) {
        final ChoiceAdapter adapter = new ChoiceAdapter(spinner,
                getResources().getStringArray(labels), getResources().getStringArray(descriptions));
        final TextView description = (TextView) findViewById(descriptionView);
        spinner.setAdapter(adapter);
        int selection = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                selection = i;
                break;
            }
        }
        spinner.setSelection(selection);
        description.setText(adapter.descriptionAt(selection));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                description.setText(adapter.descriptionAt(position));
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                description.setText(adapter.descriptionAt(0));
            }
        });
    }

    private final class ChoiceAdapter extends ArrayAdapter<String> {
        private final Spinner owner;
        private final String[] descriptions;

        ChoiceAdapter(Spinner owner, String[] labels, String[] descriptions) {
            super(SettingsActivity.this, android.R.layout.simple_spinner_item, labels);
            this.owner = owner;
            this.descriptions = descriptions;
        }

        String descriptionAt(int position) {
            return position >= 0 && position < descriptions.length ? descriptions[position] : "";
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView title = (TextView) super.getView(position, convertView, parent);
            title.setTextColor(getResources().getColor(R.color.text_primary));
            title.setTextSize(14);
            title.setSingleLine(false);
            return title;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            ChoiceRow fields;
            if (row == null || !(row.getTag() instanceof ChoiceRow)) {
                row = getLayoutInflater().inflate(R.layout.settings_choice_item, parent, false);
                fields = new ChoiceRow(row);
                row.setTag(fields);
            } else {
                fields = (ChoiceRow) row.getTag();
            }
            fields.title.setText(getItem(position));
            fields.description.setText(descriptionAt(position));
            boolean selected = position == owner.getSelectedItemPosition();
            row.setSelected(selected);
            row.setBackgroundResource(selected ? R.drawable.bg_settings_choice_selected : 0);
            fields.check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            return row;
        }
    }

    private final class ChoiceRow {
        final TextView title;
        final TextView description;
        final ImageView check;

        ChoiceRow(View row) {
            title = (TextView) row.findViewById(R.id.choice_title);
            description = (TextView) row.findViewById(R.id.choice_description);
            check = (ImageView) row.findViewById(R.id.choice_check);
            int size = (int) (22 * getResources().getDisplayMetrics().density);
            check.setImageDrawable(Icons.tinted(SettingsActivity.this,
                    R.drawable.ic_ds_checkmark_lg_regular_24,
                    getResources().getColor(R.color.text_primary), size));
        }
    }

    private String selectedValue(Spinner spinner, String[] values) {
        int index = spinner.getSelectedItemPosition();
        return index >= 0 && index < values.length ? values[index] : values[0];
    }

    /** 后台线程拉取模型列表，回来后渲染勾选项。 */
    private void doFetch(final Settings settings) {
        final String url = baseUrl.getText().toString().trim();
        final String key = apiKey.getText().toString().trim();

        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) {
            Toast.makeText(this, R.string.toast_need_url_key, Toast.LENGTH_SHORT).show();
            return;
        }

        fetchModels.setEnabled(false);
        showStatus(getString(R.string.fetching));

        new Thread(new Runnable() {
            @Override
            public void run() {
                final LlmClient.ModelsResult result = LlmClient.fetchModels(url, key);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        fetchModels.setEnabled(true);

                        if (result.error != null) {
                            showStatus(getString(R.string.fetch_failed, result.error));
                            return;
                        }

                        showStatus(getString(R.string.fetch_count, result.models.size()));
                        settings.saveModelList(result.models);
                        renderModels(result.models);
                    }
                });
            }
        }).start();
    }

    private void showStatus(String text) {
        fetchStatus.setText(text);
        fetchStatus.setVisibility(View.VISIBLE);
    }

    /** 渲染可勾选的模型列表。 */
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
            final CheckBox cb = new CheckBox(this);
            cb.setText(name);
            cb.setTextSize(13);
            cb.setTextColor(getResources().getColor(R.color.text_primary));
            cb.setChecked(name.equals(selected));
            cb.setPadding(8, 8, 8, 8);

            cb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (cb.isChecked()) {
                        // 单选：其余全部取消。
                        selected = name;
                        model.setText(name);
                        syncChecks();
                    } else {
                        // 不允许一个都不选。
                        cb.setChecked(true);
                    }
                }
            });

            modelList.addView(cb);
        }
    }

    /** 让勾选状态与 selected 保持一致。 */
    private void syncChecks() {
        for (int i = 0; i < modelList.getChildCount(); i++) {
            View child = modelList.getChildAt(i);
            if (child instanceof CheckBox) {
                CheckBox cb = (CheckBox) child;
                cb.setChecked(cb.getText().toString().equals(selected));
            }
        }
    }
}
