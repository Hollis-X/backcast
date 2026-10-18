package com.mkei.backcast.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 上下文压缩。
 *
 * 做法对齐 Codex：不丢历史，而是让模型把当前进度写成一份交接摘要，
 * 再用这份摘要开一个新的上下文窗口，后面的对话接着这份摘要走。
 *
 * 摘要之前还有一层轻量清理：较早的工具输出换成占位文字（工具调用和结果仍成对），
 * 清完就回到阈值以下时不必做摘要；压缩请求本身超窗时也靠它和丢最旧的消息把请求缩小。
 */
public final class Compactor {

    public static final int MAX_USER_MESSAGE_TOKENS = 20000;

    /** 压缩请求超窗后最多缩小几次：第一次清工具输出，之后每次丢掉最旧的约四分之一。 */
    public static final int MAX_SHRINKS = 4;

    /**
     * 压缩时发给模型的指令。在 Codex 的 CONTEXT CHECKPOINT COMPACTION 基础上分节，
     * 要点是「写给下一个接手的人看」：工具输出和大部分对话都会被移走，
     * 所以用户提过的每条要求、改过的文件、走过的弯路都要写进来，旧摘要的内容要接着带上。
     */
    public static final String PROMPT =
            "You are performing a CONTEXT CHECKPOINT COMPACTION. "
            + "Create a handoff summary for another LLM that will resume the task. "
            + "After this, the tool outputs and most of the conversation are removed; "
            + "only the system prompt, the most recent user messages and your summary remain.\n\n"
            + "Use these sections and omit any that would be empty:\n"
            + "1. User requests and constraints: every distinct requirement, correction and preference "
            + "the user stated, oldest first, including early ones. Quote the user's wording when it matters.\n"
            + "2. Progress and key decisions: what is done, what was decided and why.\n"
            + "3. Files and code: paths read or changed, what changed, and identifiers or snippets "
            + "needed to continue.\n"
            + "4. Errors and fixes: problems hit, how they were resolved, and approaches that failed.\n"
            + "5. Remaining work: what is left, and the immediate next step.\n"
            + "6. Critical data: values, commands, outputs or references needed to continue.\n\n"
            + "If an earlier handoff summary is present, carry its still-relevant content forward "
            + "instead of dropping it.\n\n"
            + "Be concise, structured, and focused on helping the next LLM "
            + "seamlessly continue the work.";

    /** 第一次摘要被输出上限截断后，重试时接在 PROMPT 后面。 */
    public static final String RETRY_SHORTER =
            "Your previous summary was cut off by the output limit. Write a shorter summary "
            + "that fits: keep every user requirement and the next step, and shorten everything else.";

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

    /** 被清掉的工具输出换成这段文字，后面带原输出的第一行。 */
    public static final String CLEARED_PREFIX =
            "[Earlier tool output cleared to save context. Re-run the tool if you need it again.";

    /** App 自己记下的状态，附在摘要后面，不靠模型复述。 */
    static final String STATE_HEADER = "[Checkpoint state recorded by the app]";
    private static final String PLAN_LINE = "Plan:";
    private static final String FILES_LINE = "Files changed in this task:";
    private static final int MAX_FILES = 30, FIRST_LINE_CHARS = 160;

    private Compactor() {
    }

    /** 把摘要包成新窗口的第一条消息内容。 */
    public static String wrap(String summary) {
        return SUMMARY_PREFIX + "\n\n" + (summary == null ? "" : summary);
    }

    /** 摘要加上 App 记下的计划和改过的文件。state 为空时与 wrap(summary) 相同。 */
    public static String wrap(String summary, String state) {
        String body = summary == null ? "" : summary;
        // 模型有时会把上一份状态原样抄进摘要，以 App 这次记下的为准。
        int copied = body.indexOf(STATE_HEADER);
        if (copied >= 0) body = body.substring(0, copied).trim();
        return wrap(state == null || state.length() == 0 ? body : body + "\n\n" + state);
    }

    public static boolean isSummary(Message message) {
        return message != null
                && (Message.USER.equals(message.role) || Message.ASSISTANT.equals(message.role))
                && message.content != null && message.content.startsWith(SUMMARY_PREFIX);
    }

    public static boolean isCleared(String content) {
        return content != null && content.startsWith(CLEARED_PREFIX);
    }

    /**
     * 从最新往前保留 keepTokens 以内的工具输出，更早的换成占位文字。
     * 返回新列表，不改动传入的消息对象；工具调用和结果仍一一对应。
     */
    public static List<Message> clearToolOutputs(List<Message> messages, int keepTokens) {
        List<Message> out = new ArrayList<Message>(messages);
        int kept = 0;
        for (int i = out.size() - 1; i >= 0; i--) {
            Message m = out.get(i);
            if (m == null || !Message.TOOL.equals(m.role) || isCleared(m.content)) continue;
            int tokens = TokenMeter.of(m.content);
            if (kept + tokens <= keepTokens) {
                kept += tokens;
                continue;
            }
            // 预算用完后，更早的一律清掉，不再挑小的留。
            keepTokens = kept;
            out.set(i, m.withContent(cleared(m.content)));
        }
        return out;
    }

    private static String cleared(String content) {
        String text = content == null ? "" : content.trim();
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        if (first.length() > FIRST_LINE_CHARS) first = first.substring(0, FIRST_LINE_CHARS) + "…";
        return first.length() == 0 ? CLEARED_PREFIX + "]" : CLEARED_PREFIX + " First line: " + first + "]";
    }

    /**
     * 丢掉最旧的约四分之一非系统消息，只在工具结果之外的位置下刀，保证不留下孤立的工具结果。
     * 什么都丢不了（剩不下任何非系统消息）时返回 null。
     */
    public static List<Message> dropOldest(List<Message> messages) {
        List<Message> system = new ArrayList<Message>(), rest = new ArrayList<Message>();
        for (Message m : messages) {
            if (m != null && Message.SYSTEM.equals(m.role)) system.add(m);
            else if (m != null) rest.add(m);
        }
        int cut = Math.max(1, rest.size() / 4);
        while (cut < rest.size() && Message.TOOL.equals(rest.get(cut).role)) cut++;
        if (cut >= rest.size()) return null;
        List<Message> out = new ArrayList<Message>(system);
        out.addAll(rest.subList(cut, rest.size()));
        return out;
    }

    /**
     * 当前计划和这个任务里改过的文件，从工具调用里直接读出来。
     * 这个窗口里没有新的计划时沿用上一份摘要记下的计划；文件清单与上一份合并。
     */
    public static String state(List<Message> messages) {
        String previous = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (isSummary(m)) {
                int at = m.content.lastIndexOf(STATE_HEADER);
                if (at >= 0) previous = m.content.substring(at);
                break;
            }
        }
        LinkedHashSet<String> files = new LinkedHashSet<String>(section(previous, FILES_LINE));
        List<String> plan = null;
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m == null || !Message.ASSISTANT.equals(m.role) || m.toolCalls == null) continue;
            for (int c = 0; c < m.toolCalls.length(); c++) {
                JSONObject call = m.toolCalls.optJSONObject(c);
                JSONObject function = call == null ? null : call.optJSONObject("function");
                if (function == null || !succeeded(resultOf(messages, i, call.optString("id", "")))) continue;
                String name = function.optString("name", "");
                JSONObject args;
                try {
                    args = new JSONObject(function.optString("arguments", "{}"));
                } catch (Exception partial) {
                    continue;
                }
                if ("edit".equals(name) || "write".equals(name)) {
                    String path = args.optString("path", "").trim();
                    if (path.length() > 0) {
                        files.remove(path);
                        files.add(path);
                    }
                } else if ("update_plan".equals(name)) {
                    List<String> steps = planSteps(args);
                    if (!steps.isEmpty()) plan = steps;
                }
            }
        }
        if (plan == null) plan = section(previous, PLAN_LINE);
        List<String> recent = new ArrayList<String>(files);
        if (recent.size() > MAX_FILES) recent = recent.subList(recent.size() - MAX_FILES, recent.size());
        if (plan.isEmpty() && recent.isEmpty()) return "";
        StringBuilder out = new StringBuilder(STATE_HEADER);
        if (!plan.isEmpty()) {
            out.append('\n').append(PLAN_LINE);
            for (String step : plan) out.append("\n- ").append(step);
        }
        if (!recent.isEmpty()) {
            out.append('\n').append(FILES_LINE);
            for (String path : recent) out.append("\n- ").append(path);
        }
        return out.toString();
    }

    /** 找到这次调用的结果；没有结果（还没执行）返回 null。 */
    private static String resultOf(List<Message> messages, int from, String id) {
        for (int i = from + 1; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m == null || !Message.TOOL.equals(m.role)) break;
            if (id.equals(m.toolCallId)) return m.content;
        }
        return null;
    }

    private static boolean succeeded(String result) {
        if (result == null) return false;
        String text = result;
        if (isCleared(text)) {
            int at = text.indexOf(" First line: ");
            text = at < 0 ? "" : text.substring(at + " First line: ".length());
        }
        text = text.trim();
        return text.length() > 0 && !text.startsWith("错误") && !text.startsWith("已停止");
    }

    private static List<String> planSteps(JSONObject args) {
        List<String> out = new ArrayList<String>();
        JSONArray items = null;
        for (String key : new String[] { "items", "plan", "todos", "steps", "tasks" }) {
            items = args.optJSONArray(key);
            if (items != null) break;
        }
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            String content = item == null ? items.optString(i, "").trim() : "";
            String status = "pending";
            if (item != null) {
                for (String key : new String[] { "content", "step", "title", "text", "task", "description" }) {
                    content = item.optString(key, "").trim();
                    if (content.length() > 0) break;
                }
                status = item.optString("status", "pending").trim();
            }
            if (content.length() > 0) out.add("[" + status + "] " + content.replace('\n', ' '));
        }
        return out;
    }

    /** 读出上一份状态里某一节的条目。 */
    private static List<String> section(String state, String title) {
        List<String> out = new ArrayList<String>();
        boolean inside = false;
        for (String line : state.split("\n")) {
            if (line.equals(PLAN_LINE) || line.equals(FILES_LINE)) {
                inside = line.equals(title);
            } else if (inside && line.startsWith("- ")) {
                out.add(line.substring(2));
            }
        }
        return out;
    }
}
