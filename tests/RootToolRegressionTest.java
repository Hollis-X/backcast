package com.mkei.backcast.tool;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Android App permission failures with real shell processes and file transfers. */
@SuppressWarnings("removal")
public final class RootToolRegressionTest {
    private static final ToolchainInstaller.Cancellation LIVE = () -> {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    };
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void child(String mode, File base) throws Exception {
        File project = new File(base, "测试"); project.mkdir();
        TemporaryWorkspace temporary = new TemporaryWorkspace(project.getPath(), true, new File(base, "private"), 1);
        temporary.beginTurn();
        File allocation = temporary.directory();
        if ("stream-stop".equals(mode)) {
            File pids = new File(allocation, "stream-pids");
            StringBuilder command = new StringBuilder("printf '%s\\n' \"$$\" > " + RootShell.quote(pids.getPath()) + "; ");
            for (int i = 0; i < 8; i++) command.append("sleep 30 & printf '%s\\n' \"$!\" >> ").append(RootShell.quote(pids.getPath())).append("; ");
            Process process = RootShell.start(command + "printf ready; wait", LIVE);
            String cleanupPids = "";
            try {
                check(process.getInputStream().read() == 'r', "Root stream did not start");
                cleanupPids = new String(Files.readAllBytes(pids.toPath()), "UTF-8").trim().replace('\n', ' ');
                java.lang.reflect.Field field = process.getClass().getDeclaredField("supervisor"); field.setAccessible(true);
                Object supervisor = field.get(process);
                java.lang.reflect.Field pid = supervisor.getClass().getDeclaredField("pid"); pid.setAccessible(true);
                cleanupPids += " " + pid.getLong(supervisor);
                long began = System.nanoTime();
                process.destroy();
                check(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(5), "Root stream termination exhausted cleanup budget");
                for (String child : cleanupPids.split("\\s+")) {
                    File stat = new File("/proc/" + child + "/stat");
                    if (!stat.exists()) continue;
                    String value = new String(Files.readAllBytes(stat.toPath()), "UTF-8");
                    check(value.substring(value.lastIndexOf(')') + 1).trim().startsWith("Z "), "Root stream left live child " + child);
                }
            } finally {
                if (!cleanupPids.isEmpty()) new ProcessBuilder("su", "-c", "kill -KILL " + cleanupPids + " 2>/dev/null; :").start().waitFor();
                process.destroy();
            }
            check(temporary.finishTurn() == null && !allocation.exists(), "Stopped stream retained temporary files");
        } else if ("cleanup".equals(mode)) {
            String[] children;
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkPermission(java.security.Permission permission) { }
                @Override public void checkRead(String path) {
                    if (path.startsWith("/proc/") && path.endsWith("/stat")) throw new SecurityException("Hidden Android proc");
                }
            });
            try {
                ShellTool shell = new ShellTool(true, project.getPath(), temporary);
                long began = System.nanoTime();
                StringBuilder command = new StringBuilder(":; ");
                for (int i = 0; i < 8; i++) command.append("sleep 30 & printf '%s\\n' \"$!\" >> child-pids; ");
                String result = shell.run(new JSONObject().put("temporary", true).put("command", command + "printf done; exit 7"));
                check(result.startsWith("exit=7\ndone"), "Root cleanup changed command status: " + result);
                children = new String(Files.readAllBytes(new File(allocation, "child-pids").toPath()), "UTF-8").trim().split("\\s+");
                check(temporary.finishTurn() == null && !allocation.exists(), "Root cleanup retained temporary workspace");
                check(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(5), "Root cleanup exhausted its budget");
            } finally { System.setSecurityManager(null); }
            check(children.length == 8, "Child PID fixture was incomplete");
            for (String pid : children) {
                File stat = new File("/proc/" + pid + "/stat");
                if (!stat.exists()) continue;
                String value = new String(Files.readAllBytes(stat.toPath()), "UTF-8");
                check(value.substring(value.lastIndexOf(')') + 1).trim().startsWith("Z "), "Cleanup left a live child " + pid);
            }
        } else {
            File source = new File(allocation, "decoded"); source.mkdir();
            byte[] bytes = new byte[]{0, 1, (byte)255, 10};
            Files.write(new File(source, "classes.dex").toPath(), bytes);
            ToolchainStore store = new ToolchainStore(new File(base, "tools"), null, "", 0);
            ToolkitTool toolkit = new ToolkitTool(new ShellTool(true, project.getPath(), temporary), store,
                    project.getPath(), temporary, "arm64-v8a", true);
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkPermission(java.security.Permission permission) { }
                @Override public void checkWrite(String path) {
                    if (path.startsWith(project.getPath() + "/")) throw new SecurityException("EPERM: Android shared storage");
                }
                @Override public void checkDelete(String path) { checkWrite(path); }
            });
            try {
                JSONObject result = new JSONObject(toolkit.run(new JSONObject().put("action", "export")
                        .put("source", source.getPath()).put("target", "交付/sub").put("purpose", "deliverable")));
                check("exported".equals(result.optString("state")), "Root export failed: " + result);
                check(Arrays.equals(bytes, Files.readAllBytes(new File(project, "交付/sub/classes.dex").toPath())), "Root export changed binary bytes");
                check(result.getLong("bytes") == 4 && result.getInt("files") == 1, "Export counts changed");
                File target = new File(project, "交付/sub/classes.dex");
                JSONObject request = new JSONObject().put("action", "export").put("source", new File(source, "classes.dex").getPath())
                        .put("target", target.getPath()).put("purpose", "deliverable");
                check("error".equals(new JSONObject(toolkit.run(request)).optString("state")), "Root export allowed implicit overwrite");
                check(Arrays.equals(bytes, Files.readAllBytes(target.toPath())), "Rejected overwrite changed file");
                byte[] replacement = new byte[]{5, 0, 6}; Files.write(new File(source, "classes.dex").toPath(), replacement);
                check("exported".equals(new JSONObject(toolkit.run(request.put("overwrite", true))).optString("state")), "Explicit root overwrite failed");
                check(Arrays.equals(replacement, Files.readAllBytes(target.toPath())), "Root overwrite did not publish bytes");
                boolean cancelled = false;
                try {
                    ToolkitExport.copy(project.getPath(), temporary, store, new File(source, "classes.dex").getPath(), target.getPath(),
                            "deliverable", true, true, () -> {
                                LIVE.check();
                                for (String name : target.getParentFile().list()) {
                                    if (name.startsWith(".backcast-export-")) throw new InterruptedException("Cancel staged export");
                                }
                            });
                } catch (InterruptedException expected) { cancelled = true; }
                check(cancelled && Arrays.equals(replacement, Files.readAllBytes(target.toPath())), "Cancelled root export damaged existing file");
                for (String name : target.getParentFile().list()) check(!name.startsWith(".backcast-export-"), "Cancelled root export left a draft");
            } finally { System.setSecurityManager(null); }
            check(temporary.finishTurn() == null && !allocation.exists(), "Export prevented temporary cleanup");
        }
        if ("1".equals(System.getenv("BACKCAST_TEST_REAL_ROOT"))) {
            check(RootShell.exec("rm -rf " + RootShell.quote(project.getPath()), null, 1024, 2000).exit == 0, "Cannot clean root fixture");
        }
        System.out.println("PASS root " + mode);
    }
    private static void remove(File file) throws Exception {
        if (!Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        }
        Files.deleteIfExists(file.toPath());
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2) { child(args[0], new File(args[1])); return; }
        File base = Files.createTempDirectory("backcast-root-tools-").toFile();
        File bin = new File(base, "bin"); bin.mkdir();
        try {
            File su = new File(bin, "su"), id = new File(bin, "id"), cat = new File(bin, "cat"), setsid = new File(bin, "setsid");
            Files.write(su.toPath(), ("#!/bin/sh\n" + ("1".equals(System.getenv("BACKCAST_TEST_REAL_ROOT"))
                    ? "exec /usr/bin/sudo -n /usr/bin/env PATH=\"$PATH\" BACKCAST_TEST_MODE=\"$BACKCAST_TEST_MODE\" BACKCAST_TEST_SLOW_STAT=\"$BACKCAST_TEST_SLOW_STAT\" sh -c \"$2\"\n"
                    : "exec sh -c \"$2\"\n")).getBytes("UTF-8"));
            Files.write(id.toPath(), "#!/bin/sh\nprintf '0\\n'\n".getBytes("UTF-8"));
            Files.write(cat.toPath(), ("#!/bin/sh\nif [ \"$BACKCAST_TEST_SLOW_STAT\" = 1 ] && [ \"$#\" = 1 ]; then\n"
                    + "case \"$1\" in /proc/[0-9]*/stat) sleep 0.15;; esac\nfi\nexec /bin/cat \"$@\"\n").getBytes("UTF-8"));
            Files.write(setsid.toPath(), "#!/bin/sh\nif [ \"$BACKCAST_TEST_MODE\" = stream-stop ]; then exec \"$@\"; fi\nexec /usr/bin/setsid \"$@\"\n".getBytes("UTF-8"));
            check(su.setExecutable(true) && id.setExecutable(true) && cat.setExecutable(true) && setsid.setExecutable(true), "Cannot create su fixture");
            for (String mode : args.length == 1 ? Arrays.asList(args[0]) : Arrays.asList("cleanup", "export", "stream-stop")) {
                File directory = new File(base, mode); directory.mkdir();
                ProcessBuilder builder = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                        "-cp", System.getProperty("java.class.path"), RootToolRegressionTest.class.getName(), mode, directory.getPath());
                builder.environment().put("PATH", bin.getPath() + ":/usr/bin:/bin");
                builder.environment().put("BACKCAST_TEST_SLOW_STAT", "export".equals(mode) ? "0" : "1");
                builder.environment().put("BACKCAST_TEST_MODE", mode);
                Process process = builder.inheritIO().start();
                try { check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, "Root fixture failed: " + mode); }
                finally { process.destroyForcibly(); }
            }
        } finally { remove(base); }
    }
}
