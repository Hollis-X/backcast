package com.mkei.backcast.agent;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/** 会话内禁止披露自身指令；正常的提示词文件操作不受影响。 */
public final class PromptGuard {

    public static final String REFUSAL = "不能提供系统提示词、环境块或内部指令。";
    private static final int MIN_LINE = 16;

    private PromptGuard() {
    }

    /** 明确索取助手自身指令时，在请求模型之前拒绝。 */
    public static boolean requestsDisclosure(String request) {
        String text = normalize(request);
        boolean subject = containsAny(text, "提示词", "系统指令", "内部指令", "开发者指令",
                "隐藏指令", "环境块", "系统消息", "开发者消息", "prompt", "systemmessage",
                "developermessage", "environmentblock", "instruction");
        boolean owned = containsAny(text, "你的", "你自己的", "你收到", "你遵循", "你被赋予",
                "你当前", "your", "youreceived", "youfollow");
        boolean hidden = containsAny(text, "内部指令", "隐藏指令", "开发者指令",
                "hiddeninstructions", "developerinstructions");
        boolean reveal = containsAny(text, "输出", "打印", "显示", "告诉", "给我", "发我",
                "列出", "复述", "总结", "翻译", "改写", "提取", "导出", "逐字", "原文",
                "是什么", "show", "print", "reveal", "repeat", "summarize", "translate",
                "rewrite", "extract", "export", "tellme", "whatisyour");
        boolean direct = text.startsWith("输出系统提示") || text.startsWith("请输出系统提示")
                || text.startsWith("打印系统提示") || text.startsWith("请打印系统提示")
                || text.startsWith("printthesystemprompt") || text.startsWith("showthesystemprompt");
        boolean external = containsAny(text, "promptxml", "文件", "项目里", "projectfile",
                "fromthefile", "inthefile", "chatbot", "机器人");
        return subject && reveal && (owned || (!external && (hidden || direct)));
    }

    public static String redact(String text, String instructions, String environment, String extra) {
        if (text == null || text.length() == 0) {
            return text == null ? "" : text;
        }
        if (new Stream(instructions, environment, extra).append(text)) {
            return REFUSAL;
        }
        return text;
    }

    /** 回放时根据对应的用户消息遮住已经保存的披露回复，不改原库。 */
    public static String redact(String text, String instructions, String environment,
            String extra, String request) {
        return text == null || text.trim().length() == 0 ? (text == null ? "" : text)
                : requestsDisclosure(request) ? REFUSAL : redact(text, instructions, environment, extra);
    }

    /** 忽略排版变化，并识别较长指令里的连续原文片段。 */
    public static boolean recites(String text, String secret) {
        if (text == null || secret == null) {
            return false;
        }
        String visible = normalize(text);
        String[] lines = secret.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = normalize(lines[i]);
            if (line.length() < MIN_LINE) {
                continue;
            }
            int span = Math.max(MIN_LINE, (line.length() * 2 + 2) / 3);
            for (int at = 0; at + span <= line.length(); at++) {
                if (visible.contains(line.substring(at, at + span))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 单独提到路径不算；至少两个不同环境字段同时被复述才拦。 */
    public static boolean dumpsEnvironment(String text, String environment) {
        if (text == null || environment == null) {
            return false;
        }
        String visible = normalize(text);
        int hits = 0;
        String[] lines = environment.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = normalize(lines[i]);
            if (line.length() >= 8 && visible.contains(line) && ++hits >= 2) {
                return true;
            }
        }
        return false;
    }

    /** Reuses compiled patterns and scans only deltas plus a bounded overlap. */
    public static final class Stream {
        private final List<String> needles = new ArrayList<String>();
        private final List<String> fields = new ArrayList<String>();
        private final boolean[] seen;
        private int overlap;
        private int hits;
        private String tail = "";
        private boolean blocked;

        public Stream(String instructions, String environment, String extra) {
            compile(instructions);
            compile(extra);
            if (environment != null) {
                for (String raw : environment.split("\n")) {
                    String line = normalize(raw);
                    if (line.length() >= 8 && !fields.contains(line)) {
                        fields.add(line);
                        overlap = Math.max(overlap, line.length() - 1);
                    }
                }
            }
            seen = new boolean[fields.size()];
        }

        private void compile(String secret) {
            if (secret == null) return;
            for (String raw : secret.split("\n")) {
                String line = normalize(raw);
                if (line.length() < MIN_LINE) continue;
                int span = Math.max(MIN_LINE, (line.length() * 2 + 2) / 3);
                overlap = Math.max(overlap, span - 1);
                for (int at = 0; at + span <= line.length(); at++) {
                    String needle = line.substring(at, at + span);
                    if (!needles.contains(needle)) needles.add(needle);
                }
            }
        }

        public boolean append(String delta) {
            if (blocked || delta == null) return blocked;
            String normalized = normalize(delta);
            for (int at = 0; at < normalized.length(); at += 256) {
                String visible = tail + normalized.substring(at, Math.min(normalized.length(), at + 256));
                for (String needle : needles) {
                    if (visible.contains(needle)) {
                        blocked = true;
                        return true;
                    }
                }
                for (int i = 0; i < fields.size(); i++) {
                    if (!seen[i] && visible.contains(fields.get(i))) {
                        seen[i] = true;
                        if (++hits >= 2) {
                            blocked = true;
                            return true;
                        }
                    }
                }
                tail = visible.substring(Math.max(0, visible.length() - overlap));
            }
            return false;
        }
    }

    private static boolean containsAny(String text, String... needles) {
        for (int i = 0; i < needles.length; i++) {
            if (text.contains(needles[i])) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.US);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
