package com.mkei.backcast.agent;

import org.json.JSONArray;

import java.util.List;

/**
 * 粗略的 token 估算。
 *
 * 服务端未返回用量时，用于窗口展示与自动压缩判定，不参与计费。按字符构成估：
 * 日韩表意文字一个字符约 1 token，拉丁与数字约 4 个字符 1 token，
 * 字符类内容（空白与标点）另算。误差在展示可接受范围内。
 */
public final class TokenMeter {

    private TokenMeter() {
    }

    /** 估算一段文本的 token 数。 */
    public static int of(String text) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        int wide = 0;
        int narrow = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isIdeographic(c)) {
                wide++;
            } else if (c < 128) {
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    other++;
                } else {
                    narrow++;
                }
            } else {
                other++;
            }
        }
        int tokens = wide + (narrow + 3) / 4 + (other + 1) / 2;
        return tokens < 1 ? 1 : tokens;
    }

    /** 表意文字与假名，一个字符按一个 token 计。 */
    private static boolean isIdeographic(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7AF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFF00 && c <= 0xFF60);
    }

    /** 估算一条消息的 token 数，含工具调用与思考内容。 */
    public static int of(Message m) {
        if (m == null) {
            return 0;
        }
        // 每条消息的角色与分隔符本身也要占几个 token。
        long n = 4;
        n += of(m.content);
        n += of(m.reasoning);
        n += of(m.toolCallId);
        n += of(values(m.toolCalls));
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** 估算整个历史的 token 数。 */
    public static int of(List<Message> messages) {
        if (messages == null) {
            return 0;
        }
        long n = 0;
        for (int i = 0; i < messages.size(); i++) {
            n += of(messages.get(i));
        }
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** 估算全部工具 schema 的 token 数，这部分每轮请求都会带上。 */
    public static int ofSchema(JSONArray schema) {
        return of(schema == null ? "" : schema.toString());
    }

    /** 把工具调用的参数与函数名拼成文本，用于估算。 */
    private static String values(JSONArray calls) {
        if (calls == null || calls.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < calls.length(); i++) {
            org.json.JSONObject call = calls.optJSONObject(i);
            if (call == null) {
                continue;
            }
            sb.append(call.optString("id", ""));
            org.json.JSONObject fn = call.optJSONObject("function");
            if (fn != null) {
                sb.append(fn.optString("name", ""));
                sb.append(fn.optString("arguments", ""));
            }
        }
        return sb.toString();
    }
}
