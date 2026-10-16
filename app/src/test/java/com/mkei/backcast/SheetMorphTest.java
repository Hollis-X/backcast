package com.mkei.backcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.ui.WorkTimeline;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** 过程列表 → 某条工具调用 → 返回：面板一直开着，内容切换，返回键回到列表。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application.class)
public class SheetMorphTest {
    private static void settle() {
        for (int i = 0; i < 8; i++) ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
    }

    private static View findText(View view, String prefix) {
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE
                && ((TextView) view).getText().toString().startsWith(prefix)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findText(((ViewGroup) view).getChildAt(i), prefix);
            if (found != null) return found;
        }
        return null;
    }

    private static WorkTimeline.CommandView command(ViewGroup panel) {
        for (int i = 0; i < panel.getChildCount(); i++)
            if (panel.getChildAt(i) instanceof WorkTimeline.CommandView) return (WorkTimeline.CommandView) panel.getChildAt(i);
        return null;
    }

    @Test
    public void toolDetailSwapsInsideTheOpenSheetAndBackReturnsToTheList() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("backcast", Context.MODE_PRIVATE).edit().putString("active_ai_provider", "custom")
                .putString("ai_profile_custom_url", "https://api.example.com").putString("ai_profile_custom_key", "sk-test")
                .putString("ai_profile_custom_model", "m").commit();
        ChatStore store = new ChatStore(app);
        long sid = store.create("面板切换");
        store.append(sid, Message.user("写一个文件"));
        Message call = Message.assistant("", new JSONArray().put(new JSONObject().put("id", "c1").put("type", "function")
                .put("function", new JSONObject().put("name", "write").put("arguments", "{\"path\":\"a.txt\",\"content\":\"x\"}"))));
        store.append(sid, call);
        store.append(sid, Message.toolResult("c1", "已写入 a.txt\n变更：+1 -0"));
        Message done = Message.assistant("好了。", null);
        done.elapsedMs = 5000;
        store.append(sid, done);
        store.close();

        Activity main = Robolectric.buildActivity(MainActivity.class, new Intent(app, MainActivity.class)).setup().get();
        settle();
        ((View) findText(main.findViewById(R.id.stream), "工作了").getParent()).performClick();
        settle();
        ViewGroup panel = (ViewGroup) main.findViewById(R.id.sheet_panel);
        assertEquals(View.VISIBLE, main.findViewById(R.id.sheet_overlay).getVisibility());
        ((View) findText(main.findViewById(R.id.sheet_body), "写入文件").getParent()).performClick();
        settle();
        assertNotNull("tool detail did not open inside the sheet", command(panel));
        assertEquals(View.GONE, main.findViewById(R.id.sheet_scroll).getVisibility());
        assertEquals(View.VISIBLE, main.findViewById(R.id.sheet_overlay).getVisibility());
        main.onBackPressed();
        settle();
        assertNull("back did not leave the tool detail", command(panel));
        assertEquals(View.VISIBLE, main.findViewById(R.id.sheet_scroll).getVisibility());
        assertEquals("back closed the sheet instead of returning to the list",
                View.VISIBLE, main.findViewById(R.id.sheet_overlay).getVisibility());
        main.onBackPressed();
        settle();
        assertEquals(View.GONE, main.findViewById(R.id.sheet_overlay).getVisibility());
    }
}
