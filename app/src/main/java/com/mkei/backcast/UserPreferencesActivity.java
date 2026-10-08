package com.mkei.backcast;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mkei.backcast.ui.Icons;

public class UserPreferencesActivity extends AppCompatActivity {
    private static final String[] OUTPUT_VERBOSITY_VALUES = { "default", "low", "medium", "high" };
    private static final String[] REASONING_SUMMARY_VALUES = { "auto", "concise", "detailed", "none" };
    private static final String[] OUTPUT_LANGUAGE_VALUES = { "zh-CN", "zh-TW", "en", "ja", "ko", "es", "fr", "de" };
    private static final String[] AGENT_CONCURRENCY_VALUES = { "1", "2", "3", "4" };
    private static final String[] REASONING_EFFORT_VALUES = { "off", "low", "medium", "high", "xhigh", "max", "ultra" };

    private EditText systemPrompt;
    private TextView envContext;
    private TextView agentStatus;
    private Spinner outputVerbosity;
    private Spinner reasoningSummary;
    private Spinner outputLanguage;
    private Spinner agentConcurrency;
    private Spinner reasoningEffort;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_user_preferences);
        androidx.appcompat.widget.Toolbar toolbar = (androidx.appcompat.widget.Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        int mark = (int) (22 * getResources().getDisplayMetrics().density);
        toolbar.setNavigationIcon(Icons.tinted(this, Icons.BACK, 0xFF0D0D0D, mark));
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        systemPrompt = (EditText) findViewById(R.id.system_prompt);
        envContext = (TextView) findViewById(R.id.env_context);
        agentStatus = (TextView) findViewById(R.id.agent_status);
        outputVerbosity = (Spinner) findViewById(R.id.output_verbosity);
        reasoningSummary = (Spinner) findViewById(R.id.reasoning_summary);
        outputLanguage = (Spinner) findViewById(R.id.output_language);
        agentConcurrency = (Spinner) findViewById(R.id.agent_concurrency);
        reasoningEffort = (Spinner) findViewById(R.id.reasoning_effort);
        final Settings settings = new Settings(this);
        bindChoices(outputVerbosity, R.array.output_verbosity_labels, R.array.output_verbosity_descriptions,
                R.id.output_verbosity_description, OUTPUT_VERBOSITY_VALUES, draft(state, "verbosity", settings.outputVerbosity()));
        bindChoices(reasoningSummary, R.array.reasoning_summary_labels, R.array.reasoning_summary_descriptions,
                R.id.reasoning_summary_description, REASONING_SUMMARY_VALUES, draft(state, "summary", settings.reasoningSummary()));
        bindChoices(outputLanguage, R.array.output_language_labels, R.array.output_language_descriptions,
                R.id.output_language_description, OUTPUT_LANGUAGE_VALUES, draft(state, "language", settings.outputLanguage()));
        bindChoices(agentConcurrency, R.array.agent_concurrency_labels, R.array.agent_concurrency_descriptions,
                R.id.agent_concurrency_description, AGENT_CONCURRENCY_VALUES,
                draft(state, "concurrency", Integer.toString(settings.agentConcurrency())));
        bindChoices(reasoningEffort, R.array.reasoning_effort_labels, R.array.reasoning_effort_descriptions,
                R.id.reasoning_effort_description, REASONING_EFFORT_VALUES, draft(state, "effort", settings.reasoningEffort()));
        systemPrompt.setText(draft(state, "prompt", settings.systemPrompt()));
        refreshAgentPreview(settings);
        Button save = (Button) findViewById(R.id.save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveSettings(settings);
                Toast.makeText(UserPreferencesActivity.this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private String draft(Bundle state, String key, String fallback) {
        String value = state == null ? null : state.getString(key);
        return value == null ? fallback : value;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("verbosity", selectedValue(outputVerbosity, OUTPUT_VERBOSITY_VALUES));
        state.putString("summary", selectedValue(reasoningSummary, REASONING_SUMMARY_VALUES));
        state.putString("language", selectedValue(outputLanguage, OUTPUT_LANGUAGE_VALUES));
        state.putString("concurrency", selectedValue(agentConcurrency, AGENT_CONCURRENCY_VALUES));
        state.putString("effort", selectedValue(reasoningEffort, REASONING_EFFORT_VALUES));
        state.putString("prompt", systemPrompt.getText().toString());
        super.onSaveInstanceState(state);
    }

    private void saveSettings(Settings settings) {
        settings.saveUserPreferences(selectedValue(outputVerbosity, OUTPUT_VERBOSITY_VALUES),
                selectedValue(reasoningSummary, REASONING_SUMMARY_VALUES),
                selectedValue(outputLanguage, OUTPUT_LANGUAGE_VALUES),
                selectedValue(reasoningEffort, REASONING_EFFORT_VALUES),
                Integer.parseInt(selectedValue(agentConcurrency, AGENT_CONCURRENCY_VALUES)),
                systemPrompt.getText().toString());
    }

    private void onChoiceChanged(Spinner spinner) {
        if (spinner == agentConcurrency || spinner == reasoningEffort) refreshAgentPreview(new Settings(this));
    }

    private void refreshAgentPreview(Settings settings) {
        String effort = selectedValue(reasoningEffort, REASONING_EFFORT_VALUES);
        String mode = Settings.EFFORT_ULTRA.equals(effort) ? Settings.AGENT_ULTRA : Settings.AGENT_MANUAL;
        int concurrency = Integer.parseInt(selectedValue(agentConcurrency, AGENT_CONCURRENCY_VALUES));
        if (agentStatus != null) agentStatus.setText(getString(Settings.AGENT_ULTRA.equals(mode)
                ? R.string.agent_status_ultra : R.string.agent_status_normal, Integer.valueOf(concurrency), effort));
        if (envContext != null) envContext.setText(settings.environmentContext(settings.useRoot(), mode, concurrency));
    }

    private void bindChoices(final Spinner spinner, int labels, int descriptions,
                             int descriptionView, String[] values, String current) {
        final ChoiceAdapter adapter = new ChoiceAdapter(spinner,
                getResources().getStringArray(labels), getResources().getStringArray(descriptions));
        final TextView description = (TextView) findViewById(descriptionView);
        spinner.setAdapter(adapter);
        int selection = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) { selection = i; break; }
        spinner.setSelection(selection);
        description.setText(adapter.descriptionAt(selection));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                description.setText(adapter.descriptionAt(position));
                adapter.notifyDataSetChanged();
                onChoiceChanged(spinner);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { description.setText(adapter.descriptionAt(0)); }
        });
    }

    private final class ChoiceAdapter extends ArrayAdapter<String> {
        private final Spinner owner;
        private final String[] descriptions;
        ChoiceAdapter(Spinner owner, String[] labels, String[] descriptions) {
            super(UserPreferencesActivity.this, android.R.layout.simple_spinner_item, labels);
            this.owner = owner;
            this.descriptions = descriptions;
        }
        String descriptionAt(int position) { return position >= 0 && position < descriptions.length ? descriptions[position] : ""; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView title = (TextView) super.getView(position, convertView, parent);
            title.setTextColor(getResources().getColor(R.color.text_primary));
            title.setTextSize(14);
            title.setSingleLine(false);
            return title;
        }
        @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            ChoiceRow fields;
            if (row == null || !(row.getTag() instanceof ChoiceRow)) {
                row = getLayoutInflater().inflate(R.layout.settings_choice_item, parent, false);
                fields = new ChoiceRow(row);
                row.setTag(fields);
            } else fields = (ChoiceRow) row.getTag();
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
            check.setImageDrawable(Icons.tinted(UserPreferencesActivity.this,
                    R.drawable.ic_ds_checkmark_lg_regular_24, getResources().getColor(R.color.text_primary), size));
        }
    }

    private String selectedValue(Spinner spinner, String[] values) {
        int index = spinner.getSelectedItemPosition();
        return index >= 0 && index < values.length ? values[index] : values[0];
    }
}
