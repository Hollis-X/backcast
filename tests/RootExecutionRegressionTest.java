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
        if ("cleanup-timeout".equals(mode)) {
            Process owned = new ProcessBuilder("/usr/bin/setsid", "sh", "-c", "sleep 30 & wait").start();
            ProcessTree tree = new ProcessTree(owned, true);
            Thread.sleep(60);
            String stat = new String(Files.readAllBytes(new File("/proc/" + owned.pid() + "/stat").toPath()), "UTF-8");
            check(tree.observeSupervisor(stat), "Deadline fixture failed to register its isolated supervisor");
            TemporaryWorkspace materials = new TemporaryWorkspace(directory.getPath(), false, new File(directory, "deadline-materials"), 42);
            materials.beginTurn(); File temporary = materials.directory();
            materials.trackProcess(temporary, new TemporaryWorkspace.ProcessCleanup() {
                public boolean stop() { return tree.stop(); }
                public boolean stop(long deadline) {
                    if (!tree.stop(deadline)) throw new IllegalStateException(tree.cleanupFailure());
                    return true;
                }
                public boolean retry(long deadline) {
                    if (!tree.retryStop(deadline)) throw new IllegalStateException(tree.cleanupFailure());
                    return true;
                }
            });
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkPermission(java.security.Permission permission) { }
                @Override public void checkRead(String path) {
                    if (path.startsWith("/proc/") && path.endsWith("/stat")) throw new SecurityException("Hidden root proc");
                }
            });
            try {
                Thread owner = Thread.currentThread();
                Thread interrupting = new Thread(() -> {
                    try { Thread.sleep(300); owner.interrupt(); }
                    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                });
                interrupting.start();
                long began = System.nanoTime();
                check(!tree.stop(began + 6000000000L), "Timed-out census incorrectly confirmed exit");
                check(Thread.interrupted(), "A fresh interrupt during root cleanup was lost");
                interrupting.join(1000);
                for (int i = 0; i < 3; i++) check(!tree.stop(System.nanoTime() + 10000000000L), "Repeated unknown stop fabricated confirmation");
                String failure = materials.finishTurn(System.nanoTime() + 10000000000L);
                check(failure != null && temporary.isDirectory(), "Timed-out identity census discarded unverified material");
                check(System.nanoTime() - began < 8000000000L, "Repeated shell stops and immediate lease close restarted the execution cleanup budget");
                check(new File(directory, "kill-attempted").exists() && owned.waitFor(1, java.util.concurrent.TimeUnit.SECONDS),
                        "Discovery timeout skipped KILL or left the STOPped supervisor alive");
                Files.deleteIfExists(new File(directory, "stop-observed").toPath());
                Files.write(new File(directory, "recovery-allowed").toPath(), new byte[]{1});
                String recovered = materials.cleanupRecovered();
                check(recovered == null && !temporary.exists(), "Unknown cleanup was cached as stopped or could not recover: " + recovered);
                System.out.println("PASS root census timeout is bounded, still attempts KILL and retains retryable material");
            } finally { System.setSecurityManager(null); owned.destroyForcibly(); }
            return;
        }
        if ("hidden-child".equals(mode)) {
            File marker = new File(directory, "hidden-child-pid");
            Process owned = new ProcessBuilder("/usr/bin/setsid", "sh", "-c", "/usr/bin/setsid sh -c 'sleep 30' & child=$!; printf '%s' \"$child\" > "
                    + RootShell.quote(marker.getPath()) + "; sleep 30").start();
            long child = -1;
            try {
                long until = System.nanoTime() + 2000000000L;
                while ((!marker.isFile() || marker.length() == 0) && System.nanoTime() < until) Thread.sleep(10);
                child = Long.parseLong(new String(Files.readAllBytes(marker.toPath()), "UTF-8"));
                final String hidden = "/proc/" + child + "/stat";
                System.setSecurityManager(new SecurityManager() {
                    @Override public void checkPermission(java.security.Permission permission) { }
                    @Override public void checkRead(String path) { if (hidden.equals(path)) throw new SecurityException("Only detached child identity is hidden"); }
                });
                ProcessTree tree = new ProcessTree(owned, true);
                String leader = new String(Files.readAllBytes(new File("/proc/" + owned.pid() + "/stat").toPath()), "UTF-8");
                check(tree.observeSupervisor(leader), "Readable root supervisor was not registered");
                check(tree.stop(), "Readable supervisor with a hidden child could not prove cleanup: " + tree.cleanupFailure());
                System.setSecurityManager(null);
                File stat = new File(hidden);
                if (stat.exists()) {
                    String value = new String(Files.readAllBytes(stat.toPath()), "UTF-8");
                    check(value.substring(value.lastIndexOf(')') + 1).trim().charAt(0) == 'Z', "Confirmed cleanup missed a detached hidden child");
                }
                System.out.println("PASS readable supervisor still discovers and stops a hidden detached root child");
            } finally {
                System.setSecurityManager(null); owned.destroyForcibly();
                if (child > 1) new ProcessBuilder("sh", "-c", "kill -KILL " + child + " 2>/dev/null || :").start().waitFor();
            }
            return;
        }
        if ("not-root".equals(mode)) {
            File target = new File(directory, "must-not-run-without-root");
            String command = "touch " + RootShell.quote(target.getPath());
            check(!RootShell.available(LIVE), "Non-root UID was accepted as authorization");
            RootShell.Out result = RootShell.exec(command, null, 100, 2000);
            check(result.exit != 0 && !target.exists(), "Unprivileged su ran a business root command");
            check(rootedShell(shell, command).startsWith("错误：") && !target.exists(), "Shell launched business command despite non-root su");
            System.out.println("PASS non-root UID cannot authorize or execute a root business command"); return;
        }
        if ("restricted-proc".equals(mode)) {
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkPermission(java.security.Permission permission) { }
                @Override public void checkRead(String path) {
                    if (path.startsWith("/proc/") && path.endsWith("/stat"))
                        throw new SecurityException("Fixture models Android hidden root /proc stat");
                }
            });
            final AtomicBoolean stopped = new AtomicBoolean(), ended = new AtomicBoolean();
            RootShell.Out restricted = RootShell.exec("id -u", null, 100, 2000);
            check(restricted.exit == 0, "Hidden /proc root status failed: " + restricted.exit + " " + restricted.stderr);
            check(RootShell.available(LIVE), "Root handshake depended on App access to root /proc");
            check(RootShell.exec("printf restricted-success", null, 100, 2000).exit == 0, "Hidden /proc root command did not finish");
            Process process = RootShell.start("sleep 30; printf forbidden-late", () -> { if (stopped.get()) throw new InterruptedException(); });
            Thread reader = new Thread(() -> { try { process.getInputStream().read(); } catch (Exception expected) { ended.set(true); } });
            reader.start(); Thread.sleep(150); stopped.set(true); reader.join(3000);
            check(!reader.isAlive() && ended.get(), "Hidden /proc cancellation left root output blocked/recursive");
            process.destroy(); check(RootShell.available(LIVE), "Hidden /proc cancellation corrupted authorization cache");
            TemporaryWorkspace materials = new TemporaryWorkspace(directory.getPath(), true,
                    new File(directory, "restricted-materials"), 41);
            materials.beginTurn(); File temporary = materials.directory();
            ShellTool managed = new ShellTool(true, directory.getPath(), materials);
            String output = rootedShell(managed, "printf rooted-complete");
            check(output.startsWith("exit=0") && output.contains("rooted-complete"), "Full hidden-proc shell cleanup failed: " + output);
            // Once the real tree is confirmed gone, neither a revoked transport
            // nor an interrupted worker should revalidate its old PIDs.
            File su = new File(new File(directory, "bin"), "su");
            byte[] originalSu = Files.readAllBytes(su.toPath());
            Files.write(su.toPath(), "#!/bin/sh\nexit 126\n".getBytes("UTF-8"));
            Thread.currentThread().interrupt();
            long cleanupStarted = System.nanoTime();
            try {
                check(materials.finishTurn() == null && !temporary.exists(), "Completed root probe depended on root again at lease close");
                check(Thread.currentThread().isInterrupted(), "Root lease close discarded the cancelled worker interrupt");
                check(System.nanoTime() - cleanupStarted < 500000000L, "Completed root probe performed fresh root verification");
            } finally { Thread.interrupted(); Files.write(su.toPath(), originalSu); }
            System.setSecurityManager(null);
            System.out.println("PASS root handshake and cancellation when Java cannot read /proc");
            System.out.println("PASS complete hidden-proc shell lease closes after root transport revocation and interrupt"); return;
        }
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
            check(!RootShell.available(LIVE), "Silent root became available");
            File su = new File(new File(directory, "bin"), "su");
            Files.write(su.toPath(), "#!/bin/sh\nexec sh -c \"$2\"\n".getBytes("UTF-8"));
            check(!RootShell.available(LIVE), "Negative root cache repeatedly prompted during its short TTL");
            java.lang.reflect.Field checkedAt = RootShell.class.getDeclaredField("availableCheckedAt"); checkedAt.setAccessible(true);
            checkedAt.setLong(null, System.nanoTime() - 6000000000L);
            check(RootShell.available(LIVE), "Authorizing root after a failed probe stayed unavailable forever");
        } else {
            if ("cleanup-silent".equals(mode)) {
                check(RootShell.available(LIVE), "Initial real uid probe failed");
                for (int i = 0; i < 4; i++) {
                    String success = rootedShell(shell, "id");
                    check(success.contains("exit=0\nuid=0"), "Business root command failed: " + success);
                    check(RootShell.available(LIVE), "A cleanup/status failure poisoned successful root authorization at repetition " + i);
                    RootShell.Out read = RootShell.exec("printf immediately-readable", null, 100, 2000);
                    check(read.exit == 0 && new String(read.stdout, "UTF-8").equals("immediately-readable"), "Root file path failed immediately after root shell cleanup");
                }
                System.out.println("PASS cleanup failure does not poison successful root authorization");
                return;
            }
            check(rootedShell(shell, "printf '中文-output'; exit 7").startsWith("exit=7\n中文-output"), "Forked su lost real output or exit");
            check(rootedShell(shell, "false").startsWith("exit=1"), "False became successful");
            check(rootedShell(shell, "test -f missing.apk").startsWith("exit=1"), "Missing file test became successful");
            check(rootedShell(shell, "not_a_backcast_program --version").startsWith("exit=127"), "Missing program became successful");
            check(shell.run(new JSONObject().put("command", "printf plain-output; false")).startsWith("exit=1\nplain-output"), "Forked setsid lost ordinary shell status");
            RootShell.Out out = RootShell.exec("printf 'binary\\000tail'; exit 9", null, 100, 2000);
            check(out.exit == 9 && Arrays.equals(out.stdout, new byte[]{98,105,110,97,114,121,0,116,97,105,108}), "Root control frames changed binary stdout or exit: " + out.exit + " " + out.stderr);
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
            final java.util.concurrent.atomic.AtomicInteger firstByte = new java.util.concurrent.atomic.AtomicInteger(-999);
            Thread reader = new Thread(() -> { try { firstByte.set(process.getInputStream().read()); } catch (Exception expected) { readFailed.set(true); } });
            reader.start(); Thread.sleep(150); stopped.set(true); reader.join(3000);
            check(!reader.isAlive() && readFailed.get(), "Cancellation left a root stream blocked or succeeded; alive=" + reader.isAlive() + ", firstByte=" + firstByte.get()); process.destroy();
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
            Files.write(su.toPath(), ("#!/bin/sh\ncase \"$BACKCAST_TEST_SU\" in\nsilent) exit 0;;\ncleanup-silent) case \"$2\" in *__backcast_root_start_*kill*) exit 0;; esac; exec sh -c \"$2\";;\n"
                    + "cleanup-timeout) case \"$2\" in *'kill -s STOP'*) touch \"$BACKCAST_TEST_DIR/stop-observed\";; *'kill -s KILL'*) touch \"$BACKCAST_TEST_DIR/kill-attempted\";; "
                    + "*'for f in /proc/'*) if [ -f \"$BACKCAST_TEST_DIR/stop-observed\" ] && [ ! -f \"$BACKCAST_TEST_DIR/recovery-allowed\" ]; then sleep 30; fi;; esac; exec sh -c \"$2\";;\n"
                    + "detached) exec 3<&0; sh -c \"$2\" <&3 & exit 0;;\n*) exec sh -c \"$2\";;\nesac\n").getBytes("UTF-8"));
            check(su.setExecutable(true), "Fake su is not executable");
            File setsid = new File(bin, "setsid");
            Files.write(setsid.toPath(), "#!/bin/sh\nexec 3<&0\n\"$@\" <&3 &\nexit 0\n".getBytes("UTF-8"));
            check(setsid.setExecutable(true), "Fake setsid is not executable");
            File id = new File(bin, "id");
            Files.write(id.toPath(), "#!/bin/sh\nif [ \"$1\" = -u ]; then if [ \"$BACKCAST_TEST_SU\" = not-root ]; then printf '1000\\n'; else printf '0\\n'; fi; else printf 'uid=0(root) gid=0(root)\\n'; fi\n".getBytes("UTF-8"));
            check(id.setExecutable(true), "Fake root probe id is not executable");
            byte[] originalSu = Files.readAllBytes(su.toPath());
            for (String mode : Arrays.asList("cleanup-silent", "normal", "detached", "silent", "restricted-proc", "not-root", "cleanup-timeout", "hidden-child")) {
                Files.write(su.toPath(), originalSu);
                ProcessBuilder child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                        "-cp", System.getProperty("java.class.path"), RootExecutionRegressionTest.class.getName(), mode, directory.getPath());
                child.environment().put("PATH", bin.getPath() + ":/usr/bin:/bin"); child.environment().put("BACKCAST_TEST_SU", mode);
                child.environment().put("BACKCAST_TEST_DIR", directory.getPath());
                Process process = child.inheritIO().start();
                check(process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0, "Root execution fixture failed: " + mode);
            }
        } finally { remove(directory); }
    }
}
