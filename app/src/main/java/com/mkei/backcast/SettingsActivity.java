package com.mkei.backcast;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.mkei.backcast.ui.Icons;
import com.mkei.backcast.ui.M3;
import com.mkei.backcast.mcp.McpServer;
import com.mkei.backcast.mcp.McpStore;
import java.io.File;

/** Settings navigation; each editor saves only its own configuration. */
public final class SettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        M3.load(this);
        setContentView(R.layout.activity_settings);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationIcon(Icons.tinted(this, Icons.BACK, M3.onSurface, dp(24)));
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        entry(R.id.settings_ai_row, R.id.settings_ai_icon, R.id.settings_ai_arrow, Icons.CHAT, 0, AiConfigActivity.class);
        entry(R.id.settings_preferences_row, R.id.settings_preferences_icon, R.id.settings_preferences_arrow,
                Icons.SETTINGS, 1, UserPreferencesActivity.class);
        entry(R.id.settings_tools_row, R.id.settings_tools_icon, R.id.settings_tools_arrow, Icons.TERMINAL, 2, ToolConfigActivity.class);
        entry(R.id.settings_mcp_row, R.id.settings_mcp_icon, R.id.settings_mcp_arrow, Icons.FOLDER, 2, McpConfigActivity.class);
        entry(R.id.settings_background_row, R.id.settings_background_icon, R.id.settings_background_arrow,
                Icons.PLAY, 1, null);
        findViewById(R.id.settings_background_row).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { BackgroundAccess.show(SettingsActivity.this, false); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        boolean free = BackgroundAccess.unrestricted(this);
        TextView background = (TextView) findViewById(R.id.settings_background_summary);
        background.setText(free ? R.string.settings_background_on : R.string.settings_background_off);
        background.setTextColor(free ? M3.onSurfaceVariant : M3.warning);
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

    /** 一行入口：M3 色调圆形图标（primary / secondary / tertiary 三种容器色），右侧箭头；首尾行大圆角。 */
    private void entry(int rowId, int iconId, int arrowId, int icon, int tone, final Class<?> destination) {
        int[] containers = { M3.primaryContainer, M3.secondaryContainer, M3.tertiaryContainer };
        int[] inks = { M3.onPrimaryContainer, M3.onSecondaryContainer, M3.onTertiaryContainer };
        ImageView tile = (ImageView) findViewById(iconId);
        tile.setImageDrawable(Icons.tinted(this, icon, inks[tone], dp(20)));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(containers[tone]);
        tile.setBackground(bg);
        ((ImageView) findViewById(arrowId)).setImageDrawable(Icons.tinted(this, Icons.CHEVRON_RIGHT, M3.onSurfaceVariant, dp(20)));
        View row = findViewById(rowId);
        String[] corners = String.valueOf(row.getTag()).split(",");
        float top = dp(Integer.parseInt(corners[0].replace("dp", ""))), bottom = dp(Integer.parseInt(corners[1].replace("dp", "")));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(M3.container);
        shape.setCornerRadii(new float[] { top, top, top, top, bottom, bottom, bottom, bottom });
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadii(shape.getCornerRadii());
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(M3.alpha(M3.onSurface, 0.1f)), shape, mask));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startActivity(new Intent(SettingsActivity.this, destination)); }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
