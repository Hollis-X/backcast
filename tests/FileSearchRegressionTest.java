package com.mkei.backcast.tool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
            System.out.println("7 file search tests passed");
        } finally {
            delete(root);
        }
    }
    private static void delete(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        Files.deleteIfExists(file.toPath());
    }
}
