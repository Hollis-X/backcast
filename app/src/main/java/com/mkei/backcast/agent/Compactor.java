package com.mkei.backcast.agent;

/**
 * 上下文压缩。
 *
 * 做法对齐 Codex：不丢历史，而是让模型把当前进度写成一份交接摘要，
 * 再用这份摘要开一个新的上下文窗口，后面的对话接着这份摘要走。
 */
public final class Compactor {

    public static final int MAX_USER_MESSAGE_TOKENS = 20000;

    /**
     * 压缩时发给模型的指令。与 Codex 的 CONTEXT CHECKPOINT COMPACTION 一致：
     * 要点是「写给下一个接手的人看」，所以必须包含已定决策、约束和下一步。
     */
    public static final String PROMPT =
            "You are performing a CONTEXT CHECKPOINT COMPACTION. "
            + "Create a handoff summary for another LLM that will resume the task.\n\n"
            + "Include:\n"
            + "- Current progress and key decisions made\n"
            + "- Important context, constraints, or user preferences\n"
            + "- What remains to be done (clear next steps)\n"
            + "- Any critical data, examples, or references needed to continue\n\n"
            + "Be concise, structured, and focused on helping the next LLM "
            + "seamlessly continue the work.";

    /**
     * 摘要前缀。新窗口里第一条就是它，模型据此知道自己接的是别人的活，
     * 不要去重复已经做过的事。
     */
    public static final String SUMMARY_PREFIX =
            "Another language model started to solve this problem and produced a summary "
            + "of its thinking process. You also have access to the state of the tools that "
            + "were used by that language model. Use this to build on the work that has "
            + "already been done and avoid duplicating work. Here is the summary produced "
            + "by the other language model, use the information in this summary to assist "
            + "with your own analysis:";

    private Compactor() {
    }

    /** 把摘要包成新窗口的第一条消息内容。 */
    public static String wrap(String summary) {
        return SUMMARY_PREFIX + "\n\n" + (summary == null ? "" : summary);
    }

    public static boolean isSummary(Message message) {
        return message != null
                && (Message.USER.equals(message.role) || Message.ASSISTANT.equals(message.role))
                && message.content != null && message.content.startsWith(SUMMARY_PREFIX);
    }
}
