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
import android.widget.ProgressBar;
import android.widget.LinearLayout;
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
    public void planCallIsInTheTimelineAndEditsShowLineCounts() throws Exception {
        Context context = new android.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        TurnTrace trace = new TurnTrace();
        trace.addStep("p1", TurnTrace.PLAN, plan("读代码", "completed", "改代码", "in_progress"));
        trace.addStep("e1", "edit", "{\"path\":\"a.txt\"}");
        for (TurnTrace.Step step : trace.steps) { step.started = true; step.done = true; }
        trace.steps.get(1).result = "已修改 1 处：a.txt\n变更：+5 -2";
        WorkTimeline view = timeline(context, trace);
        layout(view);
        assertEquals("plan call and edit both appear in the timeline", 2, view.getChildCount());
        List<String> shown = texts(view);
        assertTrue(shown.toString(), shown.contains("更新任务计划") && shown.contains("已完成 1/2"));
        boolean chips = false;
        for (String text : shown) chips |= text.contains("+5") && text.contains("-2");
        assertTrue("edit row lacks the line counts: " + shown, chips);
    }

    /** 新的用户消息开始新任务：计划版本从第 1 版重新数，上一个任务的卡片不算进来。 */
    @Test
    public void planVersionsRestartAfterEachUserMessage() throws Exception {
        Context context = new android.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        com.mkei.backcast.ui.M3.load(context);
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        PlanCard first = new PlanCard(context), second = new PlanCard(context), next = new PlanCard(context);
        first.bind(plan("读代码", "in_progress"), false);
        second.bind(plan("读代码", "completed"), false);
        next.bind(plan("改知识库", "in_progress"), false);
        page.addView(first); page.addView(second);
        LinearLayout user = new LinearLayout(context);
        user.setContentDescription("user");
        page.addView(user);
        LinearLayout block = new LinearLayout(context);
        block.addView(next);
        page.addView(block);
        PlanCard.renumber(page);
        assertTrue("older version of the first task is not history", first.isHistory());
        assertFalse("last version of the first task became history", second.isHistory());
        assertFalse("the new task's only plan was numbered after the old task", next.isHistory());
        List<String> shown = texts(next);
        for (String text : shown) assertFalse("new task plan shows a version label: " + shown, text.contains("第 2 版") || text.contains("第 3 版"));
    }

    @Test
    public void cardShowsDoneSpinnerAndDashedPendingThenSettles() throws Exception {
        Context context = new android.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        PlanCard card = new PlanCard(context);
        card.bind(plan("读代码", "completed", "改代码", "in_progress", "跑测试", "pending"), true);
        layout(card);
        assertTrue(texts(card).contains("任务计划") && texts(card).contains("1/3"));
        assertEquals("one spinner for the running step", 1, find(card, com.google.android.material.progressindicator.CircularProgressIndicator.class, new ArrayList<com.google.android.material.progressindicator.CircularProgressIndicator>()).size());
        assertEquals("progress bar shows done/total", 33, find(card, com.google.android.material.progressindicator.LinearProgressIndicator.class, new ArrayList<com.google.android.material.progressindicator.LinearProgressIndicator>()).get(0).getProgress());
        assertEquals("done and pending use icons", 2, find(card, ImageView.class, new ArrayList<ImageView>()).size());
        card.settle();
        assertEquals("the spinner stops when the turn ends", 0, find(card, com.google.android.material.progressindicator.CircularProgressIndicator.class, new ArrayList<com.google.android.material.progressindicator.CircularProgressIndicator>()).size());
        card.bind("{\"items\":[{\"content\":\"半截", true);
        assertTrue("half-streamed arguments keep the last good plan", texts(card).contains("1/3"));
    }

    @Test
    public void rendersForReview() throws Exception { render(""); }

    @Test
    @Config(qualifiers = "w411dp-h914dp-night-xxhdpi")
    public void rendersForReviewDark() throws Exception { render("-dark"); }

    private void render(String suffix) throws Exception {
        String dir = System.getenv("BACKCAST_SCREENSHOTS");
        if (dir == null || dir.length() == 0) return;
        Context context = new android.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        com.mkei.backcast.ui.M3.load(context);
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        page.setPadding(pad, pad, pad, pad);
        page.setBackgroundColor(com.mkei.backcast.ui.M3.surface);
        // 目标栏里的改动行数：一个落位，一个正在翻（个位从 7 翻到 8）。
        com.mkei.backcast.ui.RollingDiff still = new com.mkei.backcast.ui.RollingDiff(context);
        still.setColors(com.mkei.backcast.ui.M3.success, com.mkei.backcast.ui.M3.error);
        still.set(31, 0, false);
        page.addView(still);
        // 两个计划版本：第 1 版成了历史（收起），第 2 版是最新。
        PlanCard first = new PlanCard(context);
        first.bind(plan("梳理现有的子代理逻辑", "in_progress", "把等待改成完成通知", "pending",
                "补齐发消息唤醒空闲代理", "pending", "更新测试", "pending", "构建并回归", "pending"), true);
        page.addView(first, PlanCard.layout(context));
        TextView caption = new TextView(context);
        caption.setTextSize(14); caption.setTextColor(0xFF6E6E76);
        caption.setText(WorkTimeline.withDelta("思考 2 段 · 工具 4 次", new int[] { 12, 0 }));
        page.addView(caption);
        PlanCard card = new PlanCard(context);
        card.bind(plan("梳理现有的子代理逻辑", "completed", "把等待改成完成通知", "completed",
                "补齐发消息唤醒空闲代理", "in_progress", "更新测试", "pending", "构建并回归", "pending"), true);
        page.addView(card, PlanCard.layout(context));
        PlanCard.renumber(page);
        TurnTrace trace = new TurnTrace();
        trace.addStep("e", "edit", "{\"path\":\"SubAgentManager.java\"}");
        trace.addStep("w", "write", "{\"path\":\"PlanCard.java\"}");
        for (TurnTrace.Step step : trace.steps) { step.started = true; step.done = true; }
        trace.steps.get(0).result = "已修改 3 处：SubAgentManager.java\n变更：+18 -7";
        trace.steps.get(1).result = "已写入 PlanCard.java\n变更：+120 -0";
        page.addView(timeline(context, trace));
        layout(page);
        Bitmap bitmap = Bitmap.createBitmap(page.getMeasuredWidth(), Math.max(1, page.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(com.mkei.backcast.ui.M3.surface);
        page.draw(canvas);
        File out = new File(dir);
        out.mkdirs();
        try (FileOutputStream stream = new FileOutputStream(new File(out, "17-plan-card" + suffix + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }
}
