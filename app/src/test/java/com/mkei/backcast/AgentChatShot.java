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

    @Test
    public void agents() throws Exception {
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
        Message fin = Message.assistant("检查完毕：todo.txt 有 3 条，show.sh 输出正确，第 2 条已标记「已完成」。", null);
        history.put(fin.toCheckpointJson());
        checker.history = history;
        store.saveSubAgentRecord("session-" + sid, checker.toJson());
        store.saveSubAgentRecord("session-" + sid, agent("a2", "script_reviewer", SubAgentManager.IDLE).toJson());
        store.saveSubAgentRecord("session-" + sid, agent("a3", "doc_writer", SubAgentManager.CLOSED).toJson());
        store.close();

        Intent list = new Intent(app, SubAgentsActivity.class).putExtra(SubAgentsActivity.EXTRA_SESSION_ID, sid);
        shoot(Robolectric.buildActivity(SubAgentsActivity.class, list).setup().get(), dir, "21-agent-list.png");
        Intent chat = new Intent(app, SubAgentChatActivity.class)
                .putExtra(MainActivity.EXTRA_AGENT_ROOT, sid).putExtra(MainActivity.EXTRA_AGENT_ID, "a1");
        shoot(Robolectric.buildActivity(SubAgentChatActivity.class, chat).setup().get(), dir, "22-agent-chat.png");
    }
}
