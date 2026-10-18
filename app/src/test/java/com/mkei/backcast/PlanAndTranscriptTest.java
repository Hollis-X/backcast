package com.mkei.backcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.SubAgentManager;
import com.mkei.backcast.ui.PlanCard;
import com.mkei.backcast.ui.RollingDiff;
import com.mkei.backcast.ui.TurnTrace;
import com.mkei.backcast.ui.WorkTimeline;
import java.util.ArrayList;
import java.util.List;
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

/** 计划版本与历史、耗时只在顶部、当前任务的改动行数、长思考树分帧、子代理长历史页面。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application.class)
public class PlanAndTranscriptTest {
    /** RunHub 是进程级单例，按会话 id 缓存循环；同一个 JVM 里前一个用例的会话 1 会串到下一个用例，先清掉。 */
    @org.junit.Before
    public void freshHub() throws Exception {
        java.lang.reflect.Field instance = RunHub.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static void settle(int rounds) {
        for (int i = 0; i < rounds; i++) ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
    }

    private static <T extends View> List<T> find(View view, Class<T> type, List<T> out) {
        if (type.isInstance(view)) out.add(type.cast(view));
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            find(((ViewGroup) view).getChildAt(i), type, out);
        return out;
    }

    private static List<String> texts(View view) {
        List<String> out = new ArrayList<String>();
        for (TextView text : find(view, TextView.class, new ArrayList<TextView>()))
            if (text.isShown()) out.add(String.valueOf(text.getText()));
        return out;
    }

    private static JSONArray call(String id, String name, String args) throws Exception {
        return new JSONArray().put(new JSONObject().put("id", id).put("type", "function")
                .put("function", new JSONObject().put("name", name).put("arguments", args)));
    }

    private static String plan(String... pairs) throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < pairs.length; i += 2) items.put(new JSONObject().put("content", pairs[i]).put("status", pairs[i + 1]));
        return new JSONObject().put("items", items).toString();
    }

    private static Context configure() {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("backcast", Context.MODE_PRIVATE).edit().putString("active_ai_provider", "custom")
                .putString("ai_profile_custom_url", "https://api.example.com").putString("ai_profile_custom_key", "sk-test")
                .putString("ai_profile_custom_model", "m").commit();
        return app;
    }

    /** 历史在后台线程读：既推进主线程消息，也给后台线程真实时间，直到对话画出来。 */
    private static Activity open(Context app) throws Exception {
        Activity main = Robolectric.buildActivity(MainActivity.class, new Intent(app, MainActivity.class)).setup().get();
        for (int i = 0; i < 80; i++) {
            settle(1);
            Thread.sleep(15);
            ViewGroup stream = (ViewGroup) main.findViewById(R.id.stream);
            boolean rendered = false;
            for (View view : find(stream, View.class, new ArrayList<View>()))
                rendered |= "turn".equals(String.valueOf(view.getContentDescription()));
            if (rendered && i > 4) break;
        }
        settle(4);
        return main;
    }

    private static Object invoke(Object target, String name) throws Exception {
        java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    /** 每次 update_plan 一张卡片：旧版本保留当时的状态并标成历史，最新版本单独显示。 */
    @Test
    public void everyPlanUpdateIsANewVersionAndOldOnesStayAsTheyWere() throws Exception {
        Context app = configure();
        ChatStore store = new ChatStore(app);
        long sid = store.create("计划版本");
        store.append(sid, Message.user("分三步做"));
        store.append(sid, Message.assistant("", call("p1", "update_plan", plan("读代码", "in_progress", "改代码", "pending"))));
        store.append(sid, Message.toolResult("p1", "计划已更新"));
        store.append(sid, Message.assistant("", call("p2", "update_plan", plan("读代码", "completed", "改代码", "in_progress"))));
        store.append(sid, Message.toolResult("p2", "计划已更新"));
        Message done = Message.assistant("好了。", null);
        done.elapsedMs = 3000;
        store.append(sid, done);
        store.close();
        Activity main = open(app);
        List<PlanCard> cards = find(main.findViewById(R.id.stream), PlanCard.class, new ArrayList<PlanCard>());
        assertEquals("one card per plan version", 2, cards.size());
        assertTrue("older version is history", cards.get(0).isHistory());
        assertFalse("newest version is current", cards.get(1).isHistory());
        assertTrue(texts(cards.get(0)).toString(), texts(cards.get(0)).contains("第 1 版 · 历史") && texts(cards.get(0)).contains("0/2"));
        assertTrue(texts(cards.get(1)).toString(), texts(cards.get(1)).contains("第 2 版 · 最新") && texts(cards.get(1)).contains("1/2"));
        assertFalse("history is folded to one line", texts(cards.get(0)).contains("读代码"));
        ((ViewGroup) cards.get(0)).getChildAt(0).performClick();
        assertTrue("history opens to show its own steps", texts(cards.get(0)).contains("读代码"));
        assertTrue("history keeps its old count", texts(cards.get(0)).contains("0/2"));
    }

    /** 工具拒绝的计划（两步同时进行中）不画卡片，不冒充最新版。 */
    @Test
    public void rejectedPlanIsNotDrawn() throws Exception {
        Context app = configure();
        ChatStore store = new ChatStore(app);
        long sid = store.create("被拒绝的计划");
        store.append(sid, Message.user("分两步做"));
        store.append(sid, Message.assistant("", call("p1", "update_plan", plan("读代码", "in_progress", "改代码", "pending"))));
        store.append(sid, Message.toolResult("p1", "计划已更新"));
        store.append(sid, Message.assistant("", call("p2", "update_plan", plan("读代码", "in_progress", "改代码", "in_progress"))));
        store.append(sid, Message.toolResult("p2", "错误：同一时间只能有一个 in_progress。"));
        Message done = Message.assistant("好了。", null);
        done.elapsedMs = 3000;
        store.append(sid, done);
        store.close();
        Activity main = open(app);
        List<PlanCard> cards = find(main.findViewById(R.id.stream), PlanCard.class, new ArrayList<PlanCard>());
        assertEquals("rejected plan must not get a card", 1, cards.size());
        assertFalse("the accepted plan stays the latest", cards.get(0).isHistory());
    }

    /** 上一轮停在网络错误、还没答完：输入框为空时发送键是「继续任务」三角，一输入文字就变回发送。 */
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void interruptedTurnTurnsSendIntoResume() throws Exception { resumeCase("24-resume-button.png"); }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h914dp-night-xxhdpi")
    public void interruptedTurnTurnsSendIntoResumeDark() throws Exception { resumeCase("24-resume-button-dark.png"); }

    private void resumeCase(String shot) throws Exception {
        Context app = configure();
        ChatStore store = new ChatStore(app);
        long sid = store.create("断网");
        store.append(sid, Message.user("帮我检查一下 demo 目录"));
        store.close();
        Activity main = open(app);
        java.lang.reflect.Field loopField = MainActivity.class.getDeclaredField("loop");
        loopField.setAccessible(true);
        com.mkei.backcast.agent.AgentLoop loop = (com.mkei.backcast.agent.AgentLoop) loopField.get(main);
        assertNotNull("session loop", loop);
        java.lang.reflect.Field flag = com.mkei.backcast.agent.AgentLoop.class.getDeclaredField("interruptedByRequestFailure");
        flag.setAccessible(true);
        flag.setBoolean(loop, true);
        java.lang.reflect.Method tint = MainActivity.class.getDeclaredMethod("tintSend", boolean.class);
        tint.setAccessible(true);
        tint.invoke(main, false);
        settle(2);
        View send = main.findViewById(R.id.send);
        assertEquals("继续任务", String.valueOf(send.getContentDescription()));
        String dir = System.getenv("BACKCAST_SCREENSHOTS");
        if (dir != null && dir.length() > 0) {
            View decor = main.getWindow().getDecorView();
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(decor.getWidth(), decor.getHeight(),
                    android.graphics.Bitmap.Config.ARGB_8888);
            decor.draw(new android.graphics.Canvas(bitmap));
            new java.io.File(dir).mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, shot))) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
        }
        ((android.widget.EditText) main.findViewById(R.id.prompt)).setText("换个问题");
        settle(2);
        assertEquals("发送", String.valueOf(send.getContentDescription()));
        flag.setBoolean(loop, false);
    }

    /** 同一任务里续跑出来的新工作段不再画「工作了」，总耗时只在最上面那一行。 */
    @Test
    public void elapsedTimeIsShownOnlyAtTheTopOfTheTask() throws Exception {
        Context app = configure();
        ChatStore store = new ChatStore(app);
        long sid = store.create("续跑");
        store.append(sid, Message.user("一直做到完成"));
        Message first = Message.assistant("先看一下。", call("s1", "shell", "{\"command\":\"ls\"}"));
        first.elapsedMs = 5000;
        store.append(sid, first);
        store.append(sid, Message.toolResult("s1", "a.txt"));
        store.close();
        Activity main = open(app);
        invoke(main, "beginWorkRow");
        settle(2);
        int headers = 0;
        for (String text : texts(main.findViewById(R.id.stream))) if (text.startsWith("工作了")) headers++;
        assertEquals("continuation turn repeated the elapsed header", 1, headers);
        int boxes = 0;
        for (View view : find(main.findViewById(R.id.stream), View.class, new ArrayList<View>()))
            if ("turn".equals(String.valueOf(view.getContentDescription()))) boxes++;
        assertEquals("continuation box was not created", 2, boxes);
    }

    /** 目标栏的改动行数只算最后一条用户消息之后的这次任务。 */
    @Test
    public void goalDiffCountsOnlyTheCurrentTask() throws Exception {
        Context app = configure();
        ChatStore store = new ChatStore(app);
        long sid = store.create("改动");
        store.append(sid, Message.user("第一件事"));
        store.append(sid, Message.assistant("", call("e1", "edit", "{\"path\":\"a\"}")));
        store.append(sid, Message.toolResult("e1", "已修改\n变更：+5 -2"));
        store.append(sid, Message.assistant("好了", null));
        store.append(sid, Message.user("第二件事"));
        store.append(sid, Message.assistant("", call("w1", "write", "{\"path\":\"b\"}")));
        store.append(sid, Message.toolResult("w1", "已写入\n变更：+3 -0"));
        store.append(sid, Message.assistant("", call("e2", "edit", "{\"path\":\"b\"}")));
        store.append(sid, Message.toolResult("e2", "已修改\n变更：+1 -1"));
        store.close();
        Activity main = open(app);
        int[] diff = (int[]) invoke(main, "taskDiff");
        assertEquals(4, diff[0]);
        assertEquals(1, diff[1]);
    }

    /** 思考和工具很多时，展开面板分帧建行；每条结果的增删行数只解析一次。 */
    @Test
    public void longThinkingTreeBuildsRowsInBatches() throws Exception {
        Context context = new android.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        TurnTrace trace = new TurnTrace();
        trace.rawReasoning = true;
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 80; i++) longText.append("很长的推理内容。");
        for (int i = 0; i < 150; i++) {
            trace.appendThink("第 " + i + " 段思考\n" + longText);
            trace.sealThink();
            trace.addStep("c" + i, "edit", "{\"path\":\"f" + i + "\"}");
            trace.fillResult("c" + i, "", "已修改\n变更：+2 -1");
        }
        TurnTrace.Step step = trace.steps.get(0);
        assertTrue("delta is parsed once and cached", step.delta() == step.delta());
        int[] total = trace.lineDelta(0, trace.order.size());
        assertEquals(300, total[0]);
        assertEquals(150, total[1]);
        WorkTimeline view = new WorkTimeline(context, new WorkTimeline.Actions() {
            @Override public void summarize(TurnTrace.Piece piece, boolean retry) { }
            @Override public void command(TurnTrace.Step step) { }
        });
        TurnTrace.Range range = new TurnTrace.Range(trace, 0);
        range.end = trace.order.size();
        view.bind(range, false);
        assertTrue("first frame builds only a batch: " + view.getChildCount(), view.getChildCount() < trace.order.size());
        for (int i = 0; i < 40 && view.getChildCount() < trace.order.size(); i++)
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS);
        assertEquals("remaining rows arrive over the next frames", trace.order.size(), view.getChildCount());
    }

    /** 改动行数变化时逐位翻动，宽度按较长的数预留。 */
    @Test
    public void rollingDiffShowsTheNewNumbers() throws Exception {
        Context context = new android.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        RollingDiff diff = new RollingDiff(context);
        diff.set(9, 1, false);
        assertEquals("+9 -1", String.valueOf(diff.getContentDescription()));
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        diff.measure(spec, spec);
        int narrow = diff.getMeasuredWidth();
        diff.set(12, 1, true);
        diff.measure(spec, spec);
        assertTrue("width grows for the extra digit", diff.getMeasuredWidth() > narrow);
        assertEquals(12, diff.added());
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(Math.max(1, diff.getMeasuredWidth()),
                Math.max(1, diff.getMeasuredHeight()), android.graphics.Bitmap.Config.ARGB_8888);
        diff.layout(0, 0, diff.getMeasuredWidth(), diff.getMeasuredHeight());
        diff.draw(new android.graphics.Canvas(bitmap));
    }

    /** 子代理跑了很久、历史很长时，详情页也能画出来，不会被每秒的刷新反复打断。 */
    @Test
    public void childPageWithLongHistoryFinishesRendering() throws Exception {
        Context app = configure();
        ChatStore store = new ChatStore(app);
        long sid = store.create("长任务");
        store.append(sid, Message.user("派一个子代理"));
        SubAgentManager.Record record = new SubAgentManager.Record();
        record.id = "long"; record.parentId = SubAgentManager.ROOT; record.name = "assets"; record.path = "/main/assets";
        record.task = "核对资源"; record.status = SubAgentManager.RUNNING; record.phase = "tool";
        record.lastActivityAt = System.currentTimeMillis();
        JSONArray history = new JSONArray();
        history.put(Message.system("system").toCheckpointJson());
        history.put(Message.delegated("逐个核对 assets 目录下的资源文件", "").toCheckpointJson());
        for (int i = 0; i < 400; i++) {
            Message think = Message.assistant("", call("t" + i, "shell", "{\"command\":\"ls " + i + "\"}"));
            think.reasoning = "检查第 " + i + " 个文件";
            history.put(think.toCheckpointJson());
            history.put(Message.toolResult("t" + i, "file-" + i).toCheckpointJson());
        }
        history.put(Message.assistant("核对完成：400 个文件都在。", null).toCheckpointJson());
        record.history = history;
        childStore(sid).save(record);
        store.close();
        Intent chat = new Intent(app, SubAgentChatActivity.class)
                .putExtra(MainActivity.EXTRA_AGENT_ROOT, sid).putExtra(MainActivity.EXTRA_AGENT_ID, "long");
        Activity page = Robolectric.buildActivity(SubAgentChatActivity.class, chat).setup().get();
        boolean shown = false;
        for (int i = 0; i < 60 && !shown; i++) {
            settle(1);
            Thread.sleep(20);
            shown = texts(page.findViewById(R.id.stream)).contains("核对完成：400 个文件都在。");
        }
        List<String> seen = texts(page.findViewById(R.id.stream));
        assertTrue("long child history never finished rendering: title="
                + ((TextView) page.findViewById(R.id.session_title)).getText() + " shown="
                + seen.subList(Math.max(0, seen.size() - 6), seen.size()) + " count=" + seen.size(), shown);
        assertNotNull(page.findViewById(R.id.stream));
    }

    /** 和 RunHub 用同一个目录：子代理记录直接写成 JSON 文件，打开页面时由 RunHub 读回来。 */
    private static JsonSubAgentStore childStore(long sid) {
        return new JsonSubAgentStore(new java.io.File(RuntimeEnvironment.getApplication().getFilesDir(), "sub-agents"), sid, null);
    }
}
