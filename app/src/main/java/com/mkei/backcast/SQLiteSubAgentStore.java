package com.mkei.backcast;

import com.mkei.backcast.agent.Diagnostics;
import com.mkei.backcast.agent.SubAgentManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;

/** One transactional checkpoint for child work, transcript, mailbox and acknowledgement. */
public final class SQLiteSubAgentStore implements SubAgentManager.Store {
    private final ChatStore database;
    private String owner;
    private long sessionId;
    private boolean removed;

    public SQLiteSubAgentStore(ChatStore database, long sessionId) {
        if (database == null) throw new IllegalArgumentException("子 agent 数据库为空。");
        this.database = database; this.sessionId = sessionId;
        owner = sessionId < 0 ? "draft-" + UUID.randomUUID().toString() : ChatStore.subAgentOwner(sessionId);
    }

    public synchronized void bindSession(long next) {
        checkOpen();
        String target = ChatStore.subAgentOwner(next);
        if (owner.equals(target)) return;
        database.bindSubAgentRecords(owner, target);
        owner = target; sessionId = next;
    }

    @Override public synchronized List<SubAgentManager.Record> load() {
        checkOpen();
        try {
            List<SubAgentManager.Record> records = new ArrayList<SubAgentManager.Record>();
            for (JSONObject value : database.loadSubAgentRecords(owner)) records.add(SubAgentManager.Record.fromJson(value));
            return records;
        } catch (Exception failure) { throw failed("子 agent 数据库恢复失败", failure); }
    }

    @Override public synchronized void save(SubAgentManager.Record record) {
        checkOpen();
        try { database.saveSubAgentRecord(owner, record.toJson()); }
        catch (Exception failure) { throw failed("子 agent 数据库保存失败", failure); }
    }

    public synchronized void remove() {
        checkOpen();
        database.removeSubAgentRecords(owner);
        removed = true;
    }

    private void checkOpen() {
        if (removed) throw new IllegalStateException("子 agent 会话已删除。");
    }

    private IllegalStateException failed(String summary, Exception failure) {
        try { database.recordDiagnostic(sessionId, "sub_agent_store", summary, Diagnostics.boundedJson(Diagnostics.failure(failure))); }
        catch (RuntimeException unavailable) { }
        return new IllegalStateException(summary + "：" + failure.getMessage(), failure);
    }
}
