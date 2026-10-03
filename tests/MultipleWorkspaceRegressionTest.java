package com.mkei.backcast.tool;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import com.mkei.backcast.agent.ToolRegistry;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real file and shell operations across explicitly authorized project roots. */
public final class MultipleWorkspaceRegressionTest {
    private static File fixture, primary, extra, other;
    private static TemporaryWorkspace temporary;
    private static final ToolchainInstaller.Cancellation LIVE = new ToolchainInstaller.Cancellation() { public void check() { } };
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JSONObject path(File file) throws Exception { return new JSONObject().put("path", file.getPath()); }
    private static JSONObject write(String path, String content, String purpose) throws Exception {
        return new JSONObject().put("path", path).put("content", content).put("purpose", purpose);
    }
    private static void refused(File file) {
        boolean rejected = false;
        try { ToolPaths.resolve(primary.getPath(), file.getPath(), temporary); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Unauthorized path accepted: " + file);
    }
    private static void primaryAndAdditionalRootsUseRealFileTools() throws Exception {
        WriteTool writer = new WriteTool(primary.getPath(), false, temporary);
        check(writer.run(write("same.txt", "primary", "deliverable")).startsWith("已写入"), "Primary write failed");
        File file = new File(extra, "same.txt");
        check(writer.run(write(file.getPath(), "additional", "deliverable")).startsWith("已写入"), "Additional write failed");
        ReadTool reader = new ReadTool(primary.getPath(), false, temporary);
        check(reader.run(new JSONObject().put("path", "same.txt")).contains("primary"), "Relative path used additional directory");
        check(reader.run(path(file)).contains("additional"), "Additional file could not be read");
        EditTool editor = new EditTool(primary.getPath(), false, temporary);
        JSONObject edit = path(file).put("edits", new JSONArray().put(new JSONObject().put("oldText", "additional").put("newText", "edited")));
        editor.run(edit);
        check(new String(Files.readAllBytes(file.toPath()), "UTF-8").equals("edited"), "Additional edit did not reach the file");
    }
    private static void shellReadsBothRootsAndKeepsTheDirectoryBoundary() throws Exception {
        ShellTool shell = new ShellTool(false, primary.getPath(), temporary);
        try {
            String output = shell.run(new JSONObject().put("command", "cat '" + new File(extra, "same.txt").getPath() + "'"));
            check(output.contains("exit=0") && output.contains("edited"), "Shell rejected an authorized extra input");
            output = shell.run(new JSONObject().put("command", "cd '" + extra.getPath() + "' && cat same.txt"));
            check(output.contains("exit=0") && output.contains("edited"), "Shell cwd tracking rejected extra root");
            output = shell.run(new JSONObject().put("command", "cat '" + new File(other, "secret.txt").getPath() + "'"));
            check(output.startsWith("错误：") && !output.contains("outside-secret"), "Shell accessed unauthorized sibling");
        } finally { shell.abort(); }
    }
    private static void toolkitProgramAcceptsExtraInputsButNotUnlistedRoots() throws Exception {
        ShellTool shell = new ShellTool(false, primary.getPath(), temporary);
        ToolchainStore.Launcher launcher = new ToolchainStore.Launcher("readelf", "/bin/cat");
        try {
            String output = shell.runProgram(launcher, Collections.singletonList(new File(extra, "same.txt").getPath()), true, 5);
            check(output.startsWith("exit=0") && output.contains("edited"), "Structured toolkit input rejected extra root");
            output = shell.runProgram(launcher, Collections.singletonList(new File(other, "secret.txt").getPath()), true, 5);
            check(output.startsWith("错误："), "Structured toolkit input accepted unlisted root");
        } finally { shell.abort(); }
    }
    private static void traversalAndSymlinkCannotEscapeTheRootUnion() throws Exception {
        refused(new File(primary, "../other/secret.txt"));
        File linked = new File(extra, "escape"); Files.createSymbolicLink(linked.toPath(), other.toPath());
        refused(new File(linked, "secret.txt"));
        File replaced = new File(fixture, "replacement"); check(replaced.mkdir(), "Cannot create captured root");
        temporary.configureWorkDirs(Arrays.asList(primary.getPath(), extra.getPath(), replaced.getPath()));
        temporary.finishTurn(); temporary.beginTurn();
        check(replaced.delete(), "Cannot replace captured root"); Files.createSymbolicLink(replaced.toPath(), other.toPath());
        refused(new File(replaced, "secret.txt"));
        Files.delete(replaced.toPath());
        temporary.configureWorkDirs(Arrays.asList(primary.getPath(), extra.getPath()));
        temporary.finishTurn(); temporary.beginTurn();
    }
    private static void testsAndExportsUseTheOwningProjectRoot() throws Exception {
        WriteTool writer = new WriteTool(primary.getPath(), false, temporary);
        File test = new File(extra, "tests/retained.txt");
        check(writer.run(write(test.getPath(), "test", "test")).startsWith("已写入"), "Extra root formal test was rejected");
        check(writer.run(write(new File(extra, "loose.txt").getPath(), "test", "test")).startsWith("错误："), "Loose test accepted");
        File source = temporary.resolveTemporary("result.txt"); Files.write(source.toPath(), "result".getBytes("UTF-8"));
        ToolchainStore store = new ToolchainStore(new File(fixture, "toolchains"));
        File target = new File(extra, "delivery/result.txt");
        ToolkitExport.copy(primary.getPath(), temporary, store, source.getPath(), target.getPath(), "deliverable", false, LIVE);
        check(target.isFile(), "Extra project export was not published");
        for (File root : new File[]{primary, extra}) {
            boolean rejected = false;
            try { ToolkitExport.copy(primary.getPath(), temporary, store, source.getPath(), root.getPath(), "deliverable", true, LIVE); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Export targeted an entire authorized project root");
        }
    }
    private static void privateStorageIsStillLimitedToTheCurrentLease() throws Exception {
        File current = temporary.resolveTemporary("owned.txt"); Files.write(current.toPath(), "owned".getBytes("UTF-8"));
        check(ToolPaths.resolve(primary.getPath(), current.getPath(), temporary).equals(current), "Current temporary lease lost access");
        File ledger = new File(fixture, "private/session-1.json"); refused(ledger);
        temporary.configureWorkDirs(Arrays.asList(primary.getPath(), extra.getPath(), new File(fixture, "private").getPath()));
        temporary.finishTurn(); temporary.beginTurn(); refused(ledger);
        temporary.configureWorkDirs(Arrays.asList(primary.getPath(), extra.getPath()));
        temporary.finishTurn(); temporary.beginTurn();
    }
    private static void scopeChangesOnlyAffectTheNextTurn() throws Exception {
        temporary.configureWorkDirs(Collections.singletonList(primary.getPath()));
        check(ToolPaths.resolve(primary.getPath(), new File(extra, "same.txt").getPath(), temporary).isFile(), "Active snapshot changed mid-turn");
        temporary.finishTurn(); temporary.beginTurn(); refused(new File(extra, "same.txt"));
        temporary.configureWorkDirs(Arrays.asList(primary.getPath(), extra.getPath()));
        refused(new File(extra, "same.txt"));
        temporary.finishTurn(); temporary.beginTurn();
        check(ToolPaths.resolve(primary.getPath(), new File(extra, "same.txt").getPath(), temporary).isFile(), "Next turn did not pick up new authorization");
    }
    private static void parallelLeasesKeepTheirOwnScopeSnapshots() throws Exception {
        final CountDownLatch started = new CountDownLatch(1), changed = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(new Runnable() { public void run() {
            temporary.beginTurn(); started.countDown();
            try { changed.await(); check(ToolPaths.resolve(primary.getPath(), new File(extra, "same.txt").getPath(), temporary).isFile(), "Another thread changed active scope"); }
            catch (Throwable error) { failure.set(error); }
            finally { temporary.finishTurn(); }
        } });
        worker.start(); started.await();
        temporary.configureWorkDirs(Collections.singletonList(primary.getPath()));
        temporary.finishTurn(); temporary.beginTurn(); refused(new File(extra, "same.txt"));
        changed.countDown(); worker.join(5000); check(!worker.isAlive(), "Parallel fixture did not stop");
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static void registryRetargetKeepsItsCapturedScopeUntilTheNextTurn() throws Exception {
        temporary.finishTurn();
        ToolRegistry original = new ToolRegistry(), replacement = new ToolRegistry();
        original.register(new TemporaryTool(temporary, primary.getPath(), Arrays.asList(primary.getPath(), extra.getPath())));
        ReadTool originalReader = new ReadTool(primary.getPath(), false, temporary);
        temporary.configure(other.getPath(), false);
        temporary.configureWorkDirs(Collections.singletonList(other.getPath()));
        replacement.register(new TemporaryTool(temporary, other.getPath(), Collections.singletonList(other.getPath())));
        original.beginTurn();
        check(originalReader.run(path(new File(extra, "same.txt"))).contains("edited"), "Registry scope was replaced before its turn began");
        refused(new File(other, "secret.txt"));
        check(original.cleanupTemporary(true) == null, "Original registry did not release its lease");
        replacement.beginTurn();
        check(new ReadTool(other.getPath(), false, temporary).run(path(new File(other, "secret.txt"))).contains("outside-secret"),
                "Replacement registry did not receive its own primary root");
        check(new ReadTool(other.getPath(), false, temporary).run(path(new File(extra, "same.txt"))).startsWith("错误："),
                "Replacement registry retained the previous additional root");
        check(replacement.cleanupTemporary(true) == null, "Replacement registry did not release its lease");
        temporary.configure(primary.getPath(), false);
        temporary.configureWorkDirs(Arrays.asList(primary.getPath(), extra.getPath()));
        temporary.beginTurn();
    }
    private static void remove(File file) throws Exception {
        if (!Files.isSymbolicLink(file.toPath())) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); }
        Files.deleteIfExists(file.toPath());
    }
    public static void main(String[] args) throws Exception {
        fixture = Files.createTempDirectory("backcast-multiple-workspaces-").toFile();
        primary = new File(fixture, "primary"); extra = new File(fixture, "additional"); other = new File(fixture, "other");
        try {
            check(primary.mkdir() && extra.mkdir() && other.mkdir(), "Cannot create fixture roots");
            Files.write(new File(other, "secret.txt").toPath(), "outside-secret".getBytes("UTF-8"));
            temporary = new TemporaryWorkspace(primary.getPath(), false, new File(fixture, "private"), 1);
            temporary.configureWorkDirs(Arrays.asList(primary.getPath(), extra.getPath())); temporary.beginTurn();
            int passed = 0;
            for (String name : new String[]{"primaryAndAdditionalRootsUseRealFileTools", "shellReadsBothRootsAndKeepsTheDirectoryBoundary",
                    "toolkitProgramAcceptsExtraInputsButNotUnlistedRoots", "traversalAndSymlinkCannotEscapeTheRootUnion",
                    "testsAndExportsUseTheOwningProjectRoot", "privateStorageIsStillLimitedToTheCurrentLease",
                    "scopeChangesOnlyAffectTheNextTurn", "registryRetargetKeepsItsCapturedScopeUntilTheNextTurn", "parallelLeasesKeepTheirOwnScopeSnapshots"}) {
                MultipleWorkspaceRegressionTest.class.getDeclaredMethod(name).invoke(null); System.out.println("PASS " + name); passed++;
            }
            System.out.println(passed + " multiple workspace integration tests passed");
        } finally { if (temporary != null) temporary.finishTurn(); remove(fixture); }
    }
}
