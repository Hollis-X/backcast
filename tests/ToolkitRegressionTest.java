package com.mkei.backcast.tool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ToolkitRegressionTest {
    private static int passed;
    private static File root, project, state;
    private static ToolchainStore store;
    private static TemporaryWorkspace temporary;
    private static ShellTool shell;
    private static ToolkitTool toolkit;
    private static final ToolchainInstaller.Cancellation LIVE = new ToolchainInstaller.Cancellation() {
        public void check() { }
    };

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JSONObject call(String action, String id) throws Exception { return new JSONObject().put("action", action).put("tool", id); }
    private static JSONObject run(JSONObject args) throws Exception { return new JSONObject(toolkit.run(args)); }
    private static void rejects(JSONObject args) throws Exception { check("error".equals(run(args).optString("state")), "Toolkit accepted forbidden operation: " + args); }

    private static void catalogDoesNotPretendToolsAreInstalled() throws Exception {
        JSONArray catalog = toolkit.listing().getJSONArray("tools");
        check(catalog.length() == 13, "Catalog lost tools");
        for (int i = 0; i < catalog.length(); i++) check(!catalog.getJSONObject(i).getBoolean("configured"), "Absent tool was configured");
        check("unconfigured".equals(toolkit.status("objection").getString("state")), "Missing Python tool was marked available");
        for (int i = 0; i < catalog.length(); i++) check(!catalog.getJSONObject(i).getBoolean("download_available"), "Offline catalog offered an obsolete download");
    }

    private static void legacyConfigurationPersistsAndHiddenMutationsAreRejected() throws Exception {
        File dir = new File(root, "configured"); dir.mkdir();
        File executable = new File(dir, "readelf"); Files.write(executable.toPath(), new byte[]{1});
        ToolchainFixtures.configure(store, "readelf", executable.getPath(), null);
        check(new ToolchainStore(store.root(), null, "", 0).configuration("readelf").getString("path").equals(executable.getPath()), "Configuration did not persist");
        rejects(call("configure", "radare2").put("path", "/bin/sh"));
        rejects(call("configure", "readelf").put("path", "readelf"));
        rejects(call("clear", "readelf"));
        rejects(call("install", "apktool"));
        check(store.configuration("readelf").getString("path").equals(executable.getPath()), "Hidden action mutated a persisted launcher");
        ToolchainFixtures.clear(store, "readelf");
        check(store.configuration("readelf").length() == 0, "Clear retained launcher");
        check(store.root().list().length == 1, "Registry left temporary write files");
    }

    private static void apktoolNeedsRealJvm() throws Exception {
        File jar = new File(root, "apktool.jar"); Files.write(jar.toPath(), new byte[]{1});
        ToolchainFixtures.configure(store, "apktool", jar.getPath(), null);
        check("needs_runtime".equals(toolkit.status("apktool").getString("state")), "ART was treated as a JVM");
        rejects(call("configure", "apktool").put("path", jar.getPath()).put("runtime", "/system/bin/dalvikvm"));
        ToolchainFixtures.clear(store, "apktool");
    }

    private static void actualExternalExecutableUsesStructuredArguments() throws Exception {
        File executable = new File("/usr/bin/readelf");
        check(executable.isFile(), "Host readelf fixture is unavailable");
        ToolchainFixtures.configure(store, "readelf", executable.getPath(), null);
        check(toolkit.status("readelf").getBoolean("ready"), "External readelf did not probe successfully");
        File sample = new File(project, "sample.elf"); Files.copy(new File("/bin/ls").toPath(), sample.toPath());
        JSONObject result = run(call("run", "readelf").put("arguments", new JSONArray().put("-h").put(sample.getPath())));
        check(result.getBoolean("success") && result.getString("output").contains("ELF Header"), "Structured readelf did not inspect project binary");
        File leak = new File(project, "injection-leak");
        result = run(call("run", "readelf").put("arguments", new JSONArray().put("--version; touch " + leak.getPath())));
        check(!result.getBoolean("success") && !leak.exists(), "Structured argument executed shell injection");
    }

    private static void missingToolsReturnActionableErrorsBeforeOpeningAssets() throws Exception {
        final int[] opened = new int[1];
        ToolchainStore isolated = new ToolchainStore(new File(state, "argument-check"), new EmbeddedToolchain.Assets() {
            public InputStream open(String name) { opened[0]++; throw new AssertionError("Invalid arguments opened assets"); }
        }, "arm64-v8a", 30);
        ToolkitTool target = new ToolkitTool(shell, isolated, project.getPath(), temporary, "arm64-v8a");
        for (JSONObject args : new JSONObject[]{new JSONObject().put("action", "status"),
                new JSONObject().put("action", "status").put("arguments", new JSONArray().put("apktool")),
                new JSONObject().put("action", "run")}) {
            JSONObject result = new JSONObject(target.run(args));
            check("error".equals(result.getString("state")) && result.getString("error").contains("tool")
                    && result.getString("error").contains("apktool"), "Missing tool has no corrective example: " + result);
        }
        JSONObject property = target.parameters().getJSONObject("properties").getJSONObject("tool");
        check(property.getJSONArray("enum").length() == 13 && opened[0] == 0, "Tool ids are not constrained before asset work");
    }

    private static void versionProbesRejectEmptyAndCrashedSuccessfulExitCodes() throws Exception {
        final String[] output = new String[1];
        ShellTool probe = new ShellTool(false, project.getPath(), temporary) {
            @Override String runProgram(ToolchainStore.Launcher launcher, List<String> arguments, boolean temp, int timeout, int mine) {
                return output[0];
            }
        };
        ToolkitTool target = new ToolkitTool(probe, store, project.getPath(), temporary, "arm64-v8a");
        ToolchainFixtures.configure(store, "apktool", new File(root, "apktool.jar").getPath(), "/usr/bin/java");
        for (String failed : new String[]{"exit=0\n", "exit=0\nKilled \n", "exit=0\nException in thread \"main\" java.lang.ExceptionInInitializerError\n2.9.3\n"}) {
            output[0] = failed;
            JSONObject result = target.status("apktool");
            check(!result.getBoolean("ready") && "unavailable".equals(result.getString("state")), "Crashed probe declared ready: " + failed);
        }
        output[0] = "exit=0\n2.9.3\n";
        check(target.status("apktool").getBoolean("ready"), "Valid Apktool version was rejected");
        ToolchainFixtures.clear(store, "apktool");
        ToolchainFixtures.configure(store, "readelf", "/usr/bin/readelf", null);
        output[0] = "exit=0\n2.9.3\n";
        check(!target.status("readelf").getBoolean("ready"), "Wrong program version declared ready");
        output[0] = "exit=0\nGNU readelf (GNU Binutils) 2.47\n";
        check(target.status("readelf").getBoolean("ready"), "Valid GNU version was rejected");
        ToolchainFixtures.clear(store, "readelf");
        for (String id : new String[]{"objection", "radare2", "rabin2", "addr2line", "objdump", "nm", "strings", "size", "objcopy", "ar", "strip"}) {
            ToolchainFixtures.configure(store, id, new File(root, id).getPath(), null);
            String version = "objection".equals(id) ? "objection: 1.12.5" : "radare2".equals(id) || "rabin2".equals(id)
                    ? id + " 6.2.2 0 @ android-arm-64" : "GNU " + id + " (GNU Binutils) 2.47";
            output[0] = "exit=0\n" + version + "\n";
            check(target.status(id).getBoolean("ready"), "Valid " + id + " version was rejected");
            ToolchainFixtures.clear(store, id);
        }
    }

    private static void programArgumentsKeepPathBoundaries() throws Exception {
        List<String> args = Arrays.asList("-h", new File(root, "outside.elf").getPath());
        boolean rejected = false;
        try { ToolPaths.checkProgram(project.getPath(), "readelf", args, temporary, true); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Program read outside project or current temporary directory");
        rejected = false;
        try { ToolPaths.checkProgram(project.getPath(), "radare2", Arrays.asList("-c", "!touch escaped"), temporary, true); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "radare2 domain command bypassed structured runner");
        rejected = false;
        try { ToolPaths.checkProgram(project.getPath(), "apktool", Arrays.asList("d", new File(project, "sample.apk").getPath()), temporary, true); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Apktool implicit output was accepted");
        ToolPaths.checkProgram(project.getPath(), "apktool", Arrays.asList("d", new File(project, "sample.apk").getPath(), "-o", "decoded"), temporary, true);
    }

    private static void binaryMutationRequiresDisposableExplicitOutputs() throws Exception {
        for (String id : new String[]{"objcopy", "strip", "ar"}) {
            ToolPaths.checkProgram(project.getPath(), id, Arrays.asList("--version"), temporary, true);
        }
        File source = new File(project, "sample.elf"), output = new File(temporary.directory(), "copy.elf");
        ToolPaths.checkProgram(project.getPath(), "objcopy", Arrays.asList(source.getPath(), output.getPath()), temporary, true);
        ToolPaths.checkProgram(project.getPath(), "objcopy", Arrays.asList(source.getPath(), output.getPath(), "--strip-all"), temporary, true);
        ToolPaths.checkProgram(project.getPath(), "strip", Arrays.asList("-o", output.getPath(), source.getPath()), temporary, true);
        ToolPaths.checkProgram(project.getPath(), "ar", Arrays.asList("rc", output.getPath(), source.getPath()), temporary, true);
        for (String id : new String[]{"objcopy", "strip", "ar"}) {
            boolean refused = false;
            try { ToolPaths.checkProgram(project.getPath(), id, Arrays.asList(source.getPath()), temporary, false); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "Binary mutation allowed project cwd: " + id);
        }
        for (List<String> args : Arrays.asList(Arrays.asList(source.getPath(), new File(project, "leak.elf").getPath(), "--strip-all"),
                Arrays.asList("@arguments.txt", source.getPath(), output.getPath()))) {
            boolean refused = false;
            try { ToolPaths.checkProgram(project.getPath(), "objcopy", args, temporary, true); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "objcopy moved its real output outside temporary storage or expanded unchecked response file");
        }
        boolean refused = false;
        try { ToolPaths.checkProgram(project.getPath(), "ar", Arrays.asList("-v", "rc", new File(project, "leak.a").getPath(), source.getPath()), temporary, true); }
        catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "ar shifted output position with prefix options");
    }

    private static void cancellationWhileWaitingForToolLockNeverStartsProgram() throws Exception {
        final int[] started = new int[]{0};
        ShellTool counted = new ShellTool(false, project.getPath(), temporary) {
            @Override String runProgram(ToolchainStore.Launcher launcher, List<String> arguments, boolean temp, int timeout, int mine) {
                started[0]++; return "exit=0\nfixture\n";
            }
        };
        final ToolkitTool target = new ToolkitTool(counted, store, project.getPath(), temporary, "arm64-v8a");
        ToolchainFixtures.configure(store, "readelf", "/usr/bin/readelf", null);
        for (final String action : new String[]{"run", "status"}) {
            final String[] response = new String[1];
            Thread blocked = new Thread(new Runnable() {
                public void run() {
                    try {
                        response[0] = target.run(call(action, "readelf")
                                .put("arguments", new JSONArray().put("--version")));
                    }
                    catch (Exception failure) { response[0] = failure.toString(); }
                }
            });
            synchronized (store.toolLock("readelf")) {
                blocked.start(); long deadline = System.currentTimeMillis() + 2000;
                while (blocked.getState() != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) Thread.sleep(5);
                check(blocked.getState() == Thread.State.BLOCKED, "Cancellation fixture did not wait for tool lock");
                target.abort();
            }
            blocked.join(2000);
            check(!blocked.isAlive() && "cancelled".equals(new JSONObject(response[0]).optString("state")) && started[0] == 0,
                    "Cancelled lock waiter started a program: " + action + " " + response[0]);
        }
    }

    private static byte[] tar(String name, String text, int type, String link) throws Exception {
        byte[] header = new byte[512];
        put(header, 0, name); put(header, 100, "0000755"); put(header, 124, String.format("%011o", text.length()));
        header[156] = (byte) type; put(header, 157, link);
        Arrays.fill(header, 148, 156, (byte) ' '); int sum = 0; for (byte b : header) sum += b & 255;
        put(header, 148, String.format("%06o", sum)); header[154] = 0; header[155] = ' ';
        ByteArrayOutputStream result = new ByteArrayOutputStream(); result.write(header); result.write(text.getBytes("UTF-8"));
        result.write(new byte[(512 - text.length() % 512) % 512]); return result.toByteArray();
    }
    private static void put(byte[] target, int offset, String value) throws Exception {
        byte[] bytes = value.getBytes("UTF-8"); System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    private static void nativeArchiveCopiesInternalLinksSafely() throws Exception {
        File output = new File(root, "safe-tar"); output.mkdir();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(tar("native/bin/radare2", "binary", '0', ""));
        data.write(tar("native/bin/r2", "", '2', "radare2")); data.write(new byte[1024]);
        ToolchainInstaller.extractTar(new ByteArrayInputStream(data.toByteArray()), output, "native/", LIVE);
        check(new File(output, "bin/r2").isFile() && new File(output, "bin/radare2").canExecute(), "Internal link was not materialized");
        check(Arrays.equals(Files.readAllBytes(new File(output, "bin/radare2").toPath()), Files.readAllBytes(new File(output, "bin/r2").toPath())), "Link copy changed native binary");
    }

    private static void unsafeArchiveNeverWritesOutsideExtractionRoot() throws Exception {
        for (byte[] unsafe : new byte[][]{tar("native/../../archive-leak", "x", '0', ""),
                tar("native/link", "", '2', "../../archive-leak"), tar("native/link", "", '2', "/tmp/archive-leak"),
                tar("native/device", "", '3', "")}) {
            File dir = Files.createTempDirectory(root.toPath(), "bad-tar-").toFile();
            ByteArrayOutputStream archive = new ByteArrayOutputStream(); archive.write(unsafe); archive.write(new byte[1024]);
            boolean failed = false; try { ToolchainInstaller.extractTar(new ByteArrayInputStream(archive.toByteArray()), dir, "native/", LIVE); }
            catch (Exception expected) { failed = true; }
            check(failed, "Unsafe archive entry was accepted");
        }
        check(!new File(root, "archive-leak").exists(), "Unsafe archive escaped extraction root");
    }

    private static void binaryAndDirectoryExportsSurviveTurnCleanup() throws Exception {
        File source = new File(temporary.directory(), "decoded"); source.mkdir();
        byte[] bytes = new byte[]{0, 1, -1, 2}; Files.write(new File(source, "classes.dex").toPath(), bytes);
        JSONObject result = run(new JSONObject().put("action", "export").put("source", source.getPath())
                .put("target", "decoded-result").put("purpose", "deliverable"));
        check("exported".equals(result.getString("state")), "Directory export failed: " + result);
        check(Arrays.equals(bytes, Files.readAllBytes(new File(project, "decoded-result/classes.dex").toPath())), "Export was not binary safe");
        check(temporary.finishTurn() == null, "Turn cleanup failed"); temporary.beginTurn();
        check(new File(project, "decoded-result/classes.dex").isFile(), "Turn cleanup removed a deliverable");
    }

    private static void exportsRejectForeignInputsSymlinksAndImplicitOverwrite() throws Exception {
        File source = new File(temporary.directory(), "result.bin"); Files.write(source.toPath(), new byte[]{1});
        JSONObject export = new JSONObject().put("action", "export").put("source", source.getPath()).put("target", "result.bin").put("purpose", "deliverable");
        check("exported".equals(run(export).optString("state")), "Fixture export failed");
        Files.write(source.toPath(), new byte[]{2}); rejects(export);
        check(Files.readAllBytes(new File(project, "result.bin").toPath())[0] == 1, "Implicit overwrite changed user file");
        check("exported".equals(run(new JSONObject(export.toString()).put("overwrite", true)).optString("state")), "Explicit overwrite failed");
        rejects(new JSONObject(export.toString()).put("source", new File(project, "result.bin").getPath()).put("target", "bad-input.bin"));
        File link = new File(temporary.directory(), "linked.bin"); Files.createSymbolicLink(link.toPath(), source.toPath());
        rejects(new JSONObject(export.toString()).put("source", link.getPath()).put("target", "bad-link.bin"));
        rejects(new JSONObject(export.toString()).put("target", "loose-test.bin").put("purpose", "test"));
        check("exported".equals(run(new JSONObject(export.toString()).put("target", "tests/formal.bin").put("purpose", "test")).optString("state")), "Organized test export failed");
        rejects(new JSONObject(export.toString()).put("target", new File(store.root(), "registry.json").getPath()));
    }

    private static void cancelledOverwritePreservesExistingFile() throws Exception {
        File source = new File(temporary.directory(), "cancel-source.bin"); Files.write(source.toPath(), new byte[40000]);
        File target = new File(project, "existing.bin"); byte[] before = new byte[]{7, 8}; Files.write(target.toPath(), before);
        final int[] calls = new int[]{0}; boolean cancelled = false;
        try {
            ToolkitExport.copy(project.getPath(), temporary, store, source.getPath(), target.getPath(), "deliverable", true,
                    new ToolchainInstaller.Cancellation() {
                        public void check() throws Exception { if (++calls[0] >= 3) throw new InterruptedException("cancel fixture"); }
                    });
        } catch (InterruptedException expected) { cancelled = true; }
        check(cancelled && Arrays.equals(before, Files.readAllBytes(target.toPath())), "Cancelled export damaged the previous file");
        for (String name : project.list()) check(!name.startsWith(".backcast-export-"), "Cancelled export left scratch files");
    }

    private static void remove(File file) throws Exception {
        if (Files.isSymbolicLink(file.toPath())) { Files.delete(file.toPath()); return; }
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        Files.deleteIfExists(file.toPath());
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("backcast-toolkit-").toFile();
        project = new File(root, "project"); project.mkdir(); state = new File(root, "private"); state.mkdir();
        store = new ToolchainStore(new File(state, "toolchains"), null, "", 0);
        temporary = new TemporaryWorkspace(project.getPath(), false, new File(state, "temporary-workspaces"), 1);
        temporary.beginTurn(); shell = new ShellTool(false, project.getPath(), temporary);
        toolkit = new ToolkitTool(shell, store, project.getPath(), temporary, "arm64-v8a");
        try {
            for (String name : new String[]{"catalogDoesNotPretendToolsAreInstalled", "legacyConfigurationPersistsAndHiddenMutationsAreRejected",
                    "apktoolNeedsRealJvm", "actualExternalExecutableUsesStructuredArguments", "missingToolsReturnActionableErrorsBeforeOpeningAssets",
                    "versionProbesRejectEmptyAndCrashedSuccessfulExitCodes", "programArgumentsKeepPathBoundaries",
                    "binaryMutationRequiresDisposableExplicitOutputs",
                    "cancellationWhileWaitingForToolLockNeverStartsProgram",
                    "nativeArchiveCopiesInternalLinksSafely", "unsafeArchiveNeverWritesOutsideExtractionRoot",
                    "binaryAndDirectoryExportsSurviveTurnCleanup", "exportsRejectForeignInputsSymlinksAndImplicitOverwrite",
                    "cancelledOverwritePreservesExistingFile"}) {
                ToolkitRegressionTest.class.getDeclaredMethod(name).invoke(null); passed++; System.out.println("PASS " + name);
            }
            check(temporary.finishTurn() == null, "Final temporary cleanup failed");
            System.out.println(passed + " toolkit tests passed");
        } finally { temporary.finishTurn(); remove(root); }
    }
}
