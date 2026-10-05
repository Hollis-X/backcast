package com.mkei.backcast.tool;

import com.mkei.backcast.agent.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.util.Locale;

/**
 * 读文本文件。超限时截断并告诉模型下一页的 offset，不整篇拒绝。
 */
public class ReadTool implements Tool {

    static final int MAX_LINES = 2000;
    static final int MAX_BYTES = 50 * 1024;
    /** 这个大小以内整篇载入，行号和末尾换行与分段规则一致。再大就逐行读。 */
    private static final int MAX_LOAD = 8 * 1024 * 1024;

    private final String workDir;
    private final boolean useRoot;
    private final TemporaryWorkspace temporary;
    private volatile int epoch;

    public ReadTool(String workDir, boolean useRoot, TemporaryWorkspace temporary) {
        this.workDir = workDir == null || workDir.length() == 0 ? null : workDir;
        this.useRoot = useRoot;
        this.temporary = temporary;
    }

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String description() {
        return "读取已授权工作目录内的文本文件，也可读取本轮 temporary 登记的 App 私有临时文件（使用返回的绝对路径）。相对路径按主工作目录解析。"
                + "目录外路径会被拒绝。"
                + "一次最多 " + MAX_LINES + " 行或 " + (MAX_BYTES / 1024)
                + "KB，以先到的为准，不截断半行。"
                + "大文件用 offset（从 1 开始的行号）和 limit 接着读，没读完就按结果里的 offset 继续。"
                + "应用自己读不了的路径会改用 root 读，不要把文件复制到临时目录再读。"
                + "只给文件名或省略扩展名时，原位置不存在会搜索所有授权目录；完整扫描只有一个候选才会明确显示解析路径后读取，重名返回候选，不猜文件。"
                + "不清楚名字或路径时先用 find_files，可搜索带版本号、不同大小写或扩展名的文件。"
                + "不要用 cat 或 sed 读文件。目录用 shell 的 ls，不要用这个工具。";
    }

    @Override
    public JSONObject parameters() {
        try {
            JSONObject path = new JSONObject();
            path.put("type", "string");
            path.put("description", "要读的文件，相对工作目录或绝对路径");

            JSONObject offset = new JSONObject();
            offset.put("type", "integer");
            offset.put("description", "从第几行开始，1 起算。不传就从第一行");

            JSONObject limit = new JSONObject();
            limit.put("type", "integer");
            limit.put("description", "最多读多少行。不传则直到行数或字节上限");

            JSONObject props = new JSONObject();
            props.put("path", path);
            props.put("offset", offset);
            props.put("limit", limit);

            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", new JSONArray().put("path"));
            return schema;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public void abort() {
        epoch++;
    }

    @Override
    public String run(JSONObject args) throws Exception {
        final int mine = epoch;
        ToolchainInstaller.Cancellation cancellation = new ToolchainInstaller.Cancellation() {
            @Override public void check() throws Exception {
                if (epoch != mine || Thread.currentThread().isInterrupted()) throw new InterruptedException("已停止。");
            }
        };
        String path = args.optString("path", "");
        if (path.length() == 0) {
            return "错误：path 为空。";
        }
        int offset = 0;
        if (args.has("offset") && !args.isNull("offset")) {
            offset = intArg(args, "offset");
            if (offset == Integer.MIN_VALUE) {
                return "错误：offset 要是整数。";
            }
        }
        int limit = -1;
        if (args.has("limit") && !args.isNull("limit")) {
            limit = intArg(args, "limit");
            if (limit == Integer.MIN_VALUE || limit < 1) {
                return "错误：limit 要大于 0。";
            }
        }
        try {
            File file = ToolPaths.resolve(workDir, path, temporary, useRoot, cancellation);
            ToolPaths.Probe probe = ToolPaths.probe(file, useRoot, cancellation);
            if (!probe.exists && !probe.denied && path.indexOf('/') < 0 && !".".equals(path) && !"..".equals(path)) {
                FindFilesTool.Search search = new FindFilesTool(workDir, useRoot, temporary).search(path, "", 8, 16, cancellation);
                if (search.complete && search.matches.size() == 1) {
                    file = ToolPaths.resolve(workDir, search.matches.get(0).file.getPath(), temporary, useRoot, cancellation);
                    return "[按文件名解析为：" + file.getPath() + "]\n" + readFile(file, file.getPath(), offset, limit, cancellation, useRoot);
                }
                return "错误：没有找到唯一可读取的文件。请用 find_files 缩小范围或选择候选绝对路径，不要猜路径。\n" + search.json();
            }
            return readFile(file, path, offset, limit, cancellation, useRoot);
        } catch (InterruptedException stopped) {
            return "已停止。";
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        }
    }

    private static String readFile(File file, String displayPath, int offset, int limit, ToolchainInstaller.Cancellation cancellation,
                                   boolean useRoot) throws Exception {
        cancellation.check();
        ToolPaths.Probe probe = ToolPaths.probe(file, useRoot, cancellation);
        if (!probe.exists) {
            if (probe.denied) {
                return "错误：没有权限读取：" + file.getAbsolutePath();
            }
            return "错误：不存在：" + file.getAbsolutePath();
        }
        if (probe.directory) {
            return "错误：这是目录，用 shell 的 ls 查看：" + file.getAbsolutePath();
        }
        if (isImage(file.getName())) {
            return "这是图片（" + extension(file.getName()) + "），不会把像素交给模型。";
        }
        if (probe.length <= MAX_LOAD) {
            byte[] data = ToolPaths.readBytes(file, MAX_LOAD, useRoot, cancellation);
            if (containsNul(data)) {
                return "这是二进制文件，不按文本读。用 shell 查看。";
            }
            String text = decode(data);
            if (text.length() == 0) {
                return "(空文件)";
            }
            return formatText(text, displayPath, offset, limit);
        }
        if (ToolPaths.sniffNul(file, useRoot, cancellation)) {
            return "这是二进制文件，不按文本读。用 shell 查看。";
        }
        return formatStream(file, displayPath, offset, limit, cancellation, useRoot);
    }

    /** 小文件整篇载入后的分页。行数按换行切，末尾换行单独算一行。 */
    static String formatText(String text, String displayPath, int offset, int limit) {
        String[] allLines = text.split("\n", -1);
        int total = allLines.length;
        int start = offset > 0 ? offset - 1 : 0;
        if (start >= total) {
            return "错误：offset " + offset + " 超出文件末尾（共 " + total + " 行）。";
        }
        int end = total;
        int userLimited = -1;
        if (limit > 0) {
            end = Math.min(start + limit, total);
            userLimited = end - start;
        }
        String selected = join(allLines, start, end);
        Window window = truncateHead(selected);
        int startDisplay = start + 1;
        if (window.firstLineExceeds) {
            int size = utf8(allLines[start]);
            return hugeLine(startDisplay, size, displayPath);
        }
        if (window.truncated) {
            int endDisplay = startDisplay + window.outputLines - 1;
            return window.content + "\n\n" + pageNote(
                    startDisplay, endDisplay, total, endDisplay + 1, window.truncatedBy);
        }
        if (userLimited >= 0 && start + userLimited < total) {
            int next = start + userLimited + 1;
            int remaining = total - (start + userLimited);
            return window.content + "\n\n[还有 " + remaining + " 行。继续用 offset=" + next + "。]";
        }
        return window.content;
    }

    private static String formatStream(File file, String displayPath, int offset, int limit, ToolchainInstaller.Cancellation cancellation,
                                       boolean useRoot) throws Exception {
        boolean endsNl = ToolPaths.endsWithNewline(file, useRoot, cancellation);
        int start = offset > 0 ? offset - 1 : 0;
        BufferedReader reader = ToolPaths.openText(file, useRoot, cancellation);
        try {
            StringBuilder body = new StringBuilder();
            int collected = 0;
            int bodyBytes = 0;
            int seen = 0;
            boolean truncated = false;
            String truncatedBy = null;
            boolean firstExceeds = false;
            int firstBytes = 0;
            boolean pastUser = false;
            String line;
            while ((line = reader.readLine()) != null) {
                cancellation.check();
                if (seen == 0 && line.length() > 0 && line.charAt(0) == '\uFEFF') {
                    line = line.substring(1);
                }
                if (seen < start) {
                    seen++;
                    continue;
                }
                int inWindow = seen - start;
                if (limit > 0 && inWindow >= limit) {
                    pastUser = true;
                    seen++;
                    while ((line = reader.readLine()) != null) {
                        cancellation.check();
                        seen++;
                    }
                    break;
                }
                int lineBytes = utf8(line);
                if (collected == 0 && lineBytes > MAX_BYTES) {
                    firstExceeds = true;
                    firstBytes = lineBytes;
                    seen++;
                    while (reader.readLine() != null) {
                        cancellation.check();
                        seen++;
                    }
                    break;
                }
                int add = lineBytes + (collected > 0 ? 1 : 0);
                if (collected >= MAX_LINES || bodyBytes + add > MAX_BYTES) {
                    truncated = true;
                    truncatedBy = collected >= MAX_LINES ? "lines" : "bytes";
                    seen++;
                    while (reader.readLine() != null) {
                        cancellation.check();
                        seen++;
                    }
                    break;
                }
                if (collected > 0) {
                    body.append('\n');
                }
                body.append(line);
                bodyBytes += add;
                collected++;
                seen++;
            }
            if (seen == 0 && !endsNl) {
                return "(空文件)";
            }
            int total = endsNl ? seen + 1 : seen;
            if (start >= total) {
                return "错误：offset " + offset + " 超出文件末尾（共 " + total + " 行）。";
            }
            int startDisplay = start + 1;
            if (firstExceeds) {
                return hugeLine(startDisplay, firstBytes, displayPath);
            }
            boolean includePhantom = endsNl && !truncated && collected > 0
                    && start + collected == seen
                    && (limit < 0 || start + limit > seen);
            if (includePhantom) {
                body.append('\n');
            }
            if (truncated) {
                int endDisplay = startDisplay + collected - 1;
                return body + "\n\n" + pageNote(
                        startDisplay, endDisplay, total, endDisplay + 1, truncatedBy);
            }
            if (limit > 0 && start + collected < total) {
                int remaining = total - (start + collected);
                int next = start + collected + 1;
                if (pastUser || remaining > 0) {
                    return body + "\n\n[还有 " + remaining + " 行。继续用 offset=" + next + "。]";
                }
            }
            return body.toString();
        } finally {
            reader.close();
        }
    }

    private static String hugeLine(int lineNo, int size, String displayPath) {
        return "[第 " + lineNo + " 行有 " + formatSize(size)
                + "，超过 " + formatSize(MAX_BYTES)
                + "。用 shell：sed -n '" + lineNo + "p' "
                + shellQuote(displayPath) + " | head -c " + MAX_BYTES + "]";
    }

    private static String pageNote(int start, int end, int total, int next, String by) {
        if ("lines".equals(by)) {
            return "[显示第 " + start + "-" + end + " 行，共 " + total
                    + " 行。继续用 offset=" + next + "。]";
        }
        return "[显示第 " + start + "-" + end + " 行，共 " + total
                + " 行（" + formatSize(MAX_BYTES) + " 上限）。继续用 offset=" + next + "。]";
    }

    private static String join(String[] lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static Window truncateHead(String content) {
        int totalBytes = utf8(content);
        String[] lines = splitForCount(content);
        int totalLines = lines.length;
        if (totalLines <= MAX_LINES && totalBytes <= MAX_BYTES) {
            return new Window(content, false, null, totalLines, false);
        }
        if (lines.length == 0 || utf8(lines[0]) > MAX_BYTES) {
            return new Window("", true, "bytes", 0, true);
        }
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        int kept = 0;
        String by = "lines";
        for (int i = 0; i < lines.length && i < MAX_LINES; i++) {
            int lineBytes = utf8(lines[i]) + (i > 0 ? 1 : 0);
            if (bytes + lineBytes > MAX_BYTES) {
                by = "bytes";
                break;
            }
            if (i > 0) {
                out.append('\n');
            }
            out.append(lines[i]);
            bytes += lineBytes;
            kept++;
        }
        if (kept >= MAX_LINES && bytes <= MAX_BYTES) {
            by = "lines";
        }
        return new Window(out.toString(), true, by, kept, false);
    }

    private static String[] splitForCount(String content) {
        if (content.length() == 0) {
            return new String[0];
        }
        String[] lines = content.split("\n", -1);
        if (content.endsWith("\n") && lines.length > 0) {
            String[] cut = new String[lines.length - 1];
            System.arraycopy(lines, 0, cut, 0, cut.length);
            return cut;
        }
        return lines;
    }

    private static String decode(byte[] data) throws Exception {
        int off = 0;
        if (data.length >= 3
                && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            off = 3;
        }
        return new String(data, off, data.length - off, "UTF-8");
    }

    private static boolean containsNul(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".bmp");
    }

    private static String extension(String name) {
        int cut = name.lastIndexOf('.');
        if (cut < 0 || cut == name.length() - 1) {
            return "image";
        }
        return name.substring(cut + 1).toLowerCase(Locale.US);
    }

    private static int intArg(JSONObject args, String key) {
        Object value = args.opt(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    private static int utf8(String text) {
        try {
            return text.getBytes("UTF-8").length;
        } catch (Exception e) {
            return text.length();
        }
    }

    static String formatSize(int bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1fKB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private static String shellQuote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private static final class Window {
        final String content;
        final boolean truncated;
        final String truncatedBy;
        final int outputLines;
        final boolean firstLineExceeds;

        Window(String content, boolean truncated, String truncatedBy,
               int outputLines, boolean firstLineExceeds) {
            this.content = content;
            this.truncated = truncated;
            this.truncatedBy = truncatedBy;
            this.outputLines = outputLines;
            this.firstLineExceeds = firstLineExceeds;
        }
    }
}
