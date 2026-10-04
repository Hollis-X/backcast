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
    private final Map<Long, Identity> known = new HashMap<Long, Identity>();
    private Identity leader;
    private Identity supervisor;

    ProcessTree(Process process, boolean root) {
        this.root = root;
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
        ArrayList<Long> scan = new ArrayList<Long>();
        scan.addAll(known.keySet());
        for (int i = 0; i < scan.size(); i++) {
            long pid = scan.get(i).longValue();
            Identity previous = known.get(Long.valueOf(pid));
            Identity current = identity(pid);
            if (current == null || (previous != null && !previous.started.equals(current.started))) continue;
            known.put(Long.valueOf(pid), current);
        }
        // CONFIG_PROC_CHILDREN is optional on Android kernels. Reconstruct PPID
        // links from stat instead, retaining start times so reused PIDs are excluded.
        File[] entries = new File("/proc").listFiles();
        ArrayList<Identity> candidates = new ArrayList<Identity>();
        if (entries != null) for (File entry : entries) {
            try {
                Identity value = identity(Long.parseLong(entry.getName()));
                if (value != null) candidates.add(value);
            } catch (NumberFormatException ignored) { }
        }
        if (root && supervisor != null && identity(supervisor.pid) == null) {
            try {
                RootShell.Out out = RootShell.exec("cat /proc/[0-9]*/stat 2>/dev/null", null, 2097152, 8000);
                for (String line : new String(out.stdout, "UTF-8").split("\\n")) {
                    Identity value = parse(line);
                    if (value != null) candidates.add(value);
                }
            } catch (Exception ignored) { }
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
    }

    synchronized boolean observeSupervisor(String stat) {
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
        sample();
        signal("STOP");
        sample();
        signal("STOP");
        sample();
        signal("KILL");
        long deadline = System.currentTimeMillis() + 600;
        while (System.currentTimeMillis() < deadline) {
            if (!hasSurvivors()) return true;
            try { Thread.sleep(20); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        return !hasSurvivors();
    }

    private boolean hasSurvivors() {
        StringBuilder check = new StringBuilder();
        for (Identity value : known.values()) {
            String stat = read("/proc/" + value.pid + "/stat");
            Identity live = parse(stat);
            if (live != null && value.started.equals(live.started)
                    && stat.substring(stat.lastIndexOf(')') + 1).trim().charAt(0) != 'Z') return true;
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
                return true;
            }
        }
        if (check.length() > 0) {
            try { return RootShell.exec(check.append("exit 0").toString(), null, 64, 5000).exit != 0; }
            catch (Exception unavailable) { return true; }
        }
        return false;
    }

    private void signal(String signal) {
        if (leader != null) {
            // Leader stays alive until cleanup. Verify it again in the signaling
            // shell, including under su where Java may not be allowed to read proc.
            execute(guard(leader, "kill -s " + signal + " -- -" + leader.pid));
        }
        StringBuilder command = new StringBuilder();
        for (Identity tracked : known.values()) {
            Identity current = identity(tracked.pid);
            if ((root && current == null) || (current != null && tracked.started.equals(current.started))) {
                command.append(guard(tracked, "kill -s " + signal + " " + tracked.pid)).append(';');
            }
        }
        if (command.length() > 0) execute(command.toString());
    }

    private static String guard(Identity value, String action) {
        return "(s=$(cat /proc/" + value.pid + "/stat 2>/dev/null) || exit; "
                + "s=${s##*) }; set -- $s; [ $# -ge 20 ] || exit; "
                + (action.indexOf(" -- -") >= 0 ? "[ \"$3\" = " + value.pid
                    + " ] && [ \"$4\" = " + value.pid + " ] || exit; " : "")
                + "shift 19; "
                + "[ \"$1\" = " + RootShell.quote(value.started) + " ] && " + action + ")";
    }

    private void execute(String command) {
        try {
            if (root) RootShell.exec(command, null, 1024, 15000);
            else {
                Process signaler = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
                signaler.waitFor();
                signaler.getInputStream().close();
                signaler.getOutputStream().close();
            }
        } catch (Exception ignored) { }
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
