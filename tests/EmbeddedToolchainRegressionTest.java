package com.mkei.backcast.tool;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** These checks inspect the actual APK payloads, never downloaded fixtures. */
public final class EmbeddedToolchainRegressionTest {
    private static File repo, root, assets, project, privateFiles;
    private static ToolchainStore arm64, arm;
    private static TemporaryWorkspace temporary;
    private static final ToolchainInstaller.Cancellation LIVE = new ToolchainInstaller.Cancellation() {
        public void check() { }
    };
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
    private static byte[] bytes(File file) throws Exception { return Files.readAllBytes(file.toPath()); }
    private static EmbeddedToolchain.Assets packaged() {
        return new EmbeddedToolchain.Assets() {
            public InputStream open(String name) throws Exception { return new FileInputStream(new File(assets, name)); }
        };
    }
    private static ToolkitTool toolkit(ToolchainStore store) {
        return new ToolkitTool(new ShellTool(false, project.getPath(), temporary), store, project.getPath(), temporary, "arm64-v8a");
    }

    private static void packagedArtifactsMatchManifestAndGitHubSizeLimit() throws Exception {
        JSONObject manifest = new JSONObject(new String(bytes(new File(assets, "toolchain/manifest.json")), "UTF-8"));
        check("2.9.3".equals(manifest.getString("apktool")) && "6.2.2".equals(manifest.getString("radare2"))
                && "1.12.5".equals(manifest.getString("objection")), "Pinned upstream versions were lost");
        JSONArray entries = manifest.getJSONArray("artifacts");
        check(entries.length() == 3, "Missing common or Android ABI payload");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i); File file = new File(assets, entry.getString("asset"));
            check(file.length() == entry.getLong("bytes") && file.length() < 100L * 1024 * 1024, "Payload size mismatch");
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); InputStream stream = new FileInputStream(file);
            try { byte[] buffer = new byte[16384]; int read; while ((read = stream.read(buffer)) >= 0) digest.update(buffer, 0, read); }
            finally { stream.close(); }
            check(entry.getString("sha256").equals(ToolchainInstaller.hex(digest.digest())), "Payload digest mismatch");
        }
        check(manifest.getJSONArray("sources").length() > 60, "Native and Python provenance is incomplete");
        check(new File(assets, "toolchain/licenses/Objection-GPL-3.0.txt").length() > 30000, "Objection license was omitted");
    }

    private static void firstUseOfflinePreparesBothAbisAndAllLaunchers() throws Exception {
        check(toolkit(arm64).listing().getJSONArray("tools").getJSONObject(0).getBoolean("bundled"), "Before first use bundle looks absent");
        check(!arm64.root().exists(), "Listing eagerly extracted the payload");
        arm64.prepareBundled(LIVE); arm.prepareBundled(LIVE);
        for (ToolchainStore preparedStore : new ToolchainStore[]{arm64, arm}) {
            List<File> packagedFiles = new ArrayList<File>(); collect(preparedStore.root(), packagedFiles);
            for (File file : packagedFiles) check(!file.getName().endsWith(".pyc") && !file.getPath().contains(File.separator + "__pycache__" + File.separator),
                    "APK included temporary host Python bytecode: " + file);
        }
        JSONArray catalog = ToolCatalog.list(); check(catalog.length() == 13, "Toolkit catalog changed unexpectedly");
        for (ToolchainStore store : new ToolchainStore[]{arm64, arm}) {
            for (int i = 0; i < catalog.length(); i++) {
                String id = catalog.getJSONObject(i).getString("id"); ToolchainStore.Launcher launcher = store.launcher(id);
                check(launcher != null, "Missing offline launcher: " + id);
                if (!"apktool".equals(id)) check(new File(launcher.executable).isFile() && new File(launcher.executable).canExecute(), "Missing Android native entry: " + id);
                check("bundled".equals(store.configuration(id).getString("origin")), "Tool requires external configuration: " + id);
            }
            for (String child : store.root().list()) check(!child.startsWith(".embedded-"), "Successful preparation leaked staging data");
        }
    }

    private static void apktoolUsesDalvikAndAndroidImageApiInsteadOfDesktopJvm() throws Exception {
        ToolchainStore.Launcher launcher = arm64.launcher("apktool");
        check("/system/bin/app_process".equals(launcher.executable) && launcher.prefix.contains("brut.apktool.Main"), "Apktool requires a desktop JVM");
        File jar = new File(launcher.environment.getString("CLASSPATH"));
        ZipFile zip = new ZipFile(jar);
        try {
            InputStream stream = zip.getInputStream(zip.getEntry("classes.dex")); ByteArrayOutputStream dex = new ByteArrayOutputStream();
            try { byte[] buffer = new byte[8192]; int read; while ((read = stream.read(buffer)) >= 0) dex.write(buffer, 0, read); }
            finally { stream.close(); }
            String code = new String(dex.toByteArray(), "ISO-8859-1");
            check(code.startsWith("dex\n") && code.contains("Landroid/graphics/Bitmap;"), "JAR does not contain Android dex code");
            check(!code.contains("Ljava/awt/image/BufferedImage;") && !code.contains("Ljavax/imageio/ImageIO;"), "Desktop image APIs remain in Apktool");
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
        ToolchainStore.Launcher objection = arm64.launcher("objection");
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
        arm64.clear("radare2"); check(arm64.launcher("radare2") != null, "Clear permanently disabled the built-in launcher");
        arm64.configure("apktool", new File(root, "apktool.jar").getPath(), "/usr/bin/java");
        check("/system/bin/app_process".equals(arm64.launcher("apktool").executable)
                && "bundled".equals(arm64.configuration("apktool").getString("origin")), "Upgrade retained the old external JVM launcher");
        final ToolchainStore cancelled = new ToolchainStore(new File(root, "cancelled"), packaged(), "arm64-v8a", 30);
        final int[] checks = new int[1]; boolean interrupted = false;
        try { cancelled.prepareBundled(new ToolchainInstaller.Cancellation() { public void check() throws Exception { if (++checks[0] == 10) throw new InterruptedException("fixture"); } }); }
        catch (InterruptedException expected) { interrupted = true; }
        check(interrupted && cancelled.root().list().length == 0, "Cancelled extraction published software or leaked staging data");
    }

    private static void onlyDirectBuiltInActionsAreAdvertisedToTheModel() throws Exception {
        JSONObject properties = toolkit(arm64).parameters().getJSONObject("properties");
        JSONArray actions = properties.getJSONObject("action").getJSONArray("enum");
        check(actions.length() == 4 && !properties.has("path") && !properties.has("runtime"), "Model still asks the user to download or configure tools");
        check(!toolkit(arm64).description().contains("需要设备 JVM"), "Obsolete desktop JVM instructions remain");
    }

    private static void sameVersionNewPayloadPreparesSeparatelyAndRetainsTheRunningVersion() throws Exception {
        final JSONObject updated = new JSONObject(new String(bytes(new File(assets, "toolchain/manifest.json")), "UTF-8"));
        JSONObject common = updated.getJSONArray("artifacts").getJSONObject(0);
        MessageDigest sha = MessageDigest.getInstance("SHA-256"); InputStream stream = new FileInputStream(new File(assets, common.getString("asset")));
        try { byte[] buffer = new byte[16384]; int read; while ((read = stream.read(buffer)) >= 0) sha.update(buffer, 0, read); }
        finally { stream.close(); }
        sha.update((byte) 0); common.put("bytes", common.getLong("bytes") + 1).put("sha256", ToolchainInstaller.hex(sha.digest()));
        File oldJar = new File(arm64.launcher("apktool").environment.getString("CLASSPATH"));
        ToolchainStore upgraded = new ToolchainStore(arm64.root(), new EmbeddedToolchain.Assets() {
            public InputStream open(String name) throws Exception {
                if ("toolchain/manifest.json".equals(name)) return new ByteArrayInputStream(updated.toString().getBytes("UTF-8"));
                if ("toolchain/common.tar.gz".equals(name)) return new SequenceInputStream(new FileInputStream(new File(assets, name)), new ByteArrayInputStream(new byte[]{0}));
                return new FileInputStream(new File(assets, name));
            }
        }, "arm64-v8a", 30);
        File newJar = new File(upgraded.launcher("apktool").environment.getString("CLASSPATH"));
        check(!oldJar.equals(newJar) && oldJar.isFile() && newJar.isFile(), "Payload update overwrote/deleted a potentially running tool or failed on reused release date");
    }

    private static void objectionProbeNeverStartsAServerAndSingleRunsCleanItUp() throws Exception {
        File marker = new File(temporary.directory(), "server-started");
        File companion = new File(temporary.directory(), "fixture-frida-server");
        Files.write(companion.toPath(), ("#!/bin/sh\nprintf '%s' \"$$\" > " + RootShell.quote(marker.getPath()) + "\nexec sleep 30\n").getBytes("UTF-8"));
        companion.setExecutable(true, true);
        ToolchainStore.Launcher fixture = new ToolchainStore.Launcher("objection", "/usr/bin/python3");
        fixture.prefix.add("-c"); fixture.prefix.add("import time; time.sleep(0.2); print('fixture completed')"); fixture.companion = companion.getPath();
        ShellTool shell = new ShellTool(false, project.getPath(), temporary);
        List<String> arguments = new ArrayList<String>(); arguments.add("version");
        check(shell.runProgram(fixture, arguments, true, 5).startsWith("exit=0\n") && !marker.exists(), "Version probe started Frida server");
        arguments.clear(); arguments.add("run");
        check(shell.runProgram(fixture, arguments, true, 5).startsWith("exit=0\n") && marker.exists(), "Single invocation did not start its companion");
        String pid = new String(bytes(marker), "UTF-8");
        File status = new File("/proc/" + pid + "/status");
        check(!status.exists() || new String(bytes(status), "UTF-8").contains("State:\tZ"), "Completed invocation left its Frida server alive");
        JSONObject bad = new JSONObject(toolkit(arm64).run(new JSONObject().put("action", "run").put("tool", "objection")
                .put("arguments", new JSONArray().put("start"))));
        check("error".equals(bad.optString("state")), "Interactive Objection session was allowed to hang the model's tool request");
    }

    private static void collect(File root, List<File> files) { File[] children = root.listFiles(); if (children != null) for (File file : children) { if (file.isDirectory()) collect(file, files); else files.add(file); } }
    private static void remove(File file) throws Exception { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); Files.deleteIfExists(file.toPath()); }
    public static void main(String[] args) throws Exception {
        repo = new File(args[0]); assets = new File(repo, "app/src/main/assets"); root = Files.createTempDirectory("backcast-embedded-tests-").toFile();
        project = new File(root, "project"); project.mkdir(); privateFiles = new File(root, "private"); privateFiles.mkdir();
        temporary = new TemporaryWorkspace(project.getPath(), false, new File(privateFiles, "temporary"), 1); temporary.beginTurn();
        arm64 = new ToolchainStore(new File(privateFiles, "toolchain-arm64"), packaged(), "arm64-v8a", 30);
        arm = new ToolchainStore(new File(privateFiles, "toolchain-arm"), packaged(), "armeabi-v7a", 30);
        int passed = 0;
        try {
            for (String test : new String[]{"packagedArtifactsMatchManifestAndGitHubSizeLimit", "firstUseOfflinePreparesBothAbisAndAllLaunchers", "apktoolUsesDalvikAndAndroidImageApiInsteadOfDesktopJvm", "androidElfAbiAndDynamicDependenciesAreComplete", "pythonAndObjectionArePrivateAndDoNotRequireTermux", "unsupportedDevicesDoNotAttemptAssetReads", "clearRestoresTheBundledLauncherAndCancelledPreparationLeavesNoStage", "onlyDirectBuiltInActionsAreAdvertisedToTheModel", "objectionProbeNeverStartsAServerAndSingleRunsCleanItUp", "sameVersionNewPayloadPreparesSeparatelyAndRetainsTheRunningVersion"}) {
                EmbeddedToolchainRegressionTest.class.getDeclaredMethod(test).invoke(null); System.out.println("PASS " + test); passed++;
            }
            System.out.println(passed + " embedded toolchain tests passed");
        } finally { temporary.finishTurn(); remove(root); }
    }
}
