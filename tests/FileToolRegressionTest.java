import com.mkei.backcast.tool.EditTool;
import com.mkei.backcast.tool.ReadTool;
import com.mkei.backcast.tool.ShellTool;
import com.mkei.backcast.tool.WriteTool;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Checks file operations after removing the rollback stack. */
public final class FileToolRegressionTest {
    private static final String ERROR = "\u9519\u8bef\uff1a";
    private static int passed;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void pass(String name) {
        passed++;
        System.out.println("PASS " + name);
    }
    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), "UTF-8");
    }
    private static JSONObject write(String path, Object content) throws Exception {
        return new JSONObject().put("path", path).put("content", content);
    }
    private static JSONObject edit(String oldText, String newText) throws Exception {
        return new JSONObject().put("oldText", oldText).put("newText", newText);
    }
    private static JSONObject edits(String path, JSONObject... items) throws Exception {
        JSONArray edits = new JSONArray();
        for (JSONObject item : items) edits.put(item);
        return new JSONObject().put("path", path).put("edits", edits);
    }
    private static void remove(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        Files.deleteIfExists(file.toPath());
    }
    private static void run(File dir) throws Exception {
        WriteTool writer = new WriteTool(dir.getAbsolutePath(), false, null);
        EditTool editor = new EditTool(dir.getAbsolutePath(), false, null);
        File target = new File(dir, "nested/sample.txt");
        check(!writer.run(write("nested/sample.txt", "alpha beta gamma")).startsWith(ERROR), "Create failed");
        check(read(target).equals("alpha beta gamma"), "Create wrote incorrect content");
        check(!writer.run(write(target.getAbsolutePath(), "one two three")).startsWith(ERROR), "Overwrite failed");
        check(read(target).equals("one two three"), "Overwrite wrote incorrect content");
        check(Arrays.equals(dir.list(), new String[]{"nested"}) && target.getParentFile().list().length == 1,
                "File operations left extra backup files in the working directory");
        pass("writeCreatesAndOverwrites");

        check(writer.run(write("nested/sample.txt", Integer.valueOf(7))).startsWith(ERROR), "Non-string write was accepted");
        check(writer.run(write("nested", "invalid")).startsWith(ERROR), "Directory write was accepted");
        check(read(target).equals("one two three"), "Invalid write changed the file");
        pass("invalidWriteLeavesFileUnchanged");

        String result = editor.run(edits("nested/sample.txt", edit("one", "first"), edit("three", "last")));
        check(!result.startsWith(ERROR), "Multi-edit failed: " + result);
        check(read(target).equals("first two last"), "Multi-edit did not match the original file");
        pass("editAppliesMultipleOriginalMatches");

        result = editor.run(edits("nested/sample.txt", edit("missing", "bad")));
        check(result.startsWith(ERROR) && read(target).equals("first two last"), "Missing match wrote the file");
        result = editor.run(edits("nested/sample.txt", edit("first two", "bad"), edit("two last", "bad")));
        check(result.startsWith(ERROR) && read(target).equals("first two last"), "Overlapping edit wrote the file");
        result = editor.run(edits("nested/sample.txt", edit("first", "first")));
        check(result.startsWith(ERROR) && read(target).equals("first two last"), "No-op edit wrote the file");
        writer.run(write("nested/sample.txt", "same same"));
        result = editor.run(edits("nested/sample.txt", edit("same", "changed")));
        check(result.startsWith(ERROR) && read(target).equals("same same"), "Ambiguous edit wrote the file");
        pass("invalidEditsLeaveFileUnchanged");
    }

    /** 工作目录之外的路径必须被拒绝，不能靠绝对路径或 .. 绕出去。 */
    private static void escapesAreRefused(File dir) throws Exception {
        WriteTool writer = new WriteTool(dir.getAbsolutePath(), false, null);
        EditTool editor = new EditTool(dir.getAbsolutePath(), false, null);
        ReadTool reader = new ReadTool(dir.getAbsolutePath(), false, null);
        File outside = new File(dir.getParentFile(), "backcast-outside-" + dir.getName() + ".txt");

        check(writer.run(write(outside.getAbsolutePath(), "escaped")).startsWith(ERROR),
                "Write accepted a path outside the working directory");
        check(!outside.exists(), "Write created a file outside the working directory");
        check(writer.run(write("../" + outside.getName(), "escaped")).startsWith(ERROR),
                "Write accepted a parent-relative escape");
        check(editor.run(edits(outside.getAbsolutePath(), edit("a", "b"))).startsWith(ERROR),
                "Edit accepted a path outside the working directory");
        check(reader.run(new JSONObject().put("path", outside.getAbsolutePath()))
                .startsWith(ERROR), "Read accepted a path outside the working directory");
        pass("pathsOutsideWorkDirAreRefused");

        ShellTool shell = new ShellTool(false, dir.getAbsolutePath(), null);
        check(shell.run(new JSONObject().put("command", "echo hi > /tmp/backcast-escape.txt"))
                .startsWith(ERROR), "Shell redirect wrote outside the working directory");
        check(!new File("/tmp/backcast-escape.txt").exists(), "Shell created an outside file");
        check(shell.run(new JSONObject().put("command", "cd /tmp && ls")).startsWith(ERROR),
                "Shell changed into a directory outside the working directory");
        check(shell.run(new JSONObject().put("command", "sed -i s/a/b/ nested/sample.txt"))
                .startsWith(ERROR), "Shell rewrote a file in place");
        check(shell.run(new JSONObject().put("command", "ls nested")).startsWith("exit="),
                "Shell rejected an ordinary in-directory command");
        pass("shellRefusesWritesAndEscapes");
    }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("backcast-file-tests-").toFile();
        try {
            run(dir);
            escapesAreRefused(dir);
            System.out.println(passed + " file tests passed");
        } finally {
            remove(dir);
        }
    }
}
