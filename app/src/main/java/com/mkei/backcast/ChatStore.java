package com.mkei.backcast;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.Compactor;
import com.mkei.backcast.agent.Diagnostics;
import com.mkei.backcast.mcp.McpSelection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地会话库。会话一行，消息按写入顺序追加。
 * 系统提示词不入库，恢复时用当前设置重新塞到历史开头。
 */
public class ChatStore extends SQLiteOpenHelper {

    public static class Session {
        public long id;
        public String title;
    }

    /** A bounded transcript page; cursors stay valid while new messages are appended. */
    public static final class MessagePage {
        public final List<Message> messages;
        public final long firstId;
        public final long earlierCount;
        /** Original human request before this page, including local retry metadata. */
        public final Message requestBefore;
        /** Disclosure belongs only to the latest USER, never an earlier goal request. */
        public final String disclosureBefore;
        /** Only tool call labels, for results whose assistant is in the previous page. */
        public final Message leadingAssistant;
        /** Results just after this page; used to finish existing labels, never drawn twice. */
        public final List<Message> trailingResults;

        public MessagePage(List<Message> messages, long firstId, long earlierCount,
                Message requestBefore, String disclosureBefore, Message leadingAssistant, List<Message> trailingResults) {
            this.messages = Collections.unmodifiableList(messages);
            this.firstId = firstId;
            this.earlierCount = earlierCount;
            this.requestBefore = requestBefore;
            this.disclosureBefore = disclosureBefore;
            this.leadingAssistant = leadingAssistant;
            this.trailingResults = Collections.unmodifiableList(trailingResults);
        }
    }

    public static class Run {
        public String goal = "";
        public String status = "";
        public long elapsedMs;
        public boolean running;
        /** Null marks a legacy run without a duration checkpoint. */
        public Long turnElapsedMs;
        /** Null means no first content event has been recorded in this turn. */
        public Long turnThinkMs;
        /** 目标累计用掉的 token，跨重启保留。 */
        public long tokensUsed;
        /** 目标 token 预算，0 表示没设。 */
        public long tokenBudget;
        /** Null for legacy runs whose budget wrap-up must be inferred. */
        public Boolean budgetWrapFinished;
    }

    public ChatStore(Context context) {
        super(context.getApplicationContext(), "backcast.db", null, 16);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "session_id INTEGER NOT NULL,"
                + "role TEXT NOT NULL,"
                + "content TEXT,"
                + "reasoning TEXT,"
                + "tool_calls TEXT,"
                + "tool_call_id TEXT,"
                + "elapsed_ms INTEGER DEFAULT 0,"
                + "think_ms INTEGER DEFAULT 0,"
                + "display_parts TEXT,"
                + "work_dir TEXT,"
                + "mcp_selection TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_messages_session ON messages(session_id, id)");
        createRuns(db);
        createContext(db);
        createRequestEvents(db);
        createDiagnosticErrors(db);
        createCompactionEvents(db);
        createSubAgentRecords(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN elapsed_ms INTEGER DEFAULT 0");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN think_ms INTEGER DEFAULT 0");
        }
        if (oldVersion < 4) {
            createRuns(db);
        }
        if (oldVersion >= 4 && oldVersion < 5) {
            db.execSQL("ALTER TABLE runs ADD COLUMN turn_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE runs ADD COLUMN turn_wall INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE runs ADD COLUMN seen_at INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 6) {
            createContext(db);
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE messages ADD COLUMN display_parts TEXT");
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE messages ADD COLUMN work_dir TEXT");
        }
        if (oldVersion >= 4 && oldVersion < 9) {
            db.execSQL("ALTER TABLE runs ADD COLUMN tokens_used INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE runs ADD COLUMN token_budget INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion >= 4 && oldVersion < 10) {
            db.execSQL("ALTER TABLE runs ADD COLUMN budget_wrap_finished INTEGER");
        }
        if (oldVersion < 11) createRequestEvents(db);
        if (oldVersion == 11) db.execSQL("ALTER TABLE request_events ADD COLUMN diagnostic TEXT NOT NULL DEFAULT ''");
        if (oldVersion < 12) createDiagnosticErrors(db);
        if (oldVersion >= 4 && oldVersion < 13) {
            db.execSQL("ALTER TABLE runs ADD COLUMN turn_elapsed_ms INTEGER");
            db.execSQL("ALTER TABLE runs ADD COLUMN turn_think_ms INTEGER");
        }
        if (oldVersion < 14) db.execSQL("ALTER TABLE messages ADD COLUMN mcp_selection TEXT NOT NULL DEFAULT ''");
        if (oldVersion < 15) {
            createCompactionEvents(db);
            restoreLatestCompactionEvents(db);
        }
        if (oldVersion < 16) createSubAgentRecords(db);
    }

    private static void createSubAgentRecords(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sub_agent_stores ("
                + "owner TEXT PRIMARY KEY,removed INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS sub_agent_records ("
                + "owner TEXT NOT NULL,agent_id TEXT NOT NULL,revision INTEGER NOT NULL,"
                + "record_json TEXT NOT NULL,PRIMARY KEY(owner,agent_id))");
    }

    public static String subAgentOwner(long sessionId) {
        if (sessionId < 0) throw new IllegalArgumentException("子 agent 会话编号不合法。");
        return "session-" + sessionId;
    }

    public synchronized List<JSONObject> loadSubAgentRecords(String owner) {
        SQLiteDatabase db = getReadableDatabase();
        requireSubAgentStore(db, owner);
        List<JSONObject> records = new ArrayList<JSONObject>();
        Cursor c = db.query("sub_agent_records", new String[]{"agent_id", "revision", "record_json"},
                "owner=?", new String[]{owner}, null, null, "agent_id ASC");
        try {
            while (c.moveToNext()) {
                try {
                    JSONObject record = new JSONObject(c.getString(2));
                    if (!c.getString(0).equals(record.getString("id"))
                            || c.getLong(1) != record.getLong("revision"))
                        throw new IllegalStateException("子 agent 数据库索引与记录不匹配。");
                    records.add(record);
                } catch (Exception invalid) {
                    throw new IllegalStateException("子 agent 数据库记录损坏，原记录已保留。", invalid);
                }
            }
        } finally { c.close(); }
        return records;
    }

    /** Child-only work must also survive when its parent has already become idle. */
    public synchronized List<Long> subAgentWorkSessionIds() {
        java.util.Map<Long, Boolean> pending = new java.util.LinkedHashMap<Long, Boolean>();
        java.util.Set<Long> allowed = new java.util.HashSet<Long>();
        java.util.Set<Long> detached = new java.util.HashSet<Long>();
        java.util.Set<Long> authorizedChildWork = new java.util.HashSet<Long>();
        java.util.Set<Long> stopped = new java.util.HashSet<Long>();
        Cursor c = getReadableDatabase().query("sub_agent_records", new String[]{"owner", "record_json"},
                null, null, null, null, null);
        try {
            while (c.moveToNext()) {
                String owner = c.getString(0);
                if (owner == null || !owner.matches("session-[0-9]+")) continue;
                long sessionId;
                try { sessionId = Long.parseLong(owner.substring(8)); }
                catch (NumberFormatException invalid) { continue; }
                try {
                    JSONObject record = new JSONObject(c.getString(1));
                    if ("main".equals(record.optString("id"))) {
                        if (record.optBoolean("managerCancelled")) stopped.add(sessionId);
                        else if (record.optBoolean("userRestartOnly") && !record.optBoolean("rootWakeAllowed")) {
                            detached.add(sessionId);
                        } else if (record.optBoolean("rootWakeAllowed")) {
                            allowed.add(sessionId); pending.put(sessionId, Boolean.TRUE);
                        }
                    } else {
                        String status = record.optString("status");
                        if ("queued".equals(status) || "running".equals(status) || "waiting".equals(status))
                            pending.put(sessionId, Boolean.TRUE);
                        if (userRestartWorkPending(record)) authorizedChildWork.add(sessionId);
                    }
                } catch (Exception invalid) {
                    try { recordDiagnostic(sessionId, "sub_agent_store", "子 agent 恢复索引损坏，原记录已保留",
                            Diagnostics.boundedJson(Diagnostics.failure(invalid))); }
                    catch (RuntimeException unavailable) { }
                }
            }
        } finally { c.close(); }
        detached.retainAll(authorizedChildWork);
        allowed.addAll(detached);
        pending.keySet().removeAll(stopped);
        pending.keySet().retainAll(allowed);
        return new ArrayList<Long>(pending.keySet());
    }

    private static boolean userRestartWorkPending(JSONObject record) {
        String status = record.optString("status");
        if (!"queued".equals(status) && !"running".equals(status) && !"waiting".equals(status)) return false;
        java.util.Set<String> authorized = new java.util.HashSet<String>();
        JSONArray tasks = record.optJSONArray("tasks");
        if (tasks != null) for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task != null && task.optBoolean("userRestartOnly") && !task.has("resultId"))
                authorized.add(task.optString("taskId"));
        }
        authorized.remove("");
        if (authorized.contains(record.optString("currentTaskId"))) return true;
        JSONArray queue = record.optJSONArray("pending");
        if (queue != null) for (int i = 0; i < queue.length(); i++) {
            JSONObject mail = queue.optJSONObject(i);
            if (mail != null && mail.optBoolean("userRestartOnly")
                    && authorized.contains(mail.optString("taskId"))) return true;
        }
        return false;
    }

    public synchronized boolean subAgentUserRestartOnly(long sessionId) {
        Cursor c = getReadableDatabase().query("sub_agent_records", new String[]{"record_json"},
                "owner=? AND agent_id=?", new String[]{subAgentOwner(sessionId), "main"}, null, null, null);
        try {
            if (!c.moveToFirst()) return false;
            JSONObject root = new JSONObject(c.getString(0));
            return root.optBoolean("userRestartOnly") && !root.optBoolean("rootWakeAllowed")
                    && !root.optBoolean("managerCancelled");
        } catch (org.json.JSONException invalid) {
            throw new IllegalStateException("子 agent 恢复权限记录损坏，原记录已保留。", invalid);
        } finally { c.close(); }
    }

    public synchronized void saveSubAgentRecord(String owner, JSONObject record) {
        List<JSONObject> records = new ArrayList<JSONObject>(); records.add(record);
        importSubAgentRecords(owner, records);
    }

    /** Old checkpoints predate root wake permission; require independent durable work evidence. */
    public synchronized boolean legacyChildWakeAllowed(long sessionId) {
        Run run = readRun(sessionId);
        if (com.mkei.backcast.agent.Goal.isClosed(run.status)
                || "failed".equals(run.status) || "error".equals(run.status)) return false;
        if (!run.running && !(com.mkei.backcast.agent.Goal.ACTIVE.equals(run.status)
                && run.goal != null && run.goal.trim().length() > 0)) return false;
        Cursor latest = getReadableDatabase().query("request_events", new String[]{"outcome"},
                "session_id=? AND purpose=?", new String[]{String.valueOf(sessionId), "model"},
                null, null, "id DESC", "1");
        try { return !latest.moveToFirst() || "success".equals(latest.getString(0)); }
        finally { latest.close(); }
    }

    /** All legacy records commit together; callers delete files only after this returns. */
    public synchronized void importSubAgentRecords(String owner, List<JSONObject> records) {
        importSubAgentRecords(owner, records, false);
    }

    public synchronized void importSubAgentRecords(String owner, List<JSONObject> records, boolean reconcileLegacyStop) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            requireSubAgentStore(db, owner);
            ContentValues state = new ContentValues(); state.put("owner", owner); state.put("removed", 0);
            db.insertWithOnConflict("sub_agent_stores", null, state, SQLiteDatabase.CONFLICT_IGNORE);
            Cursor savedState = db.query("sub_agent_stores", new String[]{"removed"}, "owner=?",
                    new String[]{owner}, null, null, null);
            try {
                if (!savedState.moveToFirst() || savedState.getInt(0) != 0)
                    throw new IllegalStateException("无法建立子 agent 数据库所有权。");
            } finally { savedState.close(); }
            boolean stoppedRootImported = false;
            for (JSONObject record : records) {
                boolean imported = writeSubAgentRecord(db, owner, record);
                if (imported && "main".equals(record.optString("id")) && record.optBoolean("managerCancelled"))
                    stoppedRootImported = true;
            }
            if (reconcileLegacyStop && stoppedRootImported && owner.matches("session-[0-9]+")) {
                ContentValues stopped = new ContentValues(); stopped.put("running", 0);
                db.update("runs", stopped, "session_id=?", new String[]{owner.substring(8)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void requireSubAgentStore(SQLiteDatabase db, String owner) {
        if (owner == null || !owner.matches("(?:session-[0-9]+|draft-[A-Za-z0-9_-]+)"))
            throw new IllegalArgumentException("子 agent 存储所有者不合法。");
        Cursor state = db.query("sub_agent_stores", new String[]{"removed"}, "owner=?",
                new String[]{owner}, null, null, null);
        try {
            if (state.moveToFirst() && state.getInt(0) != 0)
                throw new IllegalStateException("子 agent 会话已删除。");
        } finally { state.close(); }
    }

    private static boolean writeSubAgentRecord(SQLiteDatabase db, String owner, JSONObject record) {
        try {
            String id = record.getString("id"); long revision = record.getLong("revision");
            if (!id.matches("[A-Za-z0-9_-]{1,80}") || revision < 0)
                throw new IllegalArgumentException("子 agent 编号或版本不合法。");
            Cursor old = db.query("sub_agent_records", new String[]{"revision"}, "owner=? AND agent_id=?",
                    new String[]{owner, id}, null, null, null);
            try { if (old.moveToFirst() && old.getLong(0) >= revision) return false; }
            finally { old.close(); }
            ContentValues values = new ContentValues(); values.put("owner", owner); values.put("agent_id", id);
            values.put("revision", Long.valueOf(revision)); values.put("record_json", record.toString());
            if (db.insertWithOnConflict("sub_agent_records", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                throw new IllegalStateException("无法保存子 agent 数据库记录。");
            return true;
        } catch (org.json.JSONException invalid) { throw new IllegalArgumentException("子 agent 数据库记录不完整。", invalid); }
    }

    public synchronized void bindSubAgentRecords(String owner, String next) {
        if (owner.equals(next)) return;
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            requireSubAgentStore(db, owner); requireSubAgentStore(db, next);
            Cursor c = db.query("sub_agent_records", new String[]{"record_json"}, "owner=?",
                    new String[]{owner}, null, null, null);
            try {
                while (c.moveToNext()) {
                    try { writeSubAgentRecord(db, next, new JSONObject(c.getString(0))); }
                    catch (org.json.JSONException invalid) { throw new IllegalStateException("子 agent 草稿记录损坏。", invalid); }
                }
            } finally { c.close(); }
            markSubAgentStoreRemoved(db, owner);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void removeSubAgentRecords(String owner) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { markSubAgentStoreRemoved(db, owner); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }

    private static void markSubAgentStoreRemoved(SQLiteDatabase db, String owner) {
        ContentValues state = new ContentValues(); state.put("owner", owner); state.put("removed", 1);
        if (db.insertWithOnConflict("sub_agent_stores", null, state, SQLiteDatabase.CONFLICT_REPLACE) < 0)
            throw new IllegalStateException("无法保存子 agent 会话删除状态。");
        db.delete("sub_agent_records", "owner=?", new String[]{owner});
    }

    private static void createCompactionEvents(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS compaction_events ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,session_id INTEGER NOT NULL,"
                + "through_id INTEGER NOT NULL,UNIQUE(session_id,through_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_compaction_events_session "
                + "ON compaction_events(session_id,through_id)");
    }

    /** Older builds retained only the latest checkpoint, so infer only that boundary. */
    private static void restoreLatestCompactionEvents(SQLiteDatabase db) {
        Cursor c = db.query("context_windows", new String[]{"session_id", "through_id", "window"},
                null, null, null, null, null);
        try {
            while (c.moveToNext()) {
                try {
                    JSONArray window = new JSONArray(c.getString(2));
                    for (int i = 0; i < window.length(); i++) {
                        JSONObject item = window.optJSONObject(i);
                        if (item != null && Compactor.isSummary(new Message(item.optString("role"), item.optString("content")))) {
                            saveCompactionEvent(db, c.getLong(0), c.getLong(1));
                            break;
                        }
                    }
                } catch (org.json.JSONException invalidCheckpoint) {
                    // Keep the original checkpoint intact; context recovery reports corruption.
                }
            }
        } finally { c.close(); }
    }

    private static void saveCompactionEvent(SQLiteDatabase db, long sessionId, long through) {
        if (sessionId < 0 || through <= 0) return;
        ContentValues event = new ContentValues();
        event.put("session_id", Long.valueOf(sessionId));
        event.put("through_id", Long.valueOf(through));
        db.insertWithOnConflict("compaction_events", null, event, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static void createRequestEvents(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS request_events ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,session_id INTEGER NOT NULL,"
                + "recorded_at INTEGER NOT NULL, purpose TEXT NOT NULL,"
                + "elapsed_ms INTEGER NOT NULL,outcome TEXT NOT NULL,reason TEXT NOT NULL,"
                + "retry_count INTEGER NOT NULL,diagnostic TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_request_events_session ON request_events(session_id,id)");
    }

    /** Keep at most 200 completed attempts per conversation, independently of model history. */
    public synchronized void recordRequest(long sessionId, String purpose, long elapsedMs,
            String outcome, String reason, int retryCount, String diagnostic) {
        if (sessionId < 0) return;
        ContentValues values = new ContentValues();
        values.put("session_id", Long.valueOf(sessionId));
        values.put("recorded_at", Long.valueOf(System.currentTimeMillis()));
        values.put("purpose", "compact".equals(purpose) ? "compact" : "review".equals(purpose) ? "review" : "model");
        values.put("elapsed_ms", Long.valueOf(Math.max(0L, elapsedMs)));
        values.put("outcome", "success".equals(outcome) ? "success" : "retryable_error".equals(outcome)
                ? "retryable_error" : "cancelled".equals(outcome) ? "cancelled" : "error");
        String safe = Diagnostics.scrub(reason).replace('\n', ' ').replace('\r', ' ').trim();
        values.put("reason", safe.length() > 160 ? safe.substring(0, 160) : safe);
        values.put("retry_count", Integer.valueOf(Math.max(0, retryCount)));
        values.put("diagnostic", Diagnostics.detail(diagnostic));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.insert("request_events", null, values);
            db.execSQL("DELETE FROM request_events WHERE session_id=? AND id NOT IN ("
                    + "SELECT id FROM request_events WHERE session_id=? ORDER BY id DESC LIMIT 200)",
                    new Object[]{Long.valueOf(sessionId), Long.valueOf(sessionId)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void createDiagnosticErrors(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS diagnostic_errors ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,session_id INTEGER NOT NULL,"
                + "recorded_at INTEGER NOT NULL,source TEXT NOT NULL,summary TEXT NOT NULL,detail TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_diagnostic_errors_session ON diagnostic_errors(session_id,id)");
    }

    /** -1 is an app configuration error, not tied to a conversation. */
    public synchronized void recordDiagnostic(long sessionId, String source, String summary, String detail) {
        ContentValues values = new ContentValues();
        values.put("session_id", Long.valueOf(sessionId));
        values.put("recorded_at", Long.valueOf(System.currentTimeMillis()));
        String safeSource = Diagnostics.scrub(source).replace('\n', ' ').replace('\r', ' ');
        values.put("source", safeSource.substring(0, Math.min(80, safeSource.length())));
        String safeSummary = Diagnostics.scrub(summary).replace('\n', ' ').replace('\r', ' ');
        values.put("summary", safeSummary.substring(0, Math.min(160, safeSummary.length())));
        values.put("detail", Diagnostics.detail(detail));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.insert("diagnostic_errors", null, values);
            db.execSQL("DELETE FROM diagnostic_errors WHERE session_id=? AND id NOT IN ("
                    + "SELECT id FROM diagnostic_errors WHERE session_id=? ORDER BY id DESC LIMIT 200)",
                    new Object[]{Long.valueOf(sessionId), Long.valueOf(sessionId)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** 运行状态独立于聊天记录和模型窗口。 */
    private static void createRuns(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS runs ("
                + "session_id INTEGER PRIMARY KEY,"
                + "running INTEGER NOT NULL DEFAULT 0,"
                + "goal TEXT,"
                + "status TEXT,"
                + "elapsed_ms INTEGER NOT NULL DEFAULT 0,"
                + "turn_at INTEGER NOT NULL DEFAULT 0,"
                + "turn_wall INTEGER NOT NULL DEFAULT 0,"
                + "seen_at INTEGER NOT NULL DEFAULT 0,"
                + "turn_elapsed_ms INTEGER,"
                + "turn_think_ms INTEGER,"
                + "tokens_used INTEGER NOT NULL DEFAULT 0,"
                + "token_budget INTEGER NOT NULL DEFAULT 0,"
                + "budget_wrap_finished INTEGER)");
    }

    public synchronized long create(String title) {
        ContentValues cv = new ContentValues();
        cv.put("title", title == null || title.length() == 0 ? "新会话" : title);
        cv.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().insert("sessions", null, cv);
    }

    public synchronized void append(long sessionId, Message message) {
        if (sessionId < 0 || message == null || Message.SYSTEM.equals(message.role)
                || Message.COMPACTION.equals(message.role)) {
            return;
        }
        if (Message.USER.equals(message.role)
                && !Message.isCoordination(message.content)
                && (com.mkei.backcast.agent.Goal.isSteer(message.content)
                || com.mkei.backcast.agent.Goal.isNote(message.content))) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (db.insert("messages", null, valuesOf(sessionId, message)) < 0)
                throw new IllegalStateException("会话消息保存失败。");
            db.update("sessions", touchValues(), "id=?",
                    new String[]{String.valueOf(sessionId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void createContext(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS context_windows ("
                + "session_id INTEGER PRIMARY KEY,through_id INTEGER NOT NULL,window TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS reasoning_notes ("
                + "cache_key TEXT PRIMARY KEY,summary TEXT NOT NULL)");
    }

    /** Save a model checkpoint without replacing the visible transcript. */
    public synchronized void replaceAll(long sessionId, List<Message> messages) {
        if (sessionId < 0) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long through = 0;
            Cursor tail = db.rawQuery("SELECT MAX(id) FROM messages WHERE session_id=?",
                    new String[]{String.valueOf(sessionId)});
            try {
                if (tail.moveToFirst()) through = tail.getLong(0);
            } finally {
                tail.close();
            }
            JSONArray window = new JSONArray();
            boolean compacted = false;
            if (messages != null) {
                for (int i = 0; i < messages.size(); i++) {
                    Message m = messages.get(i);
                    if (m == null || Message.SYSTEM.equals(m.role) || Message.COMPACTION.equals(m.role)) {
                        continue;
                    }
                    if (!Message.isCoordination(m.content) && (com.mkei.backcast.agent.Goal.isSteer(m.content)
                            || com.mkei.backcast.agent.Goal.isNote(m.content))) {
                        continue;
                    }
                    window.put(m.toCheckpointJson());
                    if (Compactor.isSummary(m)) compacted = true;
                }
            }
            db.update("sessions", touchValues(), "id=?",
                    new String[]{String.valueOf(sessionId)});
            ContentValues checkpoint = new ContentValues();
            checkpoint.put("session_id", Long.valueOf(sessionId));
            checkpoint.put("through_id", Long.valueOf(through));
            checkpoint.put("window", window.toString());
            if (db.insertWithOnConflict("context_windows", null, checkpoint, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                throw new IllegalStateException("会话上下文保存失败。");
            if (compacted) saveCompactionEvent(db, sessionId, through);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static ContentValues valuesOf(long sessionId, Message message) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", Long.valueOf(sessionId));
        cv.put("role", message.role);
        cv.put("content", message.content == null ? "" : message.content);
        cv.put("reasoning", message.reasoning == null ? "" : message.reasoning);
        cv.put("tool_calls", message.toolCalls == null ? "" : message.toolCalls.toString());
        cv.put("tool_call_id", message.toolCallId == null ? "" : message.toolCallId);
        cv.put("elapsed_ms", Long.valueOf(message.elapsedMs));
        cv.put("think_ms", Long.valueOf(message.thinkMs));
        cv.put("display_parts", message.displayParts == null ? "" : message.displayParts.toString());
        cv.put("work_dir", Message.USER.equals(message.role) ? message.workDir : "");
        cv.put("mcp_selection", Message.USER.equals(message.role) && message.mcpSelection != null
                ? message.mcpSelection.toJson().toString() : "");
        return cv;
    }

    private static ContentValues touchValues() {
        ContentValues touch = new ContentValues();
        touch.put("updated_at", Long.valueOf(System.currentTimeMillis()));
        return touch;
    }

    public synchronized List<Session> sessions() {
        List<Session> out = new ArrayList<Session>();
        Cursor c = getReadableDatabase().query(
                "sessions", new String[]{"id", "title"},
                null, null, null, null, "updated_at DESC");
        try {
            while (c.moveToNext()) {
                Session s = new Session();
                s.id = c.getLong(0);
                s.title = c.getString(1);
                out.add(s);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public synchronized String title(long id) {
        Cursor c = getReadableDatabase().query(
                "sessions", new String[]{"title"},
                "id=?", new String[]{String.valueOf(id)},
                null, null, null);
        try {
            if (!c.moveToFirst()) {
                return "";
            }
            String title = c.getString(0);
            return title == null ? "" : title;
        } finally {
            c.close();
        }
    }

    /** 没有会话时返回 -1。 */
    public synchronized long latestId() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM sessions ORDER BY updated_at DESC LIMIT 1", null);
        try {
            if (!c.moveToFirst()) {
                return -1;
            }
            return c.getLong(0);
        } finally {
            c.close();
        }
    }

    /** Call on a worker thread. A non-positive cursor selects the latest page. */
    public synchronized MessagePage messagePage(long sessionId, long beforeId, int limit) {
        int pageSize = Math.min(128, Math.max(1, limit));
        List<Message> out = new ArrayList<Message>();
        List<Long> messageIds = new ArrayList<Long>();
        SQLiteDatabase db = getReadableDatabase();
        String selection = "session_id=?";
        String[] args = new String[]{String.valueOf(sessionId)};
        if (beforeId > 0) {
            selection += " AND id<?";
            args = new String[]{String.valueOf(sessionId), String.valueOf(beforeId)};
        }
        Cursor c = db.query("messages", new String[]{"id", "role", "content", "reasoning",
                "tool_calls", "tool_call_id", "elapsed_ms", "think_ms", "display_parts", "work_dir", "mcp_selection"},
                selection, args, null, null, "id DESC", String.valueOf(pageSize));
        long firstId = 0;
        long lastId = 0;
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                if (lastId == 0) lastId = id;
                firstId = id;
                out.add(readMessage(c, 1));
                messageIds.add(Long.valueOf(id));
            }
        } finally {
            c.close();
        }
        Collections.reverse(out);
        Collections.reverse(messageIds);
        if (out.isEmpty()) return new MessagePage(out, 0, 0, null, "", null, Collections.<Message>emptyList());

        String[] prefixArgs = new String[]{String.valueOf(sessionId), String.valueOf(firstId)};
        long earlierCount = 0;
        Cursor count = db.rawQuery("SELECT COUNT(*) FROM messages WHERE session_id=? AND id<?", prefixArgs);
        try {
            if (count.moveToFirst()) earlierCount = count.getLong(0);
        } finally {
            count.close();
        }
        Message request = null;
        String disclosure = "";
        Cursor user = db.query("messages", new String[]{"role", "content", "reasoning",
                "tool_calls", "tool_call_id", "elapsed_ms", "think_ms", "display_parts", "work_dir", "mcp_selection"},
                "session_id=? AND id<? AND role=?",
                new String[]{prefixArgs[0], prefixArgs[1], Message.USER},
                null, null, "id DESC", null);
        try {
            boolean latest = true;
            while (user.moveToNext()) {
                String text = user.getString(1);
                boolean internal = com.mkei.backcast.agent.Goal.isSteer(text)
                        || com.mkei.backcast.agent.Goal.isNote(text);
                if (latest) {
                    if (!internal && text != null) disclosure = text;
                    latest = false;
                }
                if (!internal) {
                    request = readMessage(user, 0);
                    break;
                }
            }
        } finally {
            user.close();
        }
        Message leading = null;
        if (Message.TOOL.equals(out.get(0).role)) {
            Cursor previous = db.query("messages", new String[]{"role", "tool_calls"},
                    "session_id=? AND id<? AND role<>?",
                    new String[]{prefixArgs[0], prefixArgs[1], Message.TOOL},
                    null, null, "id DESC", "1");
            try {
                if (previous.moveToFirst() && Message.ASSISTANT.equals(previous.getString(0))) {
                    String calls = previous.getString(1);
                    if (calls != null && calls.length() > 0) {
                        try {
                            JSONArray allCalls = new JSONArray(calls);
                            JSONArray shownCalls = new JSONArray();
                            for (int i = 0; i < allCalls.length(); i++) {
                                JSONObject call = allCalls.optJSONObject(i);
                                if (call == null) continue;
                                String callId = call.optString("id", "");
                                for (int j = 0; j < out.size() && Message.TOOL.equals(out.get(j).role); j++) {
                                    if (callId.equals(out.get(j).toolCallId)) {
                                        shownCalls.put(call);
                                        break;
                                    }
                                }
                            }
                            if (shownCalls.length() > 0) leading = Message.assistant("", shownCalls);
                        } catch (Exception ignored) { }
                    }
                }
            } finally {
                previous.close();
            }
        }
        List<Message> trailing = trailingResults(db, sessionId, lastId, out);
        return new MessagePage(withCompactionEvents(db, sessionId, firstId, lastId, out, messageIds),
                firstId, earlierCount, request, disclosure, leading, trailing);
    }

    /** Decorations use message boundaries, never change cursors or the model checkpoint. */
    private List<Message> withCompactionEvents(SQLiteDatabase db, long sessionId, long firstId, long lastId,
            List<Message> messages, List<Long> ids) {
        java.util.HashSet<Long> boundaries = new java.util.HashSet<Long>();
        Cursor c = db.query("compaction_events", new String[]{"through_id"},
                "session_id=? AND through_id>=? AND through_id<=?",
                new String[]{String.valueOf(sessionId), String.valueOf(firstId), String.valueOf(lastId)},
                null, null, "through_id ASC", "128");
        try { while (c.moveToNext()) boundaries.add(Long.valueOf(c.getLong(0))); }
        finally { c.close(); }
        if (boundaries.isEmpty()) return messages;
        List<Message> decorated = new ArrayList<Message>(messages.size() + boundaries.size());
        for (int i = 0; i < messages.size(); i++) {
            decorated.add(messages.get(i));
            if (boundaries.contains(ids.get(i))) decorated.add(new Message(Message.COMPACTION, ""));
        }
        return decorated;
    }

    private List<Message> trailingResults(SQLiteDatabase db, long sessionId, long lastId,
            List<Message> page) {
        ArrayList<Message> out = new ArrayList<Message>();
        int assistantAt = page.size() - 1;
        while (assistantAt >= 0 && Message.TOOL.equals(page.get(assistantAt).role)) assistantAt--;
        if (assistantAt < 0) return out;
        Message assistant = page.get(assistantAt);
        if (!Message.ASSISTANT.equals(assistant.role) || assistant.toolCalls == null) return out;
        ArrayList<String> ids = new ArrayList<String>();
        for (int i = 0; i < assistant.toolCalls.length() && ids.size() < 128; i++) {
            JSONObject call = assistant.toolCalls.optJSONObject(i);
            if (call == null) continue;
            String id = call.optString("id", "");
            if (id.length() == 0 || ids.contains(id)) continue;
            boolean complete = false;
            for (int j = assistantAt + 1; j < page.size(); j++) {
                if (id.equals(page.get(j).toolCallId)) { complete = true; break; }
            }
            if (!complete) ids.add(id);
        }
        if (ids.isEmpty()) return out;

        long boundary = 0;
        Cursor next = db.query("messages", new String[]{"id"},
                "session_id=? AND id>? AND role<>?",
                new String[]{String.valueOf(sessionId), String.valueOf(lastId), Message.TOOL},
                null, null, "id ASC", "1");
        try {
            if (next.moveToFirst()) boundary = next.getLong(0);
        } finally {
            next.close();
        }
        StringBuilder selection = new StringBuilder("session_id=? AND id>?");
        ArrayList<String> args = new ArrayList<String>();
        args.add(String.valueOf(sessionId));
        args.add(String.valueOf(lastId));
        if (boundary > 0) {
            selection.append(" AND id<?");
            args.add(String.valueOf(boundary));
        }
        selection.append(" AND role=? AND tool_call_id IN (");
        args.add(Message.TOOL);
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) selection.append(',');
            selection.append('?');
            args.add(ids.get(i));
        }
        selection.append(')');
        Cursor results = db.query("messages", new String[]{"role", "content", "reasoning", "tool_calls",
                "tool_call_id", "elapsed_ms", "think_ms", "display_parts", "work_dir", "mcp_selection"},
                selection.toString(), args.toArray(new String[args.size()]), null, null, "id ASC", "128");
        try {
            while (results.moveToNext()) out.add(readMessage(results, 0));
        } finally {
            results.close();
        }
        return out;
    }

    /** Restore the model checkpoint plus messages appended after it. */
    public synchronized List<Message> contextMessages(long sessionId) {
        List<Message> out = new ArrayList<Message>();
        long through = -1;
        Cursor c = getReadableDatabase().query("context_windows",
                new String[]{"through_id", "window"}, "session_id=?",
                new String[]{String.valueOf(sessionId)}, null, null, null);
        try {
            if (c.moveToFirst()) {
                through = c.getLong(0);
                JSONArray window = new JSONArray(c.getString(1));
                for (int i = 0; i < window.length(); i++) {
                    JSONObject item = window.getJSONObject(i);
                    out.add(Message.fromCheckpointJson(item));
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("Invalid saved context window", error);
        } finally {
            c.close();
        }
        out.addAll(readMessages(sessionId, through));
        return out;
    }

    public synchronized String reasoningNote(String key) {
        Cursor c = getReadableDatabase().query("reasoning_notes", new String[]{"summary"},
                "cache_key=?", new String[]{key}, null, null, null);
        try {
            return c.moveToFirst() ? c.getString(0) : "";
        } finally {
            c.close();
        }
    }

    public synchronized void saveReasoningNote(String key, String summary) {
        ContentValues cv = new ContentValues();
        cv.put("cache_key", key);
        cv.put("summary", summary);
        getWritableDatabase().insertWithOnConflict("reasoning_notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private List<Message> readMessages(long sessionId, long after) {
        List<Message> out = new ArrayList<Message>();
        Cursor c = getReadableDatabase().query(
                "messages",
                new String[]{"role", "content", "reasoning", "tool_calls", "tool_call_id",
                        "elapsed_ms", "think_ms", "display_parts", "work_dir", "mcp_selection"},
                "session_id=? AND id>?", new String[]{String.valueOf(sessionId), String.valueOf(after)},
                null, null, "id ASC");
        try {
            while (c.moveToNext()) {
                out.add(readMessage(c, 0));
            }
        } finally {
            c.close();
        }
        return out;
    }

    private static Message readMessage(Cursor c, int offset) {
        Message m = new Message(c.getString(offset), c.getString(offset + 1));
        String reasoning = c.getString(offset + 2);
        if (reasoning != null && reasoning.length() > 0) m.reasoning = reasoning;
        String calls = c.getString(offset + 3);
        if (calls != null && calls.length() > 0) {
            try { m.toolCalls = new JSONArray(calls); } catch (Exception ignored) { }
        }
        String callId = c.getString(offset + 4);
        if (callId != null && callId.length() > 0) m.toolCallId = callId;
        if (!c.isNull(offset + 5)) m.elapsedMs = c.getLong(offset + 5);
        if (!c.isNull(offset + 6)) m.thinkMs = c.getLong(offset + 6);
        String parts = c.getString(offset + 7);
        if (parts != null && parts.length() > 0) {
            try { m.displayParts = new JSONArray(parts); } catch (Exception ignored) { }
        }
        String workDir = c.getString(offset + 8);
        if (workDir != null && workDir.length() > 0) m.workDir = workDir;
        String selection = c.getString(offset + 9);
        if (Message.USER.equals(m.role) && selection != null && selection.length() > 0) {
            try { m.mcpSelection = McpSelection.fromJson(new JSONObject(selection)); }
            catch (Exception invalid) { throw new IllegalStateException("MCP 工具选择记录损坏，无法恢复此请求", invalid); }
        }
        m.restoreCoordinationIds();
        return m;
    }

    /**
     * 把耗时记到本轮最后一条助手消息上，重开时还能显示。
     * 已经记下的更长耗时不被更短的盖掉：界面重进后若只用自己的几秒收尾，
     * 不能把这一轮真正等过的时间抹掉。
     */
    public synchronized void markElapsed(long sessionId, long elapsedMs, long thinkMs) {
        if (sessionId < 0 || elapsedMs <= 0) {
            return;
        }
        if (thinkMs < 0) {
            thinkMs = 0;
        }
        SQLiteDatabase db = getWritableDatabase();
        Cursor user = db.query("messages", new String[]{"id"}, "session_id=? AND role=?",
                new String[]{String.valueOf(sessionId), Message.USER}, null, null, "id DESC", "1");
        long userId = 0;
        try { if (user.moveToFirst()) userId = user.getLong(0); }
        finally { user.close(); }
        Cursor c = db.query("messages", new String[]{"id", "elapsed_ms", "think_ms"},
                "session_id=? AND role=? AND id>?",
                new String[]{String.valueOf(sessionId), Message.ASSISTANT, String.valueOf(userId)},
                null, null, "id DESC", "1");
        long assistantId;
        try {
            if (!c.moveToFirst()) return;
            assistantId = c.getLong(0);
            {
                long haveElapsed = c.getLong(1);
                long haveThink = c.getLong(2);
                if (haveElapsed > elapsedMs) {
                    elapsedMs = haveElapsed;
                }
                if (haveThink > thinkMs) {
                    thinkMs = haveThink;
                }
            }
        } finally {
            c.close();
        }
        if (thinkMs > elapsedMs) {
            elapsedMs = thinkMs;
        }
        ContentValues values = new ContentValues();
        values.put("elapsed_ms", Long.valueOf(elapsedMs));
        values.put("think_ms", Long.valueOf(thinkMs));
        db.update("messages", values, "id=?", new String[]{String.valueOf(assistantId)});
    }

    public synchronized void delete(long sessionId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("messages", "session_id=?", new String[]{String.valueOf(sessionId)});
            db.delete("runs", "session_id=?", new String[]{String.valueOf(sessionId)});
            db.delete("context_windows", "session_id=?", new String[]{String.valueOf(sessionId)});
            db.delete("request_events", "session_id=?", new String[]{String.valueOf(sessionId)});
            db.delete("diagnostic_errors", "session_id=?", new String[]{String.valueOf(sessionId)});
            db.delete("compaction_events", "session_id=?", new String[]{String.valueOf(sessionId)});
            markSubAgentStoreRemoved(db, subAgentOwner(sessionId));
            db.delete("sessions", "id=?", new String[]{String.valueOf(sessionId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 记下这个会话还在不在跑，以及目标。进程被杀掉后靠它接上。 */
    public synchronized void saveRun(long sessionId, boolean running, String goal,
            String status, long elapsedMs, long turnElapsedMs, Long turnThinkMs,
            long tokensUsed, long tokenBudget, boolean budgetWrapFinished) {
        if (sessionId < 0) {
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put("session_id", Long.valueOf(sessionId));
        cv.put("running", Integer.valueOf(running ? 1 : 0));
        cv.put("goal", goal == null ? "" : goal);
        cv.put("status", status == null ? "" : status);
        cv.put("elapsed_ms", Long.valueOf(elapsedMs < 0 ? 0 : elapsedMs));
        cv.put("turn_elapsed_ms", Long.valueOf(Math.max(0L, turnElapsedMs)));
        cv.put("turn_think_ms", turnThinkMs == null ? null : Long.valueOf(Math.max(0L, turnThinkMs.longValue())));
        cv.put("tokens_used", Long.valueOf(tokensUsed < 0 ? 0 : tokensUsed));
        cv.put("token_budget", Long.valueOf(tokenBudget < 0 ? 0 : tokenBudget));
        cv.put("budget_wrap_finished", Integer.valueOf(budgetWrapFinished ? 1 : 0));
        getWritableDatabase().insertWithOnConflict(
                "runs", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Duration heartbeat never creates a run or changes its running state. */
    public synchronized void saveClock(long sessionId, long elapsedMs, long turnElapsedMs,
            Long turnThinkMs) {
        if (sessionId < 0) return;
        ContentValues cv = new ContentValues();
        cv.put("elapsed_ms", Long.valueOf(Math.max(0L, elapsedMs)));
        cv.put("turn_elapsed_ms", Long.valueOf(Math.max(0L, turnElapsedMs)));
        cv.put("turn_think_ms", turnThinkMs == null ? null : Long.valueOf(Math.max(0L, turnThinkMs.longValue())));
        getWritableDatabase().update("runs", cv, "session_id=? AND running=1",
                new String[]{String.valueOf(sessionId)});
    }

    public synchronized Run readRun(long sessionId) {
        Run run = new Run();
        Cursor c = getReadableDatabase().query(
                "runs", new String[]{"running", "goal", "status", "elapsed_ms",
                        "turn_elapsed_ms", "turn_think_ms", "tokens_used", "token_budget", "budget_wrap_finished"},
                "session_id=?", new String[]{String.valueOf(sessionId)},
                null, null, null);
        try {
            if (!c.moveToFirst()) {
                return run;
            }
            run.running = c.getInt(0) != 0;
            run.goal = c.getString(1) == null ? "" : c.getString(1);
            run.status = c.getString(2) == null ? "" : c.getString(2);
            run.elapsedMs = c.getLong(3);
            if (!c.isNull(4)) run.turnElapsedMs = Long.valueOf(c.getLong(4));
            if (!c.isNull(5)) run.turnThinkMs = Long.valueOf(c.getLong(5));
            run.tokensUsed = c.getLong(6);
            run.tokenBudget = c.getLong(7);
            if (!c.isNull(8)) run.budgetWrapFinished = Boolean.valueOf(c.getInt(8) != 0);
            return run;
        } finally {
            c.close();
        }
    }

    public synchronized List<Long> runningIds() {
        List<Long> out = new ArrayList<Long>();
        Cursor c = getReadableDatabase().query(
                "runs", new String[]{"session_id"},
                "running=1", null, null, null, null);
        try {
            while (c.moveToNext()) {
                out.add(Long.valueOf(c.getLong(0)));
            }
        } finally {
            c.close();
        }
        return out;
    }
}
