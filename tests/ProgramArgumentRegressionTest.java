package com.mkei.backcast.tool;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercise literal argv values through the real shell and the registered Objection bootstrap. */
public final class ProgramArgumentRegressionTest {
    private static File root;
    private static TemporaryWorkspace temporary;
    private static ShellTool shell;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static String text(String output) {
        check(output.startsWith("exit=0\n"), "Program failed: " + output);
        return output.substring(7).trim();
    }
    private static ToolchainStore.Launcher python(String id, String program) {
        ToolchainStore.Launcher launcher = new ToolchainStore.Launcher(id, "/usr/bin/python3");
        launcher.prefix.add("-c"); launcher.prefix.add(program);
        return launcher;
    }
    private static void literalProgramAndArgumentsSurviveTheShell() throws Exception {
        String marker = new File(temporary.directory(), "injected").getPath();
        String value = "quote'\"; $(touch " + marker + ") `touch " + marker + "`";
        String program = "import json,sys\nprint(json.dumps(sys.argv[1:]))\n";
        String output = shell.runProgram(python("objection", program), Arrays.asList("version", value), true, 5, shell.cancellationEpoch());
        JSONArray actual = new JSONArray(text(output));
        check(actual.length() == 2 && "version".equals(actual.getString(0)) && value.equals(actual.getString(1)),
                "Structured argument was split or changed: " + output);
        check(!new File(marker).exists(), "Quoted shell syntax executed as a command");
    }
    private static void registeredObjectionBootstrapRunsBeforeVersion() throws Exception {
        File common = new File(root, "common"), nativeTools = new File(root, "native");
        ToolchainStore store = new ToolchainStore(new File(root, "registry"), null, "", 0);
        store.bundledInstalled(common, nativeTools, new JSONObject().put("version", "fixture"), "arm64-v8a");
        JSONArray prefix = store.configuration("objection").getJSONArray("prefix");
        String bootstrap = prefix.getString(1);
        check(bootstrap.contains("\n") && "-c".equals(prefix.getString(0)), "Fixture did not use the production bootstrap");
        File modules = new File(root, "modules"), console = new File(modules, "objection/console");
        check(console.mkdirs(), "Could not create fixture modules");
        Files.write(new File(modules, "frida.py").toPath(), new byte[0]);
        Files.write(new File(modules, "objection/__init__.py").toPath(), new byte[0]);
        Files.write(new File(console, "__init__.py").toPath(), new byte[0]);
        Files.write(new File(console, "cli.py").toPath(), ("import json,os\n"
                + "def cli(args,prog_name):\n"
                + " assert os.path.isfile(os.path.join(os.path.expanduser('~'),'.objection','version_info'))\n"
                + " print(json.dumps({'args':args,'program':prog_name}))\n").getBytes("UTF-8"));
        ToolchainStore.Launcher launcher = python("objection", bootstrap);
        launcher.environment.put("PYTHONPATH", modules.getPath());
        JSONObject actual = new JSONObject(text(shell.runProgram(launcher, Collections.singletonList("version"), true, 5, shell.cancellationEpoch())));
        check("objection".equals(actual.getString("program")), "Production CLI bootstrap was not invoked");
        check("version".equals(actual.getJSONArray("args").getString(5)), "Version argument did not reach CLI");
    }
    private static void invalidNulNeverStartsTheProgram() throws Exception {
        for (boolean inPrefix : new boolean[]{false, true}) {
            File marker = new File(temporary.directory(), "nul-" + inPrefix);
            ToolchainStore.Launcher launcher = python("objection", "open(" + JSONObject.quote(marker.getPath()) + ",'w').close()");
            if (inPrefix) launcher.prefix.set(1, launcher.prefix.get(1) + "\0");
            boolean refused = false;
            try {
                String output = shell.runProgram(launcher, Collections.singletonList(inPrefix ? "version" : "version\0"), true, 5, shell.cancellationEpoch());
                refused = !inPrefix && output.startsWith("错误：工具参数不合法。");
            }
            catch (IllegalArgumentException expected) { refused = expected.getMessage().contains("NUL"); }
            check(refused && !marker.exists(), "NUL argument started a program or was silently truncated");
        }
    }
    private static void pathsStillRejectNewlines() {
        for (String path : new String[]{"/private/a\nb", "/private/a\rb"}) {
            boolean refused = false;
            try { RootShell.quote(path); } catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "File path quoting now permits line breaks");
        }
    }
    private static void remove(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (file.exists() && !file.delete()) throw new IllegalStateException("Could not remove fixture " + file);
    }
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("backcast-program-argv-").toFile();
        try {
            temporary = new TemporaryWorkspace(root.getPath(), false, new File(root, "private"), 1);
            temporary.beginTurn(); shell = new ShellTool(false, root.getPath(), temporary);
            literalProgramAndArgumentsSurviveTheShell();
            System.out.println("PASS multiline program and quoted argv reach one process without shell expansion");
            registeredObjectionBootstrapRunsBeforeVersion();
            System.out.println("PASS actual registered Objection bootstrap reaches the version CLI");
            invalidNulNeverStartsTheProgram();
            System.out.println("PASS NUL in bootstrap or argv is rejected before execution");
            pathsStillRejectNewlines();
            System.out.println("PASS strict path quoting still refuses line breaks");
            System.out.println("4 program argument tests passed");
        } finally {
            if (shell != null) shell.abort();
            if (temporary != null) temporary.finishTurn();
            remove(root);
        }
    }
}
