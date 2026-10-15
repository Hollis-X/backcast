package com.mkei.backcast;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.PopupWindow;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/**
 * Renders every screen to PNG for visual review. Runs only when the
 * BACKCAST_SCREENSHOTS environment variable names an output directory.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenshotTour {
    private File out;

    @Test
    public void tour() throws Exception {
        String dir = System.getenv("BACKCAST_SCREENSHOTS");
        Assume.assumeTrue(dir != null && dir.length() > 0);
        out = new File(dir);
        out.mkdirs();
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = app.getSharedPreferences("backcast", Context.MODE_PRIVATE);
        prefs.edit().putString("active_ai_provider", "custom")
                .putString("ai_profile_custom_url", "https://api.example.com/v1")
                .putString("ai_profile_custom_key", "sk-test")
                .putString("ai_profile_custom_model", "grok-4.6")
                .putString("ai_profile_custom_models", "grok-4.6\ngpt-6-astra")
                .putString("reasoning_effort", "ultra").commit();

        Activity main = start(MainActivity.class, "01-main");
        click(main, R.id.model_chip, "02-main-model-popup", true);
        click(main, R.id.action_menu, "03-main-drawer", false);
        main = start(MainActivity.class, null);
        click(main, R.id.action_more, "04-main-more-sheet", false);
        main = start(MainActivity.class, null);
        click(main, R.id.access_chip, "05-main-access-sheet", false);
        main = start(MainActivity.class, null);
        click(main, R.id.action_plus, "06-main-plus", true);
        start(SettingsActivity.class, "10-settings");
        start(AiConfigActivity.class, "11-ai-config");
        start(UserPreferencesActivity.class, "12-preferences");
        start(ToolConfigActivity.class, "13-tool-config");
        start(McpConfigActivity.class, "14-mcp-config");
        start(SubAgentsActivity.class, "15-sub-agents");
    }

    private Activity start(Class<? extends Activity> type, String name) {
        try {
            Activity activity = Robolectric.buildActivity(type, new Intent(RuntimeEnvironment.getApplication(), type))
                    .setup().get();
            settle();
            if (name != null) save(activity.getWindow().getDecorView(), name);
            return activity;
        } catch (Throwable failure) {
            System.out.println("SCREENSHOT FAILED " + name + ": " + failure);
            return null;
        }
    }

    private void click(Activity activity, int id, String name, boolean popup) {
        try {
            if (activity == null) return;
            activity.findViewById(id).performClick();
            settle();
            PopupWindow window = popup ? org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).getLatestPopupWindow() : null;
            if (window != null && window.isShowing()) {
                View decor = activity.getWindow().getDecorView();
                Bitmap screen = render(decor, decor.getWidth(), decor.getHeight());
                View content = window.getContentView();
                Bitmap menu = render(content, Math.max(1, content.getWidth()), Math.max(1, content.getHeight()));
                int[] at = new int[2];
                content.getLocationOnScreen(at);
                Canvas canvas = new Canvas(screen);
                canvas.drawColor(0x33000000);
                canvas.drawBitmap(menu, at[0], at[1], null);
                write(screen, name);
            } else {
                save(activity.getWindow().getDecorView(), name);
            }
        } catch (Throwable failure) {
            System.out.println("SCREENSHOT FAILED " + name + ": " + failure);
        }
    }

    private static void settle() {
        for (int i = 0; i < 5; i++) ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void save(View view, String name) throws Exception {
        write(render(view, view.getWidth(), view.getHeight()), name);
    }

    private static Bitmap render(View view, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        view.draw(canvas);
        return bitmap;
    }

    private void write(Bitmap bitmap, String name) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(out, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        System.out.println("SCREENSHOT " + name);
    }
}
