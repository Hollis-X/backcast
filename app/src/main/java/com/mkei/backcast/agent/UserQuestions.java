package com.mkei.backcast.agent;

/** 能向用户展示选择题的界面。工具线程调用 show 后自己等待结果，实现方只负责显示。 */
public interface UserQuestions {
    /** 在界面线程之外调用；实现方切到界面线程弹出，用户作答或关闭时调用 request 的 answer / cancel。 */
    void show(QuestionRequest request);
}
