package com.mkei.backcast.tool;

import android.os.Build;

import com.mkei.backcast.agent.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
    private volatile Process running;
    /** 每次停止加一。正在跑的命令记下旧值，对不上就退出。 */
    private volatile int epoch;

    public ShellTool(boolean useRoot, String workDir) {
        this.useRoot = useRoot;
        this.workDir = workDir == null || workDir.length() == 0 ? null : workDir;
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
                + "传入完整命令行字符串。";
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

    private static void kill(Process process) {
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
    }

    @Override
    public String run(JSONObject args) throws Exception {
        String command = args.optString("command", "").trim();
        if (command.length() == 0) {
            return "错误：command 为空。";
        }

        try { ToolPaths.checkCommand(workDir, command); }
        catch (IllegalArgumentException error) { return "错误：" + error.getMessage(); }
        int timeoutSec = args.optInt("timeout_sec", DEFAULT_TIMEOUT_SEC);
        if (timeoutSec < 1) {
            timeoutSec = DEFAULT_TIMEOUT_SEC;
        }

        boolean wantRoot = useRoot && rootAvailable();
        String output = exec(command, timeoutSec, wantRoot);

        if (!wantRoot) {
            String note = useRoot
                    ? "注意：root 不可用（应用未获授权），本次以普通权限执行，"
                      + "涉及 /data 等目录的命令会失败。\n"
                    : "";
            return note + output;
        }
        return output;
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

    private String exec(String command, int timeoutSec, boolean withRoot) throws Exception {
        // 在命令前先切到工作目录，相对路径就不用模型自己拼了。
        String full = workDir == null ? command : "cd " + workDir + " && " + command;
        String[] shell = withRoot
                ? new String[]{"su", "-c", full}
                : new String[]{"sh", "-c", full};

        ProcessBuilder pb = new ProcessBuilder(shell);
        pb.redirectErrorStream(true);
        final int mine = epoch;
        final Process process = pb.start();
        running = process;
        if (epoch != mine) {
            kill(process);
            running = null;
            return "已停止。";
        }

        final StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = in.readLine()) != null) {
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
                if (finished(process)) {
                    break;
                }
                if (System.currentTimeMillis() >= deadline) {
                    kill(process);
                    return "命令超时（" + timeoutSec + "s），已终止。\n输出片段：\n" + textOf(sb);
                }
                Thread.sleep(150);
            }
            if (epoch != mine) {
                kill(process);
                return "已停止。\n" + textOf(sb);
            }
            reader.join(400);
            int exit = process.exitValue();
            String body = textOf(sb);
            if (body.length() == 0) {
                body = "(无输出)";
            } else if (body.length() >= MAX_OUTPUT) {
                body = body + "\n…（输出已截断）";
            }
            return "exit=" + exit + "\n" + body;
        } catch (Exception e) {
            if (epoch != mine) {
                return "已停止。\n" + textOf(sb);
            }
            throw e;
        } finally {
            if (running == process) {
                running = null;
            }
            if (epoch != mine) {
                kill(process);
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
}