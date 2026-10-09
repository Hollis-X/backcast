package com.mkei.backcast.tool;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Tracks known descendants by PID and start time; inaccessible /proc remains best effort. */
final class ProcessTree {
    private static final class Identity {
        final long pid;
        final String started;
        final long parent, group, session;
        Identity(long pid, String started, long parent, long group, long session) {
            this.pid = pid; this.started = started;
            this.parent = parent; this.group = group; this.session = session;
        }
    }

    private final boolean root;
    private final Process process;
    private final Map<Long, Identity> known = new HashMap<Long, Identity>();
    private Identity leader;
    private Identity supervisor;
    private boolean confirmedStopped;
    private long cleanupAttemptDeadline;
    private String cleanupFailure = "";

    ProcessTree(Process process, boolean root) {
        this.root = root;
        this.process = process;
        long pid = -1;
        try { pid = ((Number)Process.class.getMethod("pid").invoke(process)).longValue(); }
        catch (Exception unavailable) {
            try {
                java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
                field.setAccessible(true);
                pid = ((Number)field.get(process)).longValue();
            } catch (Exception ignored) { }
        }
        Identity initial = identity(pid);
        if (initial != null) known.put(Long.valueOf(pid), initial);
        sample();
    }

    synchronized void sample() {
        if (confirmedStopped) return;
        try (CleanupBudget budget = new CleanupBudget(System.nanoTime() + 2000000000L)) { sample(budget); }
        catch (Exception unavailable) { failure("读取进程身份", unavailable.getMessage()); }
    }

    private boolean sample(CleanupBudget budget) throws Exception {
        budget.check();
        boolean complete = true;
        ArrayList<Long> scan = new ArrayList<Long>();
        scan.addAll(known.keySet());
        for (int i = 0; i < scan.size(); i++) {
            budget.check();
            long pid = scan.get(i).longValue();
            Identity previous = known.get(Long.valueOf(pid));
            Identity current = identity(pid);
            if (current == null || (previous != null && !previous.started.equals(current.started))) continue;
            known.put(Long.valueOf(pid), current);
        }
        // CONFIG_PROC_CHILDREN is optional on Android kernels. Reconstruct PPID
        // links from stat instead, retaining start times so reused PIDs are excluded.
        File[] entries = new File("/proc").listFiles();
        if (entries == null) complete = false;
        ArrayList<Identity> candidates = new ArrayList<Identity>();
        boolean unreadable = false;
        if (entries != null) for (File entry : entries) {
            budget.check();
            try {
                long pid = Long.parseLong(entry.getName());
                Identity value = identity(pid);
                if (value != null) candidates.add(value);
                else if (entry.isDirectory()) unreadable = true;
            } catch (NumberFormatException ignored) { }
        }
        if (root && (unreadable || supervisor != null && identity(supervisor.pid) == null)) {
            try {
                // An unrelated process can exit while being scanned. Only a
                // still-present unreadable stat makes this census incomplete.
                String command = "failed=0; for f in /proc/[0-9]*/stat; do "
                        + "s=$(cat \"$f\" 2>/dev/null) && printf '%s\\n' \"$s\" "
                        + "|| { [ ! -d \"${f%/stat}\" ] || failed=1; }; done; exit \"$failed\"";
                RootShell.Out out = rootExec(command, 2097152, budget, "读取进程身份");
                complete = out.exit == 0;
                for (String line : new String(out.stdout, "UTF-8").split("\\n")) {
                    Identity value = parse(line);
                    if (value != null) candidates.add(value);
                }
            } catch (Exception unavailable) { failure("读取进程身份", unavailable.getMessage()); complete = false; }
        }
        Map<Long, Identity> snapshot = new HashMap<Long, Identity>();
        for (Identity value : candidates) snapshot.put(Long.valueOf(value.pid), value);
        Identity liveLeader = leader == null ? null : snapshot.get(Long.valueOf(leader.pid));
        boolean validGroup = liveLeader != null && leader.started.equals(liveLeader.started)
                && liveLeader.group == leader.pid && liveLeader.session == leader.pid;
        boolean changed;
        do {
            changed = false;
            for (Identity value : candidates) {
                budget.check();
                Identity owner = known.get(Long.valueOf(value.parent));
                Identity liveOwner = owner == null ? null : snapshot.get(Long.valueOf(owner.pid));
                boolean descendant = liveOwner != null && owner.started.equals(liveOwner.started);
                boolean grouped = validGroup && value.group == leader.pid && value.session == leader.pid;
                if ((descendant || grouped) && !known.containsKey(Long.valueOf(value.pid))) {
                    known.put(Long.valueOf(value.pid), value);
                    changed = true;
                }
            }
        } while (changed);
        return complete;
    }

    synchronized boolean observeSupervisor(String stat) {
        if (confirmedStopped) return false;
        Identity value = parse(stat);
        if (value == null) return false;
        Identity live = identity(value.pid);
        if (live != null && !live.started.equals(value.started)) return false;
        known.put(Long.valueOf(value.pid), value);
        supervisor = value;
        if (value.pid == value.group && value.pid == value.session) leader = value;
        return true;
    }

    synchronized boolean stop() {
        return stop(System.nanoTime() + CleanupBudget.DEFAULT_NANOS);
    }

    synchronized boolean stop(long deadlineNanos) {
        if (confirmedStopped) return true;
        if (cleanupAttemptDeadline == 0) cleanupAttemptDeadline = deadlineNanos;
        else cleanupAttemptDeadline = Math.min(cleanupAttemptDeadline, deadlineNanos);
        deadlineNanos = cleanupAttemptDeadline;
        cleanupFailure = "";
        boolean census = false;
        try (CleanupBudget budget = new CleanupBudget(deadlineNanos)) {
            long remaining = Math.max(0, deadlineNanos - System.nanoTime());
            long reserve = Math.min(2000000000L, remaining / 2);
            try (CleanupBudget discovery = new CleanupBudget(deadlineNanos - reserve)) {
                census = sample(discovery);
                signal("STOP", discovery);
                census = sample(discovery) && census;
                signal("STOP", discovery);
                census = sample(discovery) && census;
            } catch (Exception timedOut) { failure("停止进程", timedOut.getMessage()); }
            finally {
                // STOPped supervisors cannot respond to EOF. Reserve time for a
                // guarded KILL even if discovery or an earlier signal timed out.
                try { signal("KILL", budget); }
                catch (Exception failed) { failure("发送终止信号", failed.getMessage()); }
                // EOF lets the isolated supervisor reap its own group even when
                // root transport fails; the Java-owned launcher always closes.
                try { process.getOutputStream().close(); } catch (Exception ignored) { }
                process.destroy();
                try { process.destroyForcibly(); } catch (Exception ignored) { }
            }
            try {
                long exitDeadline = Math.min(deadlineNanos, System.nanoTime() + 600000000L);
                while (System.nanoTime() - exitDeadline < 0) {
                    if (!hasSurvivors(budget) && exited()) {
                        if (census && !known.isEmpty()) { confirmedStopped = true; cleanupFailure = ""; return true; }
                        failure("验证进程退出", "进程身份扫描未完整确认。");
                        return false;
                    }
                    budget.pause(20);
                }
                if (!hasSurvivors(budget) && exited() && census && !known.isEmpty()) {
                    confirmedStopped = true; cleanupFailure = ""; return true;
                }
            } catch (Exception unavailable) { failure("验证进程退出", unavailable.getMessage()); }
        }
        if (cleanupFailure.length() == 0) failure("验证进程退出", "仍有存活或无法读取身份的已登记进程。");
        return false;
    }

    synchronized boolean retryStop(long deadlineNanos) {
        if (confirmedStopped) return true;
        cleanupAttemptDeadline = 0;
        return stop(deadlineNanos);
    }

    synchronized String cleanupFailure() { return cleanupFailure; }

    synchronized long cleanupDeadlineNanos() { return cleanupAttemptDeadline; }

    private boolean exited() {
        try { process.exitValue(); return true; }
        catch (IllegalThreadStateException alive) { return false; }
    }

    private boolean hasSurvivors(CleanupBudget budget) throws Exception {
        StringBuilder check = new StringBuilder();
        for (Identity value : known.values()) {
            budget.check();
            String stat = read("/proc/" + value.pid + "/stat");
            Identity live = parse(stat);
            if (live != null && value.started.equals(live.started)
                    && stat.substring(stat.lastIndexOf(')') + 1).trim().charAt(0) != 'Z') {
                failure("验证进程退出", "已登记进程仍存活，pid=" + value.pid + "，start=" + value.started);
                return true;
            }
            if (root && live == null) {
                check.append("s=$(cat /proc/").append(value.pid).append("/stat 2>/dev/null) || { ")
                        .append("[ ! -d /proc/").append(value.pid).append(" ] || exit 9; }; ")
                        .append("s=${s##*) }; set -- $s; [ $# -ge 20 ] || { ")
                        .append("[ ! -d /proc/").append(value.pid).append(" ] || exit 9; }; ")
                        .append("if [ $# -ge 20 ] && [ \"$1\" != Z ]; then ")
                        .append("shift 19; [ \"$1\" != ").append(RootShell.quote(value.started))
                        .append(" ] || exit 9; fi; ");
            } else if (live == null && new File("/proc/" + value.pid).exists()) {
                // An unreadable, still-present proc directory cannot prove exit.
                failure("验证进程退出", "已登记进程身份不可读，pid=" + value.pid + "，start=" + value.started);
                return true;
            }
        }
        if (check.length() > 0) {
            try { return rootExec(check.append("exit 0").toString(), 64, budget, "验证进程退出").exit != 0; }
            catch (Exception unavailable) { failure("验证进程退出", unavailable.getMessage()); return true; }
        }
        return false;
    }

    private void signal(String signal, CleanupBudget budget) throws Exception {
        StringBuilder command = new StringBuilder();
        if (leader != null) {
            // Leader stays alive until cleanup. Verify it again in the signaling
            // shell, including under su where Java may not be allowed to read proc.
            command.append(guard(leader, "kill -s " + signal + " -- -" + leader.pid)).append(';');
        }
        for (Identity tracked : known.values()) {
            budget.check();
            Identity current = identity(tracked.pid);
            if ((root && current == null) || (current != null && tracked.started.equals(current.started))) {
                command.append(guard(tracked, "kill -s " + signal + " " + tracked.pid)).append(';');
            }
        }
        if (command.length() > 0) execute(command.toString(), budget);
    }

    private static String guard(Identity value, String action) {
        return "(s=$(cat /proc/" + value.pid + "/stat 2>/dev/null) || exit; "
                + "s=${s##*) }; set -- $s; [ $# -ge 20 ] || exit; "
                + (action.indexOf(" -- -") >= 0 ? "[ \"$3\" = " + value.pid
                    + " ] && [ \"$4\" = " + value.pid + " ] || exit; " : "")
                + "shift 19; "
                + "[ \"$1\" = " + RootShell.quote(value.started) + " ] && " + action + ")";
    }

    private RootShell.Out rootExec(String command, int maximum, CleanupBudget budget, String phase) throws Exception {
        int millis = budget.remainingMillis(2000);
        try (CleanupBudget call = new CleanupBudget(Math.min(budget.deadlineNanos, System.nanoTime() + millis * 1000000L))) {
            RootShell.Out out = RootShell.exec(command, null, maximum, millis, call);
            if (out.exit != 0) failure(phase, "root exit=" + out.exit + "，" + out.stderr);
            return out;
        }
    }

    private void failure(String phase, String detail) {
        String value = phase + "：" + (detail == null ? "无法确认。" : detail.replace('\n', ' '));
        cleanupFailure = value.substring(0, Math.min(value.length(), 600));
    }

    private void execute(String command, CleanupBudget budget) throws Exception {
        budget.check();
        if (root) rootExec(command, 1024, budget, "发送停止信号");
        else {
            Process signaler = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            try {
                while (signaler.isAlive()) budget.pause(10);
            } finally {
                signaler.destroy();
                signaler.getInputStream().close();
                signaler.getOutputStream().close();
            }
        }
    }

    private static Identity identity(long pid) {
        String stat = read("/proc/" + pid + "/stat");
        return parse(stat);
    }

    private static Identity parse(String stat) {
        if (stat == null) return null;
        int close = stat.lastIndexOf(')');
        if (close < 0) return null;
        String[] fields = stat.substring(close + 1).trim().split("\\s+");
        try {
            long pid = Long.parseLong(stat.substring(0, stat.indexOf(' ')));
            return fields.length > 19 ? new Identity(pid, fields[19], Long.parseLong(fields[1]),
                    Long.parseLong(fields[2]), Long.parseLong(fields[3])) : null;
        } catch (Exception malformed) { return null; }
    }

    private static String read(String path) {
        try {
            BufferedReader input = new BufferedReader(new FileReader(path));
            try { return input.readLine(); }
            finally { input.close(); }
        } catch (Exception ignored) { return null; }
    }
}
