package com.mkei.backcast.tool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

/** Filename lookup stays within authorized roots and never reads a candidate. */
public final class FileSearchRegressionTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JSONObject run(FindFilesTool tool, String name) throws Exception {
        return new JSONObject(tool.run(new JSONObject().put("name", name)));
    }
    private static boolean has(JSONArray array, String suffix) throws Exception {
        for (int i = 0; i < array.length(); i++) if (array.getJSONObject(i).getString("path").endsWith(suffix)) return true;
        return false;
    }
    private static void fuzzyReadReturnsCandidatesWithoutContents() throws Exception {
        File root = Files.createTempDirectory("backcast-fuzzy-read-").toFile();
        try {
            File prefix = new File(root, "config-deployment.json"), contains = new File(root, "production-servers.json");
            Files.write(prefix.toPath(), "prefix-sensitive-fixture".getBytes(StandardCharsets.UTF_8));
            Files.write(contains.toPath(), "contains-sensitive-fixture".getBytes(StandardCharsets.UTF_8));
            ReadTool reader = new ReadTool(root.getPath(), false, null);
            for (String[] query : new String[][]{{"config", "prefix", prefix.getName(), "prefix-sensitive-fixture"},
                    {"servers", "contains", contains.getName(), "contains-sensitive-fixture"}}) {
                String response = reader.run(new JSONObject().put("path", query[0]));
                check(response.startsWith("错误：") && response.contains(query[2]) && !response.contains(query[3]),
                        "A single approximate match was silently read: " + response);
                JSONObject result = new JSONObject(response.substring(response.indexOf('\n') + 1));
                check(result.getBoolean("complete") && result.getJSONArray("matches").length() == 1
                        && result.getJSONArray("matches").getJSONObject(0).getString("match").equals(query[1]),
                        "Approximate candidate was omitted or misclassified");
            }
            check(reader.run(new JSONObject().put("path", prefix.getPath())).contains("prefix-sensitive-fixture"),
                    "Explicit candidate path could no longer be read");
        } finally { delete(root); }
    }
    private static void defaultSearchAndReadStayInPrimaryRoot() throws Exception {
        File fixture = Files.createTempDirectory("backcast-search-roots-").toFile();
        TemporaryWorkspace temporary = null;
        try {
            File primary = new File(fixture, "empty-project"), extra = new File(fixture, "operations");
            check(primary.mkdir() && extra.mkdir(), "multi-root fixture directories");
            File config = new File(extra, "servers.json");
            Files.write(config.toPath(), "other-project-sensitive-fixture".getBytes(StandardCharsets.UTF_8));
            temporary = new TemporaryWorkspace(primary.getPath(), false, new File(fixture, "private"), 1);
            temporary.configureWorkDirs(Arrays.asList(primary.getPath(), extra.getPath()));
            temporary.beginTurn();
            FindFilesTool finder = new FindFilesTool(primary.getPath(), false, temporary);
            JSONObject result = run(finder, "servers");
            check(result.getBoolean("complete") && result.getJSONArray("matches").length() == 0,
                    "Default search expanded from an empty primary project into an additional root");
            ReadTool reader = new ReadTool(primary.getPath(), false, temporary);
            String response = reader.run(new JSONObject().put("path", "servers"));
            check(response.startsWith("错误：") && !response.contains(config.getPath())
                    && !response.contains("other-project-sensitive-fixture"), "Read fallback searched another configured root");
            result = new JSONObject(finder.run(new JSONObject().put("name", "servers").put("directory", extra.getPath())));
            check(result.getBoolean("complete") && result.getJSONArray("matches").length() == 1
                    && has(result.getJSONArray("matches"), "/operations/servers.json"), "Explicit additional-root filename search was lost");
            check(reader.run(new JSONObject().put("path", config.getPath())).contains("other-project-sensitive-fixture"),
                    "Explicit additional-root read was lost");
            check(primary.delete(), "Could not remove empty primary fixture");
            result = run(finder, "servers");
            check(!result.getBoolean("complete") && result.getJSONArray("matches").length() == 0,
                    "Unavailable primary directory caused search to expand to an additional root");
            response = reader.run(new JSONObject().put("path", "servers"));
            check(response.startsWith("错误：") && !response.contains(config.getPath())
                    && !response.contains("other-project-sensitive-fixture"), "Missing primary directory caused read to expand scope");
        } finally { if (temporary != null) temporary.finishTurn(); delete(fixture); }
    }
    private static void searchAndDescriptionsFollowCurrentTaskScope() throws Exception {
        File fixture = Files.createTempDirectory("backcast-search-task-").toFile();
        TemporaryWorkspace temporary = null;
        try {
            File project = new File(fixture, "project"), operations = new File(fixture, "operations");
            check(project.mkdir() && operations.mkdir(), "task-scope fixture directories");
            Files.write(new File(operations, "servers.json").toPath(), "outside-task-sensitive-fixture".getBytes(StandardCharsets.UTF_8));
            temporary = new TemporaryWorkspace(fixture.getPath(), false, new File(fixture, "private"), 1);
            WorkspaceRoots configured = new WorkspaceRoots(fixture.getPath(), null);
            FindFilesTool finder = new FindFilesTool(fixture.getPath(), false, temporary);
            ReadTool reader = new ReadTool(fixture.getPath(), false, temporary);
            EditTool editor = new EditTool(fixture.getPath(), false, temporary);
            temporary.beginTurn(configured.forTask(Collections.singletonList(project.getPath())));
            String expected = "本轮项目访问范围：[" + project.getPath() + "]";
            check(finder.description().contains(expected) && reader.description().contains(expected) && editor.description().contains(expected),
                    "Tool descriptions advertised configured storage instead of the active task root");
            check(run(finder, "servers").getJSONArray("matches").length() == 0, "Task-scoped default search visited sibling operations");
            String response = finder.run(new JSONObject().put("name", "servers").put("directory", operations.getPath()));
            check(response.startsWith("错误："), "Explicit directory broadened the human's task scope");
            temporary.finishTurn();
            temporary.beginTurn(configured.forTask(Arrays.asList(operations.getPath(), project.getPath())));
            expected = "本轮项目访问范围：[" + operations.getPath() + ", " + project.getPath() + "]";
            check(finder.description().contains(expected) && reader.description().contains(expected) && editor.description().contains(expected),
                    "Tool descriptions did not refresh for a newly authorized task scope");
            check(run(finder, "servers").getJSONArray("matches").length() == 1, "Default search ignored the current task's first root");
        } finally { if (temporary != null) temporary.finishTurn(); delete(fixture); }
    }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("backcast-file-search-").toFile();
        try {
            File nested = new File(root, "downloads/nested");
            check(nested.mkdirs(), "fixture directories");
            Files.write(new File(root, "BlackBox3.6.5_arm64-v8a.apk.1").toPath(), "binary\0payload".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(nested, "BlackBox3.6.5_arm64-v8a-src.txt").toPath(), "source".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(nested, "other.txt").toPath(), "other".getBytes(StandardCharsets.UTF_8));
            FindFilesTool tool = new FindFilesTool(root.getPath(), false, null);
            JSONObject result = run(tool, "BlackBox");
            check(result.getBoolean("complete") && has(result.getJSONArray("matches"), "BlackBox3.6.5_arm64-v8a.apk.1")
                    && has(result.getJSONArray("matches"), "BlackBox3.6.5_arm64-v8a-src.txt"), "basename search missed versioned files");
            System.out.println("PASS filenameSearchFindsVersionedApkWithoutReading");
            result = run(tool, "BlackBox3.6.5_arm64-v8a.apk.1");
            check(result.getJSONArray("matches").length() == 1 && result.getJSONArray("matches").getJSONObject(0).getString("match").equals("exact"), "exact match was not ranked first");
            System.out.println("PASS exactFilenameMatchIsUnambiguous");
            result = run(tool, "BlackBox3.6.5_arm64-v8a-src");
            check(result.getJSONArray("matches").length() == 1 && result.getJSONArray("matches").getJSONObject(0).getString("path").endsWith(".txt"), "extensionless basename lookup failed");
            System.out.println("PASS extensionlessBasenameResolvesUniqueFile");
            String read = new ReadTool(root.getPath(), false, null).run(new JSONObject().put("path", "other"));
            check(read.startsWith("[按文件名解析为：") && read.contains("other.txt") && read.contains("other"), "unique filename was not resolved before reading");
            System.out.println("PASS readResolvesAUniqueExtensionlessFilename");
            read = new ReadTool(root.getPath(), false, null).run(new JSONObject().put("path", "other.txt"));
            check(read.startsWith("[按文件名解析为：") && read.contains(new File(nested, "other.txt").getPath()),
                    "Unique exact filename in a nested directory did not resolve before reading");
            System.out.println("PASS readResolvesAUniqueExactFilename");
            Files.write(new File(root, "other.md").toPath(), "do not guess this".getBytes(StandardCharsets.UTF_8));
            read = new ReadTool(root.getPath(), false, null).run(new JSONObject().put("path", "other"));
            check(read.startsWith("错误：") && read.contains("other.md") && read.contains("other.txt")
                    && !read.contains("do not guess this"), "ambiguous filename was read without choosing");
            System.out.println("PASS ambiguousReadReturnsCandidatesWithoutContents");
            result = new JSONObject(tool.run(new JSONObject().put("name", "missing-name").put("max_depth", 0)));
            check(result.getJSONArray("matches").length() == 0 && !result.getBoolean("complete"), "depth limit did not report incomplete search");
            System.out.println("PASS searchDepthLimitIsExplicit");
            File outside = Files.createTempDirectory("backcast-file-search-outside-").toFile();
            try {
                String response = tool.run(new JSONObject().put("name", "other").put("directory", outside.getPath()));
                check(response.startsWith("错误：") && response.contains("路径超出工作目录"), "outside search escaped root: " + response);
                Files.createSymbolicLink(new File(root, "linked").toPath(), outside.toPath());
                result = run(tool, "other");
                check(result.getBoolean("complete") && !has(result.getJSONArray("matches"), "/linked/other.txt"), "search followed a symlink");
                Files.delete(new File(root, "linked").toPath());
            } finally { Files.deleteIfExists(outside.toPath()); }
            System.out.println("PASS searchCannotEscapeAuthorizedRoot");
            fuzzyReadReturnsCandidatesWithoutContents();
            System.out.println("PASS fuzzyReadReturnsCandidatesWithoutContents");
            defaultSearchAndReadStayInPrimaryRoot();
            System.out.println("PASS defaultSearchAndReadStayInPrimaryRoot");
            searchAndDescriptionsFollowCurrentTaskScope();
            System.out.println("PASS searchAndDescriptionsFollowCurrentTaskScope");
            System.out.println("11 file search tests passed");
        } finally {
            delete(root);
        }
    }
    private static void delete(File file) throws Exception {
        if (!Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        Files.deleteIfExists(file.toPath());
    }
}
