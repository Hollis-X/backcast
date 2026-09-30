package com.mkei.backcast.agent;

import org.json.JSONObject;

/**
 * 一个可被 agent 调用的工具。
 *
 * 新增工具只需实现本接口并注册，不需要改动 agent 循环。
 */
public interface Tool {

    /** 工具名，模型据此调用，需唯一。 */
    String name();

    /** 给模型看的说明，写清楚什么时候该用它。 */
    String description();

    /** JSON Schema 形式的参数定义。 */
    JSONObject parameters();

    /** 执行并返回结果文本。抛异常会被转成错误信息回给模型。 */
    String run(JSONObject args) throws Exception;

    /** 用户点停止时打断正在执行的调用。没有进行中的工作就什么都不做。 */
    void abort();
}