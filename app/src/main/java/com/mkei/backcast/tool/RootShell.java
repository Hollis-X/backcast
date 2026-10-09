package com.mkei.backcast.tool;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 应用进程读不到的路径，跟 shell 一样走 root。
 * 授权状态只由明确的 UID 探测更新；命令失败或清理失败不能改变授权结果。
 */
final class RootShell {

    static final class Out {
        final int exit;
        final byte[] stdout;
        final String stderr;

        Out(int exit, byte[] stdout, String stderr) {
            this.exit = exit;
            this.stdout = stdout == null ? new byte[0] : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }
    }

    private static volatile Boolean available;
    private static volatile long availableCheckedAt;
    private static final long NEGATIVE_CACHE_NANOS = 5000000000L;
    private static final java.util.concurrent.locks.ReentrantLock PROBE_LOCK = new java.util.concurrent.locks.ReentrantLock();
    private static final ToolchainInstaller.Cancellation INTERRUPTIBLE = new ToolchainInstaller.Cancellation() {
        @Override public void check() throws InterruptedException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("root 操作已停止。");
        }
    };

    private RootShell() {
    }

    static boolean available(ToolchainInstaller.Cancellation cancellation) throws Exception {
        cancellation.check();
        Boolean cached = available;
        if (cached != null && (cached.booleanValue() || System.nanoTime() - availableCheckedAt < NEGATIVE_CACHE_NANOS)) {
            return cached.booleanValue();
        }
        while (true) {
            cancellation.check();
            try { if (PROBE_LOCK.tryLock(50, TimeUnit.MILLISECONDS)) break; }
            catch (InterruptedException cancelled) {
                if (!(cancellation instanceof CleanupBudget)) throw cancelled;
                ((CleanupBudget)cancellation).recordInterrupt();
            }
        }
        try {
            cancellation.check();
            if (available != null && (available.booleanValue() || System.nanoTime() - availableCheckedAt < NEGATIVE_CACHE_NANOS)) {
                return available.booleanValue();
            }
            boolean ok = false;
            try {
                Out out = exec("id -u", null, 400, 10000, cancellation);
                String text = new String(out.stdout, "UTF-8");
                ok = out.exit == 0 && "0".equals(text.trim());
            } catch (Exception e) {
                cancellation.check();
                ok = false;
            }
            available = Boolean.valueOf(ok);
            availableCheckedAt = System.nanoTime();
            return ok;
        } finally { PROBE_LOCK.unlock(); }
    }

    static String quote(String path) {
        if (path == null) {
            return "''";
        }
        if (path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("路径不合法");
        }
        return quoteArgument(path);
    }

    /** One literal argv value; unlike a file path, a program can contain newlines. */
    static String quoteArgument(String value) {
        if (value == null) return "''";
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("程序参数不能包含 NUL 字符。");
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static Process start(String command, ToolchainInstaller.Cancellation cancellation) throws Exception {
        cancellation.check();
        String token = UUID.randomUUID().toString(), begin = "__backcast_root_start_" + token + ":",
                done = "__backcast_root_done_" + token + ":";
        // Keep stdout binary and untouched. A su launcher's status is not the
        // command status: Android implementations can detach and return zero.
        String script = "[ \"$(id -u)\" = 0 ] || { printf 'root authorization unavailable\\n' >&2; exit 126; }; "
                + "s=$(cat /proc/$$/stat) || exit 125; printf " + quoteArgument("\\n" + begin + "%s\\n")
                + " \"$s\" >&2; sh -c " + quoteArgument(command) + "; result=$?; printf "
                + quoteArgument("\\n" + done + "%s\\n") + " \"$result\" >&2; exit \"$result\"";
        String launch = "if command -v setsid >/dev/null 2>&1; then setsid sh -c " + quoteArgument(script)
                + "; else sh -c " + quoteArgument(script) + "; fi";
        String owner = "su -c " + quoteArgument(launch) + "; exec >/dev/null 2>&1; while :; do sleep 1; done";
        Process raw = new ProcessBuilder("sh", "-c", owner).start();
        return new CommandProcess(raw, begin, done, cancellation);
    }

    /** Validate stderr control frames while continuously draining ordinary errors. */
    private static final class CommandProcess extends Process {
        final Process raw;
        final ProcessTree tree;
        final ToolchainInstaller.Cancellation cancellation;
        final ByteArrayOutputStream errors = new ByteArrayOutputStream();
        final InputStream stdout;
        volatile int code = Integer.MIN_VALUE;
        volatile boolean began, controlEnded, destroyed;
        volatile RootIdentity supervisor;
        volatile String failure = "";
        volatile long cleanupDeadlineNanos;

        CommandProcess(final Process raw, final String begin, final String done,
                       ToolchainInstaller.Cancellation cancellation) {
            // RootShell is ProcessTree's lowest-level transport. Its own cleanup must
            // not reenter RootShell through root signals or unreadable /proc scans.
            this.raw = raw; this.cancellation = cancellation; tree = new ProcessTree(raw, false);
            cleanupDeadlineNanos = cancellation instanceof CleanupBudget
                    ? ((CleanupBudget)cancellation).deadlineNanos : 0;
            stdout = new FilterInputStream(raw.getInputStream()) {
                @Override public int read() throws IOException {
                    check(); int value = super.read(); check(); if (value < 0) verifyEof(); return value;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    check(); int count = super.read(bytes, offset, length); check(); if (count < 0) verifyEof(); return count;
                }
            };
            Thread control = new Thread(new Runnable() { @Override public void run() {
                try {
                    InputStream input = raw.getErrorStream(); ByteArrayOutputStream line = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096]; int count; boolean discarded = false;
                    while ((count = input.read(buffer)) >= 0) {
                        for (int i = 0; i < count; i++) {
                            if (buffer[i] == '\n') {
                                String text = new String(line.toByteArray(), "UTF-8"); line.reset();
                                if (!discarded && text.startsWith(begin)) {
                                    String stat = text.substring(begin.length());
                                    RootIdentity identity = RootIdentity.parse(stat);
                                    if (!began && identity != null && tree.observeSupervisor(stat)) {
                                        supervisor = identity; began = true;
                                    }
                                    else failure = "root 执行身份握手失败。";
                                } else if (!discarded && text.startsWith(done)) {
                                    try {
                                        int actual = Integer.parseInt(text.substring(done.length()));
                                        if (!began || actual < 0 || actual > 255) throw new NumberFormatException();
                                        code = actual;
                                    } catch (NumberFormatException invalid) { failure = "root 命令退出状态无效。"; }
                                } else appendError(text + "\n");
                                discarded = false;
                            } else if (line.size() < 16384) line.write(buffer[i]); else discarded = true;
                        }
                    }
                    if (line.size() > 0) appendError(new String(line.toByteArray(), "UTF-8"));
                    input.close();
                } catch (Exception error) {
                    if (!destroyed && code == Integer.MIN_VALUE) failure = "root 状态通道中断（" + error.getClass().getSimpleName() + "）。";
                } finally {
                    controlEnded = true;
                    if (code == Integer.MIN_VALUE && failure.length() == 0)
                        failure = began ? "root 执行结束但未返回命令退出状态。" : "root 执行未返回身份握手，命令未确认执行。";
                }
            } }, "backcast-root-status"); control.setDaemon(true); control.start();
            Thread monitor = new Thread(new Runnable() { @Override public void run() {
                long deadline = System.nanoTime() + 60000000000L;
                long startup = System.nanoTime() + 5000000000L;
                try {
                    while (!destroyed && !controlEnded && code == Integer.MIN_VALUE) {
                        CommandProcess.this.cancellation.check();
                        if (!began && System.nanoTime() - startup >= 0) { failure = "root 执行身份握手超时，命令未确认执行。"; destroy(); break; }
                        if (System.nanoTime() - deadline >= 0) { failure = "root 命令执行超时。"; destroy(); break; }
                        Thread.sleep(30);
                    }
                } catch (Exception cancelled) { failure = "root 操作已停止。"; destroy(); }
            } }, "backcast-root-cancel"); monitor.setDaemon(true); monitor.start();
        }

        void appendError(String text) throws IOException {
            byte[] bytes = text.getBytes("UTF-8");
            synchronized (errors) { errors.write(bytes, 0, Math.min(bytes.length, Math.max(0, 4000 - errors.size()))); }
        }
        void check() throws IOException {
            try { cancellation.check(); }
            catch (Exception stopped) { destroy(); throw new IOException("root 操作已停止。", stopped); }
        }
        void verifyEof() throws IOException {
            long until = System.nanoTime() + 60000000000L;
            while (!controlEnded && code == Integer.MIN_VALUE && !destroyed) {
                check();
                if (System.nanoTime() - until >= 0) { destroy(); throw new IOException("root 命令状态等待超时。"); }
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); destroy(); throw new IOException("root 操作已停止。", interrupted); }
            }
            if (failure.length() > 0 || code != 0) throw new IOException(failure.length() > 0 ? failure : "root 命令失败，exit=" + code + "：" + errorText());
        }
        String errorText() {
            synchronized (errors) {
                try { return new String(errors.toByteArray(), "UTF-8"); }
                catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
            }
        }
        @Override public OutputStream getOutputStream() { return raw.getOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() {
            return new InputStream() {
                byte[] value; int offset;
                @Override public int read() throws IOException {
                    if (value == null) {
                        while (!controlEnded && !destroyed) {
                            check(); try { Thread.sleep(10); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                        }
                        value = errorText().getBytes("UTF-8");
                    }
                    return offset < value.length ? value[offset++] & 255 : -1;
                }
            };
        }
        @Override public int exitValue() {
            if (failure.length() > 0 && controlEnded) return 125;
            if (code != Integer.MIN_VALUE) return code;
            if (controlEnded || destroyed) return 125;
            throw new IllegalThreadStateException("root command still running");
        }
        @Override public int waitFor() throws InterruptedException {
            while (true) {
                try { check(); return exitValue(); }
                catch (IllegalThreadStateException running) { Thread.sleep(10); }
                catch (IOException cancelled) { return 125; }
            }
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long until = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() - until < 0) {
                try { check(); exitValue(); return true; }
                catch (IllegalThreadStateException running) { Thread.sleep(10); }
                catch (IOException cancelled) { return true; }
            }
            return false;
        }
        @Override public boolean isAlive() { try { exitValue(); return false; } catch (IllegalThreadStateException running) { return true; } }
        @Override public void destroy() {
            synchronized (this) { if (destroyed) return; destroyed = true; }
            // Completed file operations have already released their children.
            // Interrupt only a command whose identity was actually observed.
            long deadline = cleanupDeadlineNanos == 0 ? System.nanoTime() + CleanupBudget.DEFAULT_NANOS : cleanupDeadlineNanos;
            try (CleanupBudget budget = new CleanupBudget(deadline)) {
                if (began && code == Integer.MIN_VALUE && supervisor != null) supervisor.stop(budget);
                tree.stop(deadline);
            } finally {
                try { raw.getInputStream().close(); } catch (Exception ignored) { }
                try { raw.getOutputStream().close(); } catch (Exception ignored) { }
                raw.destroyForcibly();
            }
        }
        @Override public Process destroyForcibly() { destroy(); return this; }
    }

    /** Root-read stat is authoritative when Android denies the App's Java /proc reads. */
    private static final class RootIdentity {
        final long pid;
        final String started;
        RootIdentity(long pid, String started) { this.pid = pid; this.started = started; }

        static RootIdentity parse(String stat) {
            try {
                int close = stat.lastIndexOf(')');
                String[] fields = stat.substring(close + 1).trim().split("\\s+");
                long pid = Long.parseLong(stat.substring(0, stat.indexOf(' ')));
                return close >= 0 && pid > 1 && fields.length > 19 && fields[19].matches("[0-9]+")
                        ? new RootIdentity(pid, fields[19]) : null;
            } catch (Exception invalid) { return null; }
        }

        void stop(CleanupBudget budget) {
            // This one guarded signal uses su directly, never the managed executor.
            // A PID/group is killed only after revalidating its captured start time.
            String check = "s=$(cat /proc/" + pid + "/stat 2>/dev/null) || exit 0; "
                    + "s=${s##*) }; set -- $s; [ $# -ge 20 ] || exit 0; g=$3; n=$4; shift 19; "
                    + "[ \"$1\" = " + quote(started) + " ] || exit 0; "
                    + "if [ \"$g\" = " + pid + " ] && [ \"$n\" = " + pid + " ]; then "
                    + "kill -s KILL -- -" + pid + "; else " + descendants() + " fi";
            Process signaler = null;
            try {
                signaler = new ProcessBuilder("su", "-c", check).redirectErrorStream(true)
                        .redirectOutput(new java.io.File("/dev/null")).start();
                signaler.getOutputStream().close();
                while (signaler.isAlive()) budget.pause(20);
            } catch (Exception ignored) { }
            finally { if (signaler != null) signaler.destroy(); }
        }

        private String descendants() {
            // The non-setsid fallback must also handle descendants hidden from Java.
            return "kill -s STOP " + pid + "; all=' " + pid + " '; records=" + quote(pid + ":" + started) + "; round=0; "
                    + "while [ \"$round\" -lt 32 ]; do changed=0; round=$((round + 1)); "
                    + "for f in /proc/[0-9]*/stat; do s=$(cat \"$f\" 2>/dev/null) || continue; p=${s%% *}; "
                    + "s=${s##*) }; set -- $s; [ $# -ge 20 ] || continue; parent=$2; shift 19; "
                    + "case \"$all\" in *\" $parent \"*) case \"$all\" in *\" $p \"*) ;; "
                    + "*) captured=$1; current=$(cat /proc/$p/stat 2>/dev/null) || continue; current=${current##*) }; "
                    + "set -- $current; [ $# -ge 20 ] || continue; shift 19; [ \"$1\" = \"$captured\" ] || continue; "
                    + "kill -s STOP \"$p\"; all=\"$all$p \"; records=\"$p:$captured $records\"; changed=1;; esac;; esac; done; "
                    + "[ \"$changed\" = 1 ] || break; done; "
                    + "for r in $records; do p=${r%:*}; start=${r#*:}; s=$(cat /proc/$p/stat 2>/dev/null) || continue; "
                    + "s=${s##*) }; set -- $s; [ $# -ge 20 ] || continue; shift 19; "
                    + "[ \"$1\" = \"$start\" ] && kill -s KILL \"$p\"; done;";
        }
    }

    /** 读完整份标准输出。超过 max 就停掉，不把大文件整段留在内存里。 */
    static byte[] readAll(String command, int max, ToolchainInstaller.Cancellation cancellation) throws Exception {
        Out out = exec(command, null, max, 60000, cancellation);
        if (out.exit != 0) throw new IllegalArgumentException(out.stderr.trim().length() == 0 ? "root 读取失败，exit=" + out.exit : out.stderr.trim());
        return out.stdout;
    }

    static Out exec(String command, byte[] stdin, int maxStdout, int timeoutMs) throws Exception {
        return exec(command, stdin, maxStdout, timeoutMs, INTERRUPTIBLE);
    }

    static Out exec(String command, final byte[] stdin, int maxStdout, int timeoutMs,
                    ToolchainInstaller.Cancellation cancellation) throws Exception {
        final CommandProcess process = (CommandProcess) start(command, cancellation);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread tout = drain(process.getInputStream(), out, maxStdout, process);
        final java.util.concurrent.atomic.AtomicReference<Exception> inputError = new java.util.concurrent.atomic.AtomicReference<Exception>();
        Thread writer = new Thread(new Runnable() { @Override public void run() {
            try { OutputStream input = process.getOutputStream(); if (stdin != null && stdin.length > 0) input.write(stdin); input.close(); }
            catch (Exception failed) { inputError.set(failed); }
        } }, "backcast-root-input"); writer.setDaemon(true); writer.start();
        long deadline = System.nanoTime() + Math.max(1, timeoutMs) * 1000000L;
        try {
            int exit = waitFor(process, timeoutMs, cancellation);
            while ((tout.isAlive() || writer.isAlive()) && System.nanoTime() - deadline < 0) {
                cancellation.check();
                if (cancellation instanceof CleanupBudget) ((CleanupBudget)cancellation).pause(20);
                else tout.join(20);
            }
            if (tout.isAlive() || writer.isAlive()) { process.failure = "root 输出或输入通道等待超时。"; process.destroy(); exit = -1; }
            if (inputError.get() != null && stdin != null && stdin.length > 0) { process.failure = "root 写入输入数据失败。"; exit = 125; }
            if (process.failure.length() > 0 && exit == 0) exit = 125;
            String error = process.errorText();
            if (process.failure.length() > 0) error = process.failure + "\n" + error;
            return new Out(exit, out.toByteArray(), error);
        } finally { process.destroy(); }
    }

    private static int waitFor(Process process, int timeoutMs, ToolchainInstaller.Cancellation cancellation) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            cancellation.check();
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException still) {
                if (System.currentTimeMillis() >= deadline) {
                    process.destroy();
                    return -1;
                }
                if (cancellation instanceof CleanupBudget) ((CleanupBudget)cancellation).pause(30);
                else Thread.sleep(30);
            }
        }
    }

    private static Thread drain(final InputStream in, final ByteArrayOutputStream out,
                                final int max, final Process kill) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (n == 0) {
                            continue;
                        }
                        synchronized (out) {
                            if (out.size() < max) {
                                int room = max - out.size();
                                out.write(buf, 0, Math.min(n, room));
                                if (n > room && kill != null) {
                                    if (kill instanceof CommandProcess) ((CommandProcess) kill).failure = "文件或命令输出超过 " + max + " 字节。";
                                    kill.destroy(); break;
                                }
                            } else if (kill != null) {
                                if (kill instanceof CommandProcess) ((CommandProcess) kill).failure = "文件或命令输出超过 " + max + " 字节。";
                                kill.destroy();
                                break;
                            }
                        }
                    }
                } catch (Exception failed) {
                    if (kill instanceof CommandProcess) {
                        CommandProcess process = (CommandProcess) kill;
                        // verifyEof deliberately throws for an already-known nonzero
                        // command exit. Preserve that real status instead of replacing it.
                        if (!process.destroyed && process.failure.length() == 0
                                && (process.code == Integer.MIN_VALUE || process.code == 0))
                            process.failure = "root 读取输出失败（" + failed.getClass().getSimpleName() + "）。";
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
