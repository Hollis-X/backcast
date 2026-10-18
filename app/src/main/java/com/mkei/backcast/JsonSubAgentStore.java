package com.mkei.backcast;

import com.mkei.backcast.agent.Diagnostics;
import com.mkei.backcast.agent.SubAgentManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 子代理记录存成 JSON 文件，不进数据库：{@code <根目录>/<owner>/} 下每个子代理三个文件。
 * <ul>
 *   <li>{@code <id>.json}：去掉历史后的轻量记录，每次保存都重写，只有几 KB；</li>
 *   <li>{@code <id>.history.json}：子代理自己的会话历史，历史数组换了（版本戳变了）才重写；</li>
 *   <li>{@code <id>.fork.json}：派生时复制的父会话上下文，基本只在派生时写一次。</li>
 * </ul>
 * 报阶段、报进度这种高频保存只改小文件，不再把几 MB 的历史整份序列化一遍。
 * 每个文件先写 .tmp、fsync 再改名覆盖，断电不会留下写了一半的文件。
 * owner 目录里有 {@code .removed} 标记就表示会话已删除，之后的读写都会被拒绝。
 */
public final class JsonSubAgentStore implements SubAgentManager.Store {
    /** 记录出错时写诊断；可以为 null。 */
    public interface FailureSink { void record(long sessionId, String summary, String detail); }

    private static final String META = ".json", HISTORY = ".history.json", FORK = ".fork.json", REMOVED = ".removed";

    private final File root;
    private final FailureSink failures;
    private String owner;
    private long sessionId;
    /** 已经落盘的版本号和历史版本戳；戳没变就说明历史没换，不用再写那个大文件。 */
    private final Map<String, Long> savedRevision = new HashMap<String, Long>();
    private final Map<String, Integer> savedHistory = new HashMap<String, Integer>(), savedFork = new HashMap<String, Integer>();

    public JsonSubAgentStore(File root, long sessionId, FailureSink failures) {
        if (root == null) throw new IllegalArgumentException("子 agent 存储目录为空。");
        this.root = root; this.sessionId = sessionId; this.failures = failures;
        owner = sessionId < 0 ? "draft-" + UUID.randomUUID().toString() : owner(sessionId);
    }

    public static String owner(long sessionId) {
        if (sessionId < 0) throw new IllegalArgumentException("子 agent 会话编号不合法。");
        return "session-" + sessionId;
    }

    /** 草稿会话第一条消息落库后，把草稿目录里的记录挪到正式会话下，草稿目录标记为已删除。 */
    public synchronized void bindSession(long next) {
        String target = owner(next);
        if (owner.equals(target)) return;
        File from = directory(owner), to = directory(target);
        checkOpen(from); checkOpen(to);
        try {
            File[] files = from.listFiles();
            if (files != null && files.length > 0) {
                mkdirs(to);
                for (File file : files) {
                    if (file.getName().endsWith(".tmp")) { file.delete(); continue; }
                    File moved = new File(to, file.getName());
                    if (!file.renameTo(moved)) throw new IOException("无法移动 " + file.getName());
                }
            }
            markRemoved(from);
        } catch (IOException failure) { throw failed("子 agent 草稿记录转存失败", failure); }
        owner = target; sessionId = next;
    }

    @Override public synchronized List<SubAgentManager.Record> load() {
        File dir = directory(owner);
        checkOpen(dir);
        List<SubAgentManager.Record> records = new ArrayList<SubAgentManager.Record>();
        try {
            for (File meta : metaFiles(dir)) {
                String id = meta.getName().substring(0, meta.getName().length() - META.length());
                JSONObject json;
                try {
                    json = new JSONObject(read(meta));
                    if (!id.equals(json.getString("id"))) throw new IllegalStateException("文件名与记录编号不一致。");
                    File history = new File(dir, id + HISTORY), fork = new File(dir, id + FORK);
                    json.put("history", history.exists() ? new JSONArray(read(history)) : new JSONArray());
                    json.put("forkHistory", fork.exists() ? new JSONArray(read(fork)) : new JSONArray());
                } catch (Exception invalid) {
                    throw new IllegalStateException("子 agent 记录损坏，原文件已保留：" + meta.getName(), invalid);
                }
                SubAgentManager.Record record = SubAgentManager.Record.fromJson(json);
                savedRevision.put(id, Long.valueOf(record.revision));
                records.add(record);
            }
            return records;
        } catch (Exception failure) { throw failed("子 agent 记录恢复失败", failure); }
    }

    @Override public synchronized void save(SubAgentManager.Record record) {
        File dir = directory(owner);
        checkOpen(dir);
        try {
            String id = record.id;
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}") || record.revision < 0)
                throw new IllegalArgumentException("子 agent 编号或版本不合法。");
            // 旧版本、同版本都不覆盖已经落盘的新状态。
            Long known = savedRevision.get(id);
            if (known == null) known = diskRevision(new File(dir, id + META));
            if (known != null && known.longValue() >= record.revision) return;
            mkdirs(dir);
            JSONArray history = record.history == null ? new JSONArray() : record.history;
            JSONArray fork = record.forkHistory == null ? new JSONArray() : record.forkHistory;
            int historyStamp = stamp(history), forkStamp = stamp(fork);
            // 先写大文件、最后写元数据：元数据的版本号落盘了，它引用的历史一定已经在了。
            Integer oldHistory = savedHistory.get(id), oldFork = savedFork.get(id);
            if (oldHistory == null || oldHistory.intValue() != historyStamp) {
                write(new File(dir, id + HISTORY), history.toString());
                savedHistory.put(id, Integer.valueOf(historyStamp));
            }
            if (oldFork == null || oldFork.intValue() != forkStamp) {
                write(new File(dir, id + FORK), fork.toString());
                savedFork.put(id, Integer.valueOf(forkStamp));
            }
            write(new File(dir, id + META), light(record));
            savedRevision.put(id, Long.valueOf(record.revision));
        } catch (Exception failure) { throw failed("子 agent 记录保存失败", failure); }
    }

    public synchronized void remove() {
        try { markRemoved(directory(owner)); }
        catch (IOException failure) { throw failed("子 agent 记录删除失败", failure); }
    }

    /** 删除会话时调用：会话没加载过、没有对应的 store 实例也能清掉它的子代理记录。 */
    public static void removeOwner(File root, long sessionId) throws IOException {
        markRemoved(new File(root, owner(sessionId)));
    }

    /** 进程被拉起来后要接着跑子代理的会话：只读各子代理的轻量记录，不碰历史文件。 */
    public static List<Long> workSessionIds(File root) {
        Map<Long, Boolean> pending = new LinkedHashMap<Long, Boolean>();
        Set<Long> allowed = new HashSet<Long>(), detached = new HashSet<Long>();
        Set<Long> authorizedChildWork = new HashSet<Long>(), stopped = new HashSet<Long>();
        File[] owners = root.listFiles();
        if (owners != null) {
            Arrays.sort(owners);
            for (File dir : owners) {
                String name = dir.getName();
                if (!dir.isDirectory() || !name.matches("session-[0-9]+") || new File(dir, REMOVED).exists()) continue;
                long sid;
                try { sid = Long.parseLong(name.substring(8)); }
                catch (NumberFormatException invalid) { continue; }
                for (File meta : metaFiles(dir)) {
                    try {
                        JSONObject record = new JSONObject(read(meta));
                        if ("main".equals(record.optString("id"))) {
                            if (record.optBoolean("managerCancelled")) stopped.add(sid);
                            else if (record.optBoolean("userRestartOnly") && !record.optBoolean("rootWakeAllowed")) {
                                detached.add(sid);
                            } else if (record.optBoolean("rootWakeAllowed")) {
                                allowed.add(sid); pending.put(sid, Boolean.TRUE);
                            }
                        } else {
                            String status = record.optString("status");
                            if ("queued".equals(status) || "running".equals(status) || "waiting".equals(status))
                                pending.put(sid, Boolean.TRUE);
                            if (userRestartWorkPending(record)) authorizedChildWork.add(sid);
                        }
                    } catch (Exception invalid) {
                        // 坏掉的记录不参与自动恢复，原文件留着；打开那个会话时 load() 会报出来。
                    }
                }
            }
        }
        detached.retainAll(authorizedChildWork);
        allowed.addAll(detached);
        pending.keySet().removeAll(stopped);
        pending.keySet().retainAll(allowed);
        return new ArrayList<Long>(pending.keySet());
    }

    private static boolean userRestartWorkPending(JSONObject record) {
        String status = record.optString("status");
        if (!"queued".equals(status) && !"running".equals(status) && !"waiting".equals(status)) return false;
        Set<String> authorized = new HashSet<String>();
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

    public static boolean userRestartOnly(File root, long sessionId) {
        File dir = new File(root, owner(sessionId));
        File meta = new File(dir, "main" + META);
        if (new File(dir, REMOVED).exists() || !meta.exists()) return false;
        try {
            JSONObject main = new JSONObject(read(meta));
            return main.optBoolean("userRestartOnly") && !main.optBoolean("rootWakeAllowed")
                    && !main.optBoolean("managerCancelled");
        } catch (Exception invalid) {
            throw new IllegalStateException("子 agent 恢复权限记录损坏，原记录已保留。", invalid);
        }
    }

    /** 和 SubAgentManager.historyStamp 一样：历史数组是整份替换的，换了数组或长度变了就是有新内容。 */
    private static int stamp(JSONArray history) { return System.identityHashCode(history) * 31 + history.length(); }

    private static String light(SubAgentManager.Record record) throws Exception {
        JSONArray history = record.history, fork = record.forkHistory;
        record.history = new JSONArray(); record.forkHistory = new JSONArray();
        try {
            JSONObject json = record.toJson();
            json.remove("history"); json.remove("forkHistory");
            return json.toString();
        } finally { record.history = history; record.forkHistory = fork; }
    }

    private File directory(String name) {
        if (name == null || !name.matches("(?:session-[0-9]+|draft-[A-Za-z0-9_-]+)"))
            throw new IllegalArgumentException("子 agent 存储所有者不合法。");
        return new File(root, name);
    }

    private static void checkOpen(File dir) {
        if (new File(dir, REMOVED).exists()) throw new IllegalStateException("子 agent 会话已删除。");
    }

    private static List<File> metaFiles(File dir) {
        List<File> out = new ArrayList<File>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(META) && !name.endsWith(HISTORY) && !name.endsWith(FORK)) out.add(file);
        }
        return out;
    }

    private static Long diskRevision(File meta) {
        if (!meta.exists()) return null;
        try { return Long.valueOf(new JSONObject(read(meta)).getLong("revision")); }
        catch (Exception unreadable) { return null; }
    }

    /** 先写同目录的 .tmp 并 fsync，再改名覆盖；改名在同一文件系统里是原子的。 */
    private static void write(File target, String text) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } finally { out.close(); }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("无法写入 " + target.getName());
        }
    }

    private static String read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE, Math.max(32, file.length())));
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { in.close(); }
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("无法创建 " + dir.getName());
    }

    /** 先落删除标记再删记录文件：删到一半断电，标记也已经挡住了之后的读写。 */
    private static void markRemoved(File dir) throws IOException {
        mkdirs(dir);
        File marker = new File(dir, REMOVED);
        if (!marker.exists()) write(marker, "");
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) if (!file.getName().equals(REMOVED)) file.delete();
    }

    private IllegalStateException failed(String summary, Exception failure) {
        if (failure instanceof IllegalStateException && failure.getMessage() != null
                && failure.getMessage().contains("已删除")) return (IllegalStateException) failure;
        if (failures != null) {
            try { failures.record(sessionId, summary, Diagnostics.boundedJson(Diagnostics.failure(failure))); }
            catch (RuntimeException unavailable) { }
        }
        return new IllegalStateException(summary + "：" + failure.getMessage(), failure);
    }
}
