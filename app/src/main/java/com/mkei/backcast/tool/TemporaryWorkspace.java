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
    /** Retain a live process identity until stopping it can be positively confirmed. */
    public interface ProcessCleanup {
        boolean stop() throws Exception;
        default boolean stop(long deadlineNanos) throws Exception { return stop(); }
        default boolean retry(long deadlineNanos) throws Exception { return stop(deadlineNanos); }
    }
    private static final String MARKER = ".backcast-owner";
    private static final String RECOVERED = "recovered";

    private static final class Allocation {
        final File directory;
        final String workspace;
        final String owner;
        String lease;
        boolean processPending;
        /** 登记进程时开着 root：重启后要用 root 才看得到那些进程。旧登记没有这一项，按开着算。 */
        boolean processRoot;
        final ArrayList<ProcessCleanup> processes = new ArrayList<ProcessCleanup>();

        Allocation(File directory, String workspace, String owner, String lease) {
            this.directory = directory;
            this.workspace = workspace;
            this.owner = owner;
            this.lease = lease;
        }
    }

    private String workDir;
    private volatile WorkspaceRoots projectRoots;
    private final ThreadLocal<WorkspaceRoots> turnRoots = new ThreadLocal<WorkspaceRoots>();
    private boolean useRoot;
    private final File stateDir;
    private final File materialsDir;
    private File ledger;
    private final ArrayList<Allocation> allocations = new ArrayList<Allocation>();
    private final ThreadLocal<String> turn = new ThreadLocal<String>();
    private String loadError;
    /** 重启前留下、本进程已经报过一次的清理失败；之后每轮重试仍失败时不再算进本轮的清理结果。 */
    private final java.util.Set<String> reportedStale = new java.util.HashSet<String>();
    public TemporaryWorkspace(String workDir, boolean useRoot, File stateDir, long sessionId) {
        configure(workDir, useRoot);
        this.stateDir = canonical(stateDir);
        materialsDir = this.stateDir == null ? null : new File(this.stateDir, "materials");
        ledger = this.stateDir == null ? null : new File(this.stateDir, sessionId < 0
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
        projectRoots = new WorkspaceRoots(directory, null);
        useRoot = root;
    }

    public synchronized void configureWorkDirs(java.util.List<String> directories) {
        projectRoots = new WorkspaceRoots(workDir, directories);
    }

    WorkspaceRoots projectRoots(String directory) {
        WorkspaceRoots active = turnRoots.get();
        WorkspaceRoots roots = active == null ? projectRoots : active;
        return roots != null && roots.matches(directory) ? roots : new WorkspaceRoots(directory, null);
    }

    public void beginTurn() {
        beginTurn(projectRoots);
    }

    void beginTurn(WorkspaceRoots roots) {
        turn.set(UUID.randomUUID().toString());
        turnRoots.set(roots);
    }

    void restrictToTask(WorkspaceRoots authorized, java.util.List<String> paths) {
        turnRoots.set(authorized.forTask(paths));
    }

    public String projectDirectory(String directory) {
        java.util.List<File> roots = projectRoots(directory).focusDirectories();
        if (roots.isEmpty()) throw new IllegalArgumentException("本轮指定路径不在已授权工作目录内，无法执行项目命令。");
        return roots.get(0).getPath();
    }

    private String lease() {
        String value = turn.get();
        return value == null ? "manual" : value;
    }

    public synchronized File directory() throws Exception {
        if (loadError != null) throw new IllegalArgumentException(loadError);
        if (materialsDir == null) throw new IllegalArgumentException("临时材料需要配置 App 私有存储路径。");
        File base = materialsDir;
        if (!base.getAbsolutePath().equals(base.getCanonicalPath())) {
            throw new IllegalArgumentException("App 私有临时目录被替换成链接，拒绝写入。");
        }
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new IllegalArgumentException("无法创建 App 私有临时目录：" + base.getPath());
        }
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
        if (!created) {
            allocations.remove(allocation);
            save();
            throw new IllegalArgumentException("无法创建临时目录：" + directory.getPath());
        }
        try {
            ToolPaths.writeBytes(new File(directory, MARKER), owner.getBytes("UTF-8"), false);
        } catch (Exception failure) {
            // An empty allocation remains ours even if writing its marker failed.
            directory.delete();
            throw failure;
        }
        return directory;
    }

    public synchronized File resolveTemporary(String path) throws Exception {
        File file = ToolPaths.resolve(directory().getPath(), path);
        if (MARKER.equals(file.getName())) throw new IllegalArgumentException("不能修改临时目录所有权标记。");
        return file;
    }

    /** Bind before executing user commands; callbacks may be retried after cancellation. */
    public synchronized void trackProcess(File directory, ProcessCleanup process) throws Exception {
        if (process == null) throw new IllegalArgumentException("进程清理检查为空。");
        for (Allocation allocation : allocations) {
            if (allocation.directory.equals(directory.getCanonicalFile())) {
                verify(allocation);
                allocation.processes.add(process);
                allocation.processPending = true;
                allocation.processRoot |= useRoot;
                // Persist before the supervisor is acknowledged. Losing live
                // callbacks at process death must never imply verified exit.
                save();
                return;
            }
        }
        throw new IllegalArgumentException("进程没有对应的本轮临时目录登记。");
    }

    /** Extra file-tool access is limited to this turn's verified allocations. */
    public synchronized File resolveManaged(String path) throws Exception {
        if (path == null || !new File(path).isAbsolute()) return null;
        File file = new File(path).getCanonicalFile();
        for (Allocation allocation : allocations) {
            String base = allocation.directory.getPath();
            if (!allocation.lease.equals(lease()) || RECOVERED.equals(allocation.lease)) continue;
            if (file.getPath().equals(base) || file.getPath().startsWith(base + File.separator)) {
                verify(allocation);
                return file;
            }
        }
        return null;
    }

    public synchronized boolean isOwnershipMarker(File file) throws Exception {
        if (!MARKER.equals(file.getName())) return false;
        for (Allocation allocation : allocations) {
            if (new File(allocation.directory, MARKER).equals(file)) return true;
        }
        return false;
    }

    /** A broad project root must not grant access to another turn or the private ledger. */
    synchronized boolean isPrivateStorage(File file) throws Exception {
        if (stateDir == null) return false;
        String base = stateDir.getPath(), path = file.getCanonicalPath();
        return path.equals(base) || path.startsWith(base + File.separator);
    }

    /** Lexical fallback used only after root has confirmed the real path. */
    synchronized boolean isPrivateStorageLexical(File file) {
        if (stateDir == null || file == null) return false;
        String base = stateDir.getPath(), path = file.getAbsolutePath();
        return path.equals(base) || path.startsWith(base + File.separator);
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
        return cleanup(lease(), System.nanoTime() + CleanupBudget.DEFAULT_NANOS);
    }

    public synchronized String cleanupRecovered() {
        return cleanup(RECOVERED, System.nanoTime() + CleanupBudget.DEFAULT_NANOS);
    }

    public synchronized String cleanupRecovered(long deadlineNanos) {
        return cleanup(RECOVERED, deadlineNanos);
    }

    private String cleanup(String lease, long deadlineNanos) {
        try (CleanupBudget budget = new CleanupBudget(deadlineNanos)) { return cleanup(lease, budget); }
    }

    private String cleanup(String lease, CleanupBudget budget) {
        if (loadError != null) return loadError;
        StringBuilder errors = new StringBuilder();
        for (int i = allocations.size() - 1; i >= 0; i--) {
            Allocation allocation = allocations.get(i);
            if (!allocation.lease.equals(lease) && !RECOVERED.equals(allocation.lease)) continue;
            // 本轮清理顺带重试重启前留下的目录：它失败了不代表本轮没清干净，只报一次。
            boolean stale = RECOVERED.equals(allocation.lease) && !RECOVERED.equals(lease);
            try {
                budget.check();
                if (allocation.processPending && allocation.processes.isEmpty()) {
                    // 重启后结束进程的回调已经没了：查 /proc，确认没有进程的当前目录或打开的文件在这个目录里才删。
                    String busy = holders(allocation, budget);
                    if (busy != null) {
                        throw new IllegalStateException("重启前启动的进程仍在使用临时目录，已保留目录：" + busy);
                    }
                    allocation.processPending = false;
                }
                for (int p = allocation.processes.size() - 1; p >= 0; p--) {
                    ProcessCleanup process = allocation.processes.get(p);
                    boolean stopped = RECOVERED.equals(lease) ? process.retry(budget.deadlineNanos)
                            : process.stop(budget.deadlineNanos);
                    if (!stopped) {
                        throw new IllegalArgumentException("未能确认后台子进程已结束，保留临时目录并等待重试。");
                    }
                    allocation.processes.remove(p);
                }
                allocation.processPending = false;
                remove(allocation, budget);
                allocations.remove(i);
            } catch (Exception failure) {
                allocation.lease = RECOVERED;
                String path = allocation.directory.getPath();
                if (stale && !reportedStale.add(path)) continue;
                if (errors.length() > 0) errors.append('\n');
                if (stale) errors.append("（重启前留下的目录，与本轮无关）");
                errors.append(path).append("：").append(failure.getMessage());
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
        return finishTurn(System.nanoTime() + CleanupBudget.DEFAULT_NANOS);
    }

    public synchronized String finishTurn(long deadlineNanos) {
        try { return cleanup(lease(), deadlineNanos); }
        finally { turn.remove(); turnRoots.remove(); }
    }

    private void verify(Allocation allocation) throws Exception {
        verify(allocation, null);
    }

    /**
     * 重启前启动、结束回调已随旧进程丢失的目录：先结束仍占用它的进程，再让调用方删目录。
     *
     * 判定占用的依据是进程的当前目录或任一打开的文件落在这个临时目录里（目录是 App 私有的，只有本 App 自己的进程
     * 或本 App 用 root 起的进程才进得来）。结束时按进程号发 SIGKILL（发前重核启动时间，避免号被别的进程复用后误杀），
     * 占用进程若是自己进程组的组长，连它的进程组一起结束，带走同组的子进程。结束后再扫一遍：
     * 都清了返回 null（可以删目录），还有占用返回剩下的进程号和命令行（保留目录）。
     *
     * 开着 root 时用 root 扫全部 /proc；没有 root 只能看到本 App 自己的进程，登记时开过 root 的目录（可能是 root 进程占用）
     * 就确认不了，直接抛异常保留目录。
     */
    private String holders(Allocation allocation, CleanupBudget budget) throws Exception {
        String directory = allocation.directory.getPath();
        if (useRoot && RootShell.available(budget)) {
            return rootRecover(directory, budget);
        }
        if (allocation.processRoot) {
            throw new IllegalStateException("登记时开着 root，现在 root 不可用，确认不了重启前的进程已结束，保留临时目录。");
        }
        return ownRecover(new File("/proc"), directory, budget);
    }

    /** root：在一条命令里扫描、结束、循环等待再扫描，打印仍占用目录的进程。只结束占用本目录的进程。 */
    private String rootRecover(String directory, CleanupBudget budget) throws Exception {
        String d = RootShell.quote(directory);
        String command = "d=" + d + "; hold() { mode=$1; for p in /proc/[0-9]*; do [ -d \"$p\" ] || continue; "
                + "pid=${p#/proc/}; hit=; "
                + "case \"$(readlink \"$p/cwd\" 2>/dev/null)\" in \"$d\"|\"$d\"/*) hit=1;; esac; "
                + "if [ -z \"$hit\" ]; then for f in \"$p\"/fd/*; do "
                + "case \"$(readlink \"$f\" 2>/dev/null)\" in \"$d\"|\"$d\"/*) hit=1; break;; esac; done; fi; "
                + "[ -n \"$hit\" ] || continue; "
                + "cmd=$(tr '\\0' ' ' < \"$p/cmdline\" 2>/dev/null | cut -c1-120); "
                + "s=; IFS= read -r s 2>/dev/null < \"$p/stat\" || continue; s=${s##*) }; set -- $s; "
                + "[ $# -ge 4 ] || continue; pgid=$3; "
                + "if [ \"$mode\" = kill ]; then kill -9 \"$pid\" 2>/dev/null; "
                + "[ \"$pid\" = \"$pgid\" ] && kill -9 -\"$pid\" 2>/dev/null; "
                + "else printf '%s %s\\n' \"$pid\" \"$cmd\"; fi; "
                + "done; }; "
                + "hold kill; i=0; while [ $i -lt 20 ]; do [ -z \"$(hold)\" ] && break; sleep 0.1; i=$((i+1)); done; "
                + "hold; exit 0";
        RootShell.Out out = RootShell.exec(command, null, 8192, budget.remainingMillis(4000), budget);
        if (out.exit != 0) throw new IllegalStateException("root 清理残留进程失败，exit=" + out.exit + "，保留临时目录。");
        String text = new String(out.stdout, "UTF-8").trim();
        return text.length() == 0 ? null : text.replace('\n', '；');
    }

    /** 没有 root 时只扫本 App 能读的进程；读不到的属于别的用户，不会是这里起的非 root 进程。 */
    String ownRecover(File proc, String directory, CleanupBudget budget) throws Exception {
        for (String[] holder : scanOwn(proc, directory, budget)) {
            if (budget != null) budget.check();
            killOwn(holder[0], holder[1], holder[2]);
        }
        for (int i = 0; i < 20 && !scanOwn(proc, directory, budget).isEmpty(); i++) {
            if (budget != null) budget.pause(100); else Thread.sleep(100);
        }
        StringBuilder found = new StringBuilder();
        for (String[] holder : scanOwn(proc, directory, budget)) {
            if (found.length() > 0) found.append('；');
            found.append(holder[0]).append(' ').append(holder[3]);
        }
        return found.length() == 0 ? null : found.toString();
    }

    /** 当前目录或打开的文件落在目录里的本 App 进程，每条 {pid, 启动时间, 进程组, 命令行}。 */
    private static java.util.List<String[]> scanOwn(File proc, String directory, CleanupBudget budget) throws Exception {
        File[] entries = proc.listFiles();
        if (entries == null) throw new IllegalStateException("读不到 /proc，确认不了重启前的进程已结束，保留临时目录。");
        String self = new File(proc, "self").getCanonicalFile().getName();
        java.util.List<String[]> holders = new ArrayList<String[]>();
        for (File entry : entries) {
            if (budget != null) budget.check();
            String pid = entry.getName();
            if (!pid.matches("[0-9]+") || pid.equals(self)) continue;
            boolean hit = inside(link(new File(entry, "cwd")), directory);
            File[] fds = hit ? null : new File(entry, "fd").listFiles();
            if (fds != null) for (File fd : fds) {
                if (inside(link(fd), directory)) { hit = true; break; }
            }
            if (!hit) continue;
            String[] stat = stat(entry);
            if (stat == null) continue;
            holders.add(new String[]{pid, stat[0], stat[1], cmdline(entry)});
        }
        return holders;
    }

    /** 发前重核启动时间：号被复用后那条读到的就是别的进程，启动时间对不上，不发。组长连进程组一起结束。 */
    private static void killOwn(String pid, String start, String pgid) throws Exception {
        StringBuilder c = new StringBuilder();
        c.append("s=; IFS= read -r s 2>/dev/null < /proc/").append(pid).append("/stat || exit 0; ")
                .append("s=${s##*) }; set -- $s; [ $# -ge 20 ] || exit 0; shift 19; ")
                .append("[ \"$1\" = ").append(RootShell.quote(start)).append(" ] || exit 0; ")
                .append("kill -9 ").append(pid).append(" 2>/dev/null; ");
        if (pid.equals(pgid)) c.append("kill -9 -").append(pid).append(" 2>/dev/null; ");
        c.append("exit 0");
        Process killer = new ProcessBuilder("sh", "-c", c.toString()).redirectErrorStream(true).start();
        try {
            killer.getOutputStream().close();
            killer.getInputStream().close();
            killer.waitFor();
        } finally { killer.destroyForcibly(); }
    }

    /** /proc/pid/stat 的启动时间（第 22 项）和进程组（第 5 项）；读不到返回 null。 */
    private static String[] stat(File entry) {
        String line = firstLine(new File(entry, "stat"));
        if (line == null) return null;
        int close = line.lastIndexOf(')');
        if (close < 0) return null;
        String[] fields = line.substring(close + 1).trim().split("\\s+");
        if (fields.length < 20) return null;
        return new String[]{ fields[19], fields[2] };
    }

    private static String firstLine(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int n = in.read(buffer);
            if (n <= 0) return null;
            String text = new String(buffer, 0, n, "UTF-8");
            int nl = text.indexOf('\n');
            return nl < 0 ? text : text.substring(0, nl);
        } catch (Exception unreadable) { return null; }
    }

    private static String cmdline(File entry) {
        try (FileInputStream in = new FileInputStream(new File(entry, "cmdline"))) {
            byte[] head = new byte[120];
            int n = in.read(head);
            return n > 0 ? new String(head, 0, n, "UTF-8").replace('\0', ' ').trim() : "";
        } catch (Exception unreadable) { return ""; }
    }

    private static String link(File file) {
        if (file == null) return null;
        try { return java.nio.file.Files.readSymbolicLink(file.toPath()).toString(); }
        catch (Exception unreadable) { return null; }
    }

    private static boolean inside(String path, String directory) {
        return path != null && (path.equals(directory) || path.startsWith(directory + File.separator));
    }

    private void verify(Allocation allocation, CleanupBudget budget) throws Exception {
        if (budget != null) budget.check();
        File directory = allocation.directory;
        verifyParent(allocation);
        if (!directory.getAbsolutePath().equals(directory.getCanonicalPath())) {
            throw new IllegalArgumentException("临时目录被替换成链接，拒绝写入。");
        }
        File marker = new File(directory, MARKER);
        if (!marker.getAbsolutePath().equals(marker.getCanonicalPath())
                || !allocation.owner.equals(new String(budget == null ? ToolPaths.readBytes(marker, 128, useRoot)
                        : ToolPaths.readBytes(marker, 128, useRoot, budget), "UTF-8"))) {
            throw new IllegalArgumentException("临时目录所有权无法确认，拒绝清理。");
        }
    }

    private void remove(Allocation allocation, CleanupBudget budget) throws Exception {
        budget.check();
        File directory = allocation.directory;
        verifyParent(allocation);
        if (!directory.getAbsolutePath().equals(directory.getCanonicalPath())) {
            if (!directory.delete()) throw new IllegalArgumentException("无法删除临时目录链接。");
            return;
        }
        // An app-owned allocation should not need a new root command merely to
        // close an already-confirmed process lease. Fall back only if direct
        // ownership verification or deletion is actually denied.
        ToolPaths.Probe probe = ToolPaths.probe(directory, false, budget);
        if (!probe.exists && !probe.denied) return;
        File[] empty = directory.listFiles();
        if (empty != null && empty.length == 0 && directory.delete()) return;
        try {
            if (probe.denied) throw new IllegalArgumentException("没有权限确认临时目录状态。");
            verify(allocation, budget);
            removeTree(directory, budget);
            return;
        } catch (Exception directFailure) {
            if (!useRoot || !RootShell.available(budget)) throw directFailure;
        }
        // Root commands can leave restrictive ownership/modes inside an app-owned allocation.
        if (useRoot && RootShell.available(budget)) {
            String path = RootShell.quote(directory.getPath());
            String marker = RootShell.quote(new File(directory, MARKER).getPath());
            String command = "if [ -L " + path + " ]; then rm -f " + path
                    + "; elif [ ! -e " + path + " ]; then :; elif [ ! -e " + marker
                    + " ]; then rmdir " + path + "; elif [ ! -L " + marker
                    + " ] && [ \"$(cat " + marker + ")\" = " + RootShell.quote(allocation.owner)
                    + " ]; then rm -rf " + path + "; else exit 2; fi";
            RootShell.Out out = RootShell.exec(command, null, 2048, budget.remainingMillis(2000), budget);
            if (out.exit != 0) {
                throw new IllegalArgumentException("无法确认所有权或删除临时目录，root exit=" + out.exit + "：" + out.stderr);
            }
            return;
        }
    }

    private static void verifyParent(Allocation allocation) throws Exception {
        if (!new File(allocation.workspace).getCanonicalPath().equals(allocation.workspace)) {
            throw new IllegalArgumentException("工作目录父路径被替换成链接，拒绝自动删除。");
        }
    }

    private static File canonical(File file) {
        if (file == null) return null;
        try { return file.getCanonicalFile(); }
        catch (Exception failure) { throw new IllegalArgumentException("无法确认 App 私有存储路径。", failure); }
    }

    private static void removeTree(File file, CleanupBudget budget) throws Exception {
        budget.check();
        if (file.getAbsolutePath().equals(file.getCanonicalPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IllegalArgumentException("无法读取临时目录。");
            for (File child : children) {
                if (!MARKER.equals(child.getName())) removeTree(child, budget);
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
                        || !path.equals(new File(base, ".backcast-tmp-" + owner))
                        || ("private".equals(value.optString("storage", "legacy")) && !base.equals(materialsDir))) {
                    throw new IllegalArgumentException("临时登记路径不合法。");
                }
                Allocation allocation = new Allocation(path, base.getPath(), owner, RECOVERED);
                allocation.processPending = value.optBoolean("process_pending", false);
                allocation.processRoot = value.optBoolean("process_root", true);
                allocations.add(allocation);
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
                    .put("workspace", allocation.workspace).put("owner", allocation.owner)
                    .put("process_pending", allocation.processPending)
                    .put("process_root", allocation.processRoot)
                    .put("storage", new File(allocation.workspace).equals(materialsDir) ? "private" : "legacy"));
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
