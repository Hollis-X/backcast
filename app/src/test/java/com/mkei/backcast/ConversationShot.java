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

/** Renders a seeded conversation (plan card, edits, answer) for visual review. Needs BACKCAST_SCREENSHOTS. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ConversationShot {
    private static JSONArray call(String id, String name, String args) throws Exception {
        return new JSONArray().put(new JSONObject().put("id", id).put("type", "function")
                .put("function", new JSONObject().put("name", name).put("arguments", args)));
    }

    private static String plan(String... pairs) throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < pairs.length; i += 2) items.put(new JSONObject().put("content", pairs[i]).put("status", pairs[i + 1]));
        return new JSONObject().put("items", items).toString();
    }

    @Test
    public void conversation() throws Exception {
        String dir = System.getenv("BACKCAST_SCREENSHOTS");
        Assume.assumeTrue(dir != null && dir.length() > 0);
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = app.getSharedPreferences("backcast", Context.MODE_PRIVATE);
        prefs.edit().putString("active_ai_provider", "custom")
                .putString("ai_profile_custom_url", "https://api.example.com/v1")
                .putString("ai_profile_custom_key", "sk-test")
                .putString("ai_profile_custom_model", "grok-4.6")
                .putString("ai_profile_custom_models", "grok-4.6")
                .putString("reasoning_effort", "ultra").commit();
        ChatStore store = new ChatStore(app);
        long sid = store.create("迷你待办清单");
        store.append(sid, Message.user("在项目目录里新建一个 demo 文件夹，帮我做一个迷你待办清单：\n1. demo/todo.txt 里写 3 条待办\n2. demo/show.sh 脚本：读取并显示 todo.txt，每行加编号\n3. 运行脚本，确认输出正确\n4. 把第 2 条待办改成「已完成」，再运行一次\n最后告诉我结果。"));
        Message a1 = Message.assistant("", call("c1", "update_plan", plan("新建 demo/todo.txt，写 3 条待办", "in_progress",
                "新建 demo/show.sh，按行编号显示", "pending", "运行脚本确认输出", "pending", "第 2 条改为已完成并再跑一次", "pending")));
        a1.reasoning = "先列计划，再逐步做。";
        store.append(sid, a1);
        store.append(sid, Message.toolResult("c1", "计划已更新：共 4 步，已完成 0 步，进行中：新建 demo/todo.txt，写 3 条待办。"));
        store.append(sid, Message.assistant("", call("c2", "write", "{\"path\":\"demo/todo.txt\",\"content\":\"写项目文档\\n整理测试用例\\n备份工作目录\\n\"}")));
        store.append(sid, Message.toolResult("c2", "已写入 demo/todo.txt\n变更：+3 -0"));
        store.append(sid, Message.assistant("", call("c3", "write", "{\"path\":\"demo/show.sh\",\"content\":\"#!/bin/sh\\nnl -s '. ' demo/todo.txt\\n\"}")));
        store.append(sid, Message.toolResult("c3", "已写入 demo/show.sh\n变更：+7 -0"));
        store.append(sid, Message.assistant("", call("c4", "edit", "{\"path\":\"demo/todo.txt\",\"edits\":[]}")));
        store.append(sid, Message.toolResult("c4", "已修改 1 处：demo/todo.txt\n变更：+1 -1"));
        store.append(sid, Message.assistant("", call("c5", "update_plan", plan("新建 demo/todo.txt，写 3 条待办", "completed",
                "新建 demo/show.sh，按行编号显示", "completed", "运行脚本确认输出", "completed", "第 2 条改为已完成并再跑一次", "completed"))));
        store.append(sid, Message.toolResult("c5", "计划已更新：共 4 步，已完成 4 步，全部完成。"));
        Message done = Message.assistant("已完成。\n\n第一次：\n```\n1. 写项目文档\n2. 整理测试用例\n3. 备份工作目录\n```\n\n第二次运行（第 2 条已改）：\n```\n1. 写项目文档\n2. 已完成\n3. 备份工作目录\n```\n\n文件在 `demo/todo.txt`、`demo/show.sh`。", null);
        done.elapsedMs = 71000; done.thinkMs = 4000;
        store.append(sid, done);
        store.close();

        Activity main = Robolectric.buildActivity(MainActivity.class, new Intent(app, MainActivity.class)).setup().get();
        for (int i = 0; i < 12; i++) ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        View decor = main.getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        decor.draw(canvas);
        File out = new File(dir);
        out.mkdirs();
        try (FileOutputStream stream = new FileOutputStream(new File(out, "20-conversation.png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        main.findViewById(R.id.context_meter).performClick();
        for (int i = 0; i < 6; i++) ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        Bitmap usage = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas usageCanvas = new Canvas(usage);
        usageCanvas.drawColor(Color.WHITE);
        decor.draw(usageCanvas);
        try (FileOutputStream stream = new FileOutputStream(new File(out, "23-usage.png"))) {
            usage.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }
}
