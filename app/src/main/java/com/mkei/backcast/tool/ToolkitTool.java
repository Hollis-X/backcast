package com.mkei.backcast.tool;

import com.mkei.backcast.agent.Tool;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** A structured bridge to available Android reverse engineering programs. */
public final class ToolkitTool implements Tool {
    private final ShellTool shell;
    private final ToolchainStore store;
    private final String abi;
    private String workDir;
    private TemporaryWorkspace temporary;
    private volatile int epoch;
    private final Object installationLock = new Object();
    private ToolchainInstaller.Cancellation installation;

    public ToolkitTool(ShellTool shell, ToolchainStore store, String workDir,
            TemporaryWorkspace temporary, String abi) {
        if (shell == null || store == null) throw new IllegalArgumentException("工具运行器和私有配置不能为空。");
        this.shell = shell; this.store = store; this.abi = abi;
        this.workDir = workDir; this.temporary = temporary;
    }

    @Override public String name() { return "toolkit"; }

    @Override public String description() {
        return "直接调用已安装的逆向工具，无需填写私有工具路径。工具配置中的安装按钮从固定发布源下载工具包；list、status、diagnose 和 run 不会下载。list 查看工具；status 实际探测指定 tool，例如 {action:'status',tool:'apktool'}；"
                + "diagnose 检查受管理的工具入口；Objection 会真实验证私有 Frida client/server 版本、匹配情况和 ART 兼容模式。"
                + "私有工具路径通过 toolkit 管理，不要用 shell 访问或要求用户把 App 私有目录添加为工作文件夹。"
                + "Apktool 使用 DEX JAR 和 Android aapt2，radare2/rabin2、GNU binutils、Objection/Python/Frida 安装在 App 私有目录，安装后可离线使用。"
                + "支持 Android 8.0+ ARM/ARM64；Objection 自动启用本次调用的本地 Frida server，结束后清理子进程，跨应用操作需要 root。"
                + "Objection 用 ['-n','包名','run','android hooking list classes'] 这样的单次命令；交互 start/explore 和桌面 patch/sign 工作流不适用于此入口。"
                + "list 返回各工具参数示例。radare2 用 ['-c','ii;is;afl;q','文件']，本入口自动非交互退出；rabin2 的 -I/-i 分别查看信息/导入。"
                + "addr2line 用 ['-f','-C','-e','文件.so','0x1234']，-e 后是文件，符号名先用 nm 找地址。"
                + "run 使用 arguments 字符串数组，不能传 shell 命令；临时输出用 temporary=true，自动随轮次清理。"
                + "status 仅验证版本入口，不代表目标应用的 Frida/Java hook 兼容；failure_kind/hint 提示纠正调用，不能把失败输出当成功或重复相同失败调用。"
                + "Objection 的 frida_evidence 记录真实连接、附加、脚本与 RPC 阶段；超时本身不能证明反调试。";
    }

    @Override public JSONObject parameters() {
        try {
            JSONObject properties = new JSONObject();
            properties.put("action", new JSONObject().put("type", "string").put("enum", new JSONArray()
                    .put("list").put("status").put("diagnose").put("run").put("export")));
            JSONArray ids = new JSONArray();
            JSONArray catalog = ToolCatalog.list();
            for (int i = 0; i < catalog.length(); i++) ids.put(catalog.getJSONObject(i).getString("id"));
            properties.put("tool", new JSONObject().put("type", "string").put("enum", ids)
                    .put("description", "status/diagnose/run 必填：目标工具 id。不能把工具名放到 arguments；list/export 不需要此字段"));
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

    @Override public void abort() {
        ToolchainInstaller.Cancellation owned;
        synchronized (installationLock) { epoch++; owned = installation; }
        if (owned != null) store.cancelDownloads(owned);
        shell.abort();
    }

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
            if ("status".equals(action) || "diagnose".equals(action) || "run".equals(action)) requireTool(id, action);
            if ("status".equals(action)) return status(id, mine, shellMine).toString();
            if ("diagnose".equals(action)) return diagnose(id, mine, shellMine).toString();
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
                    if (launcher == null) return status(id, mine, shellMine).put("success", false).put("error", "工具未安装或运行入口不可用，请检查工具配置。").toString();
                    checkEpoch(mine);
                    result = shell.runProgram(launcher, arguments, args.optBoolean("temporary", true), args.optInt("timeout_sec", 60), shellMine);
                } } finally { use.close(); }
                checkEpoch(mine);
                JSONObject response = new JSONObject().put("tool", id).put("output", result).put("success", succeeded(result));
                if (!succeeded(result)) {
                    response.put("state", "error").put("error", "工具执行未成功。");
                    describeFailure(id, result, response);
                } else if ("apktool".equals(id)) {
                    String artifactError = shell.verifyApktoolOutput(arguments, shellMine);
                    if (artifactError.length() > 0) response.put("state", "error").put("error", artifactError)
                            .put("failure_kind", "output_artifact_missing").put("success", false);
                }
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
            entry.put("bundled", bundled).put("configured", bundled ? bundle.optBoolean("installed") : configured.optString("path", "").length() > 0).put("configuration", visibleConfiguration(configured))
                    .put("state", bundled ? bundle.optString("state", "not_installed") : configured.optString("path", "").length() > 0 ? "configured_not_probed"
                            : store.hasPackageManifest() ? "unsupported" : "unconfigured");
        }
        JSONObject visibleBundle = new JSONObject(bundle.toString()), manifest = visibleBundle.optJSONObject("manifest");
        if (manifest != null) {
            visibleBundle.remove("manifest");
            JSONObject versions = new JSONObject();
            for (String name : new String[]{"apktool", "radare2", "objection", "java_bridge", "objection_art_mode"}) {
                if (manifest.has(name)) versions.put(name, manifest.get(name));
            }
            visibleBundle.put("versions", versions);
        }
        return new JSONObject().put("storage", store.root().getPath()).put("abi", abi).put("tools", tools).put("package", visibleBundle);
    }

    public JSONObject installBundled(EmbeddedToolchain.ProgressListener listener) throws Exception {
        final int mine;
        final ToolchainInstaller.Cancellation owned;
        synchronized (installationLock) {
            if (installation != null) throw new IllegalStateException("本会话的工具包安装仍在进行中。");
            mine = epoch;
            owned = new ToolchainInstaller.Cancellation() { public void check() throws Exception { checkEpoch(mine); } };
            installation = owned;
        }
        try {
            checkEpoch(mine);
            return store.installBundled(owned, listener);
        } finally {
            synchronized (installationLock) { if (installation == owned) installation = null; }
        }
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
            if (store.bundled(id) && store.bundledRemoved()) return ToolCatalog.get(id).json().put("ready", false).put("success", false).put("state", "removed").put("bundled", true);
            synchronized (store.toolLock(id)) { checkEpoch(mine); return statusLocked(id, mine, shellMine); }
        } finally { use.close(); }
    }

    private JSONObject statusLocked(String id, final int mine, int shellMine) throws Exception {
        if (store.bundled(id)) {
            JSONObject bundle = store.packageStatus();
            if (!bundle.optBoolean("installed")) return ToolCatalog.get(id).json().put("ready", false).put("success", false)
                    .put("state", bundle.optString("state", "not_installed")).put("bundled", true)
                    .put("hint", "请在工具配置中点击安装工具包。");
        }
        ToolchainStore.Launcher launcher = store.launcher(id, new ToolchainInstaller.Cancellation() {
            public void check() throws Exception { checkEpoch(mine); }
        });
        JSONObject result = ToolCatalog.get(id).json(), configured = store.configuration(id);
        result.put("configuration", visibleConfiguration(configured)).put("ready", false).put("bundled", store.bundled(id))
                .put("probe_type", "version").put("probe_scope", "只验证程序版本入口；目标文件分析和动态附加能力以实际 run 结果为准。");
        if (configured.optString("path", "").length() == 0) return result.put("state", store.hasPackageManifest() ? "unsupported" : "unconfigured");
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

    /** Model-visible metadata must not include the bundled Python bootstrap. */
    private static JSONObject visibleConfiguration(JSONObject configured) throws Exception {
        JSONObject visible = new JSONObject(configured.toString());
        if ("bundled".equals(visible.optString("origin"))) {
            visible.remove("prefix"); visible.remove("environment");
            visible.put("managed_private", true).put("generic_shell_access", false);
        }
        return visible;
    }

    private JSONObject diagnose(String id, final int mine, int shellMine) throws Exception {
        ToolchainStore.Use use = store.beginUse(new ToolchainInstaller.Cancellation() {
            public void check() throws Exception { checkEpoch(mine); }
        });
        try { synchronized (store.toolLock(id)) {
            checkEpoch(mine);
            if (store.bundled(id) && store.bundledRemoved()) return ToolCatalog.get(id).json()
                    .put("ready", false).put("success", false).put("state", "removed").put("bundled", true);
            JSONObject result = statusLocked(id, mine, shellMine);
            if (!"objection".equals(id)) return result;
            ToolchainStore.Launcher launcher = store.launcher(id, new ToolchainInstaller.Cancellation() {
                public void check() throws Exception { checkEpoch(mine); }
            });
            JSONObject configured = store.configuration(id);
            result.put("java_attach", "not_tested").put("art_mode", configured.optString("art_mode", "unmodified"))
                    .put("java_bridge", configured.optString("java_bridge", ""))
                    .put("probe_scope", "实际验证版本入口和 Frida client/server 配对；没有附加目标进程，也没有验证 Java hook。" );
            if (launcher == null || !"bundled".equals(configured.optString("origin"))) return result;
            JSONObject companion = new JSONObject().put("managed_private", true);
            result.put("frida_server", companion);
            File server = launcher.companion.length() == 0 ? null : store.managed(launcher.companion);
            if (server == null || !server.isFile()) {
                companion.put("exists", false).put("ready", false);
                return result.put("ready", false).put("state", "unavailable").put("failure_kind", "frida_server_missing");
            }
            companion.put("exists", true).put("bytes", server.length()).put("executable", server.canExecute());
            // This exact launcher comes from the private registry, never an input path.
            ToolchainStore.Launcher serverProbe = new ToolchainStore.Launcher("objection", server.getPath());
            java.util.Iterator<String> keys = launcher.environment.keys();
            while (keys.hasNext()) { String key = keys.next(); serverProbe.environment.put(key, launcher.environment.get(key)); }
            List<String> versionArguments = new ArrayList<String>(); versionArguments.add("--version");
            checkEpoch(mine);
            String serverOutput = shell.runProgram(serverProbe, versionArguments, true, 8, shellMine);
            checkEpoch(mine);
            String serverVersion = numericVersion(serverOutput);
            companion.put("probe_output", serverOutput).put("version", serverVersion).put("ready", serverVersion.length() > 0);
            ToolchainStore.Launcher clientProbe = new ToolchainStore.Launcher("objection", launcher.executable);
            clientProbe.prefix.add("-c"); clientProbe.prefix.add("import frida; print(frida.__version__)");
            keys = launcher.environment.keys();
            while (keys.hasNext()) { String key = keys.next(); clientProbe.environment.put(key, launcher.environment.get(key)); }
            checkEpoch(mine);
            String clientOutput = shell.runProgram(clientProbe, versionArguments, true, 8, shellMine);
            checkEpoch(mine);
            String clientVersion = numericVersion(clientOutput);
            result.put("frida_client", new JSONObject().put("probe_output", clientOutput).put("version", clientVersion)
                    .put("ready", clientVersion.length() > 0));
            boolean matches = serverVersion.length() > 0 && serverVersion.equals(clientVersion);
            result.put("frida_versions_match", matches);
            boolean ready = result.optBoolean("ready") && matches;
            result.put("ready", ready).put("state", ready ? "diagnostic_ready" : "unavailable");
            if (!matches) result.put("failure_kind", serverVersion.length() == 0 || clientVersion.length() == 0
                    ? "frida_runtime_unavailable" : "frida_version_mismatch")
                    .put("hint", "Frida client/server 必须匹配且都能启动。请在工具配置中重新安装工具包，再运行 diagnose；没有执行目标 Java hook。");
            return result;
        } } finally { use.close(); }
    }

    private static String numericVersion(String output) {
        if (!validVersion("apktool", output)) return "";
        java.util.regex.Matcher version = java.util.regex.Pattern.compile("(?m)^([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)\\s*$").matcher(output);
        return version.find() ? version.group(1) : "";
    }

    private static void describeFailure(String id, String output, JSONObject result) throws Exception {
        String phase = "";
        if ("objection".equals(id)) {
            JSONArray evidence = new JSONArray();
            String active = "", failed = "";
            for (String line : output.split("\\r?\\n")) if (line.startsWith(ObjectionBootstrap.MARKER)) {
                try {
                    JSONObject entry = new JSONObject(line.substring(ObjectionBootstrap.MARKER.length()));
                    String candidate = entry.optString("phase");
                    if (!"server_connect".equals(candidate) && !"command".equals(candidate) && !"attach".equals(candidate)
                            && !"script_create".equals(candidate) && !"script_load".equals(candidate) && !"rpc".equals(candidate)) continue;
                    String state = entry.optString("state");
                    if ("failed".equals(state)) failed = candidate;
                    else if ("started".equals(state)) active = candidate;
                    else if ("completed".equals(state) && candidate.equals(active)) active = "";
                    if (evidence.length() < 20) evidence.put(entry);
                } catch (Exception ignored) { }
            }
            phase = failed.length() > 0 ? failed : active;
            if (evidence.length() > 0) result.put("frida_phase", phase).put("frida_evidence", evidence);
        }
        if (output.contains("身份握手") || output.contains("命令退出状态") || output.contains("输出通道中断") || output.contains("执行完成状态无效")) {
            result.put("failure_kind", "execution_channel_failed").put("hint", "命令执行通道没有返回可验证的实际进程或完成状态。"
                    + "不能以启动器 exit=0、无输出或已安装认定执行成功。");
        } else if ("objection".equals(id) && output.contains("Unable to find target application")) {
            result.put("failure_kind", "target_not_running").put("hint", "目标应用没有可附加的运行进程。先确认包名和运行状态，"
                    + "再指定实际 PID 或运行中的包名；版本探测成功不代表目标正在运行。");
        } else if ("objection".equals(id) && ("attach".equals(phase)
                && (output.contains("TimedOutError") || output.contains("命令超时"))
                || output.contains("timed out while waiting for signal from process"))) {
            result.put("failure_kind", "attach_timeout").put("frida_phase", "attach").put("hint", "Frida 在 device.attach 等待目标进程信号时超时，尚未加载 Objection 脚本。"
                    + "超时本身不能证明反调试。保留本次 PID、进程起始身份、root 身份和 client/server 版本诊断；"
                    + "核对目标仍在运行及系统日志，不要重复相同附加或仅提高超时。");
        } else if ("objection".equals(id) && "server_connect".equals(phase)) {
            result.put("failure_kind", "frida_server_unavailable").put("hint", "本次私有 Frida server 连接或版本配对失败，还没有附加目标进程。"
                    + "先检查 frida_evidence 中运行身份、版本和启动状态，不要把该错误归因于目标应用。");
        } else if ("objection".equals(id) && output.contains("SyntaxError")
                && ("script_create".equals(phase) || phase.length() == 0
                && (output.contains("Script(line ") || output.contains("script(line ")))) {
            result.put("failure_kind", "frida_script_invalid").put("frida_phase", "script_create").put("hint", "Frida 已附加，但 Objection 脚本编译失败。"
                    + "这是脚本语法或打包错误，不是附加超时；使用更新后的工具包并保留原始行号。");
        } else if ("objection".equals(id) && output.contains("TimedOutError")) {
            result.put("failure_kind", "frida_operation_timeout").put("hint", "Frida 操作超时；按 frida_phase 和原始 traceback 区分脚本加载、RPC 或连接。"
                    + "没有 attach 阶段证据时不能称附加失败，也不能凭超时判定反调试。");
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
                if ("--name".equals(value) || "-n".equals(value) || "--gadget".equals(value) || "-g".equals(value)
                        || "--uid".equals(value)) {
                    if (++i >= args.size() || args.get(i).length() == 0 || args.get(i).startsWith("-")) {
                        throw new IllegalArgumentException("Objection 目标选项缺少有效参数。");
                    }
                    continue;
                }
                if (value.startsWith("--name=") && value.length() > 7 || value.startsWith("--gadget=") && value.length() > 9
                        || value.startsWith("--uid=") && value.length() > 6
                        || value.startsWith("-n") && !value.startsWith("--") && value.length() > 2
                        || value.startsWith("-g") && !value.startsWith("--") && value.length() > 2) continue;
                if ("--help".equals(value) && args.size() == 1) return;
                if ("--debug".equals(value) || "-d".equals(value) || "--spawn".equals(value) || "-s".equals(value)
                        || "--no-pause".equals(value) || "-p".equals(value) || "--foremost".equals(value) || "-f".equals(value)
                        || "--debugger".equals(value)) continue;
                // Official Objection 1.12.5 uses -h for host and -P for port;
                // -p means no-pause. Reject unknown/combined flags too, since
                // Click can decode -dN as debug + network after our settings.
                throw new IllegalArgumentException("Objection 连接由本次私有 Frida server 管理，不能覆盖 host/port/network/local/serial 或传未知连接选项。"
                        + "只指定 name/PID、一次 run 命令及 debug/spawn/no-pause/foremost 等目标选项。");
            }
            if ("version".equals(value) || "run".equals(value)) return;
            throw new IllegalArgumentException("Objection 使用非交互 run 单次命令或 version。start/explore/API 常驻服务和桌面 patch/sign 流程不由该入口执行。");
        }
        throw new IllegalArgumentException("请给 Objection 的 run 命令，例如 ['-n','包名','run','android hooking list classes']。");
    }

    private static boolean succeeded(String output) {
        if (output.startsWith("注意：")) { int line = output.indexOf('\n'); output = line < 0 ? "" : output.substring(line + 1); }
        return output.startsWith("exit=0\n");
    }
}
