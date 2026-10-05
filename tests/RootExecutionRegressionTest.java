package com.mkei.backcast.tool;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Real wrapper forks and silent su reproduce Android launcher status failures. */
public final class RootExecutionRegressionTest {
    private static final ToolchainInstaller.Cancellation LIVE = new ToolchainInstaller.Cancellation() {
        @Override public void check() throws InterruptedException { if (Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
    };
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static String rootedShell(ShellTool shell, String command) throws Exception {
        Method exec = ShellTool.class.getDeclaredMethod("exec", String.class, int.class, boolean.class, boolean.class, int.class);
        exec.setAccessible(true); return (String) exec.invoke(shell, command, 3, true, false, shell.cancellationEpoch());
    }
    private static void child(String mode, File directory) throws Exception {
        ShellTool shell = new ShellTool(false, directory.getPath(), null);
        if ("silent".equals(mode)) {
            for (String command : Arrays.asList("printf hello", "false", "test -f missing.apk", "not_a_backcast_program --version")) {
                String output = rootedShell(shell, command);
                check(output.startsWith("错误：") && !output.contains("exit=0"), "Silent launcher was reported as successful: " + output);
            }
            RootShell.Out result = RootShell.exec("false", null, 100, 2000);
            check(result.exit != 0 && result.stderr.contains("身份握手"), "Root exec trusted a silent su exit");
            boolean refused = false;
            try { RootShell.readAll("printf invisible", 100, LIVE); } catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "Root read returned a successful empty file without handshake");
            File target = new File(directory, "fake-success");
            result = RootShell.exec("cat > " + RootShell.quote(target.getPath()), "actual bytes".getBytes("UTF-8"), 100, 2000);
            check(result.exit != 0 && !target.exists(), "Root write silently succeeded without running");
            check(!RootShell.available(), "Silent root became available");
            File su = new File(new File(directory, "bin"), "su");
            Files.write(su.toPath(), "#!/bin/sh\nexec sh -c \"$2\"\n".getBytes("UTF-8"));
            check(!RootShell.available(), "Negative root cache repeatedly prompted during its short TTL");
            java.lang.reflect.Field checkedAt = RootShell.class.getDeclaredField("availableCheckedAt"); checkedAt.setAccessible(true);
            checkedAt.setLong(null, System.nanoTime() - 6000000000L);
            check(RootShell.available(), "Authorizing root after a failed probe stayed unavailable forever");
        } else {
            check(rootedShell(shell, "printf '中文-output'; exit 7").startsWith("exit=7\n中文-output"), "Forked su lost real output or exit");
            check(rootedShell(shell, "false").startsWith("exit=1"), "False became successful");
            check(rootedShell(shell, "test -f missing.apk").startsWith("exit=1"), "Missing file test became successful");
            check(rootedShell(shell, "not_a_backcast_program --version").startsWith("exit=127"), "Missing program became successful");
            check(shell.run(new JSONObject().put("command", "printf plain-output; false")).startsWith("exit=1\nplain-output"), "Forked setsid lost ordinary shell status");
            RootShell.Out out = RootShell.exec("printf 'binary\\000tail'; exit 9", null, 100, 2000);
            check(out.exit == 9 && Arrays.equals(out.stdout, new byte[]{98,105,110,97,114,121,0,116,97,105,108}), "Root control frames changed binary stdout or exit: " + out.exit);
            File target = new File(directory, "root-write-" + mode); byte[] input = new byte[]{0,1,2,10,(byte)255};
            out = RootShell.exec("cat > " + RootShell.quote(target.getPath()), input, 100, 2000);
            check(out.exit == 0 && Arrays.equals(input, Files.readAllBytes(target.toPath())), "Root stdin was swallowed by owner wrapper");
            check(Arrays.equals(input, RootShell.readAll("cat " + RootShell.quote(target.getPath()), 100, LIVE)), "Root binary read lost bytes");
            File text = new File(directory, "root-readable-" + mode + ".txt");
            Files.write(text.toPath(), "root-aware text\n".getBytes("UTF-8"));
            check(ToolPaths.resolve(directory.getPath(), "new/target.txt", null, true, LIVE).equals(new File(directory, "new/target.txt")), "Root canonical lookup rejected a new path");
            String found = new FindFilesTool(directory.getPath(), true, null).run(new JSONObject().put("name", "root-readable-" + mode));
            check(new JSONObject(found).getJSONArray("matches").length() == 1, "Root listing lost a filename: " + found);
            String read = new ReadTool(directory.getPath(), true, null).run(new JSONObject().put("path", "root-readable-" + mode));
            check(read.startsWith("[按文件名解析为：") && read.contains("root-aware text"), "Root read could not resolve its unique filename: " + read);
            File linked = new File(directory, "outside-" + mode);
            Files.createSymbolicLink(linked.toPath(), new File("/etc/passwd").toPath());
            boolean escaped = false;
            try { ToolPaths.resolve(directory.getPath(), linked.getPath(), null, true, LIVE); } catch (IllegalArgumentException expected) { escaped = true; }
            check(escaped, "Root lookup allowed a symlink outside its captured workspace");
            Files.delete(linked.toPath());
            boolean failed = false;
            try { RootShell.readAll("printf misleading; exit 3", 100, LIVE); } catch (IllegalArgumentException expected) { failed = true; }
            check(failed, "Root nonzero read returned a partial success");
            failed = false;
            try { RootShell.readAll("printf too-long-output", 3, LIVE); } catch (IllegalArgumentException expected) { failed = expected.getMessage().contains("超过"); }
            check(failed, "Root read silently truncated an oversized single chunk");
            final AtomicBoolean stopped = new AtomicBoolean();
            final ToolchainInstaller.Cancellation cancellation = new ToolchainInstaller.Cancellation() {
                @Override public void check() throws InterruptedException { if (stopped.get()) throw new InterruptedException("cancel fixture"); }
            };
            final Process process = RootShell.start("sleep 30; printf late", cancellation);
            final AtomicBoolean readFailed = new AtomicBoolean();
            Thread reader = new Thread(() -> { try { process.getInputStream().read(); } catch (Exception expected) { readFailed.set(true); } });
            reader.start(); Thread.sleep(150); stopped.set(true); reader.join(3000);
            check(!reader.isAlive() && readFailed.get(), "Cancellation left a root stream blocked or succeeded"); process.destroy();
            long started = System.nanoTime();
            out = RootShell.exec("sleep 30", null, 100, 200);
            check(out.exit != 0 && (System.nanoTime() - started) / 1000000 < 3000, "Root timeout waited for a blocked stream");
        }
        System.out.println("PASS " + mode + " actual command status, bytes, and failure propagation");
    }
    private static void remove(File file) throws Exception {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        Files.deleteIfExists(file.toPath());
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2) { child(args[0], new File(args[1])); return; }
        File directory = Files.createTempDirectory("backcast-root-execution-").toFile(), bin = new File(directory, "bin");
        try {
            check(bin.mkdir(), "No fixture bin");
            File su = new File(bin, "su");
            Files.write(su.toPath(), ("#!/bin/sh\ncase \"$BACKCAST_TEST_SU\" in\nsilent) exit 0;;\ndetached) exec 3<&0; sh -c \"$2\" <&3 & exit 0;;\n*) exec sh -c \"$2\";;\nesac\n").getBytes("UTF-8"));
            check(su.setExecutable(true), "Fake su is not executable");
            File setsid = new File(bin, "setsid");
            Files.write(setsid.toPath(), "#!/bin/sh\nexec 3<&0\n\"$@\" <&3 &\nexit 0\n".getBytes("UTF-8"));
            check(setsid.setExecutable(true), "Fake setsid is not executable");
            File id = new File(bin, "id");
            Files.write(id.toPath(), "#!/bin/sh\nprintf 'uid=0(root) gid=0(root)\\n'\n".getBytes("UTF-8"));
            check(id.setExecutable(true), "Fake root probe id is not executable");
            for (String mode : Arrays.asList("normal", "detached", "silent")) {
                ProcessBuilder child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                        "-cp", System.getProperty("java.class.path"), RootExecutionRegressionTest.class.getName(), mode, directory.getPath());
                child.environment().put("PATH", bin.getPath() + ":/usr/bin:/bin"); child.environment().put("BACKCAST_TEST_SU", mode);
                Process process = child.inheritIO().start();
                check(process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0, "Root execution fixture failed: " + mode);
            }
        } finally { remove(directory); }
    }
}
