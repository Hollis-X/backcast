package com.mkei.backcast.tool;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Tracks known descendants by PID and start time; inaccessible /proc remains best effort. */
final class ProcessTree {
    private static final class Identity {
        final long pid;
        final String started;
        Identity(long pid, String started) { this.pid = pid; this.started = started; }
    }

    private final long parent;
    private final boolean root;
    private final Map<Long, Identity> known = new HashMap<Long, Identity>();

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
        parent = pid;
        sample();
    }

    synchronized void sample() {
        ArrayList<Long> scan = new ArrayList<Long>();
        if (parent > 0) scan.add(Long.valueOf(parent));
        scan.addAll(known.keySet());
        for (int i = 0; i < scan.size(); i++) {
            long pid = scan.get(i).longValue();
            Identity previous = known.get(Long.valueOf(pid));
            Identity current = identity(pid);
            if (current == null || (previous != null && !previous.started.equals(current.started))) continue;
            known.put(Long.valueOf(pid), current);
            String children = read("/proc/" + pid + "/task/" + pid + "/children");
            if (children == null) continue;
            for (String value : children.trim().split("\\s+")) {
                try {
                    Long child = Long.valueOf(value);
                    if (child.longValue() > 0 && !scan.contains(child)) scan.add(child);
                } catch (NumberFormatException ignored) { }
            }
        }
    }

    synchronized void observeShell(long pid) {
        if (pid <= 0) return;
        Identity current = identity(pid);
        if (current != null) known.put(Long.valueOf(pid), current);
    }

    synchronized void stop() {
        sample();
        signal("STOP", true);
        sample();
        signal("STOP", true);
        sample();
        signal("KILL", true);
    }

    synchronized void stopChildren() {
        sample();
        signal("KILL", false);
    }

    private void signal(String signal, boolean includeParent) {
        StringBuilder ids = new StringBuilder();
        for (Identity tracked : known.values()) {
            if (!includeParent && tracked.pid == parent) continue;
            Identity current = identity(tracked.pid);
            if (current != null && tracked.started.equals(current.started)) ids.append(' ').append(tracked.pid);
        }
        if (ids.length() == 0) return;
        String command = "kill -" + signal + ids.toString();
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
        if (stat == null) return null;
        int close = stat.lastIndexOf(')');
        if (close < 0) return null;
        String[] fields = stat.substring(close + 1).trim().split("\\s+");
        return fields.length > 19 ? new Identity(pid, fields[19]) : null;
    }

    private static String read(String path) {
        try {
            BufferedReader input = new BufferedReader(new FileReader(path));
            try { return input.readLine(); }
            finally { input.close(); }
        } catch (Exception ignored) { return null; }
    }
}
