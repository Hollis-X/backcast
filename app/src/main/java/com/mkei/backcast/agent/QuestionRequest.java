package com.mkei.backcast.agent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** 模型向用户提的一组选择题：界面展示，用户作答后把答案交回给等待中的工具。 */
public final class QuestionRequest {
    public final JSONArray questions;
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile JSONObject answers;
    private volatile boolean cancelled;
    private volatile Runnable onCancel;

    public QuestionRequest(JSONArray questions) { this.questions = questions; }

    /** 界面登记：请求被取消时关掉弹窗。 */
    public void setOnCancel(Runnable action) { onCancel = action; }

    /** 用户提交。answers 的键是问题文字，值是所选选项或自己写的内容。 */
    public void answer(JSONObject value) {
        if (done.getCount() == 0) return;
        answers = value; done.countDown();
    }

    /** 用户跳过、关掉界面或停止本轮。 */
    public void cancel() {
        if (done.getCount() == 0) return;
        cancelled = true; done.countDown();
        Runnable action = onCancel;
        if (action != null) action.run();
    }

    public boolean finished() { return done.getCount() == 0; }
    public boolean cancelled() { return cancelled; }

    /** 等到用户作答或取消；返回 null 表示没有作答。 */
    public JSONObject await() throws InterruptedException {
        while (!done.await(250, TimeUnit.MILLISECONDS)) { if (Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
        return cancelled ? null : answers;
    }
}
