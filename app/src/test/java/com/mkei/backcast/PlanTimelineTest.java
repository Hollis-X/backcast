package com.mkei.backcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.mkei.backcast.ui.PlanCard;
import com.mkei.backcast.ui.TurnTrace;
import com.mkei.backcast.ui.WorkTimeline;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlanTimelineTest {
    private static String plan(String... pairs) throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < pairs.length; i += 2)
            items.put(new JSONObject().put("content", pairs[i]).put("status", pairs[i + 1]));
        return new JSONObject().put("items", items).toString();
    }

    private static <T extends View> List<T> find(View view, Class<T> type, List<T> out) {
        if (type.isInstance(view)) out.add(type.cast(view));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) find(group.getChildAt(i), type, out);
        }
        return out;
    }

    private static List<String> texts(View view) {
        List<String> out = new ArrayList<String>();
        for (TextView text : find(view, TextView.class, new ArrayList<TextView>())) out.add(String.valueOf(text.getText()));
        return out;
    }

    private static WorkTimeline timeline(Context context, TurnTrace trace) {
        WorkTimeline view = new WorkTimeline(context, new WorkTimeline.Actions() {
            @Override public void summarize(TurnTrace.Piece piece, boolean retry) { }
            @Override public void command(TurnTrace.Step step) { }
        });
        TurnTrace.Range range = new TurnTrace.Range(trace, 0);
        range.end = trace.order.size();
        view.bind(range, false);
        return view;
    }

    private static void layout(View view) {
        int width = Math.round(380 * RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    @Test
    public void planIsNotInTheTimelineAndEditsShowLineCounts() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        TurnTrace trace = new TurnTrace();
        trace.addStep("p1", TurnTrace.PLAN, plan("读代码", "completed", "改代码", "in_progress"));
        trace.addStep("e1", "edit", "{\"path\":\"a.txt\"}");
        for (TurnTrace.Step step : trace.steps) { step.started = true; step.done = true; }
        trace.steps.get(1).result = "已替换 1 处：a.txt\n变更：+5 -2";
        WorkTimeline view = timeline(context, trace);
        layout(view);
        assertEquals("only the edit row is in the timeline", 1, view.getChildCount());
        List<String> shown = texts(view);
        assertFalse(shown.toString(), shown.contains("任务计划"));
        boolean chips = false;
        for (String text : shown) chips |= text.contains("+5") && text.contains("-2");
        assertTrue("edit row lacks the line counts: " + shown, chips);
    }

    @Test
    public void cardShowsDoneSpinnerAndDashedPendingThenSettles() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PlanCard card = new PlanCard(context);
        card.bind(plan("读代码", "completed", "改代码", "in_progress", "跑测试", "pending"), true);
        layout(card);
        assertTrue(texts(card).contains("任务计划 · 1/3"));
        assertEquals("one spinner for the running step", 1, find(card, ProgressBar.class, new ArrayList<ProgressBar>()).size());
        assertEquals("done and pending use icons", 2, find(card, ImageView.class, new ArrayList<ImageView>()).size());
        card.settle();
        assertEquals("the spinner stops when the turn ends", 0, find(card, ProgressBar.class, new ArrayList<ProgressBar>()).size());
        card.bind("{\"items\":[{\"content\":\"半截", true);
        assertTrue("half-streamed arguments keep the last good plan", texts(card).contains("任务计划 · 1/3"));
    }

    @Test
    public void rendersForReview() throws Exception {
        String dir = System.getenv("BACKCAST_SCREENSHOTS");
        if (dir == null || dir.length() == 0) return;
        Context context = RuntimeEnvironment.getApplication();
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        page.setPadding(pad, pad, pad, pad);
        page.setBackgroundColor(Color.WHITE);
        TextView caption = new TextView(context);
        caption.setTextSize(14); caption.setTextColor(0xFF6E6E76);
        caption.setText(WorkTimeline.withDelta("思考 2 段 · 工具 4 次", new int[] { 12, 0 }));
        page.addView(caption);
        PlanCard card = new PlanCard(context);
        card.bind(plan("梳理现有的子代理逻辑", "completed", "把等待改成完成通知", "completed",
                "补齐发消息唤醒空闲代理", "in_progress", "更新测试", "pending", "构建并回归", "pending"), true);
        page.addView(card, PlanCard.layout(context));
        TurnTrace trace = new TurnTrace();
        trace.addStep("e", "edit", "{\"path\":\"SubAgentManager.java\"}");
        trace.addStep("w", "write", "{\"path\":\"PlanCard.java\"}");
        for (TurnTrace.Step step : trace.steps) { step.started = true; step.done = true; }
        trace.steps.get(0).result = "已替换 3 处：SubAgentManager.java\n变更：+18 -7";
        trace.steps.get(1).result = "已写入 PlanCard.java\n变更：+120 -0";
        page.addView(timeline(context, trace));
        layout(page);
        Bitmap bitmap = Bitmap.createBitmap(page.getMeasuredWidth(), Math.max(1, page.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        page.draw(canvas);
        File out = new File(dir);
        out.mkdirs();
        try (FileOutputStream stream = new FileOutputStream(new File(out, "17-plan-card.png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }
}
