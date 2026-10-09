package com.mkei.backcast.tool;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Real process groups, PPID discovery without children files, and PID identity checks. */
public final class ProcessIsolationRegressionTest {
    private static int passed;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void pass(String name) { System.out.println("PASS " + name); passed++; }

    private static void confirmedStopIsReusableWithoutProcOrRemainingTime() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "sleep 30 & wait").start();
        ProcessTree tree = new ProcessTree(process, false);
        try {
            Thread.sleep(60);
            check(tree.stop(), "Initial real tree did not stop");
            check(!process.isAlive(), "Stopped launcher was not reaped");
            Thread.currentThread().interrupt();
            long began = System.nanoTime();
            check(tree.stop(System.nanoTime() - 1), "Previously confirmed stop was invalidated by a spent cleanup budget");
            check(Thread.currentThread().isInterrupted(), "Reusing a confirmed stop swallowed cancellation");
            check(System.nanoTime() - began < 100000000L, "Confirmed stop rescanned or signalled the old tree");
            pass("confirmedStopReusesEvidenceAfterCancellation");
        } finally { Thread.interrupted(); process.destroyForcibly(); }
    }

    private static void cleanupIgnoresAndRestoresInterrupt() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "sleep 30 & wait").start();
        ProcessTree tree = new ProcessTree(process, false);
        try {
            Thread.sleep(60);
            Thread.currentThread().interrupt();
            check(tree.stop(System.nanoTime() + 2000000000L), "An interrupted owner could not stop its real descendants");
            check(Thread.currentThread().isInterrupted() && !process.isAlive(), "Cleanup lost cancellation or left its owner alive");
            pass("cleanupStopsRealTreeWhilePreservingInterrupt");
        } finally { Thread.interrupted(); process.destroyForcibly(); }
    }

    private static void repeatedShellKillsShareLauncherWaitDeadline() throws Exception {
        final Process raw = new ProcessBuilder("sh", "-c", "sleep 30").start();
        final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        // Model a Java reaper that has not acknowledged the actual launcher's
        // exit. Signals still target and stop a real OS process by its identity.
        Process stalled = new Process() {
            public long pid() { return raw.pid(); }
            public java.io.InputStream getInputStream() { return raw.getInputStream(); }
            public java.io.InputStream getErrorStream() { return raw.getErrorStream(); }
            public java.io.OutputStream getOutputStream() { return raw.getOutputStream(); }
            public int waitFor() throws InterruptedException { return raw.waitFor(); }
            public int exitValue() { if (!released.get()) throw new IllegalThreadStateException("Reaper pending"); return raw.exitValue(); }
            public boolean isAlive() { return !released.get() || raw.isAlive(); }
            public void destroy() { raw.destroy(); }
            public Process destroyForcibly() { raw.destroyForcibly(); return this; }
        };
        final ProcessTree tree = new ProcessTree(stalled, false);
        final ShellTool shell = new ShellTool(false, ".", null);
        Field processField = ShellTool.class.getDeclaredField("running"); processField.setAccessible(true); processField.set(shell, stalled);
        Field treeField = ShellTool.class.getDeclaredField("runningTree"); treeField.setAccessible(true); treeField.set(shell, tree);
        Method kill = ShellTool.class.getDeclaredMethod("kill", Process.class); kill.setAccessible(true);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try { kill.invoke(shell, stalled); kill.invoke(shell, stalled); }
            catch (Throwable error) { failure.set(error); }
        });
        try {
            check(!tree.stop(System.nanoTime() + 200000000L), "Stalled launcher reaper was incorrectly confirmed exited");
            check(raw.waitFor(1, java.util.concurrent.TimeUnit.SECONDS), "Cleanup did not stop the actual launcher");
            worker.start(); worker.join(1500);
            check(!worker.isAlive() && failure.get() == null, "Repeated ShellTool kills restarted the launcher wait budget");
            pass("repeatedShellKillsReuseLauncherReaperDeadline");
        } finally { released.set(true); raw.destroyForcibly(); worker.join(2000); }
    }
    private static void remove(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        Files.deleteIfExists(file.toPath());
    }
    private static String writer() {
        return "(while :; do mkdir -p leaked; touch leaked/alive; sleep 0.05; done) & ";
    }
    private static Thread command(final ShellTool shell, final String command, final AtomicReference<String> result) {
        Thread worker = new Thread(() -> {
            try { result.set(shell.run(new JSONObject().put("command", command))); }
            catch (Exception failed) { result.set("exception:" + failed); }
        });
        worker.start();
        return worker;
    }
    private static void awaitFile(File file) throws Exception {
        long until = System.nanoTime() + 5000000000L;
        while (!file.isFile() && System.nanoTime() - until < 0) Thread.sleep(20);
        check(file.isFile(), "Command did not reach its marker: " + file);
    }
    private static void siblingIsolation(File project) throws Exception {
        String appStat = new String(Files.readAllBytes(new File("/proc/self/stat").toPath()), "UTF-8");
        String appPid = appStat.substring(0, appStat.indexOf(' '));
        String[] appFields = appStat.substring(appStat.lastIndexOf(')') + 1).trim().split("\\s+");
        check(appPid.equals(appFields[2]) && appPid.equals(appFields[3]),
                "Sibling fixture JVM was not isolated from the test runner");
        ShellTool sibling = new ShellTool(false, project.getPath(), null);
        ShellTool current = new ShellTool(false, project.getPath(), null);
        AtomicReference<String> siblingResult = new AtomicReference<String>();
        AtomicReference<String> cancelledResult = new AtomicReference<String>();
        Thread other = command(sibling, "touch sibling.ready; "
                + "while [ ! -f release ]; do sleep 0.02; done; printf sibling-complete", siblingResult);
        Thread cancelled = null;
        try {
            awaitFile(new File(project, "sibling.ready"));
            String normal = current.run(new JSONObject().put("command", "printf foreground-complete"));
            check(normal.startsWith("exit=0") && other.isAlive(),
                    "Normal cleanup killed the sibling command: " + siblingResult.get());
            cancelled = command(current, "touch cancelled.ready; sleep 20", cancelledResult);
            awaitFile(new File(project, "cancelled.ready"));
            current.abort();
            cancelled.join(5000);
            check(!cancelled.isAlive() && cancelledResult.get().contains("已停止"),
                    "Cancellation did not stop its own command: " + cancelledResult.get());
            check(other.isAlive(), "Cancelling one command killed its sibling: " + siblingResult.get());
            Files.write(new File(project, "release").toPath(), new byte[0]);
            other.join(5000);
            check(!other.isAlive() && siblingResult.get().startsWith("exit=0")
                    && siblingResult.get().contains("sibling-complete"),
                    "Sibling did not complete after unrelated cleanup: " + siblingResult.get());
        } finally {
            current.abort(); sibling.abort();
            if (cancelled != null) cancelled.join(5000);
            other.join(5000);
        }
    }
    private static void runIsolatedSiblingFixture(File project, boolean sharedGroup) throws Exception {
        File fixture = new File(project, sharedGroup ? "shared-group" : "isolated-group");
        check(fixture.mkdir(), "Cannot create sibling isolation fixture");
        File sessionLauncher = null;
        for (String entry : System.getenv("PATH").split(File.pathSeparator)) {
            File candidate = new File(entry, "setsid");
            if (candidate.isFile() && candidate.canExecute()) { sessionLauncher = candidate; break; }
        }
        check(sessionLauncher != null, "No isolated session launcher for sibling fixture");
        ProcessBuilder child = new ProcessBuilder(sessionLauncher.getAbsolutePath(), new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-cp", System.getProperty("java.class.path"), ProcessIsolationRegressionTest.class.getName(),
                "sibling", fixture.getPath());
        if (sharedGroup) {
            File bin = new File(fixture, "bin"); check(bin.mkdir(), "Cannot create fallback launcher directory");
            File setsid = new File(bin, "setsid");
            Files.write(setsid.toPath(), "#!/bin/sh\nexec \"$@\"\n".getBytes("UTF-8"));
            check(setsid.setExecutable(true), "Cannot create non-isolating setsid fixture");
            child.environment().put("PATH", bin.getPath() + ":" + System.getenv("PATH"));
        }
        // The test JVM acts as the App. Isolate it from the runner so a broken
        // inherited-group signal fails this fixture without killing other tests.
        Process process = child.inheritIO().start();
        try {
            check(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0,
                    "App/sibling isolation failed (shared group=" + sharedGroup + ")");
        } finally { process.destroyForcibly(); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "sibling".equals(args[0])) {
            siblingIsolation(new File(args[1]));
            return;
        }
        File project = Files.createTempDirectory("backcast-process-test-").toFile();
        try {
            ShellTool shell = new ShellTool(false, project.getPath(), null);
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
            final ShellTool cancelled = new ShellTool(false, project.getPath(), null);
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
            runIsolatedSiblingFixture(project, false);
            pass("isolatedCommandsPreserveAppAndSibling");
            runIsolatedSiblingFixture(project, true);
            pass("sharedGroupFallbackPreservesAppAndSibling");
            confirmedStopIsReusableWithoutProcOrRemainingTime();
            cleanupIgnoresAndRestoresInterrupt();
            repeatedShellKillsShareLauncherWaitDeadline();
            System.out.println("Process isolation regression: " + passed + " passed");
        } finally { remove(project); }
    }
}
