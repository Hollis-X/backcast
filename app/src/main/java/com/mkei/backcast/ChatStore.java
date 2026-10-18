package com.mkei.backcast;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.Diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地会话存储。聊天记录、模型窗口、运行状态存成 {@code filesDir/.backcast/} 下的文件（见 {@link SessionFiles}）；
 * 数据库 backcast.db 只放报错：请求记录 request_events 和诊断 diagnostic_errors。
 * 系统提示词不落盘，恢复时用当前设置重新塞到历史开头。
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
        /** Null when the run stopped before its first duration checkpoint. */
        public Long turnElapsedMs;
        /** Null means no first content event has been recorded in this turn. */
        public Long turnThinkMs;
        /** 目标累计用掉的 token，跨重启保留。 */
        public long tokensUsed;
        /** 目标 token 预算，0 表示没设。 */
        public long tokenBudget;
        /** Null when no wrap-up decision was saved; restore infers it from the goal status. */
        public Boolean budgetWrapFinished;
    }

    /** 聊天记录改存文件之前的旧表；搬到文件后删掉，库重建时也一并删。 */
    private static final String[] CHAT_TABLES = {"sessions", "messages", "runs", "context_windows",
            "reasoning_notes", "compaction_events"};

    private final SessionFiles files;
    private boolean migrated;

    public ChatStore(Context context) {
        super(context.getApplicationContext(), "backcast.db", null, 16);
        files = new SessionFiles(new File(context.getApplicationContext().getFilesDir(), ".backcast"));
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createRequestEvents(db);
        createDiagnosticErrors(db);
    }

    /** Older schemas are not migrated: the error tables are rebuilt empty. */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        for (String table : CHAT_TABLES) db.execSQL("DROP TABLE IF EXISTS " + table);
        for (String table : new String[]{"request_events", "diagnostic_errors", "sub_agent_stores", "sub_agent_records"})
            db.execSQL("DROP TABLE IF EXISTS " + table);
        onCreate(db);
    }

    /** 子代理记录改存 JSON 文件（JsonSubAgentStore），同版本的旧库里留下的两张表打开时顺手删掉。 */
    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (db.isReadOnly()) return;
        db.execSQL("DROP TABLE IF EXISTS sub_agent_records");
        db.execSQL("DROP TABLE IF EXISTS sub_agent_stores");
    }

    /** {@code filesDir/.backcast/}：会话文件和子代理记录都在这下面。 */
    public File root() { return files.root(); }

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

    // ---- 聊天记录（文件）----

    /**
     * 第一次用到聊天记录时，把旧数据库里的会话一次性搬到文件：先写进暂存目录，整个改名启用，
     * 记下 db-migrated 标记，最后删掉库里的旧表。中途失败库里的数据原样保留，下次启动再搬。
     */
    private SessionFiles files() {
        if (migrated) return files;
        File marker = new File(files.root(), "db-migrated");
        if (!marker.exists()) {
            try {
                SQLiteDatabase db = getWritableDatabase();
                if (!files.hasSessions() && hasTable(db, "sessions")) importDatabase(db);
                SessionFiles.mkdirs(files.root());
                SessionFiles.writeAtomic(marker, "1", true);
                for (String table : CHAT_TABLES) db.execSQL("DROP TABLE IF EXISTS " + table);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("旧聊天记录搬到文件失败，数据库里的记录已保留：" + failure.getMessage(), failure);
            }
        }
        migrated = true;
        return files;
    }

    private static boolean hasTable(SQLiteDatabase db, String name) {
        Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", new String[]{name});
        try { return c.moveToFirst(); } finally { c.close(); }
    }

    private void importDatabase(SQLiteDatabase db) throws Exception {
        File staging = files.staging();
        SessionFiles.deleteTree(staging);
        SessionFiles.mkdirs(staging);
        long maxId = 0;
        Cursor sessions = db.query("sessions", new String[]{"id", "title", "updated_at"}, null, null, null, null, "id ASC");
        try {
            while (sessions.moveToNext()) {
                long id = sessions.getLong(0), updated = sessions.getLong(2);
                maxId = Math.max(maxId, id);
                String sid = String.valueOf(id);
                SessionFiles.Import target = new SessionFiles.Import(staging, id, sessions.getString(1), updated);
                Cursor m = db.query("messages", new String[]{"id", "role", "content", "reasoning", "tool_calls",
                        "tool_call_id", "elapsed_ms", "think_ms", "display_parts", "work_dir", "mcp_selection"},
                        "session_id=?", new String[]{sid}, null, null, "id ASC");
                try { while (m.moveToNext()) target.message(m.getLong(0), legacyMessage(m)); }
                finally { m.close(); }
                Cursor events = db.query("compaction_events", new String[]{"through_id"}, "session_id=?",
                        new String[]{sid}, null, null, "through_id ASC");
                try { while (events.moveToNext()) target.compaction(events.getLong(0)); }
                finally { events.close(); }
                Cursor window = db.query("context_windows", new String[]{"through_id", "window"}, "session_id=?",
                        new String[]{sid}, null, null, null);
                try { if (window.moveToFirst()) target.context(window.getLong(0), window.getString(1)); }
                finally { window.close(); }
                Cursor run = db.query("runs", new String[]{"running", "goal", "status", "elapsed_ms",
                        "turn_elapsed_ms", "turn_think_ms", "tokens_used", "token_budget", "budget_wrap_finished"},
                        "session_id=?", new String[]{sid}, null, null, null);
                try {
                    if (run.moveToFirst()) {
                        Run saved = new Run();
                        saved.running = run.getInt(0) != 0;
                        saved.goal = run.getString(1) == null ? "" : run.getString(1);
                        saved.status = run.getString(2) == null ? "" : run.getString(2);
                        saved.elapsedMs = run.getLong(3);
                        if (!run.isNull(4)) saved.turnElapsedMs = Long.valueOf(run.getLong(4));
                        if (!run.isNull(5)) saved.turnThinkMs = Long.valueOf(run.getLong(5));
                        saved.tokensUsed = run.getLong(6);
                        saved.tokenBudget = run.getLong(7);
                        if (!run.isNull(8)) saved.budgetWrapFinished = Boolean.valueOf(run.getInt(8) != 0);
                        target.run(saved);
                    }
                } finally { run.close(); }
                target.finish(updated);
            }
        } finally { sessions.close(); }
        long next = maxId + 1;
        try {
            // 删掉的最大编号也不复用：子代理目录是按会话编号放的。
            Cursor sequence = db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name='sessions'", null);
            try { if (sequence.moveToFirst()) next = Math.max(next, sequence.getLong(0) + 1); }
            finally { sequence.close(); }
        } catch (RuntimeException missing) { /* 没有 sqlite_sequence 时用最大编号 */ }
        Map<String, String> notes = new LinkedHashMap<String, String>();
        if (hasTable(db, "reasoning_notes")) {
            Cursor c = db.query("reasoning_notes", new String[]{"cache_key", "summary"}, null, null, null, null, null);
            try { while (c.moveToNext()) notes.put(c.getString(0), c.getString(1)); }
            finally { c.close(); }
        }
        files.commitImport(next, notes);
    }

    /** 旧库一行消息原样转成文件里的字段；解析不了的 JSON 原样留着，读的时候和以前一样报错。 */
    private static JSONObject legacyMessage(Cursor c) throws Exception {
        JSONObject line = new JSONObject();
        line.put("role", c.getString(1));
        line.put("content", c.getString(2) == null ? "" : c.getString(2));
        String reasoning = c.getString(3);
        if (reasoning != null && reasoning.length() > 0) line.put("reasoning", reasoning);
        String calls = c.getString(4);
        if (calls != null && calls.length() > 0) {
            try { line.put("tool_calls", new JSONArray(calls)); } catch (Exception ignored) { }
        }
        String callId = c.getString(5);
        if (callId != null && callId.length() > 0) line.put("tool_call_id", callId);
        if (!c.isNull(6) && c.getLong(6) != 0) line.put("elapsed_ms", c.getLong(6));
        if (!c.isNull(7) && c.getLong(7) != 0) line.put("think_ms", c.getLong(7));
        String parts = c.getString(8);
        if (parts != null && parts.length() > 0) {
            try { line.put("display_parts", new JSONArray(parts)); } catch (Exception ignored) { }
        }
        String workDir = c.getString(9);
        if (workDir != null && workDir.length() > 0) line.put("work_dir", workDir);
        String selection = c.getString(10);
        if (selection != null && selection.length() > 0) {
            try { line.put("mcp_selection", new JSONObject(selection)); }
            catch (Exception corrupt) { line.put("mcp_selection", selection); }
        }
        return line;
    }

    public synchronized long create(String title) {
        return files().create(title);
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
        files().append(sessionId, message);
    }

    /** Save a model checkpoint without replacing the visible transcript. */
    public synchronized void replaceAll(long sessionId, List<Message> messages) {
        if (sessionId < 0) return;
        files().replaceAll(sessionId, messages);
    }

    public synchronized List<Session> sessions() {
        return files().sessions();
    }

    public synchronized String title(long id) {
        return id < 0 ? "" : files().title(id);
    }

    /** 没有会话时返回 -1。 */
    public synchronized long latestId() {
        return files().latestId();
    }

    /** Call on a worker thread. A non-positive cursor selects the latest page. */
    public synchronized MessagePage messagePage(long sessionId, long beforeId, int limit) {
        if (sessionId < 0) return new MessagePage(new java.util.ArrayList<Message>(), 0, 0, null, "", null,
                Collections.<Message>emptyList());
        return files().messagePage(sessionId, beforeId, limit);
    }

    /** Restore the model checkpoint plus messages appended after it. */
    public synchronized List<Message> contextMessages(long sessionId) {
        if (sessionId < 0) return new java.util.ArrayList<Message>();
        return files().contextMessages(sessionId);
    }

    public synchronized String reasoningNote(String key) {
        return files().reasoningNote(key);
    }

    public synchronized void saveReasoningNote(String key, String summary) {
        files().saveReasoningNote(key, summary);
    }

    /**
     * 把耗时记到本轮最后一条助手消息上，重开时还能显示。
     * 已经记下的更长耗时不被更短的盖掉：界面重进后若只用自己的几秒收尾，
     * 不能把这一轮真正等过的时间抹掉。
     */
    public synchronized void markElapsed(long sessionId, long elapsedMs, long thinkMs) {
        if (sessionId < 0 || elapsedMs <= 0) return;
        files().markElapsed(sessionId, elapsedMs, thinkMs);
    }

    public synchronized void delete(long sessionId) {
        if (sessionId >= 0) files().delete(sessionId);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("request_events", "session_id=?", new String[]{String.valueOf(sessionId)});
            db.delete("diagnostic_errors", "session_id=?", new String[]{String.valueOf(sessionId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 记下这个会话还在不在跑，以及目标。进程被杀掉后靠它接上。 */
    public synchronized void saveRun(long sessionId, boolean running, String goal,
            String status, long elapsedMs, long turnElapsedMs, Long turnThinkMs,
            long tokensUsed, long tokenBudget, boolean budgetWrapFinished) {
        if (sessionId < 0) return;
        Run run = new Run();
        run.running = running;
        run.goal = goal == null ? "" : goal;
        run.status = status == null ? "" : status;
        run.elapsedMs = Math.max(0L, elapsedMs);
        run.turnElapsedMs = Long.valueOf(Math.max(0L, turnElapsedMs));
        run.turnThinkMs = turnThinkMs == null ? null : Long.valueOf(Math.max(0L, turnThinkMs.longValue()));
        run.tokensUsed = Math.max(0L, tokensUsed);
        run.tokenBudget = Math.max(0L, tokenBudget);
        run.budgetWrapFinished = Boolean.valueOf(budgetWrapFinished);
        files().saveRun(sessionId, run);
    }

    /** Duration heartbeat never creates a run or changes its running state. */
    public synchronized void saveClock(long sessionId, long elapsedMs, long turnElapsedMs,
            Long turnThinkMs) {
        if (sessionId < 0) return;
        files().saveClock(sessionId, elapsedMs, turnElapsedMs, turnThinkMs);
    }

    public synchronized Run readRun(long sessionId) {
        return sessionId < 0 ? new Run() : files().readRun(sessionId);
    }

    public synchronized List<Long> runningIds() {
        return files().runningIds();
    }
}
