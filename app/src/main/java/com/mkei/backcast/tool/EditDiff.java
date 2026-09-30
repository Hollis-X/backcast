package com.mkei.backcast.tool;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 按原文替换。每一处都对着替换前的原文匹配，失败则整次不改。
 * 精确对不上时，才放宽行尾空白、弯引号和破折号。
 */
final class EditDiff {

    static final class Edit {
        final String oldText;
        final String newText;

        Edit(String oldText, String newText) {
            this.oldText = oldText;
            this.newText = newText;
        }
    }

    static final class Outcome {
        final String content;
        final String error;

        private Outcome(String content, String error) {
            this.content = content;
            this.error = error;
        }
    }

    private static final class Match {
        final int index;
        final int length;
        final boolean fuzzy;

        Match(int index, int length, boolean fuzzy) {
            this.index = index;
            this.length = length;
            this.fuzzy = fuzzy;
        }
    }

    private static final class Hit {
        final int editIndex;
        final int matchIndex;
        final int matchLength;
        final String newText;

        Hit(int editIndex, int matchIndex, int matchLength, String newText) {
            this.editIndex = editIndex;
            this.matchIndex = matchIndex;
            this.matchLength = matchLength;
            this.newText = newText;
        }
    }

    private EditDiff() {
    }

    /** raw 是文件原文，可以带 BOM。成功时 content 是要写回的全文。 */
    static Outcome apply(String raw, List<Edit> edits, String path) {
        if (edits == null || edits.size() == 0) {
            return fail("edits 至少要有一处替换。");
        }
        String bom = "";
        String text = raw == null ? "" : raw;
        if (text.startsWith("\uFEFF")) {
            bom = "\uFEFF";
            text = text.substring(1);
        }
        String ending = detectEnding(text);
        String normalized = toLf(text);
        try {
            String updated = applyToLf(normalized, edits, path);
            return new Outcome(bom + restore(updated, ending), null);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    private static Outcome fail(String error) {
        return new Outcome(null, error);
    }

    private static String detectEnding(String content) {
        int crlf = content.indexOf("\r\n");
        int lf = content.indexOf('\n');
        if (lf < 0 || crlf < 0) {
            return "\n";
        }
        return crlf < lf ? "\r\n" : "\n";
    }

    private static String toLf(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static String restore(String text, String ending) {
        if ("\r\n".equals(ending)) {
            return text.replace("\n", "\r\n");
        }
        return text;
    }

    private static String applyToLf(String content, List<Edit> edits, String path) {
        int n = edits.size();
        String[] oldText = new String[n];
        String[] newText = new String[n];
        for (int i = 0; i < n; i++) {
            oldText[i] = toLf(edits.get(i).oldText);
            newText[i] = toLf(edits.get(i).newText);
            if (oldText[i].length() == 0) {
                throw new IllegalArgumentException(emptyError(path, i, n));
            }
        }

        boolean anyFuzzy = false;
        for (int i = 0; i < n; i++) {
            if (find(content, oldText[i]).fuzzy) {
                anyFuzzy = true;
                break;
            }
        }
        String base = anyFuzzy ? fuzzy(content) : content;

        List<Hit> hits = new ArrayList<Hit>();
        for (int i = 0; i < n; i++) {
            Match match = find(base, oldText[i]);
            if (match.index < 0) {
                throw new IllegalArgumentException(missingError(path, i, n));
            }
            int times = count(base, oldText[i]);
            if (times > 1) {
                throw new IllegalArgumentException(duplicateError(path, i, n, times));
            }
            hits.add(new Hit(i, match.index, match.length, newText[i]));
        }

        Collections.sort(hits, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                if (a.matchIndex < b.matchIndex) {
                    return -1;
                }
                if (a.matchIndex > b.matchIndex) {
                    return 1;
                }
                return 0;
            }
        });
        for (int i = 1; i < hits.size(); i++) {
            Hit previous = hits.get(i - 1);
            Hit current = hits.get(i);
            if (previous.matchIndex + previous.matchLength > current.matchIndex) {
                throw new IllegalArgumentException(
                        "edits[" + previous.editIndex + "] 和 edits[" + current.editIndex
                                + "] 在 " + path + " 里重叠。合成一处，或改不重叠的范围。");
            }
        }

        String updated = anyFuzzy
                ? preserve(content, base, hits)
                : splice(base, hits, 0);
        if (content.equals(updated)) {
            throw new IllegalArgumentException(sameError(path, n));
        }
        return updated;
    }

    private static Match find(String content, String oldText) {
        int exact = content.indexOf(oldText);
        if (exact >= 0) {
            return new Match(exact, oldText.length(), false);
        }
        String fuzzyContent = fuzzy(content);
        String fuzzyOld = fuzzy(oldText);
        if (fuzzyOld.length() == 0) {
            return new Match(-1, 0, false);
        }
        int at = fuzzyContent.indexOf(fuzzyOld);
        if (at < 0) {
            return new Match(-1, 0, false);
        }
        return new Match(at, fuzzyOld.length(), true);
    }

    private static int count(String content, String oldText) {
        String needle = fuzzy(oldText);
        String hay = needle.length() == 0 ? content : fuzzy(content);
        if (needle.length() == 0) {
            needle = oldText;
        }
        if (needle.length() == 0) {
            return 0;
        }
        int times = 0;
        int from = 0;
        while (from <= hay.length()) {
            int at = hay.indexOf(needle, from);
            if (at < 0) {
                return times;
            }
            times++;
            from = at + needle.length();
        }
        return times;
    }

    private static String splice(String content, List<Hit> hits, int origin) {
        String result = content;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            int at = hit.matchIndex - origin;
            result = result.substring(0, at) + hit.newText
                    + result.substring(at + hit.matchLength);
        }
        return result;
    }

    /** 只重写碰到的行，其余行保持原文，避免放宽匹配时把没改的空白也洗掉。 */
    private static String preserve(String original, String base, List<Hit> hits) {
        List<String> originalLines = keepEndings(original);
        int[] start = new int[lineCount(base)];
        int[] end = new int[start.length];
        fillSpans(base, start, end);
        if (originalLines.size() != start.length) {
            throw new IllegalArgumentException("放宽匹配后行数对不上，没有写入。");
        }

        List<int[]> groups = new ArrayList<int[]>();
        List<List<Hit>> grouped = new ArrayList<List<Hit>>();
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            int[] range = lineRange(start, end, hit);
            if (groups.size() > 0) {
                int[] current = groups.get(groups.size() - 1);
                if (range[0] < current[1]) {
                    if (range[1] > current[1]) {
                        current[1] = range[1];
                    }
                    grouped.get(grouped.size() - 1).add(hit);
                    continue;
                }
            }
            groups.add(range);
            List<Hit> bucket = new ArrayList<Hit>();
            bucket.add(hit);
            grouped.add(bucket);
        }

        StringBuilder result = new StringBuilder();
        int line = 0;
        for (int g = 0; g < groups.size(); g++) {
            int[] range = groups.get(g);
            for (int i = line; i < range[0]; i++) {
                result.append(originalLines.get(i));
            }
            int from = start[range[0]];
            int to = end[range[1] - 1];
            result.append(splice(base.substring(from, to), grouped.get(g), from));
            line = range[1];
        }
        for (int i = line; i < originalLines.size(); i++) {
            result.append(originalLines.get(i));
        }
        return result.toString();
    }

    private static int lineCount(String content) {
        return keepEndings(content).size();
    }

    private static void fillSpans(String content, int[] start, int[] end) {
        List<String> lines = keepEndings(content);
        int offset = 0;
        for (int i = 0; i < lines.size(); i++) {
            start[i] = offset;
            offset += lines.get(i).length();
            end[i] = offset;
        }
    }

    /** 返回 [起始行, 结束行)，结束行不含。 */
    private static int[] lineRange(int[] start, int[] end, Hit hit) {
        int from = hit.matchIndex;
        int to = hit.matchIndex + hit.matchLength;
        int startLine = -1;
        for (int i = 0; i < start.length; i++) {
            if (from >= start[i] && from < end[i]) {
                startLine = i;
                break;
            }
        }
        if (startLine < 0) {
            throw new IllegalArgumentException("替换范围超出原文。");
        }
        int endLine = startLine;
        while (endLine < end.length && end[endLine] < to) {
            endLine++;
        }
        if (endLine >= end.length) {
            throw new IllegalArgumentException("替换范围超出原文。");
        }
        return new int[]{startLine, endLine + 1};
    }

    private static List<String> keepEndings(String content) {
        List<String> lines = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines.add(content.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < content.length()) {
            lines.add(content.substring(start));
        }
        return lines;
    }

    private static String fuzzy(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        String[] lines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(trimEnd(lines[i]));
        }
        return fold(sb.toString());
    }

    private static String trimEnd(String line) {
        int end = line.length();
        while (end > 0 && isTrimmed(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    private static boolean isTrimmed(char c) {
        return c <= ' ' || c == '\u00A0' || (c >= '\u2000' && c <= '\u200A')
                || c == '\u202F' || c == '\u205F' || c == '\u3000';
    }

    private static String fold(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u2018' && c <= '\u201B') {
                sb.append('\'');
            } else if (c >= '\u201C' && c <= '\u201F') {
                sb.append('"');
            } else if ((c >= '\u2010' && c <= '\u2015') || c == '\u2212') {
                sb.append('-');
            } else if (c == '\u00A0' || (c >= '\u2002' && c <= '\u200A')
                    || c == '\u202F' || c == '\u205F' || c == '\u3000') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String emptyError(String path, int index, int total) {
        if (total == 1) {
            return path + " 的 oldText 不能为空。";
        }
        return path + " 的 edits[" + index + "].oldText 不能为空。";
    }

    private static String missingError(String path, int index, int total) {
        if (total == 1) {
            return "在 " + path + " 里找不到这段原文。oldText 必须和原文一致，包括空格和换行。";
        }
        return "在 " + path + " 里找不到 edits[" + index
                + "]。oldText 必须和原文一致，包括空格和换行。";
    }

    private static String duplicateError(String path, int index, int total, int times) {
        if (total == 1) {
            return "这段文本在 " + path + " 里出现了 " + times
                    + " 次，必须唯一。请补上更多上下文。";
        }
        return "edits[" + index + "] 在 " + path + " 里出现了 " + times
                + " 次，必须唯一。请补上更多上下文。";
    }

    private static String sameError(String path, int total) {
        if (total == 1) {
            return "没有改动 " + path + "。替换结果和原文一样。";
        }
        return "没有改动 " + path + "。这些替换的结果和原文一样。";
    }
}