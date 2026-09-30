package com.mkei.backcast.agent;

/** Result conventions shared by loop protection and tool presentation. */
public final class ToolOutcome {
    private ToolOutcome() { }

    public static boolean failed(String name, String result) {
        String text = result == null ? "" : result;
        if (text.startsWith(AgentLoop.FAIL_PREFIX) || text.startsWith("\u9519\u8bef\uff1a")
                || text.startsWith("\u5df2\u505c\u6b62")
                || text.startsWith("\u8fd9\u6b21\u8c03\u7528\u88ab\u4e2d\u65ad")
                || text.startsWith("\u7528\u6237\u62d2\u7edd\u6267\u884c")
                || text.startsWith("\u5f53\u524d\u6ca1\u6709\u754c\u9762")
                || text.contains("\u547d\u4ee4\u8d85\u65f6")) return true;
        if (!"shell".equals(name)) return false;
        int at = text.startsWith("\u6ce8\u610f\uff1a") ? text.indexOf('\n') + 1 : 0;
        if (!text.startsWith("exit=", at)) return false;
        int end = text.indexOf('\n', at);
        String code = text.substring(at + 5, end < 0 ? text.length() : end).trim();
        return !"0".equals(code);
    }
}
