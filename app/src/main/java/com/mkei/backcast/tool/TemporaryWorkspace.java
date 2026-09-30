package com.mkei.backcast.tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.UUID;

/** Only directories allocated by this manager may be automatically removed. */
public final class TemporaryWorkspace {
    private static final String MARKER = ".backcast-owner";
    private static final String RECOVERED = "recovered";

    private static final class Allocation {
        final File directory;
        final String workspace;
        final String owner;
        String lease;

        Allocation(File directory, String workspace, String owner, String lease) {
            this.directory = directory;
            this.workspace = workspace;
            this.owner = owner;
            this.lease = lease;
        }
    }

    private String workDir;
    private boolean useRoot;
    private final File stateDir;
    private File ledger;
    private final ArrayList<Allocation> allocations = new ArrayList<Allocation>();
    private final ThreadLocal<String> turn = new ThreadLocal<String>();
    private String loadError;

    public TemporaryWorkspace(String workDir, boolean useRoot) {
        this(workDir, useRoot, null, -1);
    }

    public TemporaryWorkspace(String workDir, boolean useRoot, File stateDir, long sessionId) {
        configure(workDir, useRoot);
        this.stateDir = stateDir;
        ledger = stateDir == null ? null : new File(stateDir, sessionId < 0
                ? "draft-" + UUID.randomUUID().toString() + ".json"
                : "session-" + sessionId + ".json");
        load();
    }

    public synchronized void bindSession(long sessionId) {
        if (stateDir == null || sessionId < 0) return;
        File target = new File(stateDir, "session-" + sessionId + ".json");
        if (target.equals(ledger)) return;
        if (!allocations.isEmpty()) {
            throw new IllegalStateException("临时材料创建前必须先绑定会话。");
        }
        ledger = target;
        load();
    }

    public synchronized void configure(String directory, boolean root) {
        workDir = directory;
        useRoot = root;
    }

    public void beginTurn() {
        turn.set(UUID.randomUUID().toString());
    }

    private String lease() {
        String value = turn.get();
        return value == null ? "manual" : value;
    }

    public synchronized File directory() throws Exception {
        if (loadError != null) throw new IllegalArgumentException(loadError);
        if (workDir == null || workDir.length() == 0) {
            throw new IllegalArgumentException("临时材料需要明确的工作目录。");
        }
        File base = ToolPaths.resolve(workDir, ".");
        String lease = lease();
        for (Allocation allocation : allocations) {
            if (allocation.workspace.equals(base.getPath()) && allocation.lease.equals(lease)) {
                verify(allocation);
                return allocation.directory;
            }
        }
        String owner = UUID.randomUUID().toString();
        File directory = new File(base, ".backcast-tmp-" + owner);
        Allocation allocation = new Allocation(directory, base.getPath(), owner, lease);
        if (directory.exists()) throw new IllegalArgumentException("临时目录已存在，请重试。");
        // Register before creation so process death cannot leave an unrecorded directory.
        allocations.add(allocation);
        save();
        boolean created = directory.mkdir();
        if (!created && useRoot && RootShell.available()) {
            created = RootShell.exec("mkdir " + RootShell.quote(directory.getPath()),
                    null, 1024, 15000).exit == 0;
        }
        if (!created) {
            allocations.remove(allocation);
            save();
            throw new IllegalArgumentException("无法创建临时目录：" + directory.getPath());
        }
        try {
            ToolPaths.writeBytes(new File(directory, MARKER), owner.getBytes("UTF-8"), useRoot);
        } catch (Exception failure) {
            // An empty allocation remains ours even if writing its marker failed.
            directory.delete();
            throw failure;
        }
        return directory;
    }

    public synchronized File resolveTemporary(String path) throws Exception {
        return ToolPaths.resolve(directory().getPath(), path);
    }

    public synchronized boolean contains(File file) throws Exception {
        String target = file.getCanonicalPath();
        for (Allocation allocation : allocations) {
            String base = allocation.directory.getPath();
            if (target.equals(base) || target.startsWith(base + File.separator)) return true;
        }
        return false;
    }

    public synchronized String cleanup() {
        return cleanup(lease());
    }

    public synchronized String cleanupRecovered() {
        return cleanup(RECOVERED);
    }

    private String cleanup(String lease) {
        if (loadError != null) return loadError;
        StringBuilder errors = new StringBuilder();
        for (int i = allocations.size() - 1; i >= 0; i--) {
            Allocation allocation = allocations.get(i);
            if (!allocation.lease.equals(lease) && !RECOVERED.equals(allocation.lease)) continue;
            try {
                remove(allocation);
                allocations.remove(i);
            } catch (Exception failure) {
                allocation.lease = RECOVERED;
                if (errors.length() > 0) errors.append('\n');
                errors.append(allocation.directory.getPath()).append("：").append(failure.getMessage());
            }
        }
        try {
            save();
        } catch (Exception failure) {
            if (errors.length() > 0) errors.append('\n');
            errors.append("临时登记更新失败：").append(failure.getMessage());
        }
        return errors.length() == 0 ? null : errors.toString();
    }

    public String finishTurn() {
        try { return cleanup(); }
        finally { turn.remove(); }
    }

    private void verify(Allocation allocation) throws Exception {
        File directory = allocation.directory;
        verifyParent(allocation);
        if (!directory.getAbsolutePath().equals(directory.getCanonicalPath())) {
            throw new IllegalArgumentException("临时目录被替换成链接，拒绝写入。");
        }
        File marker = new File(directory, MARKER);
        if (!marker.getAbsolutePath().equals(marker.getCanonicalPath())
                || !allocation.owner.equals(new String(ToolPaths.readBytes(marker, 128, useRoot), "UTF-8"))) {
            throw new IllegalArgumentException("临时目录所有权无法确认，拒绝清理。");
        }
    }

    private void remove(Allocation allocation) throws Exception {
        File directory = allocation.directory;
        verifyParent(allocation);
        if (!directory.getAbsolutePath().equals(directory.getCanonicalPath())) {
            if (!directory.delete()) throw new IllegalArgumentException("无法删除临时目录链接。");
            return;
        }
        ToolPaths.Probe probe = ToolPaths.probe(directory, useRoot);
        if (probe.denied) throw new IllegalArgumentException("没有权限确认临时目录状态。");
        if (!probe.exists) return;
        File[] empty = directory.listFiles();
        if (empty != null && empty.length == 0 && directory.delete()) return;
        if (useRoot && RootShell.available()) {
            String path = RootShell.quote(directory.getPath());
            String marker = RootShell.quote(new File(directory, MARKER).getPath());
            String command = "if [ -L " + path + " ]; then rm -f " + path
                    + "; elif [ ! -e " + path + " ]; then :; elif [ ! -e " + marker
                    + " ]; then rmdir " + path + "; elif [ ! -L " + marker
                    + " ] && [ \"$(cat " + marker + ")\" = " + RootShell.quote(allocation.owner)
                    + " ]; then rm -rf " + path + "; else exit 2; fi";
            if (RootShell.exec(command, null, 2048, 60000).exit != 0) {
                throw new IllegalArgumentException("无法确认所有权或删除临时目录。");
            }
            return;
        }
        verify(allocation);
        removeTree(directory);
    }

    private static void verifyParent(Allocation allocation) throws Exception {
        if (!new File(allocation.workspace).getCanonicalPath().equals(allocation.workspace)) {
            throw new IllegalArgumentException("工作目录父路径被替换成链接，拒绝自动删除。");
        }
    }

    private static void removeTree(File file) throws Exception {
        if (file.getAbsolutePath().equals(file.getCanonicalPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IllegalArgumentException("无法读取临时目录。");
            for (File child : children) {
                if (!MARKER.equals(child.getName())) removeTree(child);
            }
            File marker = new File(file, MARKER);
            if (marker.exists() && !marker.delete()) throw new IllegalArgumentException("无法删除所有权标记。");
        }
        if (!file.delete() && file.exists()) throw new IllegalArgumentException("无法删除临时材料。");
    }

    private void load() {
        if (ledger == null || !ledger.exists()) return;
        try {
            FileInputStream input = new FileInputStream(ledger);
            byte[] data;
            try {
                if (ledger.length() > 1024 * 1024) throw new IllegalArgumentException("临时登记过大。");
                data = new byte[(int) ledger.length()];
                int offset = 0, count;
                while (offset < data.length && (count = input.read(data, offset, data.length - offset)) > 0) {
                    offset += count;
                }
                if (offset != data.length) throw new IllegalArgumentException("临时登记不完整。");
            } finally { input.close(); }
            JSONArray roots = new JSONObject(new String(data, "UTF-8")).getJSONArray("directories");
            for (int i = 0; i < roots.length(); i++) {
                JSONObject value = roots.getJSONObject(i);
                String owner = value.getString("owner");
                File base = new File(value.getString("workspace")).getCanonicalFile();
                File path = new File(value.getString("path")).getAbsoluteFile();
                if (!UUID.fromString(owner).toString().equals(owner)
                        || !path.equals(new File(base, ".backcast-tmp-" + owner))) {
                    throw new IllegalArgumentException("临时登记路径不合法。");
                }
                allocations.add(new Allocation(path, base.getPath(), owner, RECOVERED));
            }
        } catch (Exception failure) {
            loadError = "无法恢复临时登记：" + failure.getMessage();
        }
    }

    private void save() throws Exception {
        if (ledger == null) return;
        if (allocations.isEmpty()) {
            if (ledger.exists() && !ledger.delete()) throw new IllegalArgumentException("无法移除临时登记。");
            return;
        }
        File parent = ledger.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalArgumentException("无法保存临时登记。");
        JSONArray roots = new JSONArray();
        for (Allocation allocation : allocations) {
            roots.put(new JSONObject().put("path", allocation.directory.getPath())
                    .put("workspace", allocation.workspace).put("owner", allocation.owner));
        }
        byte[] data = new JSONObject().put("directories", roots).toString().getBytes("UTF-8");
        File pending = new File(parent, ledger.getName() + ".new");
        FileOutputStream output = new FileOutputStream(pending);
        try { output.write(data); output.getFD().sync(); }
        finally { output.close(); }
        if (!pending.renameTo(ledger)) {
            pending.delete();
            throw new IllegalArgumentException("无法更新临时登记。");
        }
    }
}
