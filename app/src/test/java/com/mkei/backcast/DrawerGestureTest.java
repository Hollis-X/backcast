package com.mkei.backcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import com.mkei.backcast.agent.Message;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** 侧边栏手势：横滑拉出、推回，竖滑滚动会话列表，轻点照常打开会话，三者互不抢。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application.class)
public class DrawerGestureTest {
    private Activity main;
    private View root, overlay;

    private static void settle() {
        for (int i = 0; i < 8; i++) ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
    }

    private void drag(float x0, float y0, float x1, float y1) {
        long start = SystemClock.uptimeMillis(), t = start;
        dispatch(start, t, MotionEvent.ACTION_DOWN, x0, y0);
        int steps = 12;
        for (int i = 1; i <= steps; i++) {
            t += 16;
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
            dispatch(start, t, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps);
        }
        dispatch(start, t + 16, MotionEvent.ACTION_UP, x1, y1);
        settle();
    }

    private void tap(float x, float y) {
        long start = SystemClock.uptimeMillis();
        dispatch(start, start, MotionEvent.ACTION_DOWN, x, y);
        dispatch(start, start + 60, MotionEvent.ACTION_UP, x, y);
        settle();
    }

    private void dispatch(long down, long at, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, at, action, x, y, 0);
        root.dispatchTouchEvent(event);
        event.recycle();
    }

    @Before
    public void open() {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("backcast", Context.MODE_PRIVATE).edit().putString("active_ai_provider", "custom")
                .putString("ai_profile_custom_url", "https://api.example.com").putString("ai_profile_custom_key", "sk-test")
                .putString("ai_profile_custom_model", "m").commit();
        ChatStore store = new ChatStore(app);
        for (int i = 1; i <= 40; i++) store.append(store.create("会话 " + i), Message.user("你好 " + i));
        store.close();
        main = Robolectric.buildActivity(MainActivity.class, new Intent(app, MainActivity.class)).setup().get();
        settle();
        root = main.findViewById(R.id.main_root);
        overlay = main.findViewById(R.id.drawer_overlay);
    }

    private float midY() { return root.getHeight() * 0.45f; }

    @Test
    public void swipeRightOnTheConversationOpensAndSwipeLeftClosesTheDrawer() {
        assertEquals(View.GONE, overlay.getVisibility());
        drag(60, midY(), root.getWidth() * 0.8f, midY() + 20);
        assertEquals("rightward swipe did not open the drawer", View.VISIBLE, overlay.getVisibility());
        assertEquals(0f, main.findViewById(R.id.drawer_panel).getTranslationX(), 0.5f);
        drag(root.getWidth() * 0.6f, midY(), 40, midY() - 20);
        assertEquals("leftward swipe did not close the drawer", View.GONE, overlay.getVisibility());
    }

    @Test
    public void shortSwipeSnapsBackOpen() {
        main.findViewById(R.id.action_menu).performClick();
        settle();
        float x = root.getWidth() * 0.6f;
        long start = SystemClock.uptimeMillis();
        dispatch(start, start, MotionEvent.ACTION_DOWN, x, midY());
        // 慢慢向左挪一小段再松手：不够一半也没有甩动，抽屉弹回打开。
        for (int i = 1; i <= 10; i++) {
            ShadowLooper.idleMainLooper(60, TimeUnit.MILLISECONDS);
            dispatch(start, start + i * 60, MotionEvent.ACTION_MOVE, x - i * 12, midY());
        }
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        dispatch(start, start + 960, MotionEvent.ACTION_UP, x - 120, midY());
        settle();
        assertEquals(View.VISIBLE, overlay.getVisibility());
        assertEquals(0f, main.findViewById(R.id.drawer_panel).getTranslationX(), 0.5f);
    }

    @Test
    public void verticalSwipeScrollsTheSessionListWithoutClosingIt() {
        main.findViewById(R.id.action_menu).performClick();
        settle();
        ScrollView list = (ScrollView) ((ViewGroup) main.findViewById(R.id.drawer_sessions)).getParent();
        assertTrue("fixture should overflow the drawer", list.getChildAt(0).getHeight() > list.getHeight());
        float x = root.getWidth() * 0.4f;
        drag(x, root.getHeight() * 0.75f, x + 10, root.getHeight() * 0.3f);
        assertTrue("session list did not scroll", list.getScrollY() > 0);
        assertEquals("vertical scroll closed the drawer", View.VISIBLE, overlay.getVisibility());
    }

    @Test
    public void verticalSwipeOnTheConversationDoesNotOpenTheDrawer() {
        drag(root.getWidth() * 0.5f, root.getHeight() * 0.7f, root.getWidth() * 0.5f + 30, root.getHeight() * 0.3f);
        assertEquals(View.GONE, overlay.getVisibility());
    }

    @Test
    public void tapOnASessionStillOpensIt() {
        main.findViewById(R.id.action_menu).performClick();
        settle();
        ViewGroup sessions = (ViewGroup) main.findViewById(R.id.drawer_sessions);
        TextView row = (TextView) sessions.getChildAt(3);
        int[] rootAt = new int[2], rowAt = new int[2];
        root.getLocationInWindow(rootAt);
        row.getLocationInWindow(rowAt);
        tap(rowAt[0] - rootAt[0] + row.getWidth() / 2f, rowAt[1] - rootAt[1] + row.getHeight() / 2f);
        assertEquals("tap on a session did not close the drawer", View.GONE, overlay.getVisibility());
        assertEquals(row.getText().toString(), ((TextView) main.findViewById(R.id.session_title)).getText().toString());
    }
}
