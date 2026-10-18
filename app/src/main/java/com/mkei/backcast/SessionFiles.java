package com.mkei.backcast;

import com.mkei.backcast.agent.Compactor;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.mcp.McpSelection;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 聊天记录存成本地文件，不进数据库（数据库只放报错）。布局参照 Claude Code 的「每个会话一份只追加的 JSONL」：
 * <pre>
 * .backcast/
 *   next-session-id         下一个会话编号，只增不减，删掉的编号不复用
 *   reasoning-notes.json    活动摘要缓存，最多 {@link #NOTE_LIMIT} 条
 *   sessions/&lt;编号&gt;/
 *     meta.json             {id, title, createdAt}
 *     transcript.jsonl      每行一条记录，只往后追加：
 *                             {"type":"message","id":n, role/content/...}  一条可见消息
 *                             {"type":"elapsed","id":n,"elapsed_ms","think_ms"}  补记某条助手消息的耗时
 *                             {"type":"compaction","through":n}  压缩分隔线画在第 n 条消息后面
 *     context.json          {through, window}：压缩后的模型窗口，压缩时整份替换
 *     run.json              运行状态（目标、计时、预算），进程被杀后靠它接上
 *   sub-agents/             子代理记录，见 {@link JsonSubAgentStore}
 * </pre>
 * 追加写完就 fsync；断电留下的半行在下次读的时候截掉。整份替换的小文件先写 .tmp 再改名。
 * 消息编号在会话内从 1 递增，翻页游标就是它。读的时候只建一份「编号 → 文件偏移」的索引，按需读取单行，
 * 几万条消息的会话打开时也不用整份解析。调用方（ChatStore）负责加锁。
 */
final class SessionFiles {
    static final int NOTE_LIMIT = 500;
    private static final int PAGE_LIMIT = 128;

    private final File root, sessions;
    /** 最近用过的几个会话的索引；只有本类写文件，追加时同步更新。 */
    private final LinkedHashMap<Long, Index> indexes = new LinkedHashMap<Long, Index>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Index> eldest) { return size() > 6; }
    };
    private LinkedHashMap<String, String> notes;

    SessionFiles(File root) {
        this.root = root;
        this.sessions = new File(root, "sessions");
    }

    File root() { return root; }

    // ---- 会话 ----

    long create(String title) {
        try {
            mkdirs(sessions);
            long id = nextId();
            File dir = dir(id);
            if (dir.exists()) throw new IOException("会话目录已存在：" + dir.getName());
            writeAtomic(new File(root, "next-session-id"), String.valueOf(id + 1), true);
            mkdirs(dir);
            writeAtomic(new File(dir, "meta.json"), new JSONObject().put("id", id)
                    .put("title", title == null || title.length() == 0 ? "新会话" : title)
                    .put("createdAt", System.currentTimeMillis()).toString(), true);
            return id;
        } catch (Exception failure) { throw new IllegalStateException("会话创建失败：" + failure.getMessage(), failure); }
    }

    /** 按最近更新排序：取 meta、聊天记录、模型窗口里最新的修改时间（run.json 的心跳不算）。 */
    List<ChatStore.Session> sessions() {
        List<ChatStore.Session> out = new ArrayList<ChatStore.Session>();
        final Map<Long, Long> updated = new HashMap<Long, Long>();
        File[] dirs = sessions.listFiles();
        if (dirs != null) for (File dir : dirs) {
            long id = idOf(dir);
            if (id < 0) continue;
            File meta = new File(dir, "meta.json");
            if (!meta.isFile()) continue;
            ChatStore.Session session = new ChatStore.Session();
            session.id = id;
            session.title = titleOf(meta);
            out.add(session);
            updated.put(Long.valueOf(id), Long.valueOf(Math.max(meta.lastModified(),
                    Math.max(new File(dir, "transcript.jsonl").lastModified(), new File(dir, "context.json").lastModified()))));
        }
        Collections.sort(out, new java.util.Comparator<ChatStore.Session>() {
            @Override public int compare(ChatStore.Session a, ChatStore.Session b) {
                int byTime = Long.compare(updated.get(Long.valueOf(b.id)), updated.get(Long.valueOf(a.id)));
                return byTime != 0 ? byTime : Long.compare(b.id, a.id);
            }
        });
        return out;
    }

    String title(long id) {
        File meta = new File(dir(id), "meta.json");
        return meta.isFile() ? titleOf(meta) : "";
    }

    long latestId() {
        List<ChatStore.Session> all = sessions();
        return all.isEmpty() ? -1 : all.get(0).id;
    }

    /** 先删 meta（删到一半断电，没有 meta 的目录就不再算会话），再删其余文件。 */
    void delete(long id) {
        indexes.remove(Long.valueOf(id));
        File dir = dir(id);
        if (!dir.exists()) return;
        new File(dir, "meta.json").delete();
        deleteTree(dir);
        if (dir.exists()) throw new IllegalStateException("会话文件删除失败：" + dir.getPath());
    }

    // ---- 消息 ----

    void append(long sessionId, Message message) {
        try {
            Index index = index(sessionId);
            long id = index.nextId;
            JSONObject line = encode(message).put("type", "message").put("id", id);
            appendLine(index, line, message.role, message.toolCallId);
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("会话消息保存失败：" + failure.getMessage(), failure); }
    }

    ChatStore.MessagePage messagePage(long sessionId, long beforeId, int limit) {
        int pageSize = Math.min(PAGE_LIMIT, Math.max(1, limit));
        Index index = index(sessionId);
        List<Entry> all = index.messages;
        int end = all.size();
        if (beforeId > 0) end = lowerBound(all, beforeId);
        int start = Math.max(0, end - pageSize);
        if (start >= end) return new ChatStore.MessagePage(new ArrayList<Message>(), 0, 0, null, "", null,
                Collections.<Message>emptyList());
        List<Message> out = readRange(index, all.subList(start, end));
        long firstId = all.get(start).id, lastId = all.get(end - 1).id;

        Message request = null;
        String disclosure = "";
        boolean latest = true;
        for (int i = start - 1; i >= 0; i--) {
            Entry entry = all.get(i);
            if (!Message.USER.equals(entry.role)) continue;
            Message user = read(index, entry);
            String text = user.content;
            boolean internal = com.mkei.backcast.agent.Goal.isSteer(text) || com.mkei.backcast.agent.Goal.isNote(text);
            if (latest) {
                if (!internal && text != null) disclosure = text;
                latest = false;
            }
            if (!internal) { request = user; break; }
        }

        Message leading = null;
        if (Message.TOOL.equals(out.get(0).role)) {
            for (int i = start - 1; i >= 0; i--) {
                Entry entry = all.get(i);
                if (Message.TOOL.equals(entry.role)) continue;
                if (Message.ASSISTANT.equals(entry.role)) {
                    Message previous = read(index, entry);
                    JSONArray shown = new JSONArray();
                    if (previous.toolCalls != null) for (int c = 0; c < previous.toolCalls.length(); c++) {
                        JSONObject call = previous.toolCalls.optJSONObject(c);
                        if (call == null) continue;
                        String callId = call.optString("id", "");
                        for (int j = 0; j < out.size() && Message.TOOL.equals(out.get(j).role); j++) {
                            if (callId.equals(out.get(j).toolCallId)) { shown.put(call); break; }
                        }
                    }
                    if (shown.length() > 0) leading = Message.assistant("", shown);
                }
                break;
            }
        }
        List<Message> trailing = trailingResults(index, end, out);
        return new ChatStore.MessagePage(withCompactions(index, firstId, lastId, out, all.subList(start, end)),
                firstId, start, request, disclosure, leading, trailing);
    }

    /** 下一页之后、同一批工具调用的结果：只用来补齐已有的工具行，不会再画一遍。 */
    private List<Message> trailingResults(Index index, int end, List<Message> page) {
        ArrayList<Message> out = new ArrayList<Message>();
        int assistantAt = page.size() - 1;
        while (assistantAt >= 0 && Message.TOOL.equals(page.get(assistantAt).role)) assistantAt--;
        if (assistantAt < 0) return out;
        Message assistant = page.get(assistantAt);
        if (!Message.ASSISTANT.equals(assistant.role) || assistant.toolCalls == null) return out;
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < assistant.toolCalls.length() && ids.size() < PAGE_LIMIT; i++) {
            JSONObject call = assistant.toolCalls.optJSONObject(i);
            if (call == null) continue;
            String id = call.optString("id", "");
            if (id.length() == 0) continue;
            boolean complete = false;
            for (int j = assistantAt + 1; j < page.size(); j++) {
                if (id.equals(page.get(j).toolCallId)) { complete = true; break; }
            }
            if (!complete) ids.add(id);
        }
        if (ids.isEmpty()) return out;
        List<Entry> all = index.messages;
        for (int i = end; i < all.size() && out.size() < PAGE_LIMIT; i++) {
            Entry entry = all.get(i);
            if (!Message.TOOL.equals(entry.role)) break;
            if (entry.toolCallId != null && ids.contains(entry.toolCallId)) out.add(read(index, entry));
        }
        return out;
    }

    /** 压缩分隔线只是装饰：按消息边界插进页面，不改变游标，也不进模型窗口。 */
    private List<Message> withCompactions(Index index, long firstId, long lastId, List<Message> messages, List<Entry> entries) {
        boolean any = false;
        for (Long through : index.compactions) {
            if (through.longValue() >= firstId && through.longValue() <= lastId) { any = true; break; }
        }
        if (!any) return messages;
        List<Message> decorated = new ArrayList<Message>(messages.size() + 4);
        int shown = 0;
        for (int i = 0; i < messages.size(); i++) {
            decorated.add(messages.get(i));
            if (shown < PAGE_LIMIT && index.compactions.contains(Long.valueOf(entries.get(i).id))) {
                decorated.add(new Message(Message.COMPACTION, ""));
                shown++;
            }
        }
        return decorated;
    }

    /** 模型窗口：最近一次压缩保存的窗口，加上它之后追加的消息。 */
    List<Message> contextMessages(long sessionId) {
        List<Message> out = new ArrayList<Message>();
        long through = -1;
        File context = new File(dir(sessionId), "context.json");
        if (context.isFile()) {
            try {
                JSONObject saved = new JSONObject(read(context));
                through = saved.getLong("through");
                JSONArray window = saved.getJSONArray("window");
                for (int i = 0; i < window.length(); i++) out.add(Message.fromCheckpointJson(window.getJSONObject(i)));
            } catch (Exception error) {
                throw new IllegalStateException("Invalid saved context window", error);
            }
        }
        Index index = index(sessionId);
        for (Entry entry : index.messages) if (entry.id > through) out.add(read(index, entry));
        return out;
    }

    /** 压缩后保存新窗口；不动可见的聊天记录。 */
    void replaceAll(long sessionId, List<Message> messages) {
        try {
            Index index = index(sessionId);
            long through = index.messages.isEmpty() ? 0 : index.messages.get(index.messages.size() - 1).id;
            JSONArray window = new JSONArray();
            boolean compacted = false;
            if (messages != null) for (Message m : messages) {
                if (m == null || Message.SYSTEM.equals(m.role) || Message.COMPACTION.equals(m.role)) continue;
                if (!Message.isCoordination(m.content) && (com.mkei.backcast.agent.Goal.isSteer(m.content)
                        || com.mkei.backcast.agent.Goal.isNote(m.content))) continue;
                window.put(m.toCheckpointJson());
                if (Compactor.isSummary(m)) compacted = true;
            }
            mkdirs(dir(sessionId));
            writeAtomic(new File(dir(sessionId), "context.json"),
                    new JSONObject().put("through", through).put("window", window).toString(), true);
            if (compacted && through > 0 && !index.compactions.contains(Long.valueOf(through))) {
                appendLine(index, new JSONObject().put("type", "compaction").put("through", through), null, null);
            }
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("会话上下文保存失败：" + failure.getMessage(), failure); }
    }

    /**
     * 把耗时记到本轮最后一条助手消息上：追加一行补记，读的时候盖到那条消息上。
     * 已经记下的更长耗时不被更短的盖掉。
     */
    void markElapsed(long sessionId, long elapsedMs, long thinkMs) {
        if (thinkMs < 0) thinkMs = 0;
        Index index = index(sessionId);
        List<Entry> all = index.messages;
        Entry target = null;
        for (int i = all.size() - 1; i >= 0; i--) {
            Entry entry = all.get(i);
            if (Message.USER.equals(entry.role)) break;
            if (Message.ASSISTANT.equals(entry.role)) { target = entry; break; }
        }
        if (target == null) return;
        Message current = read(index, target);
        if (current.elapsedMs > elapsedMs) elapsedMs = current.elapsedMs;
        if (current.thinkMs > thinkMs) thinkMs = current.thinkMs;
        if (thinkMs > elapsedMs) elapsedMs = thinkMs;
        if (elapsedMs == current.elapsedMs && thinkMs == current.thinkMs) return;
        try {
            appendLine(index, new JSONObject().put("type", "elapsed").put("id", target.id)
                    .put("elapsed_ms", elapsedMs).put("think_ms", thinkMs), null, null);
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("耗时保存失败：" + failure.getMessage(), failure); }
    }

    // ---- 运行状态 ----

    void saveRun(long sessionId, ChatStore.Run run) {
        writeRun(sessionId, run, true);
    }

    /** 计时心跳：只更新正在跑的会话，不新建、不改运行状态。 */
    void saveClock(long sessionId, long elapsedMs, long turnElapsedMs, Long turnThinkMs) {
        File file = new File(dir(sessionId), "run.json");
        if (!file.isFile()) return;
        ChatStore.Run run = readRun(sessionId);
        if (!run.running) return;
        run.elapsedMs = Math.max(0L, elapsedMs);
        run.turnElapsedMs = Long.valueOf(Math.max(0L, turnElapsedMs));
        run.turnThinkMs = turnThinkMs == null ? null : Long.valueOf(Math.max(0L, turnThinkMs.longValue()));
        writeRun(sessionId, run, false);
    }

    ChatStore.Run readRun(long sessionId) {
        ChatStore.Run run = new ChatStore.Run();
        File file = new File(dir(sessionId), "run.json");
        if (!file.isFile()) return run;
        try {
            JSONObject json = new JSONObject(read(file));
            run.running = json.optBoolean("running", false);
            run.goal = json.optString("goal", "");
            run.status = json.optString("status", "");
            run.elapsedMs = json.optLong("elapsedMs", 0L);
            if (json.has("turnElapsedMs")) run.turnElapsedMs = Long.valueOf(json.getLong("turnElapsedMs"));
            if (json.has("turnThinkMs")) run.turnThinkMs = Long.valueOf(json.getLong("turnThinkMs"));
            run.tokensUsed = json.optLong("tokensUsed", 0L);
            run.tokenBudget = json.optLong("tokenBudget", 0L);
            if (json.has("budgetWrapFinished")) run.budgetWrapFinished = Boolean.valueOf(json.getBoolean("budgetWrapFinished"));
            return run;
        } catch (Exception invalid) {
            throw new IllegalStateException("运行状态记录损坏：" + file.getPath(), invalid);
        }
    }

    List<Long> runningIds() {
        List<Long> out = new ArrayList<Long>();
        File[] dirs = sessions.listFiles();
        if (dirs == null) return out;
        java.util.Arrays.sort(dirs);
        for (File dir : dirs) {
            long id = idOf(dir);
            if (id < 0 || !new File(dir, "run.json").isFile()) continue;
            try { if (readRun(id).running) out.add(Long.valueOf(id)); }
            catch (IllegalStateException corrupt) { /* 坏掉的运行状态不自动恢复；打开会话时会报出来。 */ }
        }
        return out;
    }

    private void writeRun(long sessionId, ChatStore.Run run, boolean durable) {
        try {
            JSONObject json = new JSONObject().put("running", run.running).put("goal", run.goal == null ? "" : run.goal)
                    .put("status", run.status == null ? "" : run.status).put("elapsedMs", Math.max(0L, run.elapsedMs))
                    .put("tokensUsed", Math.max(0L, run.tokensUsed)).put("tokenBudget", Math.max(0L, run.tokenBudget));
            if (run.turnElapsedMs != null) json.put("turnElapsedMs", run.turnElapsedMs.longValue());
            if (run.turnThinkMs != null) json.put("turnThinkMs", run.turnThinkMs.longValue());
            if (run.budgetWrapFinished != null) json.put("budgetWrapFinished", run.budgetWrapFinished.booleanValue());
            mkdirs(dir(sessionId));
            writeAtomic(new File(dir(sessionId), "run.json"), json.toString(), durable);
        } catch (Exception failure) { throw new IllegalStateException("运行状态保存失败：" + failure.getMessage(), failure); }
    }

    // ---- 活动摘要缓存 ----

    String reasoningNote(String key) {
        String value = notes().get(key);
        return value == null ? "" : value;
    }

    void saveReasoningNote(String key, String summary) {
        LinkedHashMap<String, String> all = notes();
        all.remove(key);
        all.put(key, summary == null ? "" : summary);
        writeNotes(all);
    }

    private void writeNotes(LinkedHashMap<String, String> all) {
        Iterator<String> oldest = all.keySet().iterator();
        while (all.size() > NOTE_LIMIT && oldest.hasNext()) { oldest.next(); oldest.remove(); }
        try {
            JSONArray out = new JSONArray();
            for (Map.Entry<String, String> entry : all.entrySet()) out.put(new JSONArray().put(entry.getKey()).put(entry.getValue()));
            mkdirs(root);
            writeAtomic(new File(root, "reasoning-notes.json"), out.toString(), false);
        } catch (Exception failure) { throw new IllegalStateException("活动摘要缓存保存失败：" + failure.getMessage(), failure); }
    }

    private LinkedHashMap<String, String> notes() {
        if (notes != null) return notes;
        notes = new LinkedHashMap<String, String>();
        File file = new File(root, "reasoning-notes.json");
        if (file.isFile()) {
            try {
                JSONArray saved = new JSONArray(read(file));
                for (int i = 0; i < saved.length(); i++) {
                    JSONArray pair = saved.getJSONArray(i);
                    notes.put(pair.getString(0), pair.getString(1));
                }
            } catch (Exception corrupt) { notes.clear(); /* 只是缓存，坏了就重新生成摘要。 */ }
        }
        return notes;
    }

    // ---- 从旧数据库搬过来（ChatStore 调用）----

    /** 搬迁时写进暂存目录用的写入器：一个会话一次打开、批量写完再 fsync。 */
    static final class Import {
        private final File dir;
        private final FileOutputStream transcript;
        Import(File staging, long id, String title, long createdAt) throws Exception {
            dir = new File(staging, String.valueOf(id));
            mkdirs(dir);
            writeAtomic(new File(dir, "meta.json"), new JSONObject().put("id", id).put("title",
                    title == null || title.length() == 0 ? "新会话" : title).put("createdAt", createdAt).toString(), false);
            transcript = new FileOutputStream(new File(dir, "transcript.jsonl"));
        }
        /** fields 是旧库一行消息的原样字段（解析不了的也原样留着，读的时候和以前一样报错）。 */
        void message(long id, JSONObject fields) throws Exception {
            transcript.write((fields.put("type", "message").put("id", id).toString() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        void compaction(long through) throws Exception {
            transcript.write((new JSONObject().put("type", "compaction").put("through", through).toString() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        void context(long through, String window) throws Exception {
            writeAtomic(new File(dir, "context.json"), new JSONObject().put("through", through)
                    .put("window", new JSONArray(window)).toString(), false);
        }
        void run(ChatStore.Run run) throws Exception {
            JSONObject json = new JSONObject().put("running", run.running).put("goal", run.goal).put("status", run.status)
                    .put("elapsedMs", run.elapsedMs).put("tokensUsed", run.tokensUsed).put("tokenBudget", run.tokenBudget);
            if (run.turnElapsedMs != null) json.put("turnElapsedMs", run.turnElapsedMs.longValue());
            if (run.turnThinkMs != null) json.put("turnThinkMs", run.turnThinkMs.longValue());
            if (run.budgetWrapFinished != null) json.put("budgetWrapFinished", run.budgetWrapFinished.booleanValue());
            writeAtomic(new File(dir, "run.json"), json.toString(), false);
        }
        /** 关闭并 fsync，再把修改时间设成旧库里的 updated_at，会话列表的排序不变。 */
        void finish(long updatedAt) throws Exception {
            try { transcript.getFD().sync(); } finally { transcript.close(); }
            if (updatedAt > 0) {
                new File(dir, "transcript.jsonl").setLastModified(updatedAt);
                new File(dir, "meta.json").setLastModified(updatedAt);
                new File(dir, "context.json").setLastModified(updatedAt);
            }
        }
    }

    File staging() { return new File(root, "sessions.importing"); }

    /** sessions 目录里已经有东西。搬迁完成前不会新建会话，所以这只可能是已经启用的搬迁结果。 */
    boolean hasSessions() {
        String[] names = sessions.list();
        return names != null && names.length > 0;
    }

    /** 暂存目录整个改名成 sessions；sessions 已经存在（不该发生）就拒绝，免得覆盖新记录。 */
    void commitImport(long nextId, Map<String, String> reasoning) throws Exception {
        File staging = staging();
        if (sessions.exists()) {
            String[] left = sessions.list();
            if (left != null && left.length > 0) throw new IOException("sessions 目录已有记录，拒绝覆盖。");
            sessions.delete();
        }
        if (staging.exists() && !staging.renameTo(sessions)) throw new IOException("无法启用搬迁后的会话目录。");
        writeAtomic(new File(root, "next-session-id"), String.valueOf(nextId), true);
        if (reasoning != null && !reasoning.isEmpty()) {
            notes = new LinkedHashMap<String, String>(reasoning);
            writeNotes(notes);
        }
        indexes.clear();
    }

    static void deleteTree(File file) {
        File[] children = file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath()) ? file.listFiles() : null;
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    // ---- 索引与读写 ----

    private static final class Entry {
        final long id, offset;
        final int length;
        final String role, toolCallId;
        Entry(long id, long offset, int length, String role, String toolCallId) {
            this.id = id; this.offset = offset; this.length = length; this.role = role; this.toolCallId = toolCallId;
        }
    }

    private static final class Index {
        final File file;
        final List<Entry> messages = new ArrayList<Entry>();
        final Map<Long, long[]> elapsed = new HashMap<Long, long[]>();
        final Set<Long> compactions = new HashSet<Long>();
        long nextId = 1, size;
        Index(File file) { this.file = file; }
    }

    private Index index(long sessionId) {
        if (sessionId < 0) throw new IllegalArgumentException("会话编号不合法。");
        Index index = indexes.get(Long.valueOf(sessionId));
        File file = new File(dir(sessionId), "transcript.jsonl");
        if (index != null && index.size == file.length()) return index;
        index = load(file);
        indexes.put(Long.valueOf(sessionId), index);
        return index;
    }

    /** 顺序扫一遍建索引。末尾没有换行的半行是断电留下的，截掉；中间坏行说明文件被改坏了，报错。 */
    private static Index load(File file) {
        Index index = new Index(file);
        if (!file.isFile()) return index;
        try (RandomAccessFile in = new RandomAccessFile(file, "rw")) {
            long length = in.length(), offset = 0;
            byte[] buffer = new byte[64 * 1024];
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            long lineStart = 0;
            while (offset < length) {
                in.seek(offset);
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, length - offset));
                if (n <= 0) break;
                for (int i = 0; i < n; i++) {
                    if (buffer[i] == '\n') {
                        byte[] bytes = line.toByteArray();
                        index(index, bytes, lineStart);
                        line.reset();
                        lineStart = offset + i + 1;
                    } else line.write(buffer[i]);
                }
                offset += n;
            }
            if (line.size() > 0) {
                // 断电留下的半行：截掉，之后的追加从完整的一行后面接着写。
                in.setLength(lineStart);
            }
            index.size = in.length();
            return index;
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("会话记录读取失败：" + file.getPath(), failure); }
    }

    private static void index(Index index, byte[] bytes, long offset) {
        if (bytes.length == 0) return;
        JSONObject json;
        try { json = new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
        catch (Exception corrupt) {
            throw new IllegalStateException("会话记录损坏（偏移 " + offset + "）：" + index.file.getPath(), corrupt);
        }
        apply(index, json, offset, bytes.length);
    }

    private static void apply(Index index, JSONObject json, long offset, int length) {
        String type = json.optString("type", "message");
        if ("message".equals(type)) {
            long id = json.optLong("id", 0L);
            if (id < index.nextId) throw new IllegalStateException("会话记录编号倒退：" + index.file.getPath());
            String callId = json.optString("tool_call_id", "");
            index.messages.add(new Entry(id, offset, length, json.optString("role", ""), callId.length() == 0 ? null : callId));
            index.nextId = id + 1;
        } else if ("elapsed".equals(type)) {
            index.elapsed.put(Long.valueOf(json.optLong("id")), new long[]{json.optLong("elapsed_ms"), json.optLong("think_ms")});
        } else if ("compaction".equals(type)) {
            index.compactions.add(Long.valueOf(json.optLong("through")));
        }
    }

    private void appendLine(Index index, JSONObject json, String role, String toolCallId) throws Exception {
        mkdirs(index.file.getParentFile());
        byte[] bytes = (json.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        long offset = index.size;
        try (FileOutputStream out = new FileOutputStream(index.file, true)) {
            out.write(bytes);
            out.getFD().sync();
        }
        apply(index, json, offset, bytes.length - 1);
        index.size = offset + bytes.length;
    }

    private static Message read(Index index, Entry entry) {
        return readRange(index, Collections.singletonList(entry)).get(0);
    }

    /** 一页消息在文件里是连续的一段：一次读出来再逐行解析，不为每条消息开一次文件。 */
    private static List<Message> readRange(Index index, List<Entry> entries) {
        List<Message> out = new ArrayList<Message>(entries.size());
        if (entries.isEmpty()) return out;
        long from = entries.get(0).offset;
        Entry last = entries.get(entries.size() - 1);
        long span = last.offset + last.length - from;
        if (span > Integer.MAX_VALUE) throw new IllegalStateException("一页会话记录过大：" + index.file.getPath());
        byte[] bytes = new byte[(int) span];
        try (RandomAccessFile in = new RandomAccessFile(index.file, "r")) {
            in.seek(from);
            in.readFully(bytes);
        } catch (IOException failure) {
            throw new IllegalStateException("会话记录读取失败：" + index.file.getPath(), failure);
        }
        for (Entry entry : entries) out.add(parse(index, entry, bytes, (int) (entry.offset - from)));
        return out;
    }

    private static Message parse(Index index, Entry entry, byte[] bytes, int at) {
        Message message;
        try { message = decode(new JSONObject(new String(bytes, at, entry.length, StandardCharsets.UTF_8))); }
        catch (IllegalStateException failure) { throw failure; }
        catch (Exception corrupt) { throw new IllegalStateException("会话记录损坏：" + index.file.getPath(), corrupt); }
        long[] patch = index.elapsed.get(Long.valueOf(entry.id));
        if (patch != null) { message.elapsedMs = patch[0]; message.thinkMs = patch[1]; }
        return message;
    }

    /** 和旧数据库 messages 表同样的字段；空字段不写。 */
    static JSONObject encode(Message message) throws Exception {
        JSONObject json = new JSONObject();
        json.put("role", message.role);
        json.put("content", message.content == null ? "" : message.content);
        if (message.reasoning != null && message.reasoning.length() > 0) json.put("reasoning", message.reasoning);
        if (message.toolCalls != null) json.put("tool_calls", message.toolCalls);
        if (message.toolCallId != null && message.toolCallId.length() > 0) json.put("tool_call_id", message.toolCallId);
        if (message.elapsedMs != 0) json.put("elapsed_ms", message.elapsedMs);
        if (message.thinkMs != 0) json.put("think_ms", message.thinkMs);
        if (message.displayParts != null) json.put("display_parts", message.displayParts);
        if (Message.USER.equals(message.role) && message.workDir != null && message.workDir.length() > 0)
            json.put("work_dir", message.workDir);
        if (Message.USER.equals(message.role) && message.mcpSelection != null) json.put("mcp_selection", message.mcpSelection.toJson());
        return json;
    }

    static Message decode(JSONObject json) throws Exception {
        Message m = new Message(json.getString("role"), json.optString("content", ""));
        String reasoning = json.optString("reasoning", "");
        if (reasoning.length() > 0) m.reasoning = reasoning;
        m.toolCalls = json.optJSONArray("tool_calls");
        String callId = json.optString("tool_call_id", "");
        if (callId.length() > 0) m.toolCallId = callId;
        m.elapsedMs = json.optLong("elapsed_ms", 0L);
        m.thinkMs = json.optLong("think_ms", 0L);
        m.displayParts = json.optJSONArray("display_parts");
        String workDir = json.optString("work_dir", "");
        if (workDir.length() > 0) m.workDir = workDir;
        if (Message.USER.equals(m.role) && json.has("mcp_selection")) {
            try { m.mcpSelection = McpSelection.fromJson(json.getJSONObject("mcp_selection")); }
            catch (Exception invalid) { throw new IllegalStateException("MCP 工具选择记录损坏，无法恢复此请求", invalid); }
        }
        m.restoreCoordinationIds();
        return m;
    }

    private static int lowerBound(List<Entry> entries, long id) {
        int low = 0, high = entries.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (entries.get(mid).id < id) low = mid + 1; else high = mid;
        }
        return low;
    }

    private long nextId() throws IOException {
        long next = 1;
        File file = new File(root, "next-session-id");
        if (file.isFile()) {
            try { next = Math.max(next, Long.parseLong(read(file).trim())); }
            catch (NumberFormatException corrupt) { /* 用目录里最大的编号兜底 */ }
        }
        File[] dirs = sessions.listFiles();
        if (dirs != null) for (File dir : dirs) next = Math.max(next, idOf(dir) + 1);
        return next;
    }

    private File dir(long id) { return new File(sessions, String.valueOf(id)); }

    private static long idOf(File dir) {
        String name = dir.getName();
        if (!dir.isDirectory() || !name.matches("[0-9]{1,18}")) return -1;
        return Long.parseLong(name);
    }

    private static String titleOf(File meta) {
        try { return new JSONObject(read(meta)).optString("title", ""); }
        catch (Exception corrupt) { return ""; }
    }

    static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE, Math.max(32, file.length())));
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 先写同目录的 .tmp（durable 时 fsync），再改名覆盖。 */
    static void writeAtomic(File target, String text, boolean durable) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            if (durable) out.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("无法写入 " + target.getName());
        }
    }

    static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("无法创建 " + dir.getPath());
    }
}
