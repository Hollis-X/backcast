package com.mkei.backcast.tool;

import android.os.Build;

import com.mkei.backcast.agent.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 执行系统命令。
 *
 * root 只是"尽力而为"：拿不到就退回普通 shell 继续跑，
 * 而不是让整条命令直接失败 —— 大部分命令（ls、unzip、file）
 * 本来就不需要 root。
 */
public class ShellTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SEC = 60;
    private static final int MAX_OUTPUT = 20000;

    /** root 探测结果缓存：一次进程内只探一次，避免每条命令都等 su。 */
    private static volatile Boolean rootAvailable;

    private final boolean useRoot;
    /** 工作目录。命令里的相对路径按它解析。 */
    private final String workDir;
    private final TemporaryWorkspace temporary;
    private volatile Process running;
    private volatile ProcessTree runningTree;
    private volatile boolean cleanupFailed;
    /** 每次停止加一。正在跑的命令记下旧值，对不上就退出。 */
    private volatile int epoch;

    public ShellTool(boolean useRoot, String workDir) {
        this(useRoot, workDir, null);
    }

    public ShellTool(boolean useRoot, String workDir, TemporaryWorkspace temporary) {
        this.useRoot = useRoot;
        this.workDir = workDir == null || workDir.length() == 0 ? null : workDir;
        this.temporary = temporary;
    }

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String description() {
        return "在设备上执行 shell 命令。用于列目录、搜索，以及运行命令行工具"
                + "（如 jadx、apktool、unzip、file）。"
                + "读文件用 read，改已有文件用 edit，新建或整篇重写用 write。"
                + "传入完整命令行字符串。TMPDIR、TMP、TEMP 指向本轮专用临时目录。"
                + "创建临时脚本或中间产物时 temporary=true，命令在专用临时目录运行；"
                + "需要读项目输入时使用工作目录内的绝对路径，临时输出禁止写到项目根或其它目录。"
                + "正式测试保留并归类到已有测试目录或 tests/，一次性验证脚本仍是临时材料。";
    }

    @Override
    public JSONObject parameters() {
        JSONObject props = new JSONObject();
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("type", "string");
            cmd.put("description", "要执行的完整命令，例如 unzip -l /sdcard/a.apk");
            props.put("command", cmd);

            JSONObject t = new JSONObject();
            t.put("type", "integer");
            t.put("description", "超时秒数，默认 " + DEFAULT_TIMEOUT_SEC);
            props.put("timeout_sec", t);
            props.put("temporary", new JSONObject().put("type", "boolean")
                    .put("description", "临时命令必须填 true；在专用临时目录执行，相对输出会自动清理"));

            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", new JSONArray().put("command"));
            return schema;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public void abort() {
        epoch++;
        Process current = running;
        if (current != null) {
            kill(current);
        }
    }

    private void kill(Process process) {
        ProcessTree tree = running == process ? runningTree : null;
        if (tree != null && !tree.stop()) {
            cleanupFailed = true;
        }
        try {
            process.getInputStream().close();
        } catch (Exception ignored) {
        }
        try {
            process.getOutputStream().close();
        } catch (Exception ignored) {
        }
        process.destroy();
        if (Build.VERSION.SDK_INT >= 26) {
            process.destroyForcibly();
        }
        long deadline = System.currentTimeMillis() + 1000L;
        while (!finished(process) && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
    }

    @Override
    public String run(JSONObject args) throws Exception {
        final int mine = epoch;
        cleanupFailed = false;
        String command = args.optString("command", "").trim();
        if (command.length() == 0) {
            return "错误：command 为空。";
        }

        try { ToolPaths.checkCommand(workDir, command, temporary, args.optBoolean("temporary", false)); }
        catch (IllegalArgumentException error) { return "错误：" + error.getMessage(); }
        int timeoutSec = args.optInt("timeout_sec", DEFAULT_TIMEOUT_SEC);
        if (timeoutSec < 1) {
            timeoutSec = DEFAULT_TIMEOUT_SEC;
        }

        boolean wantRoot = useRoot && rootAvailable();
        String output = exec(command, timeoutSec, wantRoot, args.optBoolean("temporary", false), mine);

        if (!wantRoot) {
            String note = useRoot
                    ? "注意：root 不可用（应用未获授权），本次以普通权限执行，"
                      + "涉及 /data 等目录的命令会失败。\n"
                    : "";
            return note + output;
        }
        return output;
    }

    /** Trusted launcher paths come from the private registry; user arguments stay structured. */
    public String runProgram(ToolchainStore.Launcher launcher, List<String> arguments,
            boolean temporaryCommand, int timeoutSec) throws Exception {
        return runProgram(launcher, arguments, temporaryCommand, timeoutSec, epoch);
    }

    int cancellationEpoch() { return epoch; }

    String runProgram(ToolchainStore.Launcher launcher, List<String> arguments,
            boolean temporaryCommand, int timeoutSec, final int mine) throws Exception {
        if (mine != epoch || Thread.currentThread().isInterrupted()) return "已停止。";
        cleanupFailed = false;
        try { ToolPaths.checkProgram(workDir, launcher.id, arguments, temporary, temporaryCommand); }
        catch (IllegalArgumentException failure) { return "错误：" + failure.getMessage(); }
        StringBuilder command = new StringBuilder();
        java.util.Iterator<String> keys = launcher.environment.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"R2_PREFIX".equals(key) && !"LD_LIBRARY_PATH".equals(key) && !"CLASSPATH".equals(key)
                    && !"PYTHONHOME".equals(key) && !"PYTHONPATH".equals(key) && !"SSL_CERT_FILE".equals(key)) {
                throw new IllegalArgumentException("工具环境变量不合法。");
            }
            command.append("export ").append(key).append('=').append(RootShell.quote(launcher.environment.getString(key))).append("; ");
        }
        if (temporary != null) command.append("export HOME=").append(RootShell.quote(temporary.directory().getPath())).append("; export PYTHONDONTWRITEBYTECODE=1; ");
        boolean probe = arguments.size() == 1 && ("--version".equals(arguments.get(0)) || "--help".equals(arguments.get(0))
                || "objection".equals(launcher.id) && "version".equals(arguments.get(0)));
        if (launcher.companion.length() > 0 && !probe) command.append("export BACKCAST_FRIDA_PORT=$((20000 + $$ % 40000)); ")
                .append(RootShell.quote(launcher.companion)).append(" --listen 127.0.0.1:$BACKCAST_FRIDA_PORT >/dev/null 2>&1 & export BACKCAST_FRIDA_PID=$!; ");
        command.append(RootShell.quote(launcher.executable));
        if ("apktool".equals(launcher.id) && !launcher.prefix.isEmpty() && temporary != null) {
            command.append(' ').append(RootShell.quote("-Duser.home=" + temporary.directory().getPath()));
            command.append(' ').append(RootShell.quote("-Djava.io.tmpdir=" + temporary.directory().getPath()));
        }
        for (String prefix : launcher.prefix) command.append(' ').append(RootShell.quoteArgument(prefix));
        for (String value : arguments) command.append(' ').append(RootShell.quoteArgument(value));
        if (launcher.aapt2.length() > 0 && !arguments.isEmpty()
                && ("b".equals(arguments.get(0)) || "build".equals(arguments.get(0)))) {
            command.append(" --use-aapt2 -a ").append(RootShell.quote(launcher.aapt2));
        }
        boolean withRoot = useRoot && rootAvailable();
        String result = exec(command.toString(), Math.min(600, Math.max(1, timeoutSec)), withRoot, temporaryCommand, mine, true);
        return useRoot && !withRoot ? "注意：root 不可用，本次按普通权限执行。\n" + result : result;
    }

    /** root 是否真的可用。探测失败即视为不可用，不再反复尝试。 */
    private static boolean rootAvailable() {
        Boolean cached = rootAvailable;
        if (cached != null) {
            return cached;
        }
        boolean ok = false;
        try {
            Process p = new ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            ok = finished && p.exitValue() == 0 && sb.indexOf("uid=0") >= 0;
        } catch (Exception e) {
            ok = false;
        }
        rootAvailable = ok;
        return ok;
    }

    private String exec(String command, int timeoutSec, boolean withRoot, boolean temporaryCommand, final int mine) throws Exception {
        return exec(command, timeoutSec, withRoot, temporaryCommand, mine, false);
    }

    private String exec(String command, int timeoutSec, boolean withRoot, boolean temporaryCommand, final int mine, boolean program) throws Exception {
        // 在命令前先切到工作目录，相对路径就不用模型自己拼了。
        String directory = workDir;
        String prefix = "";
        final File tempDirectory = temporary == null ? null : temporary.directory();
        if (temporary != null) {
            String temp = tempDirectory.getPath();
            if (epoch != mine) return "已停止。";
            prefix = "export TMPDIR=" + RootShell.quote(temp) + " TMP=" + RootShell.quote(temp)
                    + " TEMP=" + RootShell.quote(temp) + "; ";
            if (program) prefix += "export HOME=" + RootShell.quote(temp) + "; ";
            if (temporaryCommand) directory = temp;
            if (temporaryCommand && !program) {
                try { ToolPaths.checkTemporaryCommand(temp, command); }
                catch (IllegalArgumentException error) { return "错误：" + error.getMessage(); }
            }
        } else if (temporaryCommand) {
            return "错误：当前没有临时材料管理器。";
        }
        final String token = java.util.UUID.randomUUID().toString();
        final String pidPrefix = "__backcast_pid_" + token + ":";
        final String donePrefix = "__backcast_done_" + token + ":";
        String userCommand = prefix + (directory == null ? command
                : "cd " + RootShell.quote(directory) + " && " + command);
        // Keep a supervisor alive after command completion, so its process group
        // cannot be reused before Java kills background writers. No command runs
        // until Java has registered the supervisor identity and acknowledged it.
        String supervisor = supervisorScript(userCommand, pidPrefix, donePrefix, token);
        String full = "if command -v setsid >/dev/null 2>&1; then exec setsid sh -c "
                + quoteScript(supervisor) + "; else exec sh -c "
                + quoteScript(supervisor) + "; fi";
        String[] shell = withRoot
                ? new String[]{"su", "-c", full}
                : new String[]{"sh", "-c", full};

        ProcessBuilder pb = new ProcessBuilder(shell);
        pb.redirectErrorStream(true);
        if (epoch != mine) return "已停止。";
        final Process process = pb.start();
        final ProcessTree tree = new ProcessTree(process, withRoot);
        runningTree = tree;
        running = process;
        if (temporary != null) {
            try {
                // Register against the captured allocation before the reader can
                // acknowledge the supervisor and permit any user command.
                temporary.trackProcess(tempDirectory, new TemporaryWorkspace.ProcessCleanup() {
                    @Override public boolean stop() { return tree.stop(); }
                });
            } catch (Exception failure) {
                kill(process);
                if (running == process) { running = null; runningTree = null; }
                throw failure;
            }
        }
        if (epoch != mine) {
            kill(process);
            running = null;
            return "已停止。";
        }

        final StringBuilder sb = new StringBuilder();
        final java.util.concurrent.atomic.AtomicInteger result =
                new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith(pidPrefix)) {
                            if (tree.observeSupervisor(line.substring(pidPrefix.length())) && epoch == mine) {
                                process.getOutputStream().write((token + "\n").getBytes("UTF-8"));
                                process.getOutputStream().flush();
                            }
                            continue;
                        }
                        if (line.startsWith(donePrefix)) {
                            try { result.set(Integer.parseInt(line.substring(donePrefix.length()))); }
                            catch (NumberFormatException ignored) { }
                            break;
                        }
                        synchronized (sb) {
                            if (sb.length() < MAX_OUTPUT) {
                                sb.append(line).append('\n');
                            }
                        }
                    }
                    in.close();
                } catch (Exception ignored) {
                }
            }
        });
        reader.setDaemon(true);
        reader.start();

        try {
            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            while (epoch == mine) {
                tree.sample();
                if (result.get() != Integer.MIN_VALUE) break;
                if (finished(process)) {
                    break;
                }
                if (System.currentTimeMillis() >= deadline) {
                    kill(process);
                    return cleanupWarning() + "命令超时（" + timeoutSec + "s）。\n输出片段：\n" + textOf(sb);
                }
                Thread.sleep(150);
            }
            if (epoch != mine) {
                kill(process);
                return cleanupWarning() + "已停止。\n" + textOf(sb);
            }
            int exit;
            if (result.get() != Integer.MIN_VALUE) {
                exit = result.get();
                kill(process);
            } else {
                exit = process.exitValue();
            }
            reader.join(400);
            String body = textOf(sb);
            if (body.length() == 0) {
                body = "(无输出)";
            } else if (body.length() >= MAX_OUTPUT) {
                body = body + "\n…（输出已截断）";
            }
            return cleanupWarning() + "exit=" + exit + "\n" + body;
        } catch (Exception e) {
            if (epoch != mine) {
                return "已停止。\n" + textOf(sb);
            }
            throw e;
        } finally {
            kill(process);
            if (running == process) {
                running = null;
                runningTree = null;
            }
        }
    }

    private static boolean finished(Process process) {
        if (Build.VERSION.SDK_INT >= 26) {
            return !process.isAlive();
        }
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException still) {
            return false;
        }
    }

    private static String textOf(StringBuilder sb) {
        synchronized (sb) {
            return sb.toString();
        }
    }

    private static String quoteScript(String script) {
        return "'" + script.replace("'", "'\\''") + "'";
    }

    static String supervisorScript(String userCommand, String pidPrefix, String donePrefix, String token) {
        // EOF means the app closed or died. Keep the leader only while its owner
        // pipe exists, and independently verify an isolated group before killing
        // it. Fallback shells sharing the app's process group simply exit.
        String eofCleanup = "s=$(cat /proc/$BACKCAST_PROCESS_LEADER/stat 2>/dev/null); "
                + "s=${s##*) }; set -- $s; if [ $# -ge 4 ] "
                + "&& [ \"$3\" = \"$BACKCAST_PROCESS_LEADER\" ] "
                + "&& [ \"$4\" = \"$BACKCAST_PROCESS_LEADER\" ]; then "
                + "kill -s KILL -- -\"$BACKCAST_PROCESS_LEADER\"; fi; exit \"$result\"";
        String completion = "result=$?; printf "
                + RootShell.quote("\\n" + donePrefix + "%s\\n") + " \"$result\"; "
                + "IFS= read -r cleanup; " + eofCleanup;
        String child = "trap " + quoteScript(completion) + " EXIT; " + userCommand;
        return "BACKCAST_PROCESS_LEADER=$$; export BACKCAST_PROCESS_LEADER; "
                + "s=$(cat /proc/$$/stat) || exit 125; printf "
                + RootShell.quote(pidPrefix + "%s\\n") + " \"$s\"; "
                + "IFS= read -r ack || exit 125; [ \"$ack\" = " + RootShell.quote(token)
                + " ] || exit 125; sh -c " + quoteScript(child) + "; " + completion;
    }

    private String cleanupWarning() {
        return cleanupFailed ? "错误：未能确认所有后台子进程已结束，临时清理不能视为完成。\n" : "";
    }
}
