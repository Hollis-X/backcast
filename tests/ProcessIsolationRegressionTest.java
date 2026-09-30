package com.mkei.backcast.tool;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Map;
import org.json.JSONObject;

/** Real process groups, PPID discovery without children files, and PID identity checks. */
public final class ProcessIsolationRegressionTest {
    private static int passed;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void pass(String name) { System.out.println("PASS " + name); passed++; }
    private static void remove(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        Files.deleteIfExists(file.toPath());
    }
    private static String writer() {
        return "(while :; do mkdir -p leaked; touch leaked/alive; sleep 0.05; done) & ";
    }
    public static void main(String[] args) throws Exception {
        File project = Files.createTempDirectory("backcast-process-test-").toFile();
        try {
            ShellTool shell = new ShellTool(false, project.getPath());
            String result = shell.run(new JSONObject().put("command", "printf '中文\\n'; exit 7"));
            check(result.startsWith("exit=7") && result.contains("中文"), "Supervisor lost command output/exit: " + result);
            pass("supervisorExitAndOutput");
            result = shell.run(new JSONObject().put("command", writer() + "sleep 0.1; printf done"));
            check(result.startsWith("exit=0") && result.contains("done"), "Normal completion failed: " + result);
            remove(new File(project, "leaked"));
            Thread.sleep(300);
            check(!new File(project, "leaked").exists(), "Normal completion background writer recreated directory");
            pass("normalCompletionKillsGroupBeforeCleanup");
            result = shell.run(new JSONObject().put("command", writer() + "sleep 20").put("timeout_sec", 1));
            check(result.contains("命令超时") && !result.startsWith("错误"), "Timeout did not confirm shutdown: " + result);
            remove(new File(project, "leaked"));
            Thread.sleep(300);
            check(!new File(project, "leaked").exists(), "Timeout background writer recreated directory");
            pass("timeoutKillsBackgroundWriter");
            final ShellTool cancelled = new ShellTool(false, project.getPath());
            final String[] answer = new String[1];
            Thread worker = new Thread(new Runnable() {
                public void run() { try { answer[0] = cancelled.run(new JSONObject().put("command", writer() + "sleep 20")); }
                    catch (Exception failed) { answer[0] = "exception:" + failed; } }
            });
            worker.start();
            long until = System.currentTimeMillis() + 4000;
            while (!new File(project, "leaked/alive").exists() && System.currentTimeMillis() < until) Thread.sleep(20);
            cancelled.abort(); worker.join(5000);
            check(!worker.isAlive() && answer[0].contains("已停止") && !answer[0].startsWith("错误"),
                    "Cancel shutdown failed: " + answer[0]);
            remove(new File(project, "leaked")); Thread.sleep(300);
            check(!new File(project, "leaked").exists(), "Cancelled writer recreated directory");
            pass("cancelKillsBackgroundWriter");
            Process orphan = new ProcessBuilder("setsid", "sh", "-c",
                    ShellTool.supervisorScript(writer() + "sleep 0.1", "test_pid:", "test_done:", "ack"))
                    .directory(project).start();
            java.io.BufferedReader output = new java.io.BufferedReader(new java.io.InputStreamReader(orphan.getInputStream(), "UTF-8"));
            try {
                check(output.readLine().startsWith("test_pid:"), "EOF fixture lacked handshake");
                orphan.getOutputStream().write("ack\n".getBytes("UTF-8"));
                orphan.getOutputStream().flush();
                String line;
                do { line = output.readLine(); } while (line != null && !line.startsWith("test_done:"));
                check(line != null, "EOF fixture lacked completion");
                orphan.getOutputStream().close();
                check(orphan.waitFor(3, java.util.concurrent.TimeUnit.SECONDS), "Supervisor leaked after owner stdin closed");
                remove(new File(project, "leaked")); Thread.sleep(300);
                check(!new File(project, "leaked").exists(), "EOF cleanup left background writer");
                pass("ownerPipeEofReapsIsolatedSupervisor");
            } finally { orphan.destroyForcibly(); output.close(); }
            Process process = new ProcessBuilder("sh", "-c", "sleep 30 & wait").start();
            ProcessTree tree = new ProcessTree(process, false);
            try {
                Thread.sleep(60); tree.sample();
                Field tracked = ProcessTree.class.getDeclaredField("known"); tracked.setAccessible(true);
                check(((Map<?, ?>)tracked.get(tree)).size() >= 2, "PPID stat fallback failed to discover child");
                pass("procStatFindsChildrenWithoutChildrenFiles");
                long pid = process.pid();
                String stat = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()), "UTF-8");
                int close = stat.lastIndexOf(')');
                String[] fields = stat.substring(close + 1).trim().split("\\s+");
                fields[19] = "0";
                StringBuilder wrong = new StringBuilder(stat.substring(0, close + 1));
                for (String value : fields) wrong.append(' ').append(value);
                check(!tree.observeSupervisor(wrong.toString()), "Reused PID/starttime was accepted");
                Method guard = ProcessTree.class.getDeclaredMethod("guard", Class.forName("com.mkei.backcast.tool.ProcessTree$Identity"), String.class);
                guard.setAccessible(true);
                Method parse = ProcessTree.class.getDeclaredMethod("parse", String.class); parse.setAccessible(true);
                String guarded = (String)guard.invoke(null, parse.invoke(null, stat), "kill -s STOP -- -" + pid);
                check(guarded.contains("$3") && guarded.contains("$4") && guarded.contains("shift 19"),
                        "Group signal lacks session/group/starttime checks");
                pass("reusedPidAndGroupIdentityProtected");
            } finally { check(tree.stop(), "Fallback process tree did not stop"); process.destroy(); }
            System.out.println("Process isolation regression: " + passed + " passed");
        } finally { remove(project); }
    }
}
