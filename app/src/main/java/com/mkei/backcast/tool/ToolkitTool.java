package com.mkei.backcast.tool;

import com.mkei.backcast.agent.Tool;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** A structured bridge to available Android reverse engineering programs. */
public final class ToolkitTool implements Tool {
    private final ShellTool shell;
    private final ToolchainStore store;
    private final ToolchainInstaller installer;
    private final String abi;
    private String workDir;
    private TemporaryWorkspace temporary;
    private volatile int epoch;

    public ToolkitTool(ShellTool shell, ToolchainStore store, String workDir,
            TemporaryWorkspace temporary, String abi) {
        this(shell, store, new ToolchainInstaller(store), abi);
        this.workDir = workDir; this.temporary = temporary;
    }

    ToolkitTool(ShellTool shell, ToolchainStore store, ToolchainInstaller installer, String abi) {
        if (shell == null || store == null) throw new IllegalArgumentException("工具运行器和私有配置不能为空。");
        this.shell = shell; this.store = store; this.installer = installer; this.abi = abi;
    }

    @Override public String name() { return "toolkit"; }

    @Override public String description() {
        return "管理并调用真实可用的 Android 逆向工具。list 查看官方目录；status 实际探测指定工具；"
                + "configure 绑定已安装的绝对可执行路径，不能把目录中存在的文件当成可运行；"
                + "install 只下载固定官方版本并校验 SHA-256，不执行远端安装脚本。"
                + "Apktool 需要设备 JVM，r2 支持官方 arm/arm64 包，Objection/binutils 需已有 Android 依赖。"
                + "run 使用 arguments 字符串数组，不能传 shell 命令；临时输出用 temporary=true，自动随轮次清理。";
    }

    @Override public JSONObject parameters() {
        try {
            JSONObject properties = new JSONObject();
            properties.put("action", new JSONObject().put("type", "string").put("enum", new JSONArray()
                    .put("list").put("status").put("configure").put("install").put("run").put("export").put("clear")));
            properties.put("tool", new JSONObject().put("type", "string").put("description", "list 返回的工具 id；批量配置 binutils 用 binutils"));
            properties.put("path", new JSONObject().put("type", "string").put("description", "已有可执行文件或 Apktool JAR 的绝对路径；binutils 可填安装 bin 目录"));
            properties.put("runtime", new JSONObject().put("type", "string").put("description", "Apktool JAR 对应设备 java 的绝对路径"));
            properties.put("arguments", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string"))
                    .put("description", "程序参数数组，每个参数单独一项，不拼接 shell 语法"));
            properties.put("temporary", new JSONObject().put("type", "boolean").put("description", "默认 true；在本轮私有临时目录执行，项目输入须用绝对路径"));
            properties.put("timeout_sec", new JSONObject().put("type", "integer").put("description", "运行超时秒数，范围 1 到 600"));
            properties.put("source", new JSONObject().put("type", "string").put("description", "export 的本轮临时结果绝对路径"));
            properties.put("target", new JSONObject().put("type", "string").put("description", "export 的项目内交付路径，可以是文件或目录"));
            properties.put("purpose", new JSONObject().put("type", "string").put("enum", new JSONArray().put("deliverable").put("test")));
            properties.put("overwrite", new JSONObject().put("type", "boolean").put("description", "export 默认不覆盖，只有明确 true 才覆盖同名目标"));
            return new JSONObject().put("type", "object").put("properties", properties).put("required", new JSONArray().put("action"));
        } catch (Exception failure) { return new JSONObject(); }
    }

    @Override public void abort() { epoch++; installer.abort(); shell.abort(); }

    @Override public String run(JSONObject args) throws Exception {
        final int mine = epoch;
        final int shellMine = shell.cancellationEpoch();
        try {
            String action = args.optString("action", "");
            if ("list".equals(action)) return listing().toString();
            if ("export".equals(action)) {
                return ToolkitExport.copy(workDir, temporary, store, args.optString("source", ""),
                        args.optString("target", ""), args.optString("purpose", ""), args.optBoolean("overwrite", false),
                        new ToolchainInstaller.Cancellation() {
                            @Override public void check() throws Exception {
                                if (epoch != mine || Thread.currentThread().isInterrupted()) throw new InterruptedException("导出已取消。");
                            }
                        }).put("state", "exported").toString();
            }
            String id = args.optString("tool", "");
            if ("configure".equals(action)) {
                checkEpoch(mine);
                if ("binutils".equals(id)) return new JSONObject().put("configured", store.configureBinutilsDirectory(args.optString("path", ""),
                        new ToolchainInstaller.Cancellation() { public void check() throws Exception { checkEpoch(mine); } }))
                        .put("state", "configured_not_probed").toString();
                synchronized (store.toolLock(id)) {
                    checkEpoch(mine); store.configure(id, args.optString("path", ""), args.optString("runtime", ""));
                }
                return status(id, mine, shellMine).toString();
            }
            if ("clear".equals(action)) {
                synchronized (store.toolLock(id)) { checkEpoch(mine); store.clear(id); }
                return new JSONObject().put("tool", id).put("state", "unconfigured").toString();
            }
            if ("status".equals(action)) return status(id, mine, shellMine).toString();
            if ("install".equals(action)) {
                installer.install(id, abi, new ToolchainInstaller.Cancellation() {
                    @Override public void check() throws Exception {
                        if (epoch != mine || Thread.currentThread().isInterrupted()) throw new InterruptedException("工具安装已取消。");
                    }
                });
                checkEpoch(mine);
                return status(id, mine, shellMine).put("download_verified", true).toString();
            }
            if ("run".equals(action)) {
                ToolCatalog.get(id);
                JSONArray raw = args.optJSONArray("arguments");
                List<String> arguments = new ArrayList<String>();
                if (raw != null) for (int i = 0; i < raw.length(); i++) {
                    Object value = raw.get(i);
                    if (!(value instanceof String)) throw new IllegalArgumentException("arguments 每项必须是字符串。");
                    arguments.add((String) value);
                }
                checkEpoch(mine);
                String result;
                synchronized (store.toolLock(id)) {
                    checkEpoch(mine);
                    ToolchainStore.Launcher launcher = store.launcher(id);
                    if (launcher == null) return status(id, mine, shellMine).put("error", "工具没有可执行入口，请先 configure 或 install 并补齐运行时。").toString();
                    checkEpoch(mine);
                    result = shell.runProgram(launcher, arguments, args.optBoolean("temporary", true), args.optInt("timeout_sec", 60), shellMine);
                }
                checkEpoch(mine);
                return new JSONObject().put("tool", id).put("output", result).put("success", succeeded(result)).toString();
            }
            throw new IllegalArgumentException("未知 toolkit action：" + action);
        } catch (InterruptedException cancellation) {
            Thread.currentThread().interrupt(); return new JSONObject().put("state", "cancelled").put("error", cancellation.getMessage()).toString();
        } catch (Exception failure) {
            return new JSONObject().put("state", "error").put("error", failure.getMessage() == null ? failure.toString() : failure.getMessage()).toString();
        }
    }

    public JSONObject listing() throws Exception {
        JSONArray tools = ToolCatalog.list();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject entry = tools.getJSONObject(i), configured = store.configuration(entry.getString("id"));
            entry.put("configured", configured.optString("path", "").length() > 0).put("configuration", configured)
                    .put("state", configured.optString("path", "").length() > 0 ? "configured_not_probed" : "unconfigured");
        }
        return new JSONObject().put("storage", store.root().getPath()).put("abi", abi).put("tools", tools);
    }

    public JSONObject status(String id) throws Exception {
        return status(id, epoch, shell.cancellationEpoch());
    }

    private JSONObject status(String id, int mine, int shellMine) throws Exception {
        synchronized (store.toolLock(id)) { checkEpoch(mine); return statusLocked(id, mine, shellMine); }
    }

    private JSONObject statusLocked(String id, int mine, int shellMine) throws Exception {
        JSONObject result = ToolCatalog.get(id).json(), configured = store.configuration(id);
        result.put("configuration", configured).put("ready", false);
        if (configured.optString("path", "").length() == 0) return result.put("state", "unconfigured");
        ToolchainStore.Launcher launcher = store.launcher(id);
        if (launcher == null) return result.put("state", "needs_runtime");
        List<String> version = new ArrayList<String>(); version.add("--version");
        if ("radare2".equals(id) || "rabin2".equals(id)) { version.clear(); version.add("-v"); }
        checkEpoch(mine);
        String output = shell.runProgram(launcher, version, true, 8, shellMine);
        checkEpoch(mine);
        boolean ready = succeeded(output);
        return result.put("state", ready ? "ready" : "unavailable").put("ready", ready).put("probe_output", output);
    }

    private void checkEpoch(int mine) throws InterruptedException {
        if (epoch != mine || Thread.currentThread().isInterrupted()) throw new InterruptedException("工具操作已取消。");
    }

    private static boolean succeeded(String output) {
        if (output.startsWith("注意：")) { int line = output.indexOf('\n'); output = line < 0 ? "" : output.substring(line + 1); }
        return output.startsWith("exit=0\n");
    }
}
