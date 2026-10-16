package com.mkei.backcast;

/**
 * 某个子代理的会话页。直接用主会话的界面和渲染，
 * MainActivity 看到 EXTRA_AGENT_ROOT / EXTRA_AGENT_ID 就切到子代理模式。
 * 单独登记成一个 Activity，不会和主会话页共用一个实例。
 */
public final class SubAgentChatActivity extends MainActivity {
}
