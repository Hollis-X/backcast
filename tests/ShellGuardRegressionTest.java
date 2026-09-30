package com.mkei.backcast.tool;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;

/** Replays shell guard failures from Android without depending on desktop extensions. */
public final class ShellGuardRegressionTest {
    private static final String ERROR = "\u9519\u8bef\uff1a";
    private static File project, state;
    private static TemporaryWorkspace temporary;
    private static ShellTool shell;
    private static int passed;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String run(String command, boolean temporaryCommand) throws Exception {
        return shell.run(new JSONObject().put("command", command).put("temporary", temporaryCommand));
    }

    private static void succeeds(String command, String output) throws Exception {
        String result = run(command, false);
        check(result.startsWith("exit=0") && result.contains(output), "Rejected valid command: " + command + "\n" + result);
    }

    private static void rejects(String command, boolean temporaryCommand) throws Exception {
        rejectsPreflight(command, temporaryCommand);
        String result = run(command, temporaryCommand);
        check(result.startsWith(ERROR), "Accepted forbidden command: " + command + "\n" + result);
    }

    private static void rejectsPreflight(String command, boolean temporaryCommand) throws Exception {
        boolean rejected = false;
        try {
            guard(command, temporaryCommand);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "Preflight accepted forbidden command: " + command);
    }

    private static void guard(String command, boolean temporaryCommand) throws Exception {
        ToolPaths.checkCommand(project.getPath(), command, temporary, temporaryCommand);
        if (temporaryCommand) ToolPaths.checkTemporaryCommand(temporary.directory().getPath(), command);
    }

    private static String quote(File file) {
        return RootShell.quote(file.getAbsolutePath());
    }

    private static void devNullAndFileDescriptors() throws Exception {
        succeeds("printf ignored >/dev/null 2>&1; printf discard-ok", "discard-ok");
        succeeds("printf ignored >>/dev/null; wc -c </dev/null", "0");
        succeeds("printf fd-ok 1>&2", "fd-ok");
        rejects("printf nope >/dev/random", false);
        rejects("printf nope >/dev/null/../random", false);
    }

    private static void quotedSearchPrograms() throws Exception {
        succeeds("grep -o '/>' fixture.xml", "/>");
        succeeds("sed -n '/<defs>/,/<\\/defs>/p' fixture.xml", "<defs>");
        succeeds("sed -n -e '/<defs>/,/<\\/defs>/p' fixture.xml", "<defs>");
        succeeds("grep -F -e '/>' fixture.xml", "/>");
        succeeds("grep -nE '/>|<defs>' fixture.xml", "<defs>");
    }

    private static void multilineAwkAndEscapedQuotes() throws Exception {
        succeeds("awk '\nBEGIN {\n if (2 > 1) print \"multiline-ok\";\n if (1 < 2) print \"comparison-ok\"\n}\n'", "comparison-ok");
        succeeds("awk '\n/<defs>/ { print \"regex-ok\" }\n' fixture.xml", "regex-ok");
        succeeds("printf '%s\\n' \"escaped\\\"quote\" | grep -F 'escaped\"quote'", "escaped\"quote");
    }

    private static void optionArgumentsDoNotConsumeSearchPrograms() throws Exception {
        succeeds("awk -F , '/SVG/ { print $2 }' expressions.txt", "matched");
        succeeds("grep -A 2 '/SVG/' expressions.txt", "context");
        succeeds("grep -B 2 -C 1 -m 1 '/SVG/' expressions.txt", "matched");
        succeeds("grep -nA 2 '/SVG/' expressions.txt", "context");
        succeeds("grep -nF '/SVG/' expressions.txt", "matched");
        succeeds("grep -ne '/SVG/' expressions.txt", "matched");
        succeeds("sed -ne '/<defs>/p' fixture.xml", "<defs>");
        String outside = quote(new File(project.getParentFile(), "outside.txt"));
        rejects("awk -F , '/SVG/' " + outside, false);
        rejects("grep -A 2 '/SVG/' " + outside, false);
    }

    private static void expressionExemptionDoesNotAllowOutsideFiles() throws Exception {
        File outside = new File(project.getParentFile(), "outside.txt");
        String target = quote(outside);
        rejects("grep -F '/>' " + target, false);
        rejects("grep -f " + target + " fixture.xml", false);
        rejects("grep -nf " + target + " fixture.xml", false);
        rejects("grep -nf" + target + " fixture.xml", false);
        rejects("sed -f " + target + " fixture.xml", false);
        rejects("sed -nf " + target + " fixture.xml", false);
        rejects("sed -nf" + target + " fixture.xml", false);
        rejects("sed -ni 's/a/b/' fixture.xml", false);
        rejects("awk -f " + target + " fixture.xml", false);
        rejects("awk --file=program.awk " + target, false);
        rejects("gawk --file=program.awk " + target, false);
        rejectsPreflight("awk -e '{ print }' " + target, false);
        rejectsPreflight("gawk -e '{ print }' " + target, false);
        rejectsPreflight("gawk --source '{ print }' " + target, false);
        rejectsPreflight("gawk --source='{ print }' " + target, false);
        rejects("awk 'BEGIN { print 1 }' " + target, false);
    }

    private static void projectRedirectsStayForbidden() throws Exception {
        File leak = new File(project, "redirect-leak.txt");
        rejects("printf leak > redirect-leak.txt", false);
        rejects("printf leak >> " + quote(leak), false);
        check(!leak.exists(), "Rejected redirect wrote a project file");
    }

    private static void registeredTemporaryRedirectsWork() throws Exception {
        File temp = temporary.directory();
        String result = run("printf first > report.txt; printf second >> report.txt; wc -c < report.txt", true);
        check(result.startsWith("exit=0") && result.contains("11"), "Temporary redirection was refused: " + result);
        check("firstsecond".equals(new String(Files.readAllBytes(new File(temp, "report.txt").toPath()), "UTF-8")),
                "Temporary output was not written inside the allocation");
        rejects("printf forbidden > " + quote(new File(temp, "ordinary-mode.txt")), false);
        File marker = new File(temp, ".backcast-owner");
        byte[] owner = Files.readAllBytes(marker.toPath());
        try {
            rejects("printf forbidden > " + quote(marker), true);
        } finally {
            Files.write(marker.toPath(), owner);
        }
    }

    private static void temporaryReadOnlyCdWorks() throws Exception {
        String result = run("cd " + quote(project) + " && grep -c '<defs>' fixture.xml", true);
        check(result.startsWith("exit=0") && result.contains("1"), "Read-only project cd was refused: " + result);
        File temp = temporary.directory();
        result = run("cd " + quote(project) + " && printf temp-from-project > " + quote(new File(temp, "from-project.txt")), true);
        check(result.startsWith("exit=0"), "Explicit temporary output after cd was refused: " + result);
    }

    private static void temporaryCdCannotLeakRelativeOutputs() throws Exception {
        rejects("cd " + quote(project) + " && touch cd-leak.txt", true);
        rejects("cd " + quote(project) + " && printf leak > cd-leak.txt", true);
        rejects("cd " + quote(project) + " && cp fixture.xml copied-leak.xml", true);
        check(!new File(project, "cd-leak.txt").exists() && !new File(project, "copied-leak.xml").exists(),
                "Temporary command leaked output after changing directory");
    }

    private static void assignmentPrefixCannotBypassTemporaryOutputs() throws Exception {
        File source = new File(project, "fixture.xml");
        File leaked = new File(project, "prefix-leak.xml");
        rejects("LC_ALL=C cp " + quote(source) + " " + quote(leaked), true);
        rejects("LC_ALL=C touch " + quote(leaked), true);
        String result = run("LC_ALL=C cp " + quote(source) + " prefixed-copy.xml", true);
        check(result.startsWith("exit=0") && new File(temporary.directory(), "prefixed-copy.xml").isFile(),
                "Assignment prefix blocked a valid temporary copy: " + result);
        check(!leaked.exists(), "Assignment prefix wrote a project output");
    }

    private static void compilerDefaultOutputsStayInTemporaryDirectory() throws Exception {
        String cwd = "cd " + quote(project) + " && ";
        rejects(cwd + "cc probe.c", true);
        rejects(cwd + "cc -c probe.c", true);
        rejects(cwd + "javac Probe.java", true);
        rejects("javac " + quote(new File(project, "Probe.java")), true);
        guard(cwd + "cc -fsyntax-only probe.c", true);
        guard(cwd + "cc -E probe.c", true);
        guard(cwd + "cc probe.c -o " + quote(new File(temporary.directory(), "probe")), true);
        guard(cwd + "javac -d " + quote(temporary.directory()) + " Probe.java", true);
        check(!new File(project, "a.out").exists() && !new File(project, "Probe.class").exists(),
                "Compiler guard checks wrote project outputs");
    }

    private static void middleParentTraversalIsChecked() throws Exception {
        File nested = new File(project, "nested"); nested.mkdir();
        rejects("cat nested/../../outside.txt", false);
        rejects("cat nested/../../private/session-1.json", false);
        guard("cat nested/../fixture.xml", false);
    }

    private static void subshellCdRestoresParentDirectory() throws Exception {
        String result = run("(cd " + quote(project) + "); touch after-subshell.tmp; pwd", true);
        File temp = temporary.directory();
        check(result.startsWith("exit=0") && result.contains(temp.getPath())
                && new File(temp, "after-subshell.tmp").isFile(), "Subshell cd changed its parent's temporary cwd: " + result);
        check(!new File(project, "after-subshell.tmp").exists(), "Subshell temporary output leaked to the project");
    }

    private static void failedCdCannotLeakRelativeOutputs() throws Exception {
        File temp = temporary.directory();
        File leak = new File(temp.getParentFile(), "failed-cd-leak.tmp");
        rejects("cd missing; cat ../outside.txt", false);
        rejects("cd missing/subdir; touch ../failed-cd-leak.tmp", true);
        rejects("cd missing/subdir; printf nope > ../failed-cd-leak.tmp", true);
        String doubleCd = "cd " + quote(project) + "; cd " + quote(new File(temp, "missing")) + "; ";
        rejects(doubleCd + "touch double-cd-leak.tmp", true);
        File safe = new File(temp, "failed-cd-safe.tmp");
        String result = run("cd missing/subdir; touch " + quote(safe), true);
        check(result.startsWith("exit=0") && safe.isFile(), "Explicit temporary output after failed cd was refused: " + result);
        File safeRedirect = new File(temp, "double-cd-safe.tmp");
        result = run(doubleCd + "printf safe > " + quote(safeRedirect), true);
        check(result.startsWith("exit=0") && safeRedirect.isFile(), "Explicit temporary redirect after double cd was refused: " + result);
        check(!leak.exists() && !new File(project, "double-cd-leak.tmp").exists(), "Failed cd leaked output outside the temporary directory");
    }

    private static void commandSubstitutionCannotEscapePathChecks() throws Exception {
        String outside = quote(new File(project.getParentFile(), "outside.txt"));
        rejects("printf '%s' $(cat " + outside + ")", false);
        rejects("printf '%s' \"$(cat " + outside + ")\"", false);
        rejects("printf '%s' \"$(printf '%s' \"$(cat " + outside + ")\")\"", false);
        rejects("printf '%s' $(touch " + quote(new File(project, "substitution-leak.tmp")) + ")", true);
        rejects("printf '%s' \"$(cd " + quote(project) + " && touch substitution-leak.tmp)\"", true);
        succeeds("printf '%s' \"$(grep -c '<defs>' fixture.xml)\"", "1");
        succeeds("printf '%s' '$(cat " + new File(project.getParentFile(), "outside.txt").getPath() + ")'", "$(cat ");
        check(!new File(project, "substitution-leak.tmp").exists(), "Command substitution wrote a project output");
    }

    private static void executablePathsAndBackticksAreChecked() throws Exception {
        String outside = quote(new File(project.getParentFile(), "outside.txt"));
        rejects("printf '%s' `cat " + outside + "`", false);
        rejects("printf '%s' \"`cat " + outside + "`\"", false);
        succeeds("printf '%s' '`literal`'", "`literal`");
        succeeds("printf '%s' \\`literal\\`", "`literal`");
        succeeds("printf '%s' \"\\`literal\\`\"", "`literal`");
        rejects(quote(new File(project.getParentFile(), "outside/script.sh")), false);
        guard(quote(new File(project, "script.sh")), false);
    }

    private static void privateSiblingAndLedgerStayInaccessible() throws Exception {
        TemporaryWorkspace other = new TemporaryWorkspace(project.getPath(), false, state, 2);
        other.beginTurn();
        try {
            File sibling = other.directory();
            rejects("ls " + quote(tempParent()), false);
            rejects("ls " + quote(sibling), false);
            rejects("printf nope > " + quote(new File(sibling, "leak.txt")), true);
            rejects("printf nope > " + quote(new File(state, "session-2.json")), true);
            check(!new File(sibling, "leak.txt").exists(), "Temporary command wrote to another session");
        } finally {
            check(other.finishTurn() == null, "Other session fixture did not clean up");
        }
    }

    private static File tempParent() throws Exception {
        return temporary.directory().getParentFile();
    }

    private static void remove(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        Files.deleteIfExists(file.toPath());
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("backcast-shell-guard-").toFile();
        project = new File(root, "project"); state = new File(root, "private");
        project.mkdir(); state.mkdir();
        Files.write(new File(project, "fixture.xml").toPath(), "<svg>\n<defs>\n<path/>\n</defs>\n</svg>\n".getBytes("UTF-8"));
        Files.write(new File(project, "expressions.txt").toPath(), "/SVG/,matched\ncontext\n".getBytes("UTF-8"));
        Files.write(new File(project, "program.awk").toPath(), "{ print }\n".getBytes("UTF-8"));
        temporary = new TemporaryWorkspace(project.getPath(), false, state, 1);
        temporary.beginTurn();
        shell = new ShellTool(false, project.getPath(), temporary);
        try {
            int failures = 0;
            for (String name : new String[]{"devNullAndFileDescriptors", "quotedSearchPrograms",
                    "multilineAwkAndEscapedQuotes", "optionArgumentsDoNotConsumeSearchPrograms",
                    "expressionExemptionDoesNotAllowOutsideFiles",
                    "projectRedirectsStayForbidden", "registeredTemporaryRedirectsWork",
                    "temporaryReadOnlyCdWorks", "temporaryCdCannotLeakRelativeOutputs",
                    "assignmentPrefixCannotBypassTemporaryOutputs", "compilerDefaultOutputsStayInTemporaryDirectory",
                    "middleParentTraversalIsChecked", "subshellCdRestoresParentDirectory",
                    "failedCdCannotLeakRelativeOutputs",
                    "commandSubstitutionCannotEscapePathChecks",
                    "executablePathsAndBackticksAreChecked",
                    "privateSiblingAndLedgerStayInaccessible"}) {
                try {
                    ShellGuardRegressionTest.class.getDeclaredMethod(name).invoke(null);
                    passed++;
                    System.out.println("PASS " + name);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    failures++;
                    System.out.println("FAIL " + name + ": " + failure.getCause());
                }
            }
            check(temporary.finishTurn() == null, "Temporary command fixtures did not clean up");
            check(failures == 0, failures + " shell guard groups failed");
            System.out.println(passed + " shell guard tests passed");
        } finally {
            temporary.finishTurn();
            remove(root);
        }
    }
}
