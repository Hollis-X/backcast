package com.mkei.backcast.tool;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Managed diagnostic results never grant generic shell access to app-private files. */
public final class ToolkitDiagnosticsRegressionTest {
    private static File root, project, nativeTools, common;
    private static ToolchainStore store;
    private static TemporaryWorkspace temporary;
    private static ToolkitTool toolkit;
    private static final List<ToolchainStore.Launcher> observed = new ArrayList<ToolchainStore.Launcher>();
    private static String serverOutput = "exit=0\n17.2.14\n", clientOutput = serverOutput;
    private static boolean cancelAfterServer;

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JSONObject run(String action) throws Exception {
        return new JSONObject(toolkit.run(new JSONObject().put("action", action).put("tool", "objection")));
    }
    private static void register() throws Exception {
        store.bundledInstalled(common, nativeTools, new JSONObject().put("version", "fixture")
                .put("java_bridge", "7.0.13-backcast.1").put("objection_art_mode", "jni-no-jvmti"), "arm64-v8a");
    }

    private static void modelMetadataOmitsBundledBootstrapWithoutChangingExecution() throws Exception {
        register();
        JSONObject stored = store.configuration("objection");
        String bootstrap = stored.getJSONArray("prefix").getString(1);
        JSONObject status = run("status");
        JSONObject visible = status.getJSONObject("configuration");
        check(!visible.has("prefix") && !visible.has("environment") && visible.getBoolean("managed_private")
                && !visible.getBoolean("generic_shell_access"), "Status exposed the implementation or implied private shell access");
        check("jni-no-jvmti".equals(visible.getString("art_mode")) && status.getString("probe_scope").contains("实际 run"),
                "Compatibility mode or probe limits are absent");
        JSONArray listing = toolkit.listing().getJSONArray("tools");
        for (int i = 0; i < listing.length(); i++) {
            JSONObject config = listing.getJSONObject(i).getJSONObject("configuration");
            check(!config.has("prefix") && !config.has("environment"), "List exposed a bundled launcher script");
        }
        check(bootstrap.equals(store.launcher("objection", ToolchainFixtures.LIVE).prefix.get(1))
                && store.launcher("objection", ToolchainFixtures.LIVE).environment.has("PYTHONPATH"), "Sanitizing presentation mutated the stored launcher");
    }

    private static void customConfigurationRemainsVisible() throws Exception {
        File custom = new File(root, "custom/bin/objection"); custom.getParentFile().mkdirs(); Files.write(custom.toPath(), new byte[]{1});
        ToolchainFixtures.configure(store, "objection", custom.getPath(), "");
        JSONObject visible = run("status").getJSONObject("configuration");
        check(visible.getString("path").equals(custom.getPath()) && !visible.has("managed_private"), "User configuration was hidden or relabeled as managed");
        register();
    }

    private static void diagnosticsUseExactManagedEntriesAndNeverAttachATarget() throws Exception {
        observed.clear();
        JSONObject result = run("diagnose");
        check(result.getBoolean("ready") && result.getBoolean("frida_versions_match")
                && "diagnostic_ready".equals(result.getString("state")) && "not_tested".equals(result.getString("java_attach")),
                "Matching version diagnostics falsely failed or pretended to attach: " + result);
        check(observed.size() == 3 && observed.get(1).executable.equals(new File(nativeTools, "usr/bin/frida-server").getPath())
                && observed.get(1).prefix.isEmpty() && observed.get(1).companion.length() == 0
                && observed.get(2).prefix.get(1).equals("import frida; print(frida.__version__)")
                && observed.get(2).companion.length() == 0, "Diagnostics reused the attach bootstrap or a user-supplied path");
        check(result.getJSONObject("frida_server").getLong("bytes") == 3
                && "17.2.14".equals(result.getJSONObject("frida_client").getString("version")), "Actual entry metadata was omitted");
    }

    private static void mismatchAndCrashesNeverReportReady() throws Exception {
        clientOutput = "exit=0\n17.22.0\n";
        JSONObject mismatch = run("diagnose");
        check(!mismatch.getBoolean("ready") && "frida_version_mismatch".equals(mismatch.getString("failure_kind")), "Client/server mismatch was declared ready");
        clientOutput = "exit=0\n17.2.14\nTraceback (most recent call last): failed\n";
        JSONObject crash = run("diagnose");
        check(!crash.getBoolean("ready") && "frida_runtime_unavailable".equals(crash.getString("failure_kind"))
                && crash.getJSONObject("frida_client").getString("probe_output").equals(clientOutput), "A numeric line masked a real runtime error");
        clientOutput = serverOutput;
    }

    private static void diagnosticEntryDoesNotLoosenShellOrSymlinkBoundaries() throws Exception {
        File server = new File(nativeTools, "usr/bin/frida-server");
        boolean rejected = false;
        try { ToolPaths.checkCommand(project.getPath(), "ls -l " + server.getPath(), temporary, false); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Managed diagnostics opened private paths to generic shell");
        File outside = new File(root, "outside-server"); Files.write(outside.toPath(), new byte[]{1});
        Files.delete(server.toPath()); Files.createSymbolicLink(server.toPath(), outside.toPath());
        observed.clear();
        check("error".equals(run("diagnose").getString("state")) && observed.isEmpty(), "Managed server symlink escaped verification and started a probe");
        Files.delete(server.toPath()); Files.write(server.toPath(), new byte[]{1, 2, 3}); server.setExecutable(true);
    }

    private static void cancellationStopsBeforeTheNextProbe() throws Exception {
        observed.clear(); cancelAfterServer = true;
        JSONObject result = run("diagnose");
        check("cancelled".equals(result.getString("state")) && observed.size() == 2, "Cancelled diagnostics started the client probe");
        cancelAfterServer = false; Thread.interrupted();
    }

    private static void remove(File file) throws Exception {
        if (!Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        }
        Files.deleteIfExists(file.toPath());
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("backcast-tool-diagnostics-").toFile();
        project = new File(root, "project"); project.mkdir();
        store = new ToolchainStore(new File(root, "private/toolchains"), null, "", 0);
        nativeTools = new File(store.root(), "native"); common = new File(store.root(), "common");
        File bin = new File(nativeTools, "usr/bin"); bin.mkdirs(); common.mkdirs();
        for (String name : new String[]{"frida-server", "python3"}) {
            File file = new File(bin, name); Files.write(file.toPath(), new byte[]{1, 2, 3}); file.setExecutable(true);
        }
        temporary = new TemporaryWorkspace(project.getPath(), false, new File(root, "private/temporary-workspaces"), 1);
        temporary.beginTurn();
        ShellTool probe = new ShellTool(false, project.getPath(), temporary) {
            @Override String runProgram(ToolchainStore.Launcher launcher, List<String> arguments, boolean temp, int timeout, int mine) {
                observed.add(launcher);
                check(arguments.size() == 1 && ("version".equals(arguments.get(0)) || "--version".equals(arguments.get(0))),
                        "Diagnostic probe attempted a target operation");
                if (launcher.executable.endsWith("frida-server")) {
                    if (cancelAfterServer) toolkit.abort();
                    return serverOutput;
                }
                return launcher.prefix.size() == 2 && launcher.prefix.get(1).equals("import frida; print(frida.__version__)")
                        ? clientOutput : "exit=0\nobjection: 1.12.5\n";
            }
        };
        toolkit = new ToolkitTool(probe, store, project.getPath(), temporary, "arm64-v8a");
        try {
            int passed = 0;
            for (String name : new String[]{"modelMetadataOmitsBundledBootstrapWithoutChangingExecution", "customConfigurationRemainsVisible",
                    "diagnosticsUseExactManagedEntriesAndNeverAttachATarget", "mismatchAndCrashesNeverReportReady",
                    "diagnosticEntryDoesNotLoosenShellOrSymlinkBoundaries", "cancellationStopsBeforeTheNextProbe"}) {
                ToolkitDiagnosticsRegressionTest.class.getDeclaredMethod(name).invoke(null);
                passed++; System.out.println("PASS " + name);
            }
            System.out.println(passed + " toolkit diagnostics tests passed");
        } finally { temporary.finishTurn(); remove(root); }
    }
}
