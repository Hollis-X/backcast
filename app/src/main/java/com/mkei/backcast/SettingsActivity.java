package com.mkei.backcast;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.mkei.backcast.ui.Icons;
import com.mkei.backcast.mcp.McpServer;
import com.mkei.backcast.mcp.McpStore;
import java.io.File;

/** Settings navigation; each editor saves only its own configuration. */
public final class SettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setContentView(R.layout.activity_settings);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationIcon(Icons.tinted(this, Icons.BACK, 0xFF0D0D0D, dp(22)));
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        entry(R.id.settings_ai_row, R.id.settings_ai_icon, R.id.settings_ai_arrow, Icons.CHAT, AiConfigActivity.class);
        entry(R.id.settings_preferences_row, R.id.settings_preferences_icon, R.id.settings_preferences_arrow,
                Icons.SETTINGS, UserPreferencesActivity.class);
        entry(R.id.settings_tools_row, R.id.settings_tools_icon, R.id.settings_tools_arrow, Icons.TERMINAL, ToolConfigActivity.class);
        entry(R.id.settings_mcp_row, R.id.settings_mcp_icon, R.id.settings_mcp_arrow, Icons.TERMINAL, McpConfigActivity.class);
    }

    @Override protected void onResume() {
        super.onResume();
        Settings settings = new Settings(this);
        Settings.AiProfile active = settings.activeAiProfile();
        ((TextView) findViewById(R.id.settings_ai_summary)).setText(
                active.model.trim().length() == 0 ? getString(R.string.status_no_model) : active.model);
        String[] languageLabels = getResources().getStringArray(R.array.output_language_labels);
        String[] languages = { "zh-CN", "zh-TW", "en", "ja", "ko", "es", "fr", "de" };
        int language = 0;
        for (int i = 0; i < languages.length; i++) if (languages[i].equals(settings.outputLanguage())) language = i;
        ((TextView) findViewById(R.id.settings_preferences_summary)).setText(
                getString(R.string.settings_preferences_summary, languageLabels[language], settings.reasoningEffort()));
        int access = Settings.ACCESS_FULL.equals(settings.accessLevel()) ? R.string.access_full
                : Settings.ACCESS_GUARDED.equals(settings.accessLevel()) ? R.string.access_guarded : R.string.access_strict;
        ((TextView) findViewById(R.id.settings_tools_summary)).setText(
                getString(R.string.settings_tools_summary, getString(access),
                        getString(settings.useRoot() ? R.string.settings_root_on : R.string.settings_root_off)));
        int enabled = 0;
        try {
            for (McpServer server : new McpStore(new File(getFilesDir(), "mcp")).servers()) if (server.enabled) enabled++;
        } catch (IllegalStateException storage) {
            ((TextView) findViewById(R.id.settings_mcp_summary)).setText(R.string.settings_mcp_storage_error);
            return;
        }
        ((TextView) findViewById(R.id.settings_mcp_summary)).setText(getString(R.string.settings_mcp_summary, enabled));
    }

    private void entry(int rowId, int iconId, int arrowId, int icon, final Class<?> destination) {
        ((ImageView) findViewById(iconId)).setImageDrawable(Icons.tinted(this, icon, 0xFF3C3C43, dp(24)));
        ((ImageView) findViewById(arrowId)).setImageDrawable(Icons.tinted(this, Icons.CHEVRON_RIGHT, 0xFF8E8E93, dp(18)));
        findViewById(rowId).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startActivity(new Intent(SettingsActivity.this, destination)); }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
