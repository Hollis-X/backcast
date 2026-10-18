package com.mkei.backcast;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.SubAgentManager;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/** Renders the sub-agent list and one sub-agent conversation for visual review. Needs BACKCAST_SCREENSHOTS. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AgentChatShot {
    private static JSONArray call(String id, String name, String args) throws Exception {
        return new JSONArray().put(new JSONObject().put("id", id).put("type", "function")
                .put("function", new JSONObject().put("name", name).put("arguments", args)));
    }

    private static String plan(String... pairs) throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < pairs.length; i += 2) items.put(new JSONObject().put("content", pairs[i]).put("status", pairs[i + 1]));
        return new JSONObject().put("items", items).toString();
    }

    private static SubAgentManager.Record agent(String id, String name, String status) {
        SubAgentManager.Record r = new SubAgentManager.Record();
        r.id = id; r.parentId = SubAgentManager.ROOT; r.name = name; r.path = "/main/" + name;
        r.task = "检查 demo 目录"; r.status = status; r.phase = SubAgentManager.IDLE.equals(status) ? "completed" : "thinking";
        r.lastActivityAt = System.currentTimeMillis();
        return r;
    }

    private static void shoot(Activity activity, String dir, String file) throws Exception {
        for (int i = 0; i < 12; i++) ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        View decor = activity.getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        decor.draw(canvas);
        File out = new File(dir);
        out.mkdirs();
        try (FileOutputStream stream = new FileOutputStream(new File(out, file))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }

    private static Message coordination(String id, String from, String path, String text) throws Exception {
        Message note = Message.user(Message.COORDINATION_PREFIX + "同会话协作消息如下。\n" + new JSONObject().put("messages",
                new JSONArray().put(new JSONObject().put("id", id).put("from", from).put("fromPath", path).put("text", text))));
        note.coordinationIds = new JSONArray().put(id);
        return note;
    }

    private static JSONObject event(String id, String kind, String from, String to, String text, long at) throws Exception {
        return new JSONObject().put("id", id).put("kind", kind).put("from", from).put("to", to).put("text", text)
                .put("createdAt", at);
    }

    @Test
    public void agents() throws Exception { render(""); }

    @Test
    @Config(qualifiers = "w411dp-h914dp-night-xxhdpi")
    public void agentsDark() throws Exception { render("-dark"); }

    private void render(String suffix) throws Exception {
        String dir = System.getenv("BACKCAST_SCREENSHOTS");
        Assume.assumeTrue(dir != null && dir.length() > 0);
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = app.getSharedPreferences("backcast", Context.MODE_PRIVATE);
        prefs.edit().putString("active_ai_provider", "custom")
                .putString("ai_profile_custom_url", "https://api.example.com/v1")
                .putString("ai_profile_custom_key", "sk-test")
                .putString("ai_profile_custom_model", "grok-4.6")
                .putString("ai_profile_custom_models", "grok-4.6").commit();
        ChatStore store = new ChatStore(app);
        long sid = store.create("迷你待办清单");
        store.append(sid, Message.user("开两个子代理分别检查 demo 目录和脚本"));
        SubAgentManager.Record checker = agent("a1", "demo_checker", SubAgentManager.FAILED);
        checker.phase = "model"; checker.error = "模型请求失败";
        JSONArray history = new JSONArray();
        history.put(Message.system("system").toCheckpointJson());
        history.put(Message.delegated("检查 demo/todo.txt 是否有 3 条待办，并运行 show.sh 看输出", "").toCheckpointJson());
        Message a1 = Message.assistant("", call("t1", "shell", "{\"command\":\"sh demo/show.sh\"}"));
        a1.reasoning = "先运行脚本看看输出。";
        history.put(a1.toCheckpointJson());
        history.put(Message.toolResult("t1", "1. 写项目文档\n2. 已完成\n3. 备份工作目录").toCheckpointJson());
        // 子代理 → main 的进度汇报、同级发来的消息、发给同级的消息、用户从子代理页发的消息，按发生顺序在对话里。
        history.put(Message.assistant("", call("t2", "report_agent",
                "{\"kind\":\"progress\",\"text\":\"已运行 show.sh，输出 3 行，正在核对第 2 条的格式。\"}")).toCheckpointJson());
        history.put(Message.toolResult("t2", "{\"saved\":true}").toCheckpointJson());
        history.put(coordination("m2", "a2", "/main/script_reviewer",
                "show.sh 第 2 行的「已完成」标记要和 todo.txt 一致，麻烦确认一下。").toCheckpointJson());
        history.put(Message.assistant("", call("t3", "send_message",
                "{\"target\":\"script_reviewer\",\"message\":\"已确认：两边都是「已完成」，格式一致。\"}")).toCheckpointJson());
        history.put(Message.toolResult("t3", "{\"status\":\"idle\"}").toCheckpointJson());
        history.put(coordination("m4", "main", "/main", "也顺便看一下 README 里的用法说明。").toCheckpointJson());
        Message fin = Message.assistant("检查完毕：todo.txt 有 3 条，show.sh 输出正确，第 2 条已标记「已完成」。", null);
        history.put(fin.toCheckpointJson());
        checker.history = history;
        // 三种通信：main ↔ 子代理、子代理 → main、子代理 ↔ 子代理。
        long t = System.currentTimeMillis() - 8 * 60000L;
        checker.tasks.put(new JSONObject().put("taskId", "t1").put("request", "检查 demo").put("status", "failed"));
        checker.events.put(event("e1", "task", "main", "a1", "检查 demo/todo.txt 是否有 3 条待办，并运行 show.sh 看输出", t).put("taskId", "t1"));
        checker.events.put(event("e2", "progress", "a1", "main", "已运行 show.sh，输出 3 行，正在核对第 2 条的格式。", t + 60000)
                .put("mailId", "m1").put("status", "read"));
        checker.events.put(event("e3", "message", "a2", "a1", "show.sh 第 2 行的「已完成」标记要和 todo.txt 一致，麻烦确认一下。", t + 120000)
                .put("mailId", "m2").put("status", "read"));
        checker.events.put(event("e4", "message", "a1", "a2", "已确认：两边都是「已完成」，格式一致。", t + 180000)
                .put("mailId", "m3").put("status", "pending"));
        checker.events.put(event("e5", "message", "main", "a1", "也顺便看一下 README 里的用法说明。", t + 240000)
                .put("mailId", "m4").put("status", "pending").put("fromUser", true));
        checker.events.put(event("e6", "error", "a1", "main", "模型请求失败", t + 300000).put("taskId", "t1").put("resultId", "r1"));
        childStore(sid).save(checker);
        SubAgentManager.Record reviewer = agent("a2", "script_reviewer", SubAgentManager.IDLE);
        reviewer.task = "审阅 show.sh 的输出格式，发现问题直接找 demo_checker 确认";
        reviewer.events.put(event("r1", "message", "a2", "a1", "show.sh 第 2 行的「已完成」标记要和 todo.txt 一致，麻烦确认一下。", t + 120000)
                .put("mailId", "m2").put("status", "pending"));
        reviewer.events.put(event("r2", "message", "a1", "a2", "已确认：两边都是「已完成」，格式一致。", t + 180000)
                .put("mailId", "m3").put("status", "pending"));
        childStore(sid).save(reviewer);
        childStore(sid).save(agent("a3", "doc_writer", SubAgentManager.CLOSED));
        store.close();

        Intent list = new Intent(app, SubAgentsActivity.class).putExtra(SubAgentsActivity.EXTRA_SESSION_ID, sid);
        shoot(Robolectric.buildActivity(SubAgentsActivity.class, list).setup().get(), dir, "21-agent-list" + suffix + ".png");
        Intent chat = new Intent(app, SubAgentChatActivity.class)
                .putExtra(MainActivity.EXTRA_AGENT_ROOT, sid).putExtra(MainActivity.EXTRA_AGENT_ID, "a1");
        shoot(Robolectric.buildActivity(SubAgentChatActivity.class, chat).setup().get(), dir, "22-agent-chat" + suffix + ".png");
    }

    /** 和 RunHub 用同一个目录：子代理记录直接写成 JSON 文件，打开页面时由 RunHub 读回来。 */
    private static JsonSubAgentStore childStore(long sid) {
        return new JsonSubAgentStore(new java.io.File(RuntimeEnvironment.getApplication().getFilesDir(), "sub-agents"), sid, null);
    }
}
