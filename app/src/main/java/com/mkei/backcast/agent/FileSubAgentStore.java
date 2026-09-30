package com.mkei.backcast.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Private, recoverable checkpoints; never mix child messages into the main transcript. */
public final class FileSubAgentStore implements SubAgentManager.Store {
    private File directory;
    private boolean removed;

    public FileSubAgentStore(File directory) { this.directory = directory; }

    public synchronized void bindDirectory(File next) {
        if (directory.equals(next)) return;
        if (directory.exists() && !directory.renameTo(next)) {
            throw new IllegalStateException("无法迁移子 agent 会话记录。");
        }
        directory = next;
    }

    private void prepare() throws Exception {
        if (removed) throw new IllegalStateException("子 agent 会话已删除。");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法创建子 agent 私有目录。");
        if (!directory.getAbsolutePath().equals(directory.getCanonicalPath())) {
            throw new IllegalStateException("子 agent 存储目录不能是符号链接。");
        }
    }

    @Override public synchronized List<SubAgentManager.Record> load() {
        List<SubAgentManager.Record> records = new ArrayList<SubAgentManager.Record>();
        try {
            prepare();
            File[] files = directory.listFiles();
            if (files == null) throw new IllegalStateException("无法读取子 agent 私有目录。");
            for (File file : files) {
                if (file.getName().endsWith(".pending")) {
                    if (!file.delete()) throw new IllegalStateException("无法清理子 agent 临时写入文件。");
                    continue;
                }
                if (!file.getName().endsWith(".json.bak")) continue;
                File committed = new File(directory, file.getName().substring(0, file.getName().length() - 4));
                if (!committed.exists() && !file.renameTo(committed)) throw new IllegalStateException("无法恢复子 agent 记录。");
                if (committed.exists() && file.exists() && !file.delete()) throw new IllegalStateException("无法清理子 agent 记录备份。");
            }
            files = directory.listFiles();
            if (files == null) throw new IllegalStateException("无法读取子 agent 私有目录。");
            for (File file : files) {
                if (!file.getName().endsWith(".json")) continue;
                SubAgentManager.Record record = SubAgentManager.Record.fromJson(read(file));
                if (!file.getName().equals(validId(record.id) + ".json")) throw new IllegalStateException("子 agent 记录编号不匹配。");
                records.add(record);
            }
            return records;
        } catch (Exception failure) {
            throw new IllegalStateException("子 agent 记录恢复失败：" + failure.getMessage(), failure);
        }
    }

    public synchronized void remove() {
        removed = true;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (file.getName().endsWith(".json") || file.getName().endsWith(".json.bak")
                    || file.getName().endsWith(".pending")) file.delete();
        }
        if (directory.exists()) directory.delete();
    }

    @Override public synchronized void save(SubAgentManager.Record record) {
        File staged = null;
        try {
            prepare();
            String id = validId(record.id);
            File committed = new File(directory, id + ".json"), backup = new File(directory, id + ".json.bak");
            if (committed.exists() && read(committed).optLong("revision", -1) > record.revision) return;
            staged = File.createTempFile(id + "-", ".pending", directory);
            FileOutputStream output = new FileOutputStream(staged);
            try {
                output.write(record.toJson().toString().getBytes("UTF-8"));
                output.getFD().sync();
            } finally { output.close(); }
            if (backup.exists() && !backup.delete()) throw new IllegalStateException("无法更新子 agent 记录备份。");
            if (committed.exists() && !committed.renameTo(backup)) throw new IllegalStateException("无法备份子 agent 记录。");
            if (!staged.renameTo(committed)) {
                if (backup.exists()) backup.renameTo(committed);
                throw new IllegalStateException("无法提交子 agent 记录。");
            }
            if (backup.exists() && !backup.delete()) throw new IllegalStateException("无法清理子 agent 记录备份。");
        } catch (Exception failure) {
            throw new IllegalStateException("子 agent 记录保存失败：" + failure.getMessage(), failure);
        } finally {
            if (staged != null && staged.exists()) staged.delete();
        }
    }

    private static String validId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("子 agent 编号不合法。");
        return id;
    }

    private static JSONObject read(File file) throws Exception {
        if (!file.getAbsolutePath().equals(file.getCanonicalPath())) throw new IllegalStateException("子 agent 记录不能是符号链接。");
        if (file.length() > 16 * 1024 * 1024) throw new IllegalStateException("子 agent 记录过大。");
        FileInputStream input = new FileInputStream(file);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
        } finally { input.close(); }
        return new JSONObject(new String(bytes.toByteArray(), "UTF-8"));
    }
}
