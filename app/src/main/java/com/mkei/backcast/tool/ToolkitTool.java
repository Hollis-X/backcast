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
        return "直接调用 APK 内置逆向工具，无需用户下载或填写路径。list 查看内置工具；status 实际探测指定 tool，例如 {action:'status',tool:'apktool'}；"
                + "Apktool 使用内置 DEX JAR 和 Android aapt2，radare2/rabin2、GNU binutils、Objection/Python/Frida 均离线释放到 App 私有目录。"
                + "支持 Android 8.0+ ARM/ARM64；Objection 自动启用本次调用的本地 Frida server，结束后清理子进程，跨应用操作需要 root。"
                + "Objection 用 ['-n','包名','run','android hooking list classes'] 这样的单次命令；交互 start/explore 和桌面 patch/sign 工作流不适用于此入口。"
                + "list 返回各工具参数示例。radare2 用 ['-c','ii;is;afl;q','文件']，本入口自动非交互退出；rabin2 的 -I/-i 分别查看信息/导入。"
                + "addr2line 用 ['-f','-C','-e','文件.so','0x1234']，-e 后是文件，符号名先用 nm 找地址。"
                + "run 使用 arguments 字符串数组，不能传 shell 命令；临时输出用 temporary=true，自动随轮次清理。"
                + "status 仅验证版本入口，不代表目标应用的 Frida/Java hook 兼容；failure_kind/hint 提示纠正调用，不能把失败输出当成功或重复相同失败调用。";
    }

    @Override public JSONObject parameters() {
        try {
            JSONObject properties = new JSONObject();
            properties.put("action", new JSONObject().put("type", "string").put("enum", new JSONArray()
                    .put("list").put("status").put("run").put("export")));
            JSONArray ids = new JSONArray();
            JSONArray catalog = ToolCatalog.list();
            for (int i = 0; i < catalog.length(); i++) ids.put(catalog.getJSONObject(i).getString("id"));
            properties.put("tool", new JSONObject().put("type", "string").put("enum", ids)
                    .put("description", "status/run 必填：目标工具 id。不能把工具名放到 arguments；list/export 不需要此字段"));
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
            if ("status".equals(action) || "run".equals(action)) requireTool(id, action);
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
                return status(id, mine, shellMine).put(store.bundled(id) ? "prepared_from_apk" : "download_verified", true).toString();
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
                if ("objection".equals(id)) checkObjectionArguments(arguments);
                arguments = ToolPaths.prepareProgramArguments(id, arguments);
                if (workDir != null) ToolPaths.checkProgram(workDir, id, arguments, temporary, args.optBoolean("temporary", true));
                checkEpoch(mine);
                String result;
                ToolchainStore.Use use = store.beginUse(new ToolchainInstaller.Cancellation() {
                    public void check() throws Exception { checkEpoch(mine); }
                });
                try { synchronized (store.toolLock(id)) {
                    checkEpoch(mine);
                    ToolchainStore.Launcher launcher = store.launcher(id, new ToolchainInstaller.Cancellation() {
                        public void check() throws Exception { checkEpoch(mine); }
                    });
                    if (launcher == null) return status(id, mine, shellMine).put("error", "当前设备没有兼容的内置工具入口。").toString();
                    checkEpoch(mine);
                    result = shell.runProgram(launcher, arguments, args.optBoolean("temporary", true), args.optInt("timeout_sec", 60), shellMine);
                } } finally { use.close(); }
                checkEpoch(mine);
                JSONObject response = new JSONObject().put("tool", id).put("output", result).put("success", succeeded(result));
                if (!succeeded(result)) describeFailure(id, result, response);
                return response.toString();
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
        JSONObject bundle = store.packageStatus();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject entry = tools.getJSONObject(i), configured = store.configuration(entry.getString("id"));
            boolean bundled = store.bundled(entry.getString("id"));
            entry.put("bundled", bundled).put("configured", bundled || configured.optString("path", "").length() > 0).put("configuration", configured)
                    .put("state", bundled ? store.bundledRemoved() ? "removed" : bundle.optBoolean("installed") ? "installed" : "bundled_not_probed" : configured.optString("path", "").length() > 0 ? "configured_not_probed"
                            : store.hasBundledAssets() ? "unsupported" : "unconfigured");
        }
        return new JSONObject().put("storage", store.root().getPath()).put("abi", abi).put("tools", tools).put("package", bundle);
    }

    public JSONObject packageStatus() throws Exception { return store.packageStatus(); }
    public JSONObject installBundled() throws Exception {
        return installBundled(null);
    }
    public JSONObject installBundled(EmbeddedToolchain.ProgressListener listener) throws Exception {
        final int mine = epoch;
        return store.installBundled(new ToolchainInstaller.Cancellation() { public void check() throws Exception { checkEpoch(mine); } }, listener);
    }
    public JSONObject removeBundled() throws Exception {
        final int mine = epoch;
        return store.removeBundled(new ToolchainInstaller.Cancellation() { public void check() throws Exception { checkEpoch(mine); } });
    }

    public JSONObject status(String id) throws Exception {
        requireTool(id, "status");
        return status(id, epoch, shell.cancellationEpoch());
    }

    private JSONObject status(String id, int mine, int shellMine) throws Exception {
        final int token = mine;
        ToolchainStore.Use use = store.beginUse(new ToolchainInstaller.Cancellation() { public void check() throws Exception { checkEpoch(token); } });
        try {
            if (store.bundled(id) && store.bundledRemoved()) return ToolCatalog.get(id).json().put("ready", false).put("state", "removed").put("bundled", true);
            synchronized (store.toolLock(id)) { checkEpoch(mine); return statusLocked(id, mine, shellMine); }
        } finally { use.close(); }
    }

    private JSONObject statusLocked(String id, final int mine, int shellMine) throws Exception {
        ToolchainStore.Launcher launcher = store.launcher(id, new ToolchainInstaller.Cancellation() {
            public void check() throws Exception { checkEpoch(mine); }
        });
        JSONObject result = ToolCatalog.get(id).json(), configured = store.configuration(id);
        result.put("configuration", configured).put("ready", false).put("bundled", store.bundled(id))
                .put("probe_type", "version").put("probe_scope", "只验证程序版本入口；目标文件分析和动态附加能力以实际 run 结果为准。");
        if (configured.optString("path", "").length() == 0) return result.put("state", store.hasBundledAssets() ? "unsupported" : "unconfigured");
        if (launcher == null) return result.put("state", "needs_runtime");
        List<String> version = new ArrayList<String>(); version.add("--version");
        if ("radare2".equals(id) || "rabin2".equals(id)) { version.clear(); version.add("-v"); }
        if ("objection".equals(id)) { version.clear(); version.add("version"); }
        checkEpoch(mine);
        String output = shell.runProgram(launcher, version, true, 8, shellMine);
        checkEpoch(mine);
        boolean ready = validVersion(id, output);
        result.put("state", ready ? "ready" : "unavailable").put("ready", ready).put("probe_output", output);
        if (!ready) describeFailure(id, output, result);
        return result;
    }

    private static void describeFailure(String id, String output, JSONObject result) throws Exception {
        if ("objection".equals(id) && output.contains("Unable to find target application")) {
            result.put("failure_kind", "target_not_running").put("hint", "目标应用没有可附加的运行进程。先确认包名和运行状态，"
                    + "再指定实际 PID 或运行中的包名；版本探测成功不代表目标正在运行。");
        } else if ("objection".equals(id) && output.contains("TimedOutError")) {
            result.put("failure_kind", "attach_timeout").put("hint", "Frida 附加目标进程超时。确认目标 PID、root 授权和设备 ART 兼容性；"
                    + "不要只增加超时或重复同一附加请求，先检查目标及系统崩溃日志。");
        } else if ("objection".equals(id) && (output.contains("tryGetEnvJvmti") || output.contains("access violation"))) {
            result.put("failure_kind", "frida_java_bridge_incompatible").put("hint", "Frida Java 桥接在该设备/目标的 ART 初始化时失败。"
                    + "本次 Java hook 未完成；停止重复相同 hook，保留此错误用于兼容性诊断。版本探活不能验证 Java hook。");
        } else if (output.startsWith("命令超时")) {
            result.put("failure_kind", "timeout").put("hint", "命令已超时并停止；先缩小分析范围，不要把输出片段当作完整结果。");
        } else if (output.startsWith("错误：")) {
            result.put("failure_kind", "invalid_arguments").put("hint", output.substring(3));
        } else if (output.contains("No such file") || output.contains("Cannot open")) {
            result.put("failure_kind", "input_missing").put("hint", "先确认输入路径存在并仍属于本轮临时目录；"
                    + "上一轮临时材料会清理，新的轮次须重新生成。按 list 的示例检查输入/输出顺序。");
        } else if ("ar".equals(id) && output.contains("file format not recognized")) {
            result.put("failure_kind", "not_an_archive").put("hint", "ar t 只读取 .a 等归档，不能把单个 .so 当作归档。"
                    + "查看 .so 用 readelf/rabin2；创建归档用 ['rcs','临时输出.a','输入.o']。");
        } else {
            result.put("failure_kind", "program_failed").put("hint", "程序没有成功完成。按原始 output 和 list 参数示例纠正输入，不能仅凭已安装或版本成功判断本次成功。");
        }
    }

    private static void requireTool(String id, String action) {
        if (id == null || id.trim().length() == 0) throw new IllegalArgumentException(action
                + " 缺少必填字段 tool。示例：{\"action\":\"" + action
                + "\",\"tool\":\"apktool\"}；arguments 只用于 run 的程序参数。");
        ToolCatalog.get(id);
    }

    private static boolean validVersion(String id, String output) {
        if (!succeeded(output)) return false;
        String value = output.toLowerCase(java.util.Locale.US);
        if (value.contains("exception in thread") || value.contains("traceback (most recent call last)")
                || value.contains("cannot link executable") || value.contains("fatal exception")
                || java.util.regex.Pattern.compile("(?m)^killed\\s*$").matcher(value).find()) return false;
        if ("apktool".equals(id)) return java.util.regex.Pattern
                .compile("(?m)^\\d+\\.\\d+(?:\\.\\d+)?(?:[-+][^\\s]+)?\\s*$").matcher(output).find();
        String name = "objection".equals(id) ? "objection" : id;
        return java.util.regex.Pattern.compile("(?im)^" + java.util.regex.Pattern.quote(name)
                + "[^\\r\\n]*\\d+\\.\\d+|^gnu " + java.util.regex.Pattern.quote(name)
                + "[^\\r\\n]*\\d+\\.\\d+").matcher(output).find();
    }

    private void checkEpoch(int mine) throws InterruptedException {
        if (epoch != mine || Thread.currentThread().isInterrupted()) throw new InterruptedException("工具操作已取消。");
    }

    private static void checkObjectionArguments(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String value = args.get(i);
            if (value.startsWith("-")) {
                if ("--help".equals(value)) return;
                if ("--name".equals(value) || "-n".equals(value) || "--gadget".equals(value) || "-g".equals(value)
                        || "--uid".equals(value)) i++;
                continue;
            }
            if ("version".equals(value) || "run".equals(value)) return;
            throw new IllegalArgumentException("内置 Objection 使用非交互 run 单次命令或 version。start/explore/API 常驻服务和桌面 patch/sign 流程不由该入口执行。");
        }
        throw new IllegalArgumentException("请给 Objection 的 run 命令，例如 ['-n','包名','run','android hooking list classes']。");
    }

    private static boolean succeeded(String output) {
        if (output.startsWith("注意：")) { int line = output.indexOf('\n'); output = line < 0 ? "" : output.substring(line + 1); }
        return output.startsWith("exit=0\n");
    }
}
