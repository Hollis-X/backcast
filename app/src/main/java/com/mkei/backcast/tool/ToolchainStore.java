package com.mkei.backcast.tool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent software and launcher settings live outside disposable turn materials. */
public final class ToolchainStore {
    public static final class Launcher {
        public final String id, executable;
        public String companion = "";
        public String aapt2 = "";
        public final List<String> prefix = new ArrayList<String>();
        public final JSONObject environment = new JSONObject();
        Launcher(String id, String executable) { this.id = id; this.executable = executable; }
    }

    private final File root, registry;
    private final EmbeddedToolchain embedded;

    public ToolchainStore(File directory) {
        this(directory, null, "", 0);
    }

    public ToolchainStore(File directory, EmbeddedToolchain.Assets assets, String abi, int sdk) {
        if (directory == null) throw new IllegalArgumentException("工具安装需要 App 私有路径。");
        try { root = directory.getCanonicalFile(); }
        catch (IOException error) { throw new IllegalArgumentException("无法确认工具目录。", error); }
        registry = new File(root, "registry.json");
        embedded = assets == null ? null : new EmbeddedToolchain(this, assets, abi, sdk);
    }

    public File root() { return root; }

    public boolean bundled(String id) { return embedded != null && embedded.supports(id); }
    public boolean hasBundledAssets() { return embedded != null; }

    public File prepareBundled(ToolchainInstaller.Cancellation cancellation) throws Exception {
        if (embedded == null) throw new IllegalArgumentException("当前构建没有内置工具资源。");
        return embedded.prepare(cancellation);
    }

    public synchronized JSONObject configuration(String id) throws Exception {
        ToolCatalog.get(id);
        JSONObject tools = load().optJSONObject("tools");
        JSONObject value = tools == null ? null : tools.optJSONObject(id);
        return value == null ? new JSONObject() : new JSONObject(value.toString());
    }

    public JSONObject configure(String id, String path, String runtime) throws Exception {
        synchronized (toolLock(id)) { return configureLocked(id, path, runtime); }
    }

    private JSONObject configureLocked(String id, String path, String runtime) throws Exception {
        ToolCatalog.Entry entry = ToolCatalog.get(id);
        File executable = absolute(path);
        String name = new File(path).getName();
        boolean jar = "apktool".equals(id) && name.endsWith(".jar");
        boolean known = false;
        for (String alias : entry.aliases) if (name.equals(alias)) known = true;
        if (!known && !jar) throw new IllegalArgumentException("请选择 " + id + " 对应的可执行文件，不能绑定任意 shell。允许名称：" + names(entry.aliases));
        JSONObject value = new JSONObject().put("path", executable.getPath()).put("origin", "configured");
        if (jar) {
            if (runtime != null && runtime.length() > 0) {
                File java = absolute(runtime);
                if (!"java".equals(java.getName())) throw new IllegalArgumentException("Apktool JAR 的运行时必须是设备上的 java 可执行文件。");
                value.put("runtime", java.getPath());
            }
        } else if (runtime != null && runtime.length() > 0) {
            throw new IllegalArgumentException("此工具请绑定完整可执行入口，不要附加未经验证的运行脚本。");
        }
        put(id, value);
        return configuration(id);
    }

    public void clear(String id) throws Exception {
        synchronized (toolLock(id)) {
            synchronized (this) {
                ToolCatalog.get(id);
                JSONObject data = load(), tools = data.optJSONObject("tools");
                if (tools != null) tools.remove(id);
                save(data);
            }
        }
    }

    public JSONArray configureBinutilsDirectory(String path) throws Exception {
        return configureBinutilsDirectory(path, null);
    }

    JSONArray configureBinutilsDirectory(String path, ToolchainInstaller.Cancellation cancellation) throws Exception {
        File directory = absolute(path);
        JSONArray configured = new JSONArray();
        JSONArray catalog = ToolCatalog.list();
        for (int i = 0; i < catalog.length(); i++) {
            String id = catalog.getJSONObject(i).getString("id");
            ToolCatalog.Entry entry = ToolCatalog.get(id);
            if (!"binutils".equals(entry.group)) continue;
            for (String alias : entry.aliases) {
                File candidate = new File(directory, alias);
                if (candidate.isFile()) {
                    synchronized (toolLock(id)) {
                        if (cancellation != null) cancellation.check();
                        configure(id, candidate.getPath(), null);
                    }
                    configured.put(id); break;
                }
            }
        }
        return configured;
    }

    public Launcher launcher(String id) throws Exception {
        return launcher(id, new ToolchainInstaller.Cancellation() {
            public void check() throws InterruptedException {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("工具准备已取消。");
            }
        });
    }

    Launcher launcher(String id, ToolchainInstaller.Cancellation cancellation) throws Exception {
        JSONObject config = configuration(id);
        if (bundled(id) && (!"bundled".equals(config.optString("origin", "")) || !embedded.isPrepared())) {
            prepareBundled(cancellation); config = configuration(id);
        }
        String path = config.optString("path", "");
        if (path.length() == 0) return null;
        if (path.startsWith(root.getPath() + File.separator)) managed(path);
        File file = absolute(path);
        String runtime = config.optString("runtime", "");
        Launcher result;
        if ("bundled".equals(config.optString("origin", ""))) {
            result = new Launcher(id, file.getPath());
            JSONArray prefix = config.optJSONArray("prefix");
            if (prefix != null) for (int i = 0; i < prefix.length(); i++) result.prefix.add(prefix.getString(i));
            JSONObject environment = config.optJSONObject("environment");
            if (environment != null) {
                java.util.Iterator<String> keys = environment.keys();
                while (keys.hasNext()) { String key = keys.next(); result.environment.put(key, environment.getString(key)); }
            }
            String companion = config.optString("companion", "");
            if (companion.length() > 0) result.companion = managed(companion).getPath();
            String aapt2 = config.optString("aapt2", "");
            if (aapt2.length() > 0) result.aapt2 = managed(aapt2).getPath();
        } else if ("apktool".equals(id) && file.getName().endsWith(".jar")) {
            if (runtime.length() == 0) return null;
            result = new Launcher(id, absolute(runtime).getPath());
            result.prefix.add("-jar"); result.prefix.add(file.getPath());
        } else {
            result = new Launcher(id, file.getPath());
        }
        String nativePrefix = config.optString("native_prefix", "");
        if (nativePrefix.length() > 0) {
            File prefix = managed(nativePrefix);
            result.environment.put("R2_PREFIX", prefix.getPath());
            result.environment.put("LD_LIBRARY_PATH", new File(prefix, "lib").getPath());
        }
        return result;
    }

    synchronized void bundledInstalled(File common, File nativeTools, JSONObject manifest, String abi) throws Exception {
        JSONObject data = load(), tools = data.optJSONObject("tools");
        if (tools == null) { tools = new JSONObject(); data.put("tools", tools); }
        File usr = new File(nativeTools, "usr"), lib = new File(usr, "lib");
        JSONArray catalog = ToolCatalog.list();
        for (int i = 0; i < catalog.length(); i++) {
            String id = catalog.getJSONObject(i).getString("id");
            JSONObject config = new JSONObject().put("origin", "bundled").put("abi", abi)
                    .put("version", manifest.getString("version"));
            JSONObject env = new JSONObject().put("LD_LIBRARY_PATH", lib.getPath());
            JSONArray prefix = new JSONArray();
            String path;
            if ("apktool".equals(id)) {
                path = "/system/bin/app_process";
                env.put("CLASSPATH", new File(common, "apktool/apktool-dex.jar").getPath());
                prefix.put("-Dsun.arch.data.model=" + ("arm64-v8a".equals(abi) ? "64" : "32"))
                        .put("/system/bin").put("brut.apktool.Main");
                config.put("aapt2", new File(usr, "bin/aapt2").getPath());
            } else if ("radare2".equals(id) || "rabin2".equals(id)) {
                File r2 = new File(nativeTools, "radare2");
                path = new File(r2, "bin/" + id).getPath();
                env.put("R2_PREFIX", r2.getPath()).put("LD_LIBRARY_PATH", new File(r2, "lib").getPath() + ":" + lib.getPath());
            } else if ("objection".equals(id)) {
                path = new File(usr, "bin/python3").getPath();
                env.put("PYTHONHOME", usr.getPath()).put("PYTHONPATH", new File(common, "python-site").getPath())
                        .put("SSL_CERT_FILE", new File(usr, "etc/tls/cert.pem").getPath());
                prefix.put("-c").put("import os,sys,time,json,frida\nfrom datetime import datetime\n"
                        + "cache=os.path.join(os.path.expanduser('~'),'.objection')\nos.makedirs(cache,exist_ok=True)\n"
                        + "with open(os.path.join(cache,'version_info'),'w') as cached:\n"
                        + " json.dump({'remote_version':'1.12.5','last_check':datetime.now().strftime('%d%m%y %H:%M:%S')},cached)\n"
                        + "from objection.console.cli import cli\n"
                        + "port=os.environ.get('BACKCAST_FRIDA_PORT','27043')\n"
                        + "if not any(x in sys.argv[1:] for x in ('--help','--version','version')):\n"
                        + " for attempt in range(30):\n"
                        + "  os.kill(int(os.environ['BACKCAST_FRIDA_PID']),0)\n"
                        + "  try:\n   frida.get_device_manager().add_remote_device('127.0.0.1:'+port).enumerate_processes(); break\n"
                        + "  except (frida.TransportError,frida.ServerNotRunningError):\n   time.sleep(0.1)\n"
                        + "cli(args=['--network','--host','127.0.0.1','--port',port]+sys.argv[1:],prog_name='objection')");
                config.put("companion", new File(usr, "bin/frida-server").getPath());
            } else {
                path = new File(usr, "bin/g" + id).getPath();
            }
            config.put("path", path).put("prefix", prefix).put("environment", env);
            tools.put(id, config);
        }
        save(data);
    }

    Object toolLock(String id) {
        ToolCatalog.get(id);
        return ToolPaths.lock(new File(root, "rabin2".equals(id) ? "radare2" : id));
    }

    void installed(ToolCatalog.Artifact artifact, File destination) throws Exception {
        synchronized (toolLock(artifact.id)) {
            synchronized (this) { installedLocked(artifact, destination); }
        }
    }

    private void installedLocked(ToolCatalog.Artifact artifact, File destination) throws Exception {
        JSONObject value = new JSONObject().put("origin", "official").put("version", artifact.version)
                .put("sha256", artifact.sha256).put("source", artifact.url).put("abi", artifact.abi);
        if ("apktool".equals(artifact.id)) {
            value.put("path", new File(destination, "apktool.jar").getPath());
            String runtime = configuration("apktool").optString("runtime", "");
            if (runtime.length() > 0) value.put("runtime", runtime);
            put("apktool", value);
        } else {
            value.put("path", new File(destination, "bin/radare2").getPath()).put("native_prefix", destination.getPath());
            JSONObject rabin = new JSONObject(value.toString()).put("path", new File(destination, "bin/rabin2").getPath());
            JSONObject data = load(), tools = data.optJSONObject("tools");
            if (tools == null) { tools = new JSONObject(); data.put("tools", tools); }
            tools.put("radare2", value); tools.put("rabin2", rabin); save(data);
        }
    }

    File managed(String path) throws Exception {
        File file = new File(path).getAbsoluteFile();
        if (!file.getPath().equals(file.getCanonicalPath()) || !within(root, file)) {
            throw new IllegalArgumentException("工具目录路径被链接替换或超出 App 私有目录。");
        }
        if (!root.getPath().equals(root.getCanonicalPath())) throw new IllegalArgumentException("工具目录被链接替换。");
        return file;
    }

    static File absolute(String path) throws Exception {
        if (path == null || path.length() == 0 || path.indexOf('\0') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("工具路径不合法。");
        }
        File file = new File(path);
        if (!file.isAbsolute()) throw new IllegalArgumentException("工具路径必须是绝对路径。");
        return file.getCanonicalFile();
    }

    private static String names(String[] names) {
        StringBuilder result = new StringBuilder();
        for (String name : names) { if (result.length() > 0) result.append(", "); result.append(name); }
        return result.toString();
    }

    private synchronized void put(String id, JSONObject value) throws Exception {
        JSONObject data = load(), tools = data.optJSONObject("tools");
        if (tools == null) { tools = new JSONObject(); data.put("tools", tools); }
        tools.put(id, value); save(data);
    }

    private JSONObject load() throws Exception {
        managed(registry.getPath());
        if (!registry.exists()) return new JSONObject().put("tools", new JSONObject());
        if (registry.length() > 128 * 1024) throw new IllegalArgumentException("工具配置文件过大。");
        FileInputStream input = new FileInputStream(registry);
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int read;
            while ((read = input.read(buffer)) >= 0) {
                bytes.write(buffer, 0, read);
                if (bytes.size() > 128 * 1024) throw new IllegalArgumentException("工具配置文件过大。");
            }
            return new JSONObject(new String(bytes.toByteArray(), "UTF-8"));
        } finally { input.close(); }
    }

    private void save(JSONObject value) throws Exception {
        managed(root.getPath());
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建 App 私有工具目录。");
        File draft = managed(new File(root, ".registry-" + UUID.randomUUID() + ".tmp").getPath());
        try {
            FileOutputStream output = new FileOutputStream(draft);
            try { output.write(value.toString().getBytes("UTF-8")); output.getFD().sync(); }
            finally { output.close(); }
            managed(registry.getPath());
            if (!draft.renameTo(registry)) throw new IOException("无法保存工具配置。");
        } finally { draft.delete(); }
    }

    static boolean within(File root, File file) {
        return file.getPath().equals(root.getPath()) || file.getPath().startsWith(root.getPath() + File.separator);
    }
}
