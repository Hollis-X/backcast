package com.mkei.backcast.tool;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android-path fixtures exercise the same ELF/executable probe used on devices. */
public final class ArtRuntimeLauncherRegressionTest {
    private static File root;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class Device implements ArtRuntimeLauncher.Probe {
        final File directory;
        Device(String name) throws Exception { directory = new File(root, name); check(directory.mkdir(), "Cannot create device fixture"); }
        File file(String path) { return new File(directory, path.substring(1)); }
        File runtime(String path, int bits) throws Exception {
            File file = file(path); check(file.getParentFile().isDirectory() || file.getParentFile().mkdirs(), "Cannot create Android fixture path");
            byte[] header = new byte[20]; header[0] = 127; header[1] = 'E'; header[2] = 'L'; header[3] = 'F';
            header[4] = (byte) (bits == 64 ? 2 : 1); header[5] = 1;
            int machine = bits == 64 ? 183 : 40; header[18] = (byte) machine; header[19] = (byte) (machine >> 8);
            Files.write(file.toPath(), header); check(file.setExecutable(true, false), "Cannot make fixture executable"); return file;
        }
        public int executableBits(String path) { return ArtRuntimeLauncher.inspect(file(path)); }
    }

    private static void only64DeviceWithoutGenericAliasUsesDalvikvm64() throws Exception {
        Device device = new Device("only64"); device.runtime("/system/bin/dalvikvm64", 64);
        ArtRuntimeLauncher.Selection result = ArtRuntimeLauncher.select("arm64-v8a", device);
        check("/system/bin/dalvikvm64".equals(result.path) && result.bits == 64, "64-only device required a missing dalvikvm alias");
    }

    private static void only32DeviceWithoutGenericAliasUsesDalvikvm32() throws Exception {
        Device device = new Device("only32"); device.runtime("/system/bin/dalvikvm32", 32);
        ArtRuntimeLauncher.Selection result = ArtRuntimeLauncher.select("armeabi-v7a", device);
        check("/system/bin/dalvikvm32".equals(result.path) && result.bits == 32, "32-only device selected an unavailable 64-bit VM");
    }

    private static void apexArtAndOlderRuntimeEntrypointsAreSupported() throws Exception {
        for (String module : new String[]{"com.android.art", "com.android.runtime"}) {
            Device device = new Device(module); String path = "/apex/" + module + "/bin/dalvikvm64";
            device.runtime(path, 64);
            check(path.equals(ArtRuntimeLauncher.select("arm64-v8a", device).path), "APEX runtime entry was ignored: " + module);
        }
    }

    private static void genericAliasUsesItsActualElfBitnessAndPrefersMatchingAbi() throws Exception {
        Device device = new Device("dual"); device.runtime("/system/bin/dalvikvm", 32);
        device.runtime("/apex/com.android.art/bin/dalvikvm64", 64);
        check(ArtRuntimeLauncher.select("arm64-v8a", device).bits == 64, "Generic 32-bit alias displaced the compatible 64-bit VM");
        check("/system/bin/dalvikvm".equals(ArtRuntimeLauncher.select("armeabi-v7a", device).path), "32-bit generic alias was rejected");
        Files.delete(device.file("/apex/com.android.art/bin/dalvikvm64").toPath());
        ArtRuntimeLauncher.Selection fallback = ArtRuntimeLauncher.select("arm64-v8a", device);
        check(fallback.bits == 32, "Pure Java Apktool could not use the remaining 32-bit ART runtime");
    }

    private static void inaccessibleMalformedAndWrongArchitectureExecutablesAreRejected() throws Exception {
        Device device = new Device("invalid"); File disabled = device.runtime("/system/bin/dalvikvm64", 64);
        check(disabled.setExecutable(false, false), "Cannot disable fixture execution");
        File wrong = device.runtime("/system/bin/dalvikvm32", 32);
        byte[] header = Files.readAllBytes(wrong.toPath()); header[18] = 3; Files.write(wrong.toPath(), header);
        File malformed = device.runtime("/system/bin/dalvikvm", 64); Files.write(malformed.toPath(), "not ELF".getBytes("UTF-8"));
        boolean rejected = false;
        try { ArtRuntimeLauncher.select("arm64-v8a", device); }
        catch (IOException expected) {
            rejected = expected.getMessage().contains("没有可执行的 ART") && expected.getMessage().contains("/apex/com.android.art/bin")
                    && expected.getMessage().contains("其他内置工具");
        }
        check(rejected, "Unavailable runtime was registered as a working launcher");
    }

    private static ToolchainStore installed(Device device, String name) throws Exception {
        ToolchainStore store = new ToolchainStore(new File(root, name), null, "arm64-v8a", 30, device);
        File common = new File(store.root(), "common"), nativeTools = new File(store.root(), "native");
        File jar = new File(common, "apktool/apktool-dex.jar"); check(jar.getParentFile().mkdirs(), "Cannot create packaged DEX fixture");
        Files.write(jar.toPath(), new byte[]{1});
        store.bundledInstalled(common, nativeTools, new JSONObject().put("version", "fixture"), "arm64-v8a"); return store;
    }

    private static void registrationDoesNotRequireArtAndOtherToolsRemainIndependent() throws Exception {
        Device device = new Device("absent"); ToolchainStore store = installed(device, "no-art-store");
        check("android-art".equals(store.configuration("apktool").getString("runtime_family")), "Installation omitted the logical ART launcher");
        boolean unavailable = false; try { store.launcher("apktool", ToolchainFixtures.LIVE); }
        catch (IOException expected) { unavailable = expected.getMessage().contains("没有可执行的 ART"); }
        check(unavailable && store.launcher("readelf", ToolchainFixtures.LIVE) != null, "Missing ART prevented independent GNU tool launchers");
    }

    private static void existingRegistryRefreshesWithoutRestartAndFixesOldAppProcessBootstrap() throws Exception {
        Device device = new Device("changing"); device.runtime("/system/bin/dalvikvm64", 64);
        ToolchainStore store = installed(device, "changing-store"); ToolchainStore.Launcher first = store.launcher("apktool", ToolchainFixtures.LIVE);
        check(first.executable.endsWith("dalvikvm64"), "Initial launcher did not select the executable runtime");
        File registry = new File(store.root(), "registry.json"); JSONObject data = new JSONObject(new String(Files.readAllBytes(registry.toPath()), "UTF-8"));
        JSONObject old = data.getJSONObject("tools").getJSONObject("apktool");
        old.put("path", "/system/bin/app_process").put("prefix", new JSONArray().put("/").put("brut.apktool.Main"))
                .getJSONObject("environment").put("CLASSPATH", first.prefix.get(first.prefix.indexOf("-cp") + 1));
        Files.write(registry.toPath(), data.toString().getBytes("UTF-8"));
        Files.delete(device.file("/system/bin/dalvikvm64").toPath());
        String newPath = "/apex/com.android.runtime/bin/dalvikvm32"; device.runtime(newPath, 32);
        ToolchainStore.Launcher refreshed = store.launcher("apktool", ToolchainFixtures.LIVE);
        check(newPath.equals(refreshed.executable) && refreshed.prefix.contains("-Dsun.arch.data.model=32")
                && refreshed.prefix.contains("-cp") && refreshed.prefix.contains("brut.apktool.Main")
                && !refreshed.environment.has("CLASSPATH"), "Existing registry retained the stale path, bootstrap or wrong JVM bitness");
        check(newPath.equals(store.configuration("apktool").getString("path")), "Refreshed runtime was not persisted");
    }

    private static void remove(File file) throws Exception {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); Files.deleteIfExists(file.toPath());
    }
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("backcast-art-runtime-tests-").toFile(); int passed = 0;
        try {
            for (String test : new String[]{"only64DeviceWithoutGenericAliasUsesDalvikvm64", "only32DeviceWithoutGenericAliasUsesDalvikvm32",
                    "apexArtAndOlderRuntimeEntrypointsAreSupported", "genericAliasUsesItsActualElfBitnessAndPrefersMatchingAbi",
                    "inaccessibleMalformedAndWrongArchitectureExecutablesAreRejected", "registrationDoesNotRequireArtAndOtherToolsRemainIndependent",
                    "existingRegistryRefreshesWithoutRestartAndFixesOldAppProcessBootstrap"}) {
                ArtRuntimeLauncherRegressionTest.class.getDeclaredMethod(test).invoke(null); System.out.println("PASS " + test); passed++;
            }
            System.out.println(passed + " ART runtime launcher tests passed");
        } finally { remove(root); }
    }
}
