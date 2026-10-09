package com.mkei.backcast.tool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
    private final ToolchainDownloader downloader;
    private final ArtRuntimeLauncher.Probe artRuntime;
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> OPERATIONS =
            new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private final ReentrantReadWriteLock operations;

    public static final class Use {
        private final ReentrantReadWriteLock operations;
        private boolean closed;
        private Use(ReentrantReadWriteLock operations) { this.operations = operations; }
        public void close() { if (!closed) { closed = true; operations.readLock().unlock(); } }
    }

    public ToolchainStore(File directory, EmbeddedToolchain.Assets assets, String abi, int sdk) {
        this(directory, assets, abi, sdk, ArtRuntimeLauncher.DEVICE);
    }

    ToolchainStore(File directory, EmbeddedToolchain.Assets assets, String abi, int sdk, ArtRuntimeLauncher.Probe artRuntime) {
        this(directory, assets, abi, sdk, artRuntime, null);
    }

    ToolchainStore(File directory, EmbeddedToolchain.Assets assets, String abi, int sdk, ArtRuntimeLauncher.Probe artRuntime,
            ToolchainDownloader.Transport transport) {
        if (directory == null) throw new IllegalArgumentException("工具安装需要 App 私有路径。");
        if (artRuntime == null) throw new IllegalArgumentException("ART 入口探测不能为空。");
        this.artRuntime = artRuntime;
        try { root = directory.getCanonicalFile(); }
        catch (IOException error) { throw new IllegalArgumentException("无法确认工具目录。", error); }
        registry = new File(root, "registry.json");
        downloader = assets == null ? null : transport == null ? new ToolchainDownloader() : new ToolchainDownloader(transport);
        embedded = assets == null ? null : new EmbeddedToolchain(this, assets, abi, sdk, downloader);
        ReentrantReadWriteLock created = new ReentrantReadWriteLock(true);
        ReentrantReadWriteLock shared = OPERATIONS.putIfAbsent(root.getPath(), created);
        operations = shared == null ? created : shared;
    }

    public File root() { return root; }

    public boolean bundled(String id) { return embedded != null && embedded.supports(id); }
    public boolean hasPackageManifest() { return embedded != null; }
    public void cancelDownloads(ToolchainInstaller.Cancellation owner) { if (downloader != null) downloader.cancel(owner); }

    public File prepareBundled(ToolchainInstaller.Cancellation cancellation) throws Exception {
        return prepareBundled(cancellation, null);
    }

    public File prepareBundled(ToolchainInstaller.Cancellation cancellation, EmbeddedToolchain.ProgressListener listener) throws Exception {
        Use use = beginUse(cancellation);
        try {
            if (embedded == null) throw new IllegalArgumentException("当前构建没有工具包清单。");
            if (bundledRemoved()) throw new IllegalStateException("工具包已删除，请在工具配置中重新安装。");
            return embedded.prepare(cancellation, listener, false);
        } finally { use.close(); }
    }

    public Use beginUse(ToolchainInstaller.Cancellation cancellation) throws Exception {
        while (true) {
            cancellation.check();
            if (operations.readLock().tryLock(100, TimeUnit.MILLISECONDS)) {
                try { cancellation.check(); return new Use(operations); }
                catch (Exception cancelled) { operations.readLock().unlock(); throw cancelled; }
            }
        }
    }

    public synchronized boolean bundledRemoved() throws Exception { return load().optBoolean("bundled_removed", false); }

    public JSONObject packageStatus() throws Exception {
        if (embedded == null) return new JSONObject().put("state", "unconfigured").put("installed", false);
        return embedded.packageStatus().put("busy", operations.getReadLockCount() > 0 || operations.isWriteLocked())
                .put("can_remove", !releasedBundles().isEmpty() || !downloadFiles().isEmpty());
    }

    public JSONObject installBundled(final ToolchainInstaller.Cancellation cancellation, final EmbeddedToolchain.ProgressListener listener, boolean allowRootCleanup) throws Exception {
        cancellation.check();
        if (!operations.writeLock().tryLock()) throw new IllegalStateException("工具正在执行，暂时不能安装或删除工具包。");
        try {
            if (embedded == null) throw new IllegalArgumentException("当前构建没有工具包清单。");
            boolean wasRemoved = bundledRemoved();
            if (wasRemoved) {
                embedded.resetPrepared();
                // A cancelled deletion may leave only part of a verified
                // directory; never treat that receipt as a complete install.
                for (File file : releasedBundles()) { cancellation.check(); removeManaged(file, cancellation, null, allowRootCleanup); }
            }
            synchronized (this) { JSONObject data = load(); data.put("bundled_removed", false); save(data); }
            try {
                final EmbeddedToolchain.Progress[] complete = new EmbeddedToolchain.Progress[1];
                embedded.prepare(cancellation, listener == null ? null : new EmbeddedToolchain.ProgressListener() {
                    public void onProgress(EmbeddedToolchain.Progress progress) {
                        if ("complete".equals(progress.stage)) complete[0] = progress;
                        else listener.onProgress(progress);
                    }
                }, true);
                JSONObject status = packageStatus();
                cancellation.check();
                if (listener != null && complete[0] != null) listener.onProgress(complete[0]);
                return status;
            }
            catch (Exception failure) {
                synchronized (this) { JSONObject data = load(); data.put("bundled_removed", wasRemoved); save(data); }
                throw failure;
            }
        } finally { operations.writeLock().unlock(); }
    }

    public JSONObject removeBundled(ToolchainInstaller.Cancellation cancellation, EmbeddedToolchain.ProgressListener listener, boolean allowRootCleanup) throws Exception {
        cancellation.check();
        if (!operations.writeLock().tryLock()) throw new IllegalStateException("工具正在执行，暂时不能安装或删除工具包。");
        try {
            if (embedded == null) throw new IllegalArgumentException("当前构建没有工具包清单。");
            List<File> removable = releasedBundles();
            List<File> downloads = downloadFiles();
            RemovalProgress progress = new RemovalProgress(listener);
            for (File file : removable) progress.total += countManaged(file, cancellation, allowRootCleanup);
            for (File file : downloads) progress.total += countManaged(file, cancellation, allowRootCleanup);
            File downloadDirectory = managed(new File(root, ".downloads").getPath());
            if (downloadDirectory.isDirectory() && downloadDirectory.list().length == downloads.size()) progress.total++;
            cancellation.check();
            // Disable implicit installation before deletion, including interrupted deletion.
            synchronized (this) {
                JSONObject data = load(), tools = data.optJSONObject("tools");
                if (tools != null) {
                    JSONArray ids = tools.names();
                    if (ids != null) for (int i = 0; i < ids.length(); i++) {
                        String id = ids.getString(i); JSONObject config = tools.optJSONObject(id);
                        if (config != null && "bundled".equals(config.optString("origin"))) tools.remove(id);
                    }
                }
                data.put("bundled_removed", true); save(data);
            }
            embedded.resetPrepared();
            progress.emit(true);
            for (File file : removable) { cancellation.check(); removeManaged(file, cancellation, progress, allowRootCleanup); }
            for (File file : downloads) { cancellation.check(); removeManaged(file, cancellation, progress, allowRootCleanup); }
            if (downloadDirectory.isDirectory() && downloadDirectory.list().length == 0) {
                cancellation.check();
                if (!downloadDirectory.delete()) throw new IOException("无法删除工具包下载缓存目录。");
                progress.advance(1);
            }
            cancellation.check();
            JSONObject status = packageStatus();
            if (status.optBoolean("installed") || status.optLong("installed_bytes") != 0 || status.optBoolean("can_remove"))
                throw new IOException("工具包仍有文件未清理，删除尚未完成。");
            progress.finish();
            return status;
        } finally { operations.writeLock().unlock(); }
    }

    private List<File> releasedBundles() throws Exception {
        managed(root.getPath());
        File[] files = root.listFiles();
        List<File> removable = new ArrayList<File>();
        if (files != null) for (File file : files) {
            if (!file.getName().matches("builtin-(?:common|arm64-v8a|armeabi-v7a)-[A-Za-z0-9._-]+-[0-9a-f]{16}")) continue;
            managed(file.getPath());
            File receipt = managed(new File(file, ".verified-sha256").getPath());
            String digest = receipt.isFile() ? new String(ToolPaths.readBytes(receipt, 128, false), "UTF-8") : "";
            if (!digest.matches("[0-9a-f]{64}") || !file.getName().endsWith("-" + digest.substring(0, 16))) {
                throw new IOException("私有工具包缺少校验记录，拒绝删除：" + file.getName());
            }
            removable.add(file);
        }
        return removable;
    }

    private void removeManaged(File file, ToolchainInstaller.Cancellation cancellation, RemovalProgress progress, boolean allowRootCleanup) throws Exception {
        cancellation.check(); managed(file.getPath());
        if (file.isDirectory()) makeRemovableDirectory(file, cancellation, allowRootCleanup);
        File[] children = file.listFiles();
        if (children != null) {
            File receipt = null;
            for (File child : children) {
                if (".verified-sha256".equals(child.getName())) receipt = child;
                else removeManaged(child, cancellation, progress, allowRootCleanup);
            }
            // Keep ownership evidence until the directory is empty so a
            // cancelled deletion can be resumed by the next remove request.
            if (receipt != null) {
                cancellation.check();
                managed(receipt.getPath());
                byte[] ownership = ToolPaths.readBytes(receipt, 128, false);
                if (!receipt.delete()) throw new IOException("无法删除私有工具校验记录。");
                // Keep receipt and its directory as one cancellation unit. If
                // rmdir fails, restore ownership for a later cleanup attempt.
                if (!file.delete()) {
                    ToolPaths.writeBytes(receipt, ownership, false);
                    throw new IOException("无法删除私有工具目录：" + file.getName());
                }
                if (progress != null) progress.advance(2);
                return;
            }
        }
        if (file.exists() && !file.delete()) throw new IOException("无法删除私有工具文件：" + file.getName());
        if (progress != null) progress.advance(1);
    }

    private List<File> downloadFiles() throws Exception {
        File directory = managed(new File(root, ".downloads").getPath());
        File[] files = directory.listFiles();
        List<File> downloads = new ArrayList<File>();
        if (files == null) {
            if (directory.exists()) throw new IOException("无法读取工具包下载缓存。");
            return downloads;
        }
        for (File file : files) {
            if (!file.getName().matches("[0-9a-f]{64}\\.(?:part|archive)")) continue;
            managed(file.getPath());
            if (!file.isFile()) throw new IOException("工具包下载缓存不是普通文件。");
            downloads.add(file);
        }
        return downloads;
    }

    long payloadBytes(File file) throws Exception {
        managed(file.getPath());
        if (file.isFile()) return ".verified-sha256".equals(file.getName()) ? 0 : file.length();
        File[] children = file.listFiles();
        if (children == null) return -1;
        long bytes = 0;
        for (File child : children) {
            long size = payloadBytes(child);
            if (size < 0) return -1;
            bytes += size;
        }
        return bytes;
    }

    private long countManaged(File file, ToolchainInstaller.Cancellation cancellation, boolean allowRootCleanup) throws Exception {
        cancellation.check(); managed(file.getPath());
        if (file.isFile()) return 1;
        makeRemovableDirectory(file, cancellation, allowRootCleanup);
        File[] children = file.listFiles();
        if (children == null) throw new IOException("无法读取待删除工具目录。");
        long count = 1;
        for (File child : children) count += countManaged(child, cancellation, allowRootCleanup);
        return count;
    }

    private void makeRemovableDirectory(File directory, ToolchainInstaller.Cancellation cancellation, boolean allowRootCleanup) throws Exception {
        cancellation.check(); managed(directory.getPath());
        if (directory.canRead() && directory.canWrite() && directory.canExecute()) return;
        // TAR extraction creates app-owned directories. A rooted tool may add
        // its own cache directories later; repair only this verified payload.
        directory.setReadable(true, true); directory.setWritable(true, true); directory.setExecutable(true, true);
        if (directory.canRead() && directory.canWrite() && directory.canExecute()) return;
        File owner = directory;
        while (owner.getParentFile() != null && !owner.getParentFile().equals(root)) owner = owner.getParentFile();
        if (owner.getParentFile() == null || !owner.getParentFile().equals(root)
                || !releasedBundles().contains(owner)) throw new IOException("待删除目录不属于已校验工具包。");
        if (!allowRootCleanup || !RootShell.available(cancellation)) throw new IOException("工具目录由 root 创建，无法清理；请启用 root 后重试删除。");
        String rootPath = RootShell.quote(root.getPath()), path = RootShell.quote(directory.getPath());
        String guard = "[ ! -L " + rootPath + " ] && [ \"$(readlink -f " + rootPath + ")\" = " + rootPath + " ]"
                + " && [ ! -L " + path + " ] && [ \"$(readlink -f " + path + ")\" = " + path + " ] || exit 1; ";
        RootShell.Out repaired = RootShell.exec(guard + "owner=$(stat -c '%u:%g' " + rootPath + ") || exit 1; "
                + "case \"$owner\" in *[!0-9:]*|:*|*:) exit 1;; esac; "
                + "chown \"$owner\" " + path + " && chmod u+rwx " + path, null, 512, 10000, cancellation);
        managed(directory.getPath());
        if (repaired.exit != 0 || !directory.canRead() || !directory.canWrite() || !directory.canExecute())
            throw new IOException("无法恢复已校验工具目录的清理权限。");
    }

    private static final class RemovalProgress {
        final EmbeddedToolchain.ProgressListener listener;
        long completed, total, notified;
        RemovalProgress(EmbeddedToolchain.ProgressListener listener) { this.listener = listener; }
        void advance(long count) { completed += count; emit(completed == count || completed == total); }
        void emit(boolean force) {
            if (listener == null) return;
            long now = System.nanoTime();
            if (!force && now - notified < 100000000L) return;
            notified = now;
            listener.onProgress(new EmbeddedToolchain.Progress("removing", "", completed, total));
        }
        void finish() {
            if (listener != null) listener.onProgress(new EmbeddedToolchain.Progress("complete", "", completed, total));
        }
    }

    public synchronized JSONObject configuration(String id) throws Exception {
        ToolCatalog.get(id);
        JSONObject tools = load().optJSONObject("tools");
        JSONObject value = tools == null ? null : tools.optJSONObject(id);
        return value == null ? new JSONObject() : new JSONObject(value.toString());
    }

    Launcher launcher(String id, ToolchainInstaller.Cancellation cancellation) throws Exception {
        JSONObject config = configuration(id);
        if (bundled(id) && (!"bundled".equals(config.optString("origin", "")) || !embedded.isPrepared())) {
            if (bundledRemoved()) throw new IllegalStateException("工具包已删除，请在工具配置中重新安装。");
            if (!embedded.packageStatus().optBoolean("installed")) return null;
            // Re-register legacy installed paths offline. Probes and model calls never fetch releases.
            prepareBundled(cancellation); config = configuration(id);
        }
        if ("apktool".equals(id) && "bundled".equals(config.optString("origin", ""))) {
            config = refreshBundledArt(config);
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

    private JSONObject refreshBundledArt(JSONObject config) throws Exception {
        ArtRuntimeLauncher.Selection runtime = ArtRuntimeLauncher.select(config.optString("abi"), artRuntime);
        String classpath = "";
        JSONArray oldPrefix = config.optJSONArray("prefix");
        if (oldPrefix != null) for (int i = 0; i + 1 < oldPrefix.length(); i++) {
            if ("-cp".equals(oldPrefix.optString(i))) { classpath = oldPrefix.getString(i + 1); break; }
        }
        JSONObject environment = config.optJSONObject("environment");
        if (environment == null) environment = new JSONObject();
        if (classpath.length() == 0) classpath = environment.optString("CLASSPATH");
        if (classpath.length() == 0) throw new IOException("Apktool 的 DEX 文件登记缺失，请在工具配置中重新安装。");
        File jar = managed(classpath);
        if (!jar.isFile() || !"apktool-dex.jar".equals(jar.getName())) {
            throw new IOException("Apktool 的 DEX 文件缺失，请在工具配置中重新安装。");
        }
        environment.remove("CLASSPATH");
        JSONArray prefix = new JSONArray().put("-Dsun.arch.data.model=" + runtime.bits)
                .put("-cp").put(jar.getPath()).put("brut.apktool.Main");
        JSONObject refreshed = new JSONObject(config.toString()).put("path", runtime.path)
                .put("runtime_family", "android-art").put("prefix", prefix).put("environment", environment);
        // A running App can outlive an OS/module update; recheck each invocation,
        // and replace previously persisted dalvikvm/app_process launchers too.
        if (!refreshed.toString().equals(config.toString())) put("apktool", refreshed);
        return refreshed;
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
                // Register the payload independently of the device runtime.
                // launcher() resolves an executable ART path at invocation time.
                path = "";
                config.put("runtime_family", "android-art");
                prefix.put("-Dsun.arch.data.model=" + ("arm64-v8a".equals(abi) ? "64" : "32"))
                        .put("-cp").put(new File(common, "apktool/apktool-dex.jar").getPath())
                        .put("brut.apktool.Main");
                config.put("aapt2", new File(usr, "bin/aapt2").getPath());
            } else if ("radare2".equals(id) || "rabin2".equals(id)) {
                File r2 = new File(nativeTools, "radare2");
                path = new File(r2, "bin/" + id).getPath();
                env.put("R2_PREFIX", r2.getPath()).put("LD_LIBRARY_PATH", new File(r2, "lib").getPath() + ":" + lib.getPath());
            } else if ("objection".equals(id)) {
                path = new File(usr, "bin/python3").getPath();
                config.put("java_bridge", manifest.optString("java_bridge", ""))
                        .put("art_mode", manifest.optString("objection_art_mode", ""))
                        .put("art_patch_source", manifest.optString("objection_art_patch_source", ""));
                env.put("PYTHONHOME", usr.getPath()).put("PYTHONPATH", new File(common, "python-site").getPath())
                        .put("SSL_CERT_FILE", new File(usr, "etc/tls/cert.pem").getPath());
                prefix.put("-c").put(ObjectionBootstrap.program());
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
