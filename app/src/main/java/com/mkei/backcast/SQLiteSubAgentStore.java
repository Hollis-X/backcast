package com.mkei.backcast;

import com.mkei.backcast.agent.Diagnostics;
import com.mkei.backcast.agent.SubAgentManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/** One transactional checkpoint for child work, transcript, mailbox and acknowledgement. */
public final class SQLiteSubAgentStore implements SubAgentManager.Store {
    private final ChatStore database;
    private final File legacy;
    private String owner;
    private long sessionId;
    private boolean migrated, removed;

    public SQLiteSubAgentStore(ChatStore database, long sessionId, File legacy) {
        if (database == null) throw new IllegalArgumentException("子 agent 数据库为空。");
        this.database = database; this.sessionId = sessionId; this.legacy = legacy;
        owner = sessionId < 0 ? "draft-" + UUID.randomUUID().toString() : ChatStore.subAgentOwner(sessionId);
    }

    /** Upgrade only the app's private session directories, before constructing the recovery index. */
    public static void migrateLegacySessions(ChatStore database, File filesDirectory) {
        File root = new File(filesDirectory, "sub-agents");
        if (!root.exists()) return;
        try {
            if (!root.getAbsolutePath().equals(root.getCanonicalPath()) || !root.isDirectory())
                throw new IllegalStateException("旧子 agent 私有目录不合法。");
            File[] folders = root.listFiles();
            if (folders == null) throw new IllegalStateException("无法读取旧子 agent 私有目录。");
            for (File folder : folders) {
                if (!folder.getName().matches("session-[0-9]+")) continue;
                long session;
                try { session = Long.parseLong(folder.getName().substring(8)); }
                catch (NumberFormatException invalid) { continue; }
                SQLiteSubAgentStore old = new SQLiteSubAgentStore(database, session, folder);
                try { old.load(); }
                catch (RuntimeException retained) { /* Migration already recorded the error and retained its sources. */ }
            }
        } catch (Exception failure) {
            try { database.recordDiagnostic(-1L, "sub_agent_store", "旧子 agent 私有目录扫描失败，原文件已保留",
                    Diagnostics.boundedJson(Diagnostics.failure(failure))); }
            catch (RuntimeException unavailable) { }
        }
    }

    public synchronized void bindSession(long next) {
        checkOpen();
        String target = ChatStore.subAgentOwner(next);
        if (owner.equals(target)) return;
        migrateLegacy();
        database.bindSubAgentRecords(owner, target);
        owner = target; sessionId = next;
    }

    @Override public synchronized List<SubAgentManager.Record> load() {
        checkOpen(); migrateLegacy();
        try {
            List<SubAgentManager.Record> records = new ArrayList<SubAgentManager.Record>();
            for (JSONObject value : database.loadSubAgentRecords(owner)) records.add(SubAgentManager.Record.fromJson(value));
            return records;
        } catch (Exception failure) { throw failed("子 agent 数据库恢复失败", failure); }
    }

    @Override public synchronized void save(SubAgentManager.Record record) {
        checkOpen(); migrateLegacy();
        try { database.saveSubAgentRecord(owner, record.toJson()); }
        catch (Exception failure) { throw failed("子 agent 数据库保存失败", failure); }
    }

    public synchronized void remove() {
        checkOpen();
        database.removeSubAgentRecords(owner);
        removed = true;
        // Deletion of a conversation is explicit. Keep unknown files untouched.
        deleteLegacyFiles();
    }

    private void checkOpen() {
        if (removed) throw new IllegalStateException("子 agent 会话已删除。");
    }

    private void migrateLegacy() {
        if (migrated) return;
        if (legacy == null || !legacy.exists()) { migrated = true; return; }
        try {
            if (!legacy.getAbsolutePath().equals(legacy.getCanonicalPath()) || !legacy.isDirectory())
                throw new IllegalStateException("旧子 agent 存储目录不合法。");
            File[] files = legacy.listFiles();
            if (files == null) throw new IllegalStateException("无法读取旧子 agent 存储目录。");
            Map<String, File> committed = new LinkedHashMap<String, File>();
            Map<String, File> backups = new LinkedHashMap<String, File>();
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".json")) committed.put(name.substring(0, name.length() - 5), file);
                else if (name.endsWith(".json.bak")) backups.put(name.substring(0, name.length() - 9), file);
            }
            for (Map.Entry<String, File> backup : backups.entrySet())
                if (!committed.containsKey(backup.getKey())) committed.put(backup.getKey(), backup.getValue());
            List<JSONObject> records = new ArrayList<JSONObject>();
            for (Map.Entry<String, File> item : committed.entrySet()) {
                JSONObject value = read(item.getValue());
                SubAgentManager.Record record = SubAgentManager.Record.fromJson(value);
                if (!item.getKey().matches("[A-Za-z0-9_-]{1,80}") || !item.getKey().equals(record.id))
                    throw new IllegalStateException("旧子 agent 记录编号不匹配。");
                if (SubAgentManager.ROOT.equals(record.id) && !value.has("rootWakeAllowed")) {
                    record.rootWakeAllowed = !record.managerCancelled && record.error.length() == 0
                            && !SubAgentManager.CLOSED.equals(record.status) && !SubAgentManager.FAILED.equals(record.status)
                            && sessionId >= 0 && database.legacyChildWakeAllowed(sessionId);
                    // A stale old running row must not overrule explicit stop or
                    // API failure evidence when RunHub resumes the parent.
                    if (!record.rootWakeAllowed && (record.error.length() > 0
                            || SubAgentManager.CLOSED.equals(record.status) || SubAgentManager.FAILED.equals(record.status)
                            || sessionId >= 0 && database.readRun(sessionId).running)) record.managerCancelled = true;
                }
                records.add(record.toJson());
            }
            database.importSubAgentRecords(owner, records, true);
            // A failed transaction never reaches this point, so every source is
            // retained for retry. Re-import after death is revision-idempotent.
            migrated = true;
            deleteLegacyFiles();
        } catch (Exception failure) { throw failed("旧子 agent 检查点迁移失败，原文件已保留", failure); }
    }

    private void deleteLegacyFiles() {
        if (legacy == null || !legacy.exists()) return;
        try {
            if (!legacy.getAbsolutePath().equals(legacy.getCanonicalPath())) return;
            File[] files = legacy.listFiles();
            if (files == null) throw new IllegalStateException("无法读取迁移后的旧目录。");
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".json") || name.endsWith(".json.bak") || name.endsWith(".pending"))
                    if (!file.delete()) throw new IllegalStateException("无法删除已迁移的旧检查点：" + name);
            }
            File[] remaining = legacy.listFiles();
            if (remaining != null && remaining.length == 0 && !legacy.delete())
                throw new IllegalStateException("无法删除空的旧检查点目录。");
        } catch (Exception failure) { diagnostic("子 agent 旧文件清理失败，数据库记录已保留", failure); }
    }

    private IllegalStateException failed(String summary, Exception failure) {
        diagnostic(summary, failure);
        return new IllegalStateException(summary + "：" + failure.getMessage(), failure);
    }

    private void diagnostic(String summary, Throwable failure) {
        try { database.recordDiagnostic(sessionId, "sub_agent_store", summary, Diagnostics.boundedJson(Diagnostics.failure(failure))); }
        catch (RuntimeException unavailable) { }
    }

    private static JSONObject read(File file) throws Exception {
        if (!file.getAbsolutePath().equals(file.getCanonicalPath()) || !file.isFile())
            throw new IllegalStateException("旧子 agent 记录不能是符号链接或目录。");
        if (file.length() > 16 * 1024 * 1024) throw new IllegalStateException("旧子 agent 记录过大。");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) {
                if (bytes.size() + count > 16 * 1024 * 1024) throw new IllegalStateException("旧子 agent 记录过大。");
                bytes.write(buffer, 0, count);
            }
        } finally { input.close(); }
        return new JSONObject(new String(bytes.toByteArray(), "UTF-8"));
    }
}
