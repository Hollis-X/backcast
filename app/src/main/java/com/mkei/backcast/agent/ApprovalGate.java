package com.mkei.backcast.agent;

import org.json.JSONObject;

/**
 * 工具调用的人工放行。
 *
 * 权限分三档：
 * - 完全访问：直接执行。
 * - 受限访问：每次调用先让模型自己审一遍，它判定危险的才交给用户批准。
 * - 限制访问：所有调用一律用户批准。
 *
 * 循环本身不认识界面，所以这里只定义问什么、怎么答；
 * 具体弹窗由界面实现。
 */
public interface ApprovalGate {

    String ACCESS_FULL = "full";
    String ACCESS_GUARDED = "guarded";
    String ACCESS_STRICT = "strict";

    /**
     * 请用户批准这次调用。
     *
     * 受限访问下模型自查判定危险、以及限制访问下的所有调用，都会走到这里。
     * 在跑循环的线程上调用，实现方负责切到界面线程弹窗并等结果。
     * 用户拒绝或超时都返回 false。
     */
    boolean approve(String toolName, JSONObject args);
}