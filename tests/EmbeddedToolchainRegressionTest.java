package com.mkei.backcast.tool;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Audits the actual release payloads and their installation through the real downloader. */
public final class EmbeddedToolchainRegressionTest {
    private static File repo, root, assets, releaseFiles, project, privateFiles;
    private static final java.util.concurrent.atomic.AtomicInteger releaseReads = new java.util.concurrent.atomic.AtomicInteger();
    private static ToolchainStore arm64, arm;
    private static TemporaryWorkspace temporary;
    private static final ToolchainInstaller.Cancellation LIVE = new ToolchainInstaller.Cancellation() {
        public void check() { }
    };
    private static final ArtRuntimeLauncher.Probe ANDROID_RUNTIME = new ArtRuntimeLauncher.Probe() {
        public int executableBits(String path) {
            return "/system/bin/dalvikvm64".equals(path) ? 64 : "/system/bin/dalvikvm32".equals(path) ? 32 : 0;
        }
    };
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
    private static byte[] bytes(File file) throws Exception { return Files.readAllBytes(file.toPath()); }
    private static EmbeddedToolchain.Assets packaged() {
        return new EmbeddedToolchain.Assets() {
            public InputStream open(String name) throws Exception {
                check("toolchain/manifest.json".equals(name), "Installation attempted to read an archive from APK assets");
                return new FileInputStream(new File(assets, name));
            }
        };
    }
    private static ToolchainDownloader.Transport downloads() throws Exception {
        JSONObject manifest = new JSONObject(new String(bytes(new File(assets, "toolchain/manifest.json")), "UTF-8"));
        return ToolchainFixtures.transport(new EmbeddedToolchain.Assets() { public InputStream open(String name) throws Exception {
            check(new File(releaseFiles, new File(name).getName()).isFile(), "Release fixture missing; run python3 -B tools/fetch_toolchain_test_fixtures.py explicitly");
            releaseReads.incrementAndGet(); return new FileInputStream(new File(releaseFiles, new File(name).getName()));
        } }, manifest);
    }
    private static ToolkitTool toolkit(ToolchainStore store) {
        return new ToolkitTool(new ShellTool(false, project.getPath(), temporary), store, project.getPath(), temporary, "arm64-v8a");
    }
    private static File apktoolJar(ToolchainStore.Launcher launcher) {
        int classpath = launcher.prefix.indexOf("-cp");
        check(classpath >= 0 && classpath + 1 < launcher.prefix.size(), "Apktool has no private dex classpath");
        return new File(launcher.prefix.get(classpath + 1));
    }

    private static void releaseArtifactsMatchManifestAndAreAbsentFromApk() throws Exception {
        JSONObject manifest = new JSONObject(new String(bytes(new File(assets, "toolchain/manifest.json")), "UTF-8"));
        check("2.9.3".equals(manifest.getString("apktool")) && "6.2.2".equals(manifest.getString("radare2"))
                && "1.12.5".equals(manifest.getString("objection")), "Pinned upstream versions were lost");
        JSONArray entries = manifest.getJSONArray("artifacts");
        check(entries.length() == 3, "Missing common or Android ABI payload");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i); File file = new File(releaseFiles, entry.getString("file"));
            check(!new File(assets, "toolchain/" + entry.getString("file")).exists() && !entry.has("asset") && !entry.has("tar_asset"), "Large release remained in APK assets or advertised an offline fallback");
            check(entry.getString("url").equals("https://github.com/Hollis-X/backcast/releases/download/toolchain-" + manifest.getString("version") + "/" + file.getName()), "Release URL is not pinned");
            check(file.length() == entry.getLong("bytes") && file.length() < 100L * 1024 * 1024, "Payload size mismatch");
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); InputStream stream = new FileInputStream(file);
            try { byte[] buffer = new byte[16384]; int read; while ((read = stream.read(buffer)) >= 0) digest.update(buffer, 0, read); }
            finally { stream.close(); }
            check(entry.getString("sha256").equals(ToolchainInstaller.hex(digest.digest())), "Payload digest mismatch");
            MessageDigest tarDigest = MessageDigest.getInstance("SHA-256"); long tarBytes = 0;
            stream = new GZIPInputStream(new FileInputStream(file));
            try { byte[] buffer = new byte[16384]; int read; while ((read = stream.read(buffer)) >= 0) { tarDigest.update(buffer, 0, read); tarBytes += read; } }
            finally { stream.close(); }
            check(entry.getString("tar_sha256").equals(ToolchainInstaller.hex(tarDigest.digest())) && tarBytes == entry.getLong("tar_bytes"), "Uncompressed TAR digest or size mismatch");
            byte[] all = bytes(file); JSONObject refreshed = ToolchainFixtures.pin(new JSONObject(entry.toString()), manifest.getString("version"), all);
            check(entry.getInt("prefix_bytes") == refreshed.getInt("prefix_bytes") && entry.getString("prefix_sha256").equals(refreshed.getString("prefix_sha256"))
                    && entry.getInt("chunk_bytes") == 4 * 1024 * 1024 && entry.getJSONArray("chunk_sha256").toString().equals(refreshed.getJSONArray("chunk_sha256").toString()), "Pinned prefix/block digests differ from released bytes");
        }
        check(manifest.getJSONArray("sources").length() > 60, "Native and Python provenance is incomplete");
        check(new File(assets, "toolchain/licenses/Objection-GPL-3.0.txt").length() > 30000, "Objection license was omitted");
        check("2.1.0".equals(manifest.getString("pngj")), "Pure Java PNG codec version was omitted");
        boolean pngjSource = false; JSONArray sources = manifest.getJSONArray("sources");
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.getJSONObject(i);
            if ("ar.com.hjg:pngj".equals(source.optString("package"))) {
                pngjSource = "https://repo.maven.apache.org/maven2/ar/com/hjg/pngj/2.1.0/pngj-2.1.0.jar".equals(source.getString("url"))
                        && "e6b762f15e4891178dddd74e4d57318f518ce278000129efa41f85410b132ccc".equals(source.getString("sha256"))
                        && "Apache-2.0".equals(source.getString("license"));
            }
        }
        check(pngjSource && new File(assets, "toolchain/licenses/PNGJ-LICENSE.txt").length() > 10000
                && new File(assets, "toolchain/licenses/PNGJ-NOTICE.txt").length() > 200, "PNGJ provenance or license notices are incomplete");
    }

    private static void onlyExplicitInstallDownloadsAndPreparesBothAbis() throws Exception {
        check(toolkit(arm64).listing().getJSONArray("tools").getJSONObject(0).getBoolean("bundled"), "Before first use bundle looks absent");
        check(!arm64.root().exists(), "Listing eagerly extracted the payload");
        int opened = releaseReads.get();
        check("not_installed".equals(toolkit(arm64).status("apktool").getString("state")), "Probe did not report an uninstalled package");
        check("not_installed".equals(new JSONObject(toolkit(arm64).run(new JSONObject().put("action", "diagnose").put("tool", "apktool"))).getString("state")), "Diagnose installed a package implicitly");
        check("not_installed".equals(new JSONObject(toolkit(arm64).run(new JSONObject().put("action", "run").put("tool", "apktool").put("arguments", new JSONArray().put("--version")))).getString("state")) && releaseReads.get() == opened, "Model invocation downloaded release files");
        arm64.installBundled(LIVE, null); arm.installBundled(LIVE, null);
        for (ToolchainStore preparedStore : new ToolchainStore[]{arm64, arm}) {
            List<File> packagedFiles = new ArrayList<File>(); collect(preparedStore.root(), packagedFiles);
            for (File file : packagedFiles) check(!file.getName().endsWith(".pyc") && !file.getPath().contains(File.separator + "__pycache__" + File.separator),
                    "APK included temporary host Python bytecode: " + file);
        }
        JSONArray catalog = ToolCatalog.list(); check(catalog.length() == 13, "Toolkit catalog changed unexpectedly");
        for (ToolchainStore store : new ToolchainStore[]{arm64, arm}) {
            for (int i = 0; i < catalog.length(); i++) {
                String id = catalog.getJSONObject(i).getString("id"); ToolchainStore.Launcher launcher = store.launcher(id, ToolchainFixtures.LIVE);
                check(launcher != null, "Missing offline launcher: " + id);
                if (!"apktool".equals(id)) check(new File(launcher.executable).isFile() && new File(launcher.executable).canExecute(), "Missing Android native entry: " + id);
                check("bundled".equals(store.configuration(id).getString("origin")), "Tool requires external configuration: " + id);
            }
            for (String child : store.root().list()) check(!child.startsWith(".embedded-"), "Successful preparation leaked staging data");
        }
    }

    private static void apktoolUsesDalvikAndBundledPureJavaPngInsteadOfDesktopOrNativeImageApis() throws Exception {
        ToolchainStore.Launcher launcher = arm64.launcher("apktool", ToolchainFixtures.LIVE);
        check("/system/bin/dalvikvm64".equals(launcher.executable) && launcher.prefix.contains("brut.apktool.Main")
                && !launcher.environment.has("CLASSPATH"), "Apktool was not launched with the independent Android runtime");
        File jar = apktoolJar(launcher);
        ZipFile zip = new ZipFile(jar);
        try {
            ByteArrayOutputStream dex = new ByteArrayOutputStream();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                InputStream stream = zip.getInputStream(entry);
                try { byte[] buffer = new byte[8192]; int read; while ((read = stream.read(buffer)) >= 0) dex.write(buffer, 0, read); }
                finally { stream.close(); }
            }
            String code = new String(dex.toByteArray(), "ISO-8859-1");
            check(code.startsWith("dex\n") && code.contains("Lar/com/hjg/pngj/PngReader;")
                    && code.contains("Lar/com/hjg/pngj/PngWriter;"), "Pure Java PNG codec was not actually merged into Android dex code");
            check(!code.contains("Ljava/awt/image/BufferedImage;") && !code.contains("Ljavax/imageio/ImageIO;")
                    && !code.contains("Landroid/graphics/Bitmap;") && !code.contains("Landroid/graphics/BitmapFactory;"), "Desktop or unregistered native image APIs remain in Apktool");
            check(code.contains("sun.arch.data.model") && code.contains("property") && code.contains("Lbrut/util/OSDetection;"), "Android JVM property compatibility patch is absent from the actual payload");
            check(zip.getEntry("brut/androlib/android-framework.jar") != null && zip.getEntry("prebuilt/linux/aapt") == null, "Android resources were omitted or desktop aapt remained");
        } finally { zip.close(); }
        check(new File(launcher.aapt2).isFile(), "Android build lacks aapt2");
    }

    private static void androidElfAbiAndDynamicDependenciesAreComplete() throws Exception {
        Set<String> system = new HashSet<String>();
        for (String lib : new String[]{"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so", "libstdc++.so"}) system.add(lib);
        for (ToolchainStore store : new ToolchainStore[]{arm64, arm}) {
            File nativeRoot = store.prepareBundled(LIVE); List<File> files = new ArrayList<File>(); collect(nativeRoot, files);
            int machine = store == arm64 ? 183 : 40; int examined = 0;
            for (File file : files) {
                InputStream input = new FileInputStream(file); byte[] header = new byte[20]; int got;
                try { got = input.read(header); } finally { input.close(); }
                if (got < 20 || header[0] != 127 || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') continue;
                check((header[18] & 255) + ((header[19] & 255) << 8) == machine, "Desktop or wrong ABI ELF in Android payload: " + file);
                Process readelf = new ProcessBuilder("/usr/bin/readelf", "-d", file.getPath()).redirectErrorStream(true).start();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); input = readelf.getInputStream();
                try { byte[] buffer = new byte[4096]; int read; while ((read = input.read(buffer)) >= 0) bytes.write(buffer, 0, read); }
                finally { input.close(); }
                check(readelf.waitFor() == 0, "ELF inspection failed: " + file);
                for (String line : new String(bytes.toByteArray(), "UTF-8").split("\n")) if (line.contains("(NEEDED)")) {
                    String lib = line.substring(line.indexOf('[') + 1, line.indexOf(']'));
                    check(system.contains(lib) || new File(nativeRoot, "usr/lib/" + lib).isFile()
                            || new File(nativeRoot, "radare2/lib/" + lib).isFile(), "Unbundled dynamic dependency " + lib + " for " + file);
                }
                examined++;
            }
            check(examined > 100, "Native dependency audit missed tools or Python extensions");
        }
    }

    private static void pythonAndObjectionArePrivateAndDoNotRequireTermux() throws Exception {
        ToolchainStore.Launcher objection = arm64.launcher("objection", ToolchainFixtures.LIVE);
        File home = new File(objection.environment.getString("PYTHONHOME"));
        check(home.getPath().startsWith(arm64.root().getPath()), "Python uses an external installation");
        String subprocess = new String(bytes(new File(home, "lib/python3.14/subprocess.py")), "UTF-8");
        check(subprocess.contains("/system/bin/sh") && !subprocess.contains("/data/data/com.termux/files/usr/bin/sh"), "Python shell execution still requires Termux");
        check(new File(home, "lib/python3.14/site-packages/frida/_frida.abi3.so").isFile() && new File(objection.companion).isFile(), "Objection lacks Android Frida client/server");
        File site = new File(objection.environment.getString("PYTHONPATH"));
        for (String module : new String[]{"objection", "click", "flask", "litecli", "requests", "pexpect", "markupsafe", "websockets"}) {
            check(new File(site, module).exists() || new File(site, module + ".py").isFile(), "Missing Objection Python dependency: " + module);
        }
        String bootstrap = objection.prefix.get(1);
        check(bootstrap.contains("version_info") && bootstrap.contains("BACKCAST_FRIDA_PORT") && bootstrap.contains("BACKCAST_FRIDA_PID"), "Objection startup is not offline or isolated per invocation");
        String agent = new String(bytes(new File(site, "objection/agent.js")), "UTF-8");
        int separator = agent.indexOf("\n✄\n");
        String declaration = agent.substring(agent.indexOf('\n') + 1, separator);
        int declaredBytes = Integer.parseInt(declaration.substring(0, declaration.indexOf(' ')));
        check(declaration.endsWith(" /src/index.js") && declaredBytes == agent.substring(separator + 3).getBytes("UTF-8").length,
                "Installed Frida bundle has stale module byte lengths");
        check(agent.startsWith("📦\n") && agent.contains("\n✄\n// Backcast:") && agent.contains("globalThis.FRIDA_JAVA_BRIDGE_DISABLE_JVMTI = true;")
                && agent.contains("function tryGetEnvJvmti(vm3, runtime4) {\n  let env3 = null;\n  if (globalThis.FRIDA_JAVA_BRIDGE_DISABLE_JVMTI === true)")
                && agent.contains("if (art_api.class_offset_copied_methods_offset != 0)"), "The actual extracted agent lacks the guarded ART/JNI fallback");
        check("jni-no-jvmti".equals(arm64.configuration("objection").getString("art_mode"))
                && "7.0.13-backcast.2".equals(arm64.configuration("objection").getString("java_bridge")), "The compatibility mode was not registered with the installed payload");
        check(new File(home, "share/LICENSES/GPL-3.0.txt").length() > 30000, "GNU license texts were omitted");
    }

    private static void unsupportedDevicesDoNotAttemptAssetReads() throws Exception {
        final int[] reads = new int[1];
        EmbeddedToolchain.Assets unavailable = new EmbeddedToolchain.Assets() { public InputStream open(String name) { reads[0]++; throw new AssertionError("Unsupported device read an asset"); } };
        for (ToolchainStore store : new ToolchainStore[]{new ToolchainStore(new File(root, "unsupported-x86"), unavailable, "x86_64", 30), new ToolchainStore(new File(root, "unsupported-api"), unavailable, "arm64-v8a", 25)}) {
            JSONObject list = toolkit(store).listing().getJSONArray("tools").getJSONObject(0);
            check(!list.getBoolean("bundled") && "unsupported".equals(list.getString("state")), "Unsupported device appeared downloadable");
            check("unsupported".equals(toolkit(store).status("apktool").getString("state")), "Unsupported probe requested user installation");
        }
        check(reads[0] == 0, "Unsupported device performed asset work");
    }

    private static void clearRestoresTheBundledLauncherAndCancelledPreparationLeavesNoStage() throws Exception {
        ToolchainFixtures.clear(arm64, "radare2"); check(arm64.launcher("radare2", ToolchainFixtures.LIVE) != null, "Missing registry entry permanently disabled the built-in launcher");
        ToolchainFixtures.configure(arm64, "apktool", new File(root, "apktool.jar").getPath(), "/usr/bin/java");
        check("/system/bin/dalvikvm64".equals(arm64.launcher("apktool", ToolchainFixtures.LIVE).executable)
                && "bundled".equals(arm64.configuration("apktool").getString("origin")), "Upgrade retained the old external JVM launcher");
        File registry = new File(arm64.root(), "registry.json");
        JSONObject old = new JSONObject(new String(bytes(registry), "UTF-8"));
        old.getJSONObject("tools").getJSONObject("apktool").put("path", "/system/bin/app_process")
                .put("prefix", new JSONArray().put("/").put("brut.apktool.Main"))
                .put("environment", new JSONObject().put("CLASSPATH", apktoolJar(arm64.launcher("apktool", ToolchainFixtures.LIVE)).getPath()));
        Files.write(registry.toPath(), old.toString().getBytes("UTF-8"));
        ToolchainStore migrated = new ToolchainStore(arm64.root(), packaged(), "arm64-v8a", 30, ANDROID_RUNTIME);
        check("/system/bin/dalvikvm64".equals(migrated.launcher("apktool", ToolchainFixtures.LIVE).executable)
                && !migrated.launcher("apktool", ToolchainFixtures.LIVE).environment.has("CLASSPATH") && apktoolJar(migrated.launcher("apktool", ToolchainFixtures.LIVE)).isFile(),
                "Upgrade retained a previously registered app_process launcher");
        final ToolchainStore cancelled = new ToolchainStore(new File(root, "cancelled"), packaged(), "arm64-v8a", 30, ANDROID_RUNTIME, downloads());
        final java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger(); boolean interrupted = false;
        try { cancelled.installBundled(new ToolchainInstaller.Cancellation() { public void check() throws Exception { if (checks.incrementAndGet() >= 10) throw new InterruptedException("fixture"); } }, null); }
        catch (InterruptedException expected) { interrupted = true; }
        check(interrupted && noPublishedFiles(cancelled), "Cancelled extraction published software"); checkNoStages(cancelled);
    }

    private static void onlyDirectBuiltInActionsAreAdvertisedToTheModel() throws Exception {
        JSONObject properties = toolkit(arm64).parameters().getJSONObject("properties");
        JSONArray actions = properties.getJSONObject("action").getJSONArray("enum");
        Set<String> allowed = new HashSet<String>();
        for (int i = 0; i < actions.length(); i++) allowed.add(actions.getString(i));
        check(allowed.size() == 5 && allowed.contains("list") && allowed.contains("status") && allowed.contains("diagnose")
                && allowed.contains("run") && allowed.contains("export") && !properties.has("path") && !properties.has("runtime"),
                "Model actions lost managed diagnostics or ask the user to download/configure tools");
        check(!toolkit(arm64).description().contains("需要设备 JVM"), "Obsolete desktop JVM instructions remain");
    }

    private static void sameVersionNewPayloadPreparesSeparatelyAndRetainsTheRunningVersion() throws Exception {
        final JSONObject updated = new JSONObject(new String(bytes(new File(assets, "toolchain/manifest.json")), "UTF-8"));
        JSONObject common = updated.getJSONArray("artifacts").getJSONObject(0);
        final byte[] nextCommon = java.util.Arrays.copyOf(bytes(new File(releaseFiles, common.getString("file"))), (int) common.getLong("bytes") + 1);
        common.put("bytes", nextCommon.length).put("sha256", ToolchainFixtures.sha(nextCommon));
        ToolchainFixtures.pin(common, updated.getString("version"), nextCommon);
        File oldJar = apktoolJar(arm64.launcher("apktool", ToolchainFixtures.LIVE));
        ToolchainStore upgraded = new ToolchainStore(arm64.root(), new EmbeddedToolchain.Assets() {
            public InputStream open(String name) throws Exception {
                if ("toolchain/manifest.json".equals(name)) return new ByteArrayInputStream(updated.toString().getBytes("UTF-8"));
                throw new AssertionError("APK archive access during upgrade");
            }
        }, "arm64-v8a", 30, ANDROID_RUNTIME, ToolchainFixtures.transport(new EmbeddedToolchain.Assets() {
            public InputStream open(String name) throws Exception {
                return "toolchain/common.tar.gz".equals(name) ? new ByteArrayInputStream(nextCommon) : new FileInputStream(new File(releaseFiles, new File(name).getName()));
            }
        }, updated));
        check(upgraded.launcher("apktool", LIVE) == null && oldJar.isFile(), "Upgrade silently downloaded new release bytes");
        upgraded.installBundled(LIVE, null);
        File newJar = apktoolJar(upgraded.launcher("apktool", ToolchainFixtures.LIVE));
        check(!oldJar.equals(newJar) && oldJar.isFile() && newJar.isFile(), "Payload update overwrote/deleted a potentially running tool or failed on reused release date");
    }

    private static void objectionProbeNeverStartsAServerAndSingleRunsCleanItUp() throws Exception {
        File marker = new File(temporary.directory(), "server-started");
        File companion = new File(temporary.directory(), "fixture-frida-server");
        Files.write(companion.toPath(), ("#!/bin/sh\nif [ \"$1\" = --version ]; then printf '17.2.14\\n'; exit 0; fi\nprintf '%s' \"$$\" > " + RootShell.quote(marker.getPath()) + "\nexec sleep 30\n").getBytes("UTF-8"));
        companion.setExecutable(true, true);
        ToolchainStore.Launcher fixture = new ToolchainStore.Launcher("objection", "/usr/bin/python3");
        fixture.prefix.add("-c"); fixture.prefix.add("import time; time.sleep(0.2); print('fixture completed')"); fixture.companion = companion.getPath();
        ShellTool shell = new ShellTool(false, project.getPath(), temporary);
        List<String> arguments = new ArrayList<String>(); arguments.add("version");
        check(shell.runProgram(fixture, arguments, true, 5, shell.cancellationEpoch()).startsWith("exit=0\n") && !marker.exists(), "Version probe started Frida server");
        arguments.clear(); arguments.add("run");
        check(shell.runProgram(fixture, arguments, true, 5, shell.cancellationEpoch()).startsWith("exit=0\n") && marker.exists(), "Single invocation did not start its companion");
        String pid = new String(bytes(marker), "UTF-8");
        File status = new File("/proc/" + pid + "/status");
        check(!status.exists() || new String(bytes(status), "UTF-8").contains("State:\tZ"), "Completed invocation left its Frida server alive");
        JSONObject bad = new JSONObject(toolkit(arm64).run(new JSONObject().put("action", "run").put("tool", "objection")
                .put("arguments", new JSONArray().put("start"))));
        check("error".equals(bad.optString("state")), "Interactive Objection session was allowed to hang the model's tool request");
    }

    private static final class ArchiveFixture {
        final byte[] tar, gzip;
        final JSONObject manifest;
        ArchiveFixture() throws Exception {
            byte[] header = new byte[512], value = "verified payload".getBytes("UTF-8");
            put(header, 0, "fixture.txt"); put(header, 100, "0000644"); put(header, 124, String.format("%011o", value.length));
            header[156] = '0'; put(header, 257, "ustar");
            for (int i = 148; i < 156; i++) header[i] = ' ';
            int sum = 0; for (byte b : header) sum += b & 255;
            put(header, 148, String.format("%06o", sum)); header[154] = 0; header[155] = ' ';
            ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write(header); output.write(value);
            output.write(new byte[512 - value.length]); output.write(new byte[1024]); tar = output.toByteArray();
            output = new ByteArrayOutputStream(); GZIPOutputStream zipped = new GZIPOutputStream(output); zipped.write(tar); zipped.close(); gzip = output.toByteArray();
            JSONObject artifact = ToolchainFixtures.pin(new JSONObject().put("file", "fixture.tar.gz")
                    .put("bytes", gzip.length).put("sha256", ToolchainInstaller.hex(MessageDigest.getInstance("SHA-256").digest(gzip)))
                    .put("tar_bytes", tar.length).put("tar_sha256", ToolchainInstaller.hex(MessageDigest.getInstance("SHA-256").digest(tar))), "fixture", gzip);
            manifest = new JSONObject().put("version", "fixture").put("artifacts", new JSONArray()
                    .put(new JSONObject(artifact.toString()).put("abi", "any")).put(new JSONObject(artifact.toString()).put("abi", "arm64-v8a")));
        }
        ToolchainStore store(String name, final byte[] payload, final JSONObject metadata) throws Exception {
            return new ToolchainStore(new File(root, name), new EmbeddedToolchain.Assets() {
                public InputStream open(String path) throws Exception {
                    if ("toolchain/manifest.json".equals(path)) return new ByteArrayInputStream(metadata.toString().getBytes("UTF-8"));
                    throw new AssertionError("Archive requested from APK assets");
                }
            }, "arm64-v8a", 30, ANDROID_RUNTIME, ToolchainFixtures.transport(new EmbeddedToolchain.Assets() {
                public InputStream open(String name) { return new ByteArrayInputStream(payload); }
            }, metadata));
        }
    }
    private static void put(byte[] output, int at, String value) throws Exception { byte[] bytes = value.getBytes("UTF-8"); System.arraycopy(bytes, 0, output, at, bytes.length); }
    private static void checkNoStages(ToolchainStore store) {
        String[] children = store.root().list();
        if (children != null) for (String name : children) check(!name.startsWith(".embedded-"), "Rejected resource left temporary extraction data");
    }
    private static boolean noPublishedFiles(ToolchainStore store) {
        String[] children = store.root().list();
        if (children != null) for (String child : children) if (child.startsWith("builtin-")) return false;
        return true;
    }

    private static void compressedReleaseInstallsWithoutApkArchiveReads() throws Exception {
        ArchiveFixture fixture = new ArchiveFixture(); ToolchainStore store = fixture.store("gzip-release", fixture.gzip, fixture.manifest);
        store.installBundled(LIVE, null); File directory = store.prepareBundled(LIVE);
        check("verified payload".equals(new String(bytes(new File(directory, "fixture.txt")), "UTF-8")), "Verified release file failed to install");
        checkNoStages(store);
    }

    private static void corruptedOrUndeclaredReleaseFilesNeverPublish() throws Exception {
        ArchiveFixture fixture = new ArchiveFixture(); byte[] corrupted = fixture.tar.clone(); corrupted[512] ^= 1;
        ToolchainStore tampered = fixture.store("tampered-release", corrupted, fixture.manifest);
        boolean refused = false; try { tampered.installBundled(LIVE, null); } catch (Exception expected) { refused = true; }
        check(refused && noPublishedFiles(tampered), "Corrupt release skipped its SHA-256 verification"); checkNoStages(tampered);
        JSONObject undeclared = new JSONObject(fixture.manifest.toString());
        for (int i = 0; i < undeclared.getJSONArray("artifacts").length(); i++) undeclared.getJSONArray("artifacts").getJSONObject(i).remove("tar_sha256");
        ToolchainStore noDigest = fixture.store("unverified-release", fixture.gzip, undeclared);
        refused = false; try { noDigest.installBundled(LIVE, null); } catch (Exception expected) { refused = true; }
        check(refused && noPublishedFiles(noDigest), "Undeclared fallback archive was accepted"); checkNoStages(noDigest);
    }

    private static void gzipTransportVerificationAlsoChecksTheFullUncompressedTar() throws Exception {
        ArchiveFixture fixture = new ArchiveFixture(); JSONObject metadata = new JSONObject(fixture.manifest.toString());
        metadata.getJSONArray("artifacts").getJSONObject(0).put("tar_sha256", "0000000000000000000000000000000000000000000000000000000000000000");
        ToolchainStore store = fixture.store("wrong-inner-sha", fixture.gzip, metadata);
        boolean refused = false; try { store.installBundled(LIVE, null); } catch (Exception expected) { refused = true; }
        check(refused && noPublishedFiles(store), "Compressed SHA success bypassed the declared TAR content checksum"); checkNoStages(store);
    }

    private static void collect(File root, List<File> files) { File[] children = root.listFiles(); if (children != null) for (File file : children) { if (file.isDirectory()) collect(file, files); else files.add(file); } }
    private static void remove(File file) throws Exception { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); Files.deleteIfExists(file.toPath()); }
    public static void main(String[] args) throws Exception {
        repo = new File(args[0]); assets = new File(repo, "app/src/main/assets"); releaseFiles = new File(repo, "app/build/toolchain-release"); root = Files.createTempDirectory("backcast-embedded-tests-").toFile();
        project = new File(root, "project"); project.mkdir(); privateFiles = new File(root, "private"); privateFiles.mkdir();
        temporary = new TemporaryWorkspace(project.getPath(), false, new File(privateFiles, "temporary"), 1); temporary.beginTurn();
        arm64 = new ToolchainStore(new File(privateFiles, "toolchain-arm64"), packaged(), "arm64-v8a", 30, ANDROID_RUNTIME, downloads());
        arm = new ToolchainStore(new File(privateFiles, "toolchain-arm"), packaged(), "armeabi-v7a", 30, ANDROID_RUNTIME, downloads());
        int passed = 0;
        try {
            for (String test : new String[]{"releaseArtifactsMatchManifestAndAreAbsentFromApk", "onlyExplicitInstallDownloadsAndPreparesBothAbis", "apktoolUsesDalvikAndBundledPureJavaPngInsteadOfDesktopOrNativeImageApis", "androidElfAbiAndDynamicDependenciesAreComplete", "pythonAndObjectionArePrivateAndDoNotRequireTermux", "unsupportedDevicesDoNotAttemptAssetReads", "clearRestoresTheBundledLauncherAndCancelledPreparationLeavesNoStage", "onlyDirectBuiltInActionsAreAdvertisedToTheModel", "objectionProbeNeverStartsAServerAndSingleRunsCleanItUp", "sameVersionNewPayloadPreparesSeparatelyAndRetainsTheRunningVersion", "compressedReleaseInstallsWithoutApkArchiveReads", "corruptedOrUndeclaredReleaseFilesNeverPublish", "gzipTransportVerificationAlsoChecksTheFullUncompressedTar"}) {
                EmbeddedToolchainRegressionTest.class.getDeclaredMethod(test).invoke(null); System.out.println("PASS " + test); passed++;
            }
            System.out.println(passed + " embedded toolchain tests passed");
        } finally { temporary.finishTurn(); remove(root); }
    }
}
