package com.mkei.backcast.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 文件改动的增删行数：工具结果末尾写一行“变更：+a -d”，界面读它画红绿数字。 */
public final class LineDelta {
    private static final String PREFIX = "变更：";
    private static final Pattern MARK = Pattern.compile("(?m)^" + PREFIX + "\\+(\\d+) -(\\d+)$");
    private LineDelta() { }

    /** 按行计数比较：新内容里多出来的行算新增，旧内容里少掉的行算删除。只看行的多少，不做完整对齐。 */
    public static int[] count(String before, String after) {
        Map<String, Integer> lines = new HashMap<String, Integer>();
        for (String line : split(before)) lines.put(line, count(lines, line) + 1);
        int added = 0;
        for (String line : split(after)) {
            int left = count(lines, line);
            if (left > 0) lines.put(line, left - 1); else added++;
        }
        int removed = 0;
        for (int left : lines.values()) removed += left;
        return new int[] { added, removed };
    }

    public static String mark(int[] delta) { return PREFIX + "+" + delta[0] + " -" + delta[1]; }

    /** 从工具结果里读出增删行数；没有标记返回 null。 */
    public static int[] parse(String result) {
        if (result == null) return null;
        Matcher matcher = MARK.matcher(result);
        if (!matcher.find()) return null;
        try { return new int[] { Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)) }; }
        catch (NumberFormatException overflow) { return null; }
    }

    /** 去掉结果里的变更标记，给界面显示正文用。 */
    public static String strip(String result) {
        if (result == null) return "";
        return MARK.matcher(result).replaceAll("").replaceAll("\\n+$", "");
    }

    private static int count(Map<String, Integer> lines, String line) {
        Integer value = lines.get(line);
        return value == null ? 0 : value.intValue();
    }

    private static String[] split(String text) {
        if (text == null || text.length() == 0) return new String[0];
        String body = text.replace("\r\n", "\n");
        if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
        return body.split("\n", -1);
    }
}
