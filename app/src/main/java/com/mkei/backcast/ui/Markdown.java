package com.mkei.backcast.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/**
 * 轻量 Markdown → Spannable。
 *
 * 只覆盖模型回复里常见的写法：标题、加粗、行内代码、代码块、列表、表格。
 * 解析不接触 View，可交给 MarkdownRenderQueue 在后台执行。
 */
public final class Markdown {

    private Markdown() {
    }

    public static CharSequence render(String src, int codeBg) {
        if (src == null || src.length() == 0) {
            return "";
        }
        String[] lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                i = appendFence(out, lines, i, codeBg);
                continue;
            }
            if (isTableLine(trimmed) && i + 1 < lines.length && isSeparator(lines[i + 1].trim())) {
                i = appendTable(out, lines, i, codeBg);
                continue;
            }
            if (trimmed.length() == 0) {
                appendBreak(out);
                i++;
                continue;
            }
            appendContentLine(out, line, codeBg);
            i++;
        }
        trimTrailingBreaks(out);
        return out;
    }

    private static void appendContentLine(SpannableStringBuilder out, String line, int codeBg) {
        String trimmed = line.trim();
        int start = out.length();

        if (trimmed.startsWith("#")) {
            int level = 0;
            while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                level++;
            }
            appendInline(out, trimmed.substring(level).trim(), codeBg);
            if (out.length() > start) {
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                float scale = level <= 1 ? 1.22f : level == 2 ? 1.12f : 1.05f;
                out.setSpan(new RelativeSizeSpan(scale), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } else if (isBullet(trimmed)) {
            out.append("• ");
            appendInline(out, trimmed.substring(bulletCut(trimmed)).trim(), codeBg);
        } else if (isOrdered(trimmed)) {
            int dot = trimmed.indexOf('.');
            out.append(trimmed.substring(0, dot + 1)).append(' ');
            appendInline(out, trimmed.substring(dot + 1).trim(), codeBg);
        } else {
            appendInline(out, trimmed, codeBg);
        }
        out.append('\n');
    }

    /** 代码块，返回下一行下标。 */
    private static int appendFence(SpannableStringBuilder out, String[] lines, int i, int codeBg) {
        StringBuilder body = new StringBuilder();
        i++;
        while (i < lines.length && !lines[i].trim().startsWith("```")) {
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(lines[i]);
            i++;
        }
        if (i < lines.length) {
            i++;
        }
        if (body.length() == 0) {
            return i;
        }
        int start = out.length();
        out.append(body);
        out.append('\n');
        // A ReplacementSpan is a single layout item; it cannot draw a multiline code block.
        out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(codeBg), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.92f), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return i;
    }

    /** 表格按「表头 + 单元格」折成可读的行，手机上不硬撑等宽网格。 */
    private static int appendTable(SpannableStringBuilder out, String[] lines, int i, int codeBg) {
        java.util.ArrayList<String> header = cells(lines[i].trim());
        i += 2;
        boolean any = false;
        while (i < lines.length && isTableLine(lines[i].trim())) {
            java.util.ArrayList<String> row = cells(lines[i].trim());
            for (int c = 0; c < row.size(); c++) {
                String cell = row.get(c);
                if (cell.length() == 0) {
                    continue;
                }
                any = true;
                if (c < header.size() && header.get(c).length() > 0) {
                    int a = out.length();
                    out.append(header.get(c));
                    out.setSpan(new StyleSpan(Typeface.BOLD), a, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.append("  ");
                }
                appendInline(out, cell, codeBg);
                out.append('\n');
            }
            i++;
        }
        if (any) {
            appendBreak(out);
        }
        return i;
    }

    private static void appendInline(SpannableStringBuilder out, String s, int codeBg) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '`') {
                int j = s.indexOf('`', i + 1);
                if (j > i) {
                    int a = out.length();
                    out.append(s.substring(i + 1, j));
                    styleCode(out, a, out.length(), codeBg);
                    i = j + 1;
                    continue;
                }
            }
            if (c == '*' && i + 1 < n && s.charAt(i + 1) == '*') {
                int j = s.indexOf("**", i + 2);
                if (j > i + 1) {
                    int a = out.length();
                    appendInline(out, s.substring(i + 2, j), codeBg);
                    if (out.length() > a) {
                        out.setSpan(new StyleSpan(Typeface.BOLD), a, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    i = j + 2;
                    continue;
                }
            }
            if (c == '_' && i + 1 < n && s.charAt(i + 1) == '_') {
                int j = s.indexOf("__", i + 2);
                if (j > i + 1) {
                    int a = out.length();
                    appendInline(out, s.substring(i + 2, j), codeBg);
                    if (out.length() > a) {
                        out.setSpan(new StyleSpan(Typeface.BOLD), a, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    i = j + 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
    }

    private static void styleCode(SpannableStringBuilder out, int start, int end, int codeBg) {
        if (end <= start) {
            return;
        }
        // Native spans preserve the characters and allow Android to wrap long code tokens.
        out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(codeBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static boolean isTableLine(String trimmed) {
        return trimmed.startsWith("|") && trimmed.indexOf('|', 1) > 0;
    }

    private static boolean isSeparator(String trimmed) {
        if (!isTableLine(trimmed) && trimmed.indexOf('|') < 0) {
            return false;
        }
        String body = trimmed;
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.indexOf('-') < 0) {
            return false;
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '|' && c != '-' && c != ':' && c != ' ') {
                return false;
            }
        }
        return true;
    }

    private static java.util.ArrayList<String> cells(String trimmed) {
        String body = trimmed;
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        String[] parts = body.split("\\|", -1);
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            out.add(parts[i].trim());
        }
        return out;
    }

    private static boolean isBullet(String trimmed) {
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            return true;
        }
        return trimmed.length() > 2 && trimmed.charAt(1) == ' '
                && (trimmed.charAt(0) == '-' || trimmed.charAt(0) == '*' || trimmed.charAt(0) == '+');
    }

    private static int bulletCut(String trimmed) {
        return 2;
    }

    private static boolean isOrdered(String trimmed) {
        int i = 0;
        while (i < trimmed.length() && trimmed.charAt(i) >= '0' && trimmed.charAt(i) <= '9') {
            i++;
        }
        return i > 0 && i < trimmed.length() && trimmed.charAt(i) == '.'
                && i + 1 < trimmed.length() && trimmed.charAt(i + 1) == ' ';
    }

    private static void appendBreak(SpannableStringBuilder out) {
        int n = out.length();
        if (n == 0) {
            return;
        }
        if (out.charAt(n - 1) != '\n') {
            out.append('\n');
        }
        n = out.length();
        if (n >= 2 && out.charAt(n - 2) == '\n') {
            return;
        }
        out.append('\n');
    }

    private static void trimTrailingBreaks(SpannableStringBuilder out) {
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
    }
}
