package com.mkei.backcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/** 窄屏加长模型名：只压窄模型芯片，权限芯片和发送键保持完整尺寸，互不重叠。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w360dp-h780dp-xxhdpi", application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class InputBarLayoutTest {
    private static int[] at(View view) {
        int[] xy = new int[2];
        view.getLocationInWindow(xy);
        return xy;
    }

    @Test
    public void longModelNameDoesNotSqueezeTheAccessChipOrTheSendButton() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("backcast", Context.MODE_PRIVATE).edit().putString("active_ai_provider", "custom")
                .putString("ai_profile_custom_url", "https://api.example.com").putString("ai_profile_custom_key", "sk-test")
                .putString("ai_profile_custom_model", "deepseek-v4.1-flash-preview-extended-context")
                .putString("reasoning_effort", "ultra").commit();
        Activity main = Robolectric.buildActivity(MainActivity.class, new Intent(app, MainActivity.class)).setup().get();
        for (int i = 0; i < 6; i++) ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        float density = main.getResources().getDisplayMetrics().density;
        View bar = main.findViewById(R.id.input_bar), send = main.findViewById(R.id.send);
        View model = main.findViewById(R.id.model_chip);
        TextView access = (TextView) main.findViewById(R.id.access_chip);

        assertEquals("send button lost its size", Math.round(40 * density), send.getWidth());
        assertEquals(Math.round(40 * density), send.getHeight());
        assertTrue("send button sticks out of the input bar", at(send)[0] + send.getWidth() <= at(bar)[0] + bar.getWidth());
        int natural = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        access.measure(natural, View.MeasureSpec.makeMeasureSpec(access.getHeight(), View.MeasureSpec.EXACTLY));
        int wanted = access.getMeasuredWidth();
        access.requestLayout();
        for (int i = 0; i < 2; i++) ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        assertTrue("access chip was squeezed: " + access.getWidth() + " < " + wanted, access.getWidth() >= wanted);
        assertTrue("model chip overlaps the access chip", at(model)[0] + model.getWidth() <= at(access)[0]);
        assertTrue("access chip overlaps the send button", at(access)[0] + access.getWidth() <= at(send)[0]);

        String dir = System.getenv("BACKCAST_SCREENSHOTS");
        if (dir != null && dir.length() > 0) {
            View decor = main.getWindow().getDecorView();
            Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            decor.draw(canvas);
            new File(dir).mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(dir, "08-input-narrow.png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        }
    }
}
