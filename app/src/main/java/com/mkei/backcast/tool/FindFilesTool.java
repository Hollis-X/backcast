package com.mkei.backcast.tool;

import com.mkei.backcast.agent.Tool;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Filename lookup over the current turn's authorized roots, without reading any contents. */
public final class FindFilesTool implements Tool {
    private static final int MAX_ENTRIES = 20000, MAX_RESULTS = 100, MAX_OUTPUT = 48000;
    private static final long MAX_TIME = TimeUnit.SECONDS.toNanos(20);
    private final String workDir;
    private final boolean useRoot;
    private final TemporaryWorkspace temporary;
    private volatile int epoch;

    public FindFilesTool(String workDir, boolean useRoot, TemporaryWorkspace temporary) {
        this.workDir = workDir; this.useRoot = useRoot; this.temporary = temporary;
    }

    @Override public String name() { return "find_files"; }

    @Override public String description() {
        return "按文件名搜索本轮主项目目录，返回文件的绝对路径，不读取内容。"
                + "默认只搜索本轮第一个项目目录；只有明确指定 directory 才搜索其他本轮允许的目录，不因未找到文件而扩大范围。"
                + "用户只给名字或省略扩展名时先调用它，例如 BlackBox 可找到 BlackBox3.6.5_arm64-v8a.apk.1。"
                + "优先匹配完整文件名与无扩展名的基名，再返回忽略大小写的前缀/包含匹配。"
                + "有多个候选不要猜，结合用户指定目录或让用户选择后再 read/edit；写新文件仍须指定目标路径。"
                + "不跟随符号链接、不访问未授权目录或其他会话临时目录。root 开启时可遍历 App 无权读取的项目目录。"
                + "结果 complete=false 表示扫描受权限、深度、数量、时间或输出限制，不能据此断言文件不存在。"
                + ToolPaths.workspaceDescription(workDir, temporary);
    }

    @Override public JSONObject parameters() {
        try {
            JSONObject props = new JSONObject()
                    .put("name", new JSONObject().put("type", "string").put("description", "完整文件名、无扩展名基名或文件名的一部分；不填路径或通配符"))
                    .put("directory", new JSONObject().put("type", "string").put("description", "可选，明确选择一个本轮允许的目录；不填时只搜索本轮主项目目录，相对路径按该主目录解析"))
                    .put("limit", new JSONObject().put("type", "integer").put("minimum", 1).put("maximum", MAX_RESULTS).put("description", "最多返回多少个候选，默认40，上限100"))
                    .put("max_depth", new JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 64).put("description", "从授权根或directory向下搜索的最大目录层数，默认16；0只找该目录直接文件"));
            return new JSONObject().put("type", "object").put("properties", props).put("required", new JSONArray().put("name"));
        } catch (Exception failure) { return new JSONObject(); }
    }

    @Override public void abort() { epoch++; }

    @Override public String run(JSONObject args) throws Exception {
        final int mine = epoch;
        ToolchainInstaller.Cancellation cancellation = new ToolchainInstaller.Cancellation() {
            @Override public void check() throws Exception {
                if (epoch != mine || Thread.currentThread().isInterrupted()) throw new InterruptedException("已停止。");
            }
        };
        try {
            int limit = number(args, "limit", 40, MAX_RESULTS), depth = number(args, "max_depth", 16, 64);
            if (limit < 1 || depth < 0) return "错误：limit 或 max_depth 不合法。";
            return search(args.optString("name", ""), args.optString("directory", ""), limit, depth, cancellation).json().toString();
        } catch (InterruptedException stopped) { return "已停止。"; }
        catch (IllegalArgumentException invalid) { return "错误：" + invalid.getMessage(); }
    }

    private static int number(JSONObject args, String key, int fallback, int maximum) {
        if (!args.has(key)) return fallback;
        Object value = args.opt(key);
        if (!(value instanceof Number)) return -1;
        double raw = ((Number) value).doubleValue();
        return Double.isNaN(raw) || Double.isInfinite(raw) || raw != Math.rint(raw) || raw < 0 || raw > maximum ? -1 : (int) raw;
    }

    static final class Match {
        final File file;
        final int rank;
        Match(File file, int rank) { this.file = file; this.rank = rank; }
    }

    static final class Search {
        final List<Match> matches = new ArrayList<Match>();
        final JSONArray skipped = new JSONArray();
        boolean complete = true;
        int visited;
        String limitedBy = "";
        JSONObject json() throws Exception {
            JSONArray found = new JSONArray();
            for (Match match : matches) found.put(new JSONObject().put("path", match.file.getPath())
                    .put("name", match.file.getName()).put("match", new String[]{"exact", "basename", "prefix", "contains"}[match.rank]));
            return new JSONObject().put("matches", found).put("complete", complete).put("scanned_entries", visited)
                    .put("limited_by", limitedBy).put("skipped_directories", skipped);
        }
    }

    private static final class Visit {
        final File directory;
        final int depth;
        final boolean rooted;
        Visit(File directory, int depth, boolean rooted) { this.directory = directory; this.depth = depth; this.rooted = rooted; }
    }

    private static final class SearchTimeout extends RuntimeException { }

    Search search(String name, String directory, int limit, int maxDepth, ToolchainInstaller.Cancellation cancellation) throws Exception {
        if (name == null || name.trim().length() == 0 || name.length() > 255 || name.indexOf('/') >= 0
                || name.indexOf('\0') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0)
            throw new IllegalArgumentException("name 必须是文件名或文件名的一部分，不是路径。");
        Search result = new Search();
        List<File> roots = ToolPaths.searchRoots(workDir, temporary);
        if (roots.isEmpty() && (directory == null || directory.isEmpty()))
            throw new IllegalArgumentException("本轮没有可用的任务默认目录，请明确指定已授权目录。");
        final long started = System.nanoTime();
        final ToolchainInstaller.Cancellation caller = cancellation;
        ToolchainInstaller.Cancellation bounded = new ToolchainInstaller.Cancellation() {
            @Override public void check() throws Exception {
                caller.check();
                if (System.nanoTime() - started >= MAX_TIME) throw new SearchTimeout();
            }
        };
        ArrayDeque<Visit> queue = new ArrayDeque<Visit>();
        if (directory != null && !directory.isEmpty()) queue.add(new Visit(
                ToolPaths.resolve(workDir, directory, temporary, useRoot, bounded), 0, useRoot));
        else queue.add(new Visit(roots.get(0), 0, useRoot));
        Set<String> seenDirectories = new HashSet<String>(), seenFiles = new HashSet<String>();
        try {
        while (!queue.isEmpty()) {
            bounded.check();
            Visit visit = queue.removeFirst();
            File current;
            List<ToolPaths.DirectoryEntry> entries;
            try {
                current = ToolPaths.resolve(workDir, visit.directory.getPath(), temporary, visit.rooted, bounded);
                if (!seenDirectories.add(current.getPath())) continue;
                entries = ToolPaths.listDirectory(current, useRoot, bounded);
            } catch (IllegalArgumentException | java.io.IOException denied) {
                result.complete = false;
                if (result.skipped.length() < 20) result.skipped.put(visit.directory.getPath());
                continue;
            }
            for (ToolPaths.DirectoryEntry entry : entries) {
                bounded.check();
                if (++result.visited > MAX_ENTRIES) { result.complete = false; result.limitedBy = "entries"; queue.clear(); break; }
                File child;
                try {
                    child = entry.rooted
                            ? ToolPaths.resolve(workDir, entry.file.getPath(), temporary, true, bounded)
                            : ToolPaths.resolve(workDir, entry.file.getPath(), temporary);
                }
                catch (IllegalArgumentException protectedPath) { continue; }
                if (entry.directory) {
                    if (visit.depth < maxDepth) queue.addLast(new Visit(child, visit.depth + 1, entry.rooted));
                    else { result.complete = false; if (result.limitedBy.isEmpty()) result.limitedBy = "depth"; }
                    continue;
                }
                int rank = rank(child.getName(), name);
                if (rank < 0 || !seenFiles.add(child.getPath())) continue;
                if (entry.rooted) {
                    try { child = ToolPaths.resolve(workDir, child.getPath(), temporary, true, bounded); }
                    catch (IllegalArgumentException | java.io.IOException denied) { result.complete = false; continue; }
                }
                result.matches.add(new Match(child, rank));
            }
        }
        } catch (SearchTimeout exhausted) { result.complete = false; result.limitedBy = "time"; }
        Collections.sort(result.matches, new Comparator<Match>() {
            @Override public int compare(Match a, Match b) { return a.rank == b.rank ? a.file.getPath().compareTo(b.file.getPath()) : a.rank - b.rank; }
        });
        if (result.matches.size() > limit) {
            result.matches.subList(limit, result.matches.size()).clear();
            result.complete = false; result.limitedBy = "results";
        }
        while (result.json().toString().length() > MAX_OUTPUT && !result.matches.isEmpty()) {
            result.matches.remove(result.matches.size() - 1); result.complete = false; result.limitedBy = "output";
        }
        while (result.json().toString().length() > MAX_OUTPUT && result.skipped.length() > 0) {
            result.skipped.remove(result.skipped.length() - 1); result.complete = false; result.limitedBy = "output";
        }
        cancellation.check();
        return result;
    }

    private static int rank(String file, String query) {
        if (file.equals(query)) return 0;
        int dot = file.lastIndexOf('.');
        if (dot > 0 && file.substring(0, dot).equals(query)) return 1;
        String lower = file.toLowerCase(Locale.ROOT), wanted = query.toLowerCase(Locale.ROOT);
        if (lower.startsWith(wanted)) return 2;
        return lower.contains(wanted) ? 3 : -1;
    }
}
