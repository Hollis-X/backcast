package com.mkei.backcast.tool;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Replays the failed argument shapes from device evidence and runs real host GNU tools. */
public final class ToolkitOperationRegressionTest {
    private static File root, project, input;
    private static TemporaryWorkspace temporary;
    private static ToolchainStore store;
    private static ToolkitTool toolkit;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JSONObject request(String id, String... args) throws Exception {
        JSONArray arguments = new JSONArray(); for (String arg : args) arguments.put(arg);
        return new JSONObject().put("action", "run").put("tool", id).put("arguments", arguments);
    }
    private static JSONObject run(String id, String... args) throws Exception { return new JSONObject(toolkit.run(request(id, args))); }
    private static void accepts(String id, String... args) throws Exception { ToolPaths.checkProgram(project.getPath(), id, Arrays.asList(args), temporary, true); }
    private static void rejects(String id, String... args) throws Exception {
        boolean rejected = false; try { accepts(id, args); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Unsafe or invalid arguments accepted: " + id + " " + Arrays.asList(args));
    }

    private static void rabinInfoImportsAndEntrypointsAreDistinctFromRadareScripts() throws Exception {
        for (String flag : new String[]{"-I", "-i", "-e", "-w", "-P", "-jIs"}) accepts("rabin2", flag, input.getPath());
        for (String flag : new String[]{"-x", "-PP", "-o", "-O", "-C", "-X"}) rejects("rabin2", flag, input.getPath());
        rejects("radare2", "-i", input.getPath()); rejects("radare2", "-I", input.getPath());
    }

    private static void radareAnalysisAndFiltersAreAllowedWhileExecutionAndWritingAreRejected() throws Exception {
        accepts("radare2", "-c", "aa;afl;ii;is;izz~sqlite;pdf @ sym.main;q", input.getPath());
        accepts("radare2", "-e", "scr.interactive=false", "-ebin.relocs.apply=true", "-c", "i;q", input.getPath());
        for (String program : new String[]{"!touch escaped", "i;w8 00;q", "i>outside", ". script", "i|sh", "i`touch x`"}) rejects("radare2", "-c", program, input.getPath());
        rejects("radare2", "-e", "io.radare.rabin2.elf:" + input.getPath());
        rejects("radare2", "-e", "cmd.prompt=!touch escaped", input.getPath());
        rejects("radare2", "-w", input.getPath()); rejects("radare2", "-d", input.getPath());
    }

    private static void radareRequestsExitWithoutWaitingForConsoleInput() throws Exception {
        List<String> original = Arrays.asList("-A", input.getPath());
        List<String> automatic = ToolPaths.prepareProgramArguments("radare2", original);
        check(automatic.equals(Arrays.asList("-q", "-c", "i;q", "-A", input.getPath())) && original.size() == 2,
                "A bare file/analysis request still waits for console input or mutated caller arguments");
        ToolPaths.checkProgram(project.getPath(), "radare2", automatic, temporary, true);
        List<String> multiple = ToolPaths.prepareProgramArguments("radare2", Arrays.asList("-c", "ii", "-ciq", input.getPath()));
        check("ii".equals(multiple.get(2)) && "-ciq;q".equals(multiple.get(3)), "An earlier command exited before later -c commands, or iq was mistaken for quit");
        check(multiple.equals(ToolPaths.prepareProgramArguments("radare2", multiple)), "Preparing twice added duplicate commands/quit flags");
        check(ToolPaths.prepareProgramArguments("radare2", Arrays.asList("-v")).equals(Arrays.asList("-v")), "Version probe was rewritten into an analysis request");
    }

    private static void invalidAddr2lineCallsReturnCorrectExamplesBeforeStartingAProgram() throws Exception {
        for (String value : new String[]{"JNI_OnLoad", "0x4c03c"}) {
            JSONObject result = run("addr2line", "-e", value, "-f", "-C", input.getPath());
            check("error".equals(result.optString("state")) && result.getString("error").contains("-e")
                    && result.getString("error").contains("0x1234"), "Bad -e operand reached the native tool or lacked a correction example");
        }
        rejects("addr2line", "-f", input.getPath()); rejects("addr2line", "-e", input.getPath());
        rejects("addr2line", "-e", input.getPath(), "JNI_OnLoad");
    }

    private static void correctAddr2lineAddressesRunTheActualGnuProgram() throws Exception {
        ToolchainFixtures.configure(store, "addr2line", "/usr/bin/addr2line", null);
        JSONObject result = run("addr2line", "-f", "-C", "-e", input.getPath(), "0x1000");
        check(result.getBoolean("success") && result.getString("output").startsWith("exit=0\n"), "Valid addr2line request did not run the real program: " + result);
        ToolchainFixtures.clear(store, "addr2line");
    }

    private static void objcopyDiscardSymbolsWritesAnExplicitTemporaryOutput() throws Exception {
        ToolchainFixtures.configure(store, "objcopy", "/usr/bin/objcopy", null);
        byte[] original = Files.readAllBytes(input.toPath()); File output = new File(temporary.directory(), "discarded.elf");
        JSONObject result = run("objcopy", "-x", input.getPath(), output.getPath());
        check(result.getBoolean("success") && output.isFile() && Arrays.equals(original, Files.readAllBytes(input.toPath())),
                "objcopy -x was rejected or changed the project input: " + result);
        rejects("objcopy", "-x", input.getPath());
        rejects("objcopy", "-x", input.getPath(), new File(project, "overwrite.elf").getPath());
        ToolchainFixtures.clear(store, "objcopy");
    }

    private static void archiveListingReadsProjectArchivesAndExplainsSharedObjectMisuse() throws Exception {
        File member = new File(project, "member.o"), archive = new File(project, "sample.a"); Files.write(member.toPath(), new byte[]{1, 2, 3});
        Process process = new ProcessBuilder("/usr/bin/ar", "rcs", archive.getPath(), member.getPath()).start();
        check(process.waitFor() == 0, "Cannot create actual GNU archive fixture"); ToolchainFixtures.configure(store, "ar", "/usr/bin/ar", null);
        JSONObject listing = new JSONObject(toolkit.run(request("ar", "t", archive.getPath()).put("temporary", false)));
        check(listing.getBoolean("success") && listing.getString("output").contains("member.o"), "Read-only archive listing required private output or failed: " + listing);
        JSONObject wrong = run("ar", "t", input.getPath());
        check(!wrong.getBoolean("success") && "not_an_archive".equals(wrong.getString("failure_kind")), "Shared-object archive misuse lost its specific explanation");
        ToolchainFixtures.clear(store, "ar");
    }

    private static void dynamicFailuresKeepRealOutputAndDoNotImplyWorkingJavaHooks() throws Exception {
        final String[] output = new String[1];
        ShellTool failing = new ShellTool(false, project.getPath(), temporary) {
            @Override String runProgram(ToolchainStore.Launcher launcher, List<String> args, boolean temp, int timeout, int epoch) { return output[0]; }
        };
        ToolkitTool target = new ToolkitTool(failing, store, project.getPath(), temporary, "arm64-v8a");
        ToolchainFixtures.configure(store, "objection", new File(root, "objection").getPath(), null);
        for (String override : new String[]{"--host=192.0.2.1", "-h192.0.2.1", "--port=1234", "-P1234", "--network",
                "-N", "--local", "-L", "--serial=remote", "-Sremote", "-dN"}) {
            JSONObject response = new JSONObject(target.run(request("objection", override, "-n", "sample.running.app", "run", "memory list modules")));
            check("error".equals(response.optString("state")) && response.getString("error").contains("私有 Frida server"),
                    "A later Click option could redirect the managed connection: " + response);
        }
        String[][] failures = {{"exit=1\nUnable to find target application.\n", "target_not_running"},
                {"exit=1\nfrida.TimedOutError: unexpectedly timed out while waiting for signal from process with PID 4543\n", "attach_timeout"},
                {"exit=1\n[backcast-frida] {\"phase\":\"rpc\",\"state\":\"failed\"}\nfrida.TimedOutError: RPC timed out\n", "frida_operation_timeout"},
                {"exit=1\n[backcast-frida] {\"phase\":\"server_connect\",\"state\":\"failed\"}\nRuntimeError: server did not become ready\n", "frida_server_unavailable"},
                {"exit=1\n[backcast-frida] {\"phase\":\"attach\",\"state\":\"completed\"}\n[backcast-frida] {\"phase\":\"script_create\",\"state\":\"failed\"}\nScript(line 4): SyntaxError: unexpected character\n", "frida_script_invalid"},
                {"exit=1\n[backcast-frida] {\"phase\":\"rpc\",\"state\":\"failed\"}\nSyntaxError: RPC supplied bad expression\n", "program_failed"},
                {"exit=1\nSyntaxError: invalid Python bootstrap syntax\n", "program_failed"},
                {"exit=1\nfrida.core.RPCException: Error: access violation accessing 0x0\n at tryGetEnvJvmti (/src/index.js:3435)\n", "frida_java_bridge_incompatible"}};
        for (String[] failure : failures) {
            output[0] = failure[0]; JSONObject response = new JSONObject(target.run(request("objection", "-n", "sample.running.app", "run", "android hooking list activities")));
            check(!response.getBoolean("success") && failure[1].equals(response.getString("failure_kind"))
                    && failure[0].equals(response.getString("output")) && response.getString("hint").length() > 20, "Failure was hidden or rewritten into success: " + response);
            if (failure[0].contains("[backcast-frida]")) check(response.getJSONArray("frida_evidence").length() > 0, "Actual phase diagnostics were dropped");
            if ("attach_timeout".equals(failure[1])) check(response.getString("hint").contains("不能证明反调试")
                    && response.getString("hint").contains("尚未加载"), "Native attach timeout was blamed on script/anti-debug");
        }
        output[0] = "exit=0\nobjection: 1.12.5\n"; JSONObject probe = target.status("objection");
        check(probe.getBoolean("ready") && "version".equals(probe.getString("probe_type")) && probe.getString("probe_scope").contains("实际 run"),
                "Version probe implied target attach/Java hook compatibility"); ToolchainFixtures.clear(store, "objection");
    }

    private static void toolCatalogOffersConcreteCorrectParameterShapes() throws Exception {
        for (String id : new String[]{"radare2", "rabin2", "addr2line", "objcopy", "ar", "objection"}) {
            JSONObject entry = ToolCatalog.get(id).json(); check(entry.getJSONArray("argument_examples").length() > 0 && entry.getString("usage").length() > 15,
                    "Model-facing catalog lacks correct invocation details: " + id);
        }
    }

    private static void apktoolDecodeRequiresItsActualManifestAndMetadataWithoutRequiringSmali() throws Exception {
        File executable = new File(root, "apktool");
        ToolchainFixtures.configure(store, "apktool", executable.getPath(), null);
        for (String body : new String[]{"exit 0", "mkdir -p \"$4\"; printf metadata > \"$4/apktool.yml\"",
                "mkdir -p \"$4\"; printf metadata > \"$4/apktool.yml\"; printf manifest > \"$4/AndroidManifest.xml\""}) {
            Files.write(executable.toPath(), ("#!/bin/sh\n" + body + "\n").getBytes("UTF-8")); executable.setExecutable(true);
            String output = "decode-" + System.nanoTime();
            JSONObject result = run("apktool", "d", input.getPath(), "-o", output);
            boolean complete = body.contains("AndroidManifest.xml");
            check(result.getBoolean("success") == complete && (complete || "error".equals(result.optString("state"))
                    && "output_artifact_missing".equals(result.optString("failure_kind")) && result.getString("error").length() > 0),
                    "Exit zero bypassed decode artifact checks or no-smali decoding was rejected: " + result);
        }
        ToolchainFixtures.clear(store, "apktool");
    }

    private static void apktoolBuildRequiresANonemptyZipHeader() throws Exception {
        File executable = new File(root, "apktool"), archive = new File(project, "fixture.apk");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("AndroidManifest.xml")); zip.write("fixture".getBytes("UTF-8")); zip.closeEntry();
        }
        ToolchainFixtures.configure(store, "apktool", executable.getPath(), null);
        String[] bodies = {"touch \"$4\"", "printf 'plain file pretending to be a built apk' > \"$4\"", "cp " + RootShell.quote(archive.getPath()) + " \"$4\""};
        for (int i = 0; i < bodies.length; i++) {
            Files.write(executable.toPath(), ("#!/bin/sh\n" + bodies[i] + "\n").getBytes("UTF-8")); executable.setExecutable(true);
            JSONObject result = run("apktool", "b", project.getPath(), "-o", "build-" + i + ".apk");
            check(result.getBoolean("success") == (i == 2) && (i == 2 || "output_artifact_missing".equals(result.optString("failure_kind"))),
                    "Empty/non-ZIP build became successful or real ZIP was rejected: " + result);
        }
        ToolchainFixtures.clear(store, "apktool");
    }

    private static void remove(File file) throws Exception { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); Files.deleteIfExists(file.toPath()); }
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("backcast-toolkit-operation-tests-").toFile(); project = new File(root, "project"); project.mkdir();
        input = new File(project, "sample.so"); Files.copy(new File("/bin/ls").toPath(), input.toPath());
        temporary = new TemporaryWorkspace(project.getPath(), false, new File(root, "private/materials"), 1); temporary.beginTurn();
        store = new ToolchainStore(new File(root, "private/toolchains"), null, "", 0); toolkit = new ToolkitTool(new ShellTool(false, project.getPath(), temporary), store, project.getPath(), temporary, "arm64-v8a");
        int passed = 0;
        try {
            for (String test : new String[]{"rabinInfoImportsAndEntrypointsAreDistinctFromRadareScripts", "radareAnalysisAndFiltersAreAllowedWhileExecutionAndWritingAreRejected",
                    "radareRequestsExitWithoutWaitingForConsoleInput", "invalidAddr2lineCallsReturnCorrectExamplesBeforeStartingAProgram", "correctAddr2lineAddressesRunTheActualGnuProgram",
                    "objcopyDiscardSymbolsWritesAnExplicitTemporaryOutput", "archiveListingReadsProjectArchivesAndExplainsSharedObjectMisuse", "dynamicFailuresKeepRealOutputAndDoNotImplyWorkingJavaHooks",
                    "toolCatalogOffersConcreteCorrectParameterShapes", "apktoolDecodeRequiresItsActualManifestAndMetadataWithoutRequiringSmali",
                    "apktoolBuildRequiresANonemptyZipHeader"}) {
                ToolkitOperationRegressionTest.class.getDeclaredMethod(test).invoke(null); System.out.println("PASS " + test); passed++;
            }
            System.out.println(passed + " toolkit operation tests passed");
        } finally { temporary.finishTurn(); remove(root); }
    }
}
