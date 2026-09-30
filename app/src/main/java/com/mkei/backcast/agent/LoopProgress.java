package com.mkei.backcast.agent;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 目标推进过程中的停滞检测。
 *
 * 两件事会停手，都不替模型宣布完成或 blocked：
 * 同一个调用原地打转，或者连续多轮只读、没有改文件。
 * 完成与达不到仍只能由模型经 update_goal 声明。
 */
final class LoopProgress {
    /** 同一个工具调用连续返回多少次相同结果，就认定在原地打转。 */
    private static final int REPEAT_LIMIT = 3;
    /**
     * 连续多少轮只有读取和检查、没有改文件，就认定在重复验证。
     *
     * 成功的 edit / write 会把计数清掉，所以一边改一边查不会被停。
     * 还没改过文件的排查同样受这个上限：换路径读、换脚本查，只要不改文件，
     * 就不是 Codex 说的「改变了权威状态」。
     */
    private static final int READONLY_LIMIT = 12;
    /**
     * 连续多少轮一个工具都没调用，算原地表态。
     *
     * 对齐 Codex 的阻塞审计：同一个阻塞条件连续至少三轮才算真的卡住。
     * 中间调过工具就把计数清掉，所以正常的「说一句、继续干活」不会被误伤。
     */
    private static final int IDLE_LIMIT = 3;
    /**
     * 连续多少轮自动续跑完全没有输出，就停下。
     *
     * 对齐 Codex：空的自动续跑连续三轮，把目标标成 blocked，避免空转继续烧 token。
     * 有正文、有思考或有工具调用都算有活动，计数清掉。
     */
    private static final int EMPTY_LIMIT = 3;
    private static final int TRACKED_CALLS = 64;

    private static final class Result {
        String fingerprint;
        int repeats;
    }
    private static final class Call {
        String name;
        JSONObject args;
        Call(String name, JSONObject args) { this.name = name; this.args = args; }
    }

    private final LinkedHashMap<String, Result> results = new LinkedHashMap<String, Result>();
    private int idleRounds;
    private int emptyRounds;
    private int readonlyRounds;
    private boolean roundMutated;
    /** 这一段目标里已经执行过工具。纯口头宣布完成不算数。 */
    private boolean acted;
    /** 只读上限触发的停手。之后真的改了文件就撤销，避免重进时把已经改过的历史判死。 */
    private boolean readonlyStall;
    private boolean emptyStall;
    private String reason = "";

    String reason() { return reason; }

    boolean stalled() { return reason.length() > 0; }

    /** 这一段里已经有过工具调用。还没动手就宣布完成，不能当完成。 */
    boolean acted() { return acted; }

    /** 已经攒够的空续跑轮数。重进时从历史接着算。 */
    boolean emptyBlocked() { return emptyRounds >= EMPTY_LIMIT; }

    String emptyBlockReason() {
        return "连续 " + EMPTY_LIMIT + " 轮自动续跑没有输出任何内容，目标已停下。";
    }

    /**
     * 这一轮自动续跑有没有实质输出。
     *
     * @return 攒满空轮时返回停下来的原因；否则 null。
     */
    String noteEmpty(boolean blank) {
        if (!blank) {
            emptyRounds = 0;
            return null;
        }
        emptyRounds++;
        if (emptyRounds >= EMPTY_LIMIT) {
            emptyStall = true;
            return emptyBlockReason();
        }
        return null;
    }

    /** 这一轮调了工具，原地表态的计数清掉。这一轮是否改了文件另算。 */
    void ranTools() {
        idleRounds = 0;
        emptyRounds = 0;
        roundMutated = false;
        acted = true;
    }

    /**
     * 这一轮只回文字，没有调用任何工具。
     *
     * @return null 表示还能再续一轮；否则返回停下来的原因。
     */
    String idle() {
        idleRounds++;
        if (idleRounds >= IDLE_LIMIT) {
            reason = "目标连续 " + idleRounds
                    + " 轮没有调用任何工具，只在原地表态。"
                    + "已停止自动续跑：要么给出可核验的完成证据并调用 update_goal 标成完成，"
                    + "要么说明卡在哪里并标成 blocked。";
            return reason;
        }
        return null;
    }

    /**
     * 同一个工具调用已经连续拿到相同结果时，不再原样重跑。
     *
     * @return null 表示放行；否则返回要回给模型的结果文本。
     */
    String before(String name, JSONObject args) {
        Result previous = results.get(key(name, args));
        if (previous != null && previous.repeats >= REPEAT_LIMIT) {
            reason = "同一工具调用已连续返回 " + REPEAT_LIMIT + " 次相同结果（" + name
                    + "），且没有成功修改文件。已停止重复执行，"
                    + "请先查清错误类型、路径或参数，再换一种做法。";
            return AgentLoop.FAIL_PREFIX + reason;
        }
        return null;
    }

    void tool(String name, JSONObject args, String output) {
        // 成功改了文件就是实质进展，之前那些重复结果和只读轮次都不再算数。
        if (("edit".equals(name) || "write".equals(name)) && !ToolOutcome.failed(name, output)) {
            results.clear();
            roundMutated = true;
            readonlyRounds = 0;
            if (readonlyStall) {
                readonlyStall = false;
                reason = "";
            }
            return;
        }
        String key = key(name, args), fingerprint = digest(output == null ? "" : output);
        Result previous = results.get(key);
        if (previous == null) { previous = new Result(); results.put(key, previous); }
        previous.repeats = fingerprint.equals(previous.fingerprint) ? previous.repeats + 1 : 1;
        previous.fingerprint = fingerprint;
        if (results.size() > TRACKED_CALLS) results.remove(results.keySet().iterator().next());
    }

    /**
     * 这一轮工具已经跑完。没改文件就记一笔只读。
     *
     * @return null 表示还能继续；否则返回停下来的原因。
     */
    String finishRound() {
        if (reason.length() > 0) {
            return reason;
        }
        if (roundMutated) {
            readonlyRounds = 0;
            return null;
        }
        readonlyRounds++;
        if (readonlyRounds >= READONLY_LIMIT) {
            readonlyStall = true;
            reason = "目标连续 " + readonlyRounds
                    + " 轮只有读取和检查，没有修改任何文件。"
                    + "已停止自动续跑：若完成审计已经通过，调用 update_goal 标成完成；"
                    + "若还要改代码，先改再继续；若确实做不到，标成 blocked。";
            return reason;
        }
        return null;
    }

    /** 用户点了继续：只读打转重新计数。同一个调用原地打转的记录留着。 */
    void pardonReadonly() {
        readonlyRounds = 0;
        emptyRounds = 0;
        roundMutated = false;
        if (readonlyStall || emptyStall) {
            readonlyStall = false;
            emptyStall = false;
            reason = "";
        }
    }

    /** 从已有历史重建打转状态，重进会话时接着算。 */
    void restore(List<Message> history) {
        int start = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (Message.USER.equals(message.role) && !Goal.isSteer(message.content)
                    && !Goal.isNote(message.content)) {
                start = i + 1;
                break;
            }
        }
        Map<String, Call> pending = new HashMap<String, Call>();
        boolean sawTools = false;
        for (int i = start; i < history.size(); i++) {
            Message message = history.get(i);
            if (Message.ASSISTANT.equals(message.role)) {
                if (sawTools) {
                    finishRound();
                }
                pending.clear();
                sawTools = message.toolCalls != null && message.toolCalls.length() > 0;
                if (!sawTools) {
                    boolean blank = message.content == null || message.content.trim().length() == 0;
                    boolean thought = message.reasoning != null && message.reasoning.trim().length() > 0;
                    if (blank && !thought) {
                        emptyRounds++;
                    } else {
                        emptyRounds = 0;
                    }
                    continue;
                }
                ranTools();
                for (int c = 0; c < message.toolCalls.length(); c++) {
                    JSONObject call = message.toolCalls.optJSONObject(c);
                    JSONObject fn = call == null ? null : call.optJSONObject("function");
                    if (fn == null) continue;
                    JSONObject args;
                    try { args = new JSONObject(fn.optString("arguments", "{}")); }
                    catch (Exception invalid) { args = new JSONObject(); }
                    pending.put(call.optString("id", ""), new Call(fn.optString("name"), args));
                }
            } else if (Message.TOOL.equals(message.role)) {
                Call call = pending.remove(message.toolCallId == null ? "" : message.toolCallId);
                if (call != null) tool(call.name, call.args, message.content);
            }
        }
        if (sawTools) {
            finishRound();
        }
    }

    private static String key(String name, JSONObject args) { return name + ":" + digest(canonical(args)); }

    private static String canonical(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<String>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) out.append(JSONObject.quote(key)).append(':').append(canonical(object.opt(key))).append(',');
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) out.append(canonical(array.opt(i))).append(',');
            return out.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder();
            for (byte item : hash) out.append(Integer.toHexString((item & 0xFF) | 0x100).substring(1));
            return out.toString();
        } catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }
}