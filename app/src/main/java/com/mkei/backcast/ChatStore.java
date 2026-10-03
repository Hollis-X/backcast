package com.mkei.backcast;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.mkei.backcast.agent.Message;

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

    /** Local transport diagnostics; no request body, credentials or provider response. */
    public static final class RequestEvent {
        public final long id, recordedAt, elapsedMs;
        public final String purpose, outcome, reason;
        public final int retryCount;

        RequestEvent(long id, long recordedAt, long elapsedMs, String purpose,
                String outcome, String reason, int retryCount) {
            this.id = id; this.recordedAt = recordedAt; this.elapsedMs = elapsedMs;
            this.purpose = purpose; this.outcome = outcome; this.reason = reason;
            this.retryCount = retryCount;
        }
    }

    /** A bounded transcript page; cursors stay valid while new messages are appended. */
    public static final class MessagePage {
        public final List<Message> messages;
        public final long firstId;
        public final long lastId;
        public final long earlierCount;
        public final String requestBefore;
        /** Only tool call labels, for results whose assistant is in the previous page. */
        public final Message leadingAssistant;
        /** Results just after this page; used to finish existing labels, never drawn twice. */
        public final List<Message> trailingResults;

        public MessagePage(List<Message> messages, long firstId, long lastId, long earlierCount,
                String requestBefore, Message leadingAssistant) {
            this(messages, firstId, lastId, earlierCount, requestBefore, leadingAssistant,
                    new ArrayList<Message>());
        }

        public MessagePage(List<Message> messages, long firstId, long lastId, long earlierCount,
                String requestBefore, Message leadingAssistant, List<Message> trailingResults) {
            this.messages = Collections.unmodifiableList(messages);
            this.firstId = firstId;
            this.lastId = lastId;
            this.earlierCount = earlierCount;
            this.requestBefore = requestBefore;
            this.leadingAssistant = leadingAssistant;
            this.trailingResults = Collections.unmodifiableList(trailingResults);
        }
    }

    public static class Run {
        public String goal = "";
        public String status = "";
        public long elapsedMs;
        public boolean running;
        /** 这一轮起点的开机时间。0 表示没有正在计的一轮。 */
        public long turnAt;
        /** 起点的墙钟，用来确认还是同一次开机。 */
        public long turnWall;
        /** 第一次有内容的开机时间。0 表示还没有。 */
        public long seenAt;
        /** 目标累计用掉的 token，跨重启保留。 */
        public long tokensUsed;
        /** 目标 token 预算，0 表示没设。 */
        public long tokenBudget;
        /** Null for legacy runs whose budget wrap-up must be inferred. */
        public Boolean budgetWrapFinished;
    }

    public ChatStore(Context context) {
        super(context.getApplicationContext(), "backcast.db", null, 11);
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
                + "work_dir TEXT)");
        db.execSQL("CREATE INDEX idx_messages_session ON messages(session_id, id)");
        createRuns(db);
        createContext(db);
        createRequestEvents(db);
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
    }

    private static void createRequestEvents(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS request_events ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,session_id INTEGER NOT NULL,"
                + "recorded_at INTEGER NOT NULL, purpose TEXT NOT NULL,"
                + "elapsed_ms INTEGER NOT NULL,outcome TEXT NOT NULL,reason TEXT NOT NULL,"
                + "retry_count INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_request_events_session ON request_events(session_id,id)");
    }

    /** Keep at most 200 completed attempts per conversation, independently of model history. */
    public synchronized void recordRequest(long sessionId, String purpose, long elapsedMs,
            String outcome, String reason, int retryCount) {
        if (sessionId < 0) return;
        ContentValues values = new ContentValues();
        values.put("session_id", Long.valueOf(sessionId));
        values.put("recorded_at", Long.valueOf(System.currentTimeMillis()));
        values.put("purpose", "compact".equals(purpose) ? "compact" : "review".equals(purpose) ? "review" : "model");
        values.put("elapsed_ms", Long.valueOf(Math.max(0L, elapsedMs)));
        values.put("outcome", "success".equals(outcome) ? "success" : "retryable_error".equals(outcome)
                ? "retryable_error" : "cancelled".equals(outcome) ? "cancelled" : "error");
        String safe = reason == null ? "" : reason.replace('\n', ' ').replace('\r', ' ').trim();
        values.put("reason", safe.length() > 160 ? safe.substring(0, 160) : safe);
        values.put("retry_count", Integer.valueOf(Math.max(0, retryCount)));
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

    public synchronized List<RequestEvent> requestEvents(long sessionId, int limit) {
        List<RequestEvent> events = new ArrayList<RequestEvent>();
        Cursor cursor = getReadableDatabase().query("request_events",
                new String[]{"id", "recorded_at", "elapsed_ms", "purpose", "outcome", "reason", "retry_count"},
                "session_id=?", new String[]{String.valueOf(sessionId)}, null, null, "id DESC",
                String.valueOf(Math.max(1, Math.min(200, limit))));
        try {
            while (cursor.moveToNext()) events.add(new RequestEvent(cursor.getLong(0), cursor.getLong(1),
                    cursor.getLong(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getInt(6)));
        } finally { cursor.close(); }
        return Collections.unmodifiableList(events);
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
        if (sessionId < 0 || message == null || Message.SYSTEM.equals(message.role)) {
            return;
        }
        if (Message.USER.equals(message.role)
                && (com.mkei.backcast.agent.Goal.isSteer(message.content)
                || com.mkei.backcast.agent.Goal.isNote(message.content))) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.insert("messages", null, valuesOf(sessionId, message));
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
            if (messages != null) {
                for (int i = 0; i < messages.size(); i++) {
                    Message m = messages.get(i);
                    if (m == null || Message.SYSTEM.equals(m.role)) {
                        continue;
                    }
                    if (com.mkei.backcast.agent.Goal.isSteer(m.content)
                            || com.mkei.backcast.agent.Goal.isNote(m.content)) {
                        continue;
                    }
                    window.put(m.toCheckpointJson());
                }
            }
            db.update("sessions", touchValues(), "id=?",
                    new String[]{String.valueOf(sessionId)});
            ContentValues checkpoint = new ContentValues();
            checkpoint.put("session_id", Long.valueOf(sessionId));
            checkpoint.put("through_id", Long.valueOf(through));
            checkpoint.put("window", window.toString());
            db.insertWithOnConflict("context_windows", null, checkpoint, SQLiteDatabase.CONFLICT_REPLACE);
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

    public synchronized List<Message> messages(long sessionId) {
        return readMessages(sessionId, -1);
    }

    /** Call on a worker thread. A non-positive cursor selects the latest page. */
    public synchronized MessagePage messagePage(long sessionId, long beforeId, int limit) {
        int pageSize = Math.min(128, Math.max(1, limit));
        List<Message> out = new ArrayList<Message>();
        SQLiteDatabase db = getReadableDatabase();
        String selection = "session_id=?";
        String[] args = new String[]{String.valueOf(sessionId)};
        if (beforeId > 0) {
            selection += " AND id<?";
            args = new String[]{String.valueOf(sessionId), String.valueOf(beforeId)};
        }
        Cursor c = db.query("messages", new String[]{"id", "role", "content", "reasoning",
                "tool_calls", "tool_call_id", "elapsed_ms", "think_ms", "display_parts", "work_dir"},
                selection, args, null, null, "id DESC", String.valueOf(pageSize));
        long firstId = 0;
        long lastId = 0;
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                if (lastId == 0) lastId = id;
                firstId = id;
                out.add(readMessage(c, 1));
            }
        } finally {
            c.close();
        }
        Collections.reverse(out);
        if (out.isEmpty()) return new MessagePage(out, 0, 0, 0, "", null);

        String[] prefixArgs = new String[]{String.valueOf(sessionId), String.valueOf(firstId)};
        long earlierCount = 0;
        Cursor count = db.rawQuery("SELECT COUNT(*) FROM messages WHERE session_id=? AND id<?", prefixArgs);
        try {
            if (count.moveToFirst()) earlierCount = count.getLong(0);
        } finally {
            count.close();
        }
        String request = "";
        Cursor user = db.query("messages", new String[]{"content"},
                "session_id=? AND id<? AND role=?",
                new String[]{prefixArgs[0], prefixArgs[1], Message.USER},
                null, null, "id DESC", "1");
        try {
            if (user.moveToFirst()) {
                String text = user.getString(0);
                if (text != null && !com.mkei.backcast.agent.Goal.isSteer(text)
                        && !com.mkei.backcast.agent.Goal.isNote(text)) request = text;
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
        return new MessagePage(out, firstId, lastId, earlierCount, request, leading,
                trailingResults(db, sessionId, lastId, out));
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
                "tool_call_id", "elapsed_ms", "think_ms", "display_parts", "work_dir"},
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
                        "elapsed_ms", "think_ms", "display_parts", "work_dir"},
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
            db.delete("sessions", "id=?", new String[]{String.valueOf(sessionId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 记下这个会话还在不在跑，以及目标。进程被杀掉后靠它接上。 */
    public synchronized void saveRun(long sessionId, boolean running, String goal,
            String status, long elapsedMs, long turnAt, long turnWall, long seenAt,
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
        cv.put("turn_at", Long.valueOf(turnAt < 0 ? 0 : turnAt));
        cv.put("turn_wall", Long.valueOf(turnWall < 0 ? 0 : turnWall));
        cv.put("seen_at", Long.valueOf(seenAt < 0 ? 0 : seenAt));
        cv.put("tokens_used", Long.valueOf(tokensUsed < 0 ? 0 : tokensUsed));
        cv.put("token_budget", Long.valueOf(tokenBudget < 0 ? 0 : tokenBudget));
        cv.put("budget_wrap_finished", Integer.valueOf(budgetWrapFinished ? 1 : 0));
        getWritableDatabase().insertWithOnConflict(
                "runs", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized Run readRun(long sessionId) {
        Run run = new Run();
        Cursor c = getReadableDatabase().query(
                "runs", new String[]{"running", "goal", "status", "elapsed_ms",
                        "turn_at", "turn_wall", "seen_at", "tokens_used", "token_budget", "budget_wrap_finished"},
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
            run.turnAt = c.getLong(4);
            run.turnWall = c.getLong(5);
            run.seenAt = c.getLong(6);
            run.tokensUsed = c.getLong(7);
            run.tokenBudget = c.getLong(8);
            if (!c.isNull(9)) run.budgetWrapFinished = Boolean.valueOf(c.getInt(9) != 0);
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
