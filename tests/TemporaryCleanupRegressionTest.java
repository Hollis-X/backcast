import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.Goal;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.ToolRegistry;
import com.mkei.backcast.tool.TemporaryTool;
import com.mkei.backcast.tool.TemporaryWorkspace;
import com.mkei.backcast.tool.WriteTool;
import com.mkei.backcast.tool.ShellTool;
import com.mkei.backcast.tool.ReadTool;
import com.mkei.backcast.tool.EditTool;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Temporary material ownership, process recovery, and turn lifecycle. */
public final class TemporaryCleanupRegressionTest {
    private static final String ERROR = "\u9519\u8bef\uff1a";
    private static int passed;
    private static File project;
    private static File state;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void pass(String name) {
        passed++;
        System.out.println("PASS " + name);
    }
    private static JSONObject write(String path, String purpose) throws Exception {
        return new JSONObject().put("path", path).put("purpose", purpose).put("content", "fixture");
    }
    private static void remove(File file) throws Exception {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            for (File child : file.listFiles()) remove(child);
        }
        Files.deleteIfExists(file.toPath());
    }
    private static TemporaryWorkspace manager(long sid) {
        return new TemporaryWorkspace(project.getPath(), false, state, sid);
    }

    private static void managedMaterialsOnly() throws Exception {
        TemporaryWorkspace materials = manager(1);
        materials.beginTurn();
        WriteTool writer = new WriteTool(project.getPath(), false, materials);
        File temp = materials.directory();
        check(temp.getCanonicalPath().startsWith(new File(state, "materials").getCanonicalPath() + File.separator),
                "Temporary allocation was not inside App private storage");
        check(project.listFiles().length == 0, "Allocation created temporary materials in the project");
        check(!writer.run(write("probe.py", "temporary")).startsWith(ERROR), "Temporary write failed");
        check(new File(temp, "probe.py").isFile(), "Temporary write escaped the allocated directory");
        check(!writer.run(write("tests/regression.py", "test")).startsWith(ERROR), "Formal test write failed");
        check(writer.run(write("root_test.py", "test")).startsWith(ERROR), "Formal test was accepted loose in project root");
        check(!writer.run(write("app/src/androidTest/Fixture.java", "test")).startsWith(ERROR), "Android test directory was refused");
        check(!writer.run(write("result.txt", "deliverable")).startsWith(ERROR), "Deliverable write failed");
        check(writer.run(write(new File(project, "misplaced.py").getPath(), "temporary")).startsWith(ERROR),
                "Absolute temporary path outside allocated directory was accepted");
        check(writer.run(write(new File(temp, "fake-test.py").getPath(), "test")).startsWith(ERROR),
                "Formal test was accepted inside disposable temporary directory");
        check(materials.finishTurn() == null && !temp.exists(), "Managed directory was not removed");
        check(new File(project, "tests/regression.py").isFile() && new File(project, "result.txt").isFile(),
                "Cleanup removed formal tests or deliverables");
        pass("managedMaterialsOnly");
    }

    private static void privateFileAccessIsScoped() throws Exception {
        TemporaryWorkspace materials = manager(18), other = manager(19);
        materials.beginTurn(); other.beginTurn();
        File temp = materials.directory(), otherTemp = other.directory();
        WriteTool writer = new WriteTool(project.getPath(), false, materials);
        check(!writer.run(write("probe.txt", "temporary")).startsWith(ERROR), "Private fixture write failed");
        File file = new File(temp, "probe.txt");
        ReadTool reader = new ReadTool(project.getPath(), false, materials);
        EditTool editor = new EditTool(project.getPath(), false, materials);
        check(reader.run(new JSONObject().put("path", file.getPath())).contains("fixture"),
                "Current turn cannot read its private temporary file");
        JSONObject edit = new JSONObject().put("path", file.getPath()).put("edits", new JSONArray()
                .put(new JSONObject().put("oldText", "fixture").put("newText", "updated")));
        check(!editor.run(edit).startsWith(ERROR), "Current turn cannot edit its private temporary file");
        check(reader.run(new JSONObject().put("path", file.getPath())).contains("updated"), "Private edit was not written");
        check(!writer.run(new JSONObject().put("path", "probe.sh").put("purpose", "temporary")
                .put("content", "printf private-script-success")).startsWith(ERROR), "Private shell fixture write failed");
        ShellTool shell = new ShellTool(false, project.getPath(), materials);
        String shellOutput = shell.run(new JSONObject().put("command", "sh '" + new File(temp, "probe.sh").getPath() + "'"));
        check(shellOutput.startsWith("exit=0") && shellOutput.contains("private-script-success"), "Shell cannot execute current private script by absolute path");
        File foreign = new File(otherTemp, "other.txt"); Files.write(foreign.toPath(), "fixture".getBytes("UTF-8"));
        check(reader.run(new JSONObject().put("path", foreign.getPath())).startsWith(ERROR), "Other session private read was accepted");
        check(editor.run(new JSONObject(edit.toString()).put("path", foreign.getPath())).startsWith(ERROR),
                "Other session private edit was accepted");
        File unrelated = new File(state, "unrelated.txt"); Files.write(unrelated.toPath(), "secret".getBytes("UTF-8"));
        check(reader.run(new JSONObject().put("path", unrelated.getPath())).startsWith(ERROR), "Unrelated App private data was readable");
        File marker = new File(temp, ".backcast-owner");
        JSONObject markerEdit = new JSONObject().put("path", marker.getPath()).put("edits", new JSONArray()
                .put(new JSONObject().put("oldText", new String(Files.readAllBytes(marker.toPath()), "UTF-8"))
                .put("newText", "forged-owner")));
        check(editor.run(markerEdit).startsWith(ERROR), "Ownership marker edit was accepted");
        File escape = new File(temp, "escape"); Files.createSymbolicLink(escape.toPath(), unrelated.toPath());
        check(reader.run(new JSONObject().put("path", escape.getPath())).startsWith(ERROR), "Private symlink escaped its allocation");
        ReadTool broadReader = new ReadTool("/", false, materials);
        EditTool broadEditor = new EditTool("/", false, materials);
        WriteTool broadWriter = new WriteTool("/", false, materials);
        ShellTool broadShell = new ShellTool(false, "/", materials);
        check(broadReader.run(new JSONObject().put("path", file.getPath())).contains("updated"), "Root project blocked current private allocation");
        check(broadReader.run(new JSONObject().put("path", foreign.getPath())).startsWith(ERROR), "Root project bypassed other-session read restriction");
        check(broadReader.run(new JSONObject().put("path", foreign.getPath().substring(1))).startsWith(ERROR), "Relative root project bypassed other-session read restriction");
        check(broadEditor.run(new JSONObject(edit.toString()).put("path", foreign.getPath())).startsWith(ERROR), "Root project bypassed other-session edit restriction");
        File ledger = new File(state, "session-18.json");
        check(broadReader.run(new JSONObject().put("path", ledger.getPath())).startsWith(ERROR), "Root project allowed ledger read");
        check(broadWriter.run(write(ledger.getPath(), "deliverable")).startsWith(ERROR), "Root project allowed ledger overwrite");
        check(broadShell.run(new JSONObject().put("command", "cat '" + foreign.getPath() + "'")).startsWith(ERROR), "Root project bypassed other-session shell restriction");
        check(broadShell.run(new JSONObject().put("command", "cat '" + ledger.getPath() + "'")).startsWith(ERROR), "Root project allowed ledger shell read");
        check(materials.finishTurn() == null && other.finishTurn() == null, "Private access fixtures did not clean");
        check(temp.mkdir(), "Could not recreate expired allocation fixture");
        Files.write(file.toPath(), "expired-file".getBytes("UTF-8"));
        Files.write(new File(temp, "probe.sh").toPath(), "printf expired-script".getBytes("UTF-8"));
        check(broadReader.run(new JSONObject().put("path", file.getPath())).startsWith(ERROR), "Finished turn retained private read access");
        check(broadShell.run(new JSONObject().put("command", "sh '" + new File(temp, "probe.sh").getPath() + "'")).startsWith(ERROR), "Finished turn retained private shell access");
        remove(temp);
        Files.delete(unrelated.toPath());
        pass("privateFileAccessIsScoped");
    }

    private static void rootAndWorkDirDoNotMovePrivateAllocation() throws Exception {
        TemporaryWorkspace materials = manager(20); materials.beginTurn();
        File temp = materials.directory();
        materials.configure("/root", true);
        check(temp.equals(materials.directory()), "Root or work directory switch moved private temporary allocation");
        materials.configure(project.getPath(), false);
        check(materials.finishTurn() == null && !temp.exists(), "Private allocation did not clean after configuration change");
        TemporaryWorkspace rootMaterials = new TemporaryWorkspace("/root", true, state, 22);
        rootMaterials.beginTurn(); File rootTemp = rootMaterials.directory();
        check(rootTemp.getParentFile().equals(new File(state, "materials")), "Initial root mode allocated materials outside App private storage");
        rootMaterials.configure(project.getPath(), false);
        check(rootMaterials.finishTurn() == null && !rootTemp.exists(), "Initially root allocation did not clean");
        boolean refused = false;
        try { new TemporaryWorkspace(project.getPath(), false, null, -1).directory(); }
        catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "Missing App private path fell back to workspace allocation");
        pass("rootAndWorkDirDoNotMovePrivateAllocation");
    }

    private static void legacyWorkspaceRecoveryMigrates() throws Exception {
        String owner = java.util.UUID.randomUUID().toString();
        File old = new File(project, ".backcast-tmp-" + owner); old.mkdir();
        Files.write(new File(old, ".backcast-owner").toPath(), owner.getBytes("UTF-8"));
        Files.write(new File(old, "obsolete.py").toPath(), new byte[]{1});
        JSONObject ledger = new JSONObject().put("directories", new JSONArray().put(new JSONObject()
                .put("path", old.getPath()).put("workspace", project.getPath()).put("owner", owner)));
        Files.write(new File(state, "session-21.json").toPath(), ledger.toString().getBytes("UTF-8"));
        TemporaryWorkspace recovered = manager(21);
        check(recovered.cleanupRecovered() == null && !old.exists(), "Legacy project allocation was not recovered safely");
        recovered.beginTurn(); File fresh = recovered.directory();
        check(fresh.getParentFile().equals(new File(state, "materials")), "Recovery allocated new materials in the legacy project path");
        check(recovered.finishTurn() == null && !fresh.exists(), "Migrated allocation did not clean");
        pass("legacyWorkspaceRecoveryMigrates");
    }

    private static void purposeIsRequiredAndShellUsesManagedPaths() throws Exception {
        TemporaryWorkspace materials = manager(2);
        materials.beginTurn();
        WriteTool writer = new WriteTool(project.getPath(), false, materials);
        check(writer.run(new JSONObject().put("path", "unknown.py").put("content", "fixture")).startsWith(ERROR),
                "Unclassified material was accepted");
        ShellTool shell = new ShellTool(false, project.getPath(), materials);
        String output = shell.run(new JSONObject().put("command", "touch generated.tmp; pwd").put("temporary", true));
        File temp = materials.directory();
        check(output.startsWith("exit=0") && output.contains(temp.getPath())
                && new File(temp, "generated.tmp").isFile(), "Temporary shell did not use temporary cwd");
        output = shell.run(new JSONObject().put("command", "mktemp"));
        check(output.startsWith("exit=0") && output.contains(temp.getPath()), "mktemp escaped managed TMPDIR");
        check(materials.finishTurn() == null && !temp.exists(), "Shell temporary materials survived cleanup");
        pass("purposeIsRequiredAndShellUsesManagedPaths");
    }

    private static void symlinkTargetsSurvive() throws Exception {
        TemporaryWorkspace materials = manager(3);
        materials.beginTurn();
        File temp = materials.directory();
        File user = new File(project, "user-data");
        user.mkdir();
        File keep = new File(user, "keep.txt");
        Files.write(keep.toPath(), new byte[]{1, 2, 3});
        Files.createSymbolicLink(new File(temp, "external").toPath(), user.toPath());
        Files.createSymbolicLink(new File(temp, "broken").toPath(), new File(project, "missing").toPath());
        check(materials.finishTurn() == null && !temp.exists(), "Linked temporary directory did not clean");
        check(keep.isFile() && Files.readAllBytes(keep.toPath()).length == 3, "Cleanup followed a symbolic link");

        materials.beginTurn();
        temp = materials.directory();
        remove(temp);
        Files.createSymbolicLink(temp.toPath(), user.toPath());
        check(materials.finishTurn() == null && !Files.exists(temp.toPath()), "Replaced allocation link survived");
        check(keep.isFile(), "Replaced allocation link deleted its target");
        pass("symlinkTargetsSurvive");
    }

    private static void temporaryShellRejectsProjectOutputs() throws Exception {
        TemporaryWorkspace materials = manager(16);
        materials.beginTurn();
        ShellTool shell = new ShellTool(false, project.getPath(), materials);
        File source = new File(project, "input.txt");
        Files.write(source.toPath(), new byte[]{1});
        File leak = new File(project, "leak.tmp");
        String target = "'" + leak.getPath() + "'";
        for (String command : new String[]{"touch " + target, "mkdir " + target,
                "cp '" + source.getPath() + "' " + target,
                "mv '" + source.getPath() + "' " + target,
                "cd '" + project.getPath() + "'; touch relative-leak.tmp"}) {
            check(shell.run(new JSONObject().put("command", command).put("temporary", true)).startsWith(ERROR),
                    "Temporary shell accepted a project output: " + command);
        }
        check(source.isFile() && !leak.exists() && !new File(project, "relative-leak.tmp").exists(),
                "Rejected temporary command changed project files");
        String copied = shell.run(new JSONObject().put("command", "cp '" + source.getPath() + "' copied.txt")
                .put("temporary", true));
        File temp = materials.directory();
        check(copied.startsWith("exit=0") && new File(temp, "copied.txt").isFile(),
                "Temporary guard blocked a project input or relative temporary output");
        check(materials.finishTurn() == null && !temp.exists() && source.isFile(),
                "Temporary command cleanup changed the project input");
        pass("temporaryShellRejectsProjectOutputs");
    }

    private static void persistentRecoveryIsScopedToSession() throws Exception {
        TemporaryWorkspace first = manager(4);
        TemporaryWorkspace other = manager(5);
        first.beginTurn(); other.beginTurn();
        File abandoned = first.directory(), activeOther = other.directory();
        Files.write(new File(abandoned, "unfinished.py").toPath(), new byte[]{1});
        TemporaryWorkspace recovered = manager(4);
        check(recovered.cleanupRecovered() == null && !abandoned.exists(), "Restart did not clean registered materials without a new run");
        check(activeOther.isDirectory(), "Recovery cleaned a different session's materials");
        check(other.finishTurn() == null && !activeOther.exists(), "Second session did not clean its materials");
        pass("persistentRecoveryIsScopedToSession");
    }

    private static void recoveryNeverCleansLiveLease() throws Exception {
        TemporaryWorkspace materials = manager(11);
        materials.beginTurn();
        File live = materials.directory();
        check(materials.cleanupRecovered() == null && live.isDirectory(), "Background recovery removed a live turn's material");
        check(materials.finishTurn() == null && !live.exists(), "Live lease did not finish cleanup");
        pass("recoveryNeverCleansLiveLease");
    }

    private static void changedOwnershipBlocksCompletion() throws Exception {
        TemporaryWorkspace materials = manager(6);
        materials.beginTurn();
        File temp = materials.directory();
        File marker = new File(temp, ".backcast-owner");
        byte[] original = Files.readAllBytes(marker.toPath());
        Files.write(marker.toPath(), "foreign".getBytes("UTF-8"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TemporaryTool(materials, project.getPath(), java.util.Collections.singletonList(project.getPath())));
        AgentLoop loop = new AgentLoop(null, registry, new AgentLoop.Quiet());
        loop.setGoal("deliver the fixture");
        check(loop.closeGoal(Goal.COMPLETE, "").startsWith(ERROR), "Goal completed despite failed cleanup");
        check(Goal.ACTIVE.equals(loop.goalStatus()) && temp.isDirectory(), "Failed cleanup changed goal or deleted material");
        Files.write(marker.toPath(), original);
        check(!loop.closeGoal(Goal.COMPLETE, "").startsWith(ERROR) && !temp.exists(), "Repaired cleanup could not complete goal");
        check(Goal.COMPLETE.equals(loop.goalStatus()), "Clean goal did not become complete");
        pass("changedOwnershipBlocksCompletion");
    }

    private static void unconfirmedProcessesBlockCleanupAndCanRetry() throws Exception {
        final TemporaryWorkspace materials = manager(23);
        final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicInteger retries = new java.util.concurrent.atomic.AtomicInteger();
        materials.beginTurn(); final File temp = materials.directory();
        materials.trackProcess(temp, new TemporaryWorkspace.ProcessCleanup() {
            public boolean stop() { retries.incrementAndGet(); return stopped.get(); }
        });
        Files.write(new File(temp, "live.py").toPath(), new byte[]{1});
        ToolRegistry registry = new ToolRegistry(); registry.register(new TemporaryTool(materials, project.getPath(), java.util.Collections.singletonList(project.getPath())));
        AgentLoop loop = new AgentLoop(null, registry, new AgentLoop.Quiet()); loop.setGoal("deliver fixture");
        check(loop.closeGoal(Goal.COMPLETE, "").startsWith(ERROR), "Goal completed while a process could still recreate temporary files");
        check(Goal.ACTIVE.equals(loop.goalStatus()) && new File(temp, "live.py").isFile(), "Failed process check deleted files or closed goal");
        check(materials.finishTurn() != null && temp.isDirectory(), "Turn completion ignored an unconfirmed process");
        materials.beginTurn(); File next = materials.directory();
        final java.util.concurrent.atomic.AtomicInteger nextStops = new java.util.concurrent.atomic.AtomicInteger();
        materials.trackProcess(next, new TemporaryWorkspace.ProcessCleanup() {
            public boolean stop() { nextStops.incrementAndGet(); return true; }
        });
        check(materials.cleanupRecovered() != null && next.isDirectory() && nextStops.get() == 0,
                "Recovered-process cleanup crossed into another live turn");
        stopped.set(true);
        check(materials.cleanupRecovered() == null && !temp.exists() && next.isDirectory() && nextStops.get() == 0,
                "Process retry did not recover safely or cleaned the live turn");
        check(!loop.closeGoal(Goal.COMPLETE, "").startsWith(ERROR) && !next.exists() && nextStops.get() == 1,
                "Confirmed process retry permanently blocked goal completion");
        check(retries.get() >= 4, "Process cleanup callback was not retried");
        materials.finishTurn();
        pass("unconfirmedProcessesBlockCleanupAndCanRetry");
    }

    private static void separateTurnLeases() throws Exception {
        final TemporaryWorkspace materials = manager(7);
        final CountDownLatch oldReady = new CountDownLatch(1), newReady = new CountDownLatch(1), oldCleaned = new CountDownLatch(1);
        final File[] dirs = new File[2];
        final Throwable[] errors = new Throwable[2];
        Thread old = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    materials.beginTurn(); dirs[0] = materials.directory(); oldReady.countDown();
                    check(newReady.await(5, TimeUnit.SECONDS), "New turn never started");
                    check(materials.finishTurn() == null && !dirs[0].exists(), "Old turn failed cleanup");
                    check(dirs[1].isDirectory(), "Old turn cleaned the new turn's directory");
                } catch (Throwable error) { errors[0] = error; }
                finally { oldCleaned.countDown(); }
            }
        });
        Thread next = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    check(oldReady.await(5, TimeUnit.SECONDS), "Old turn never started");
                    materials.beginTurn(); dirs[1] = materials.directory(); newReady.countDown();
                    check(oldCleaned.await(5, TimeUnit.SECONDS), "Old turn never cleaned");
                    check(materials.finishTurn() == null && !dirs[1].exists(), "New turn failed cleanup");
                } catch (Throwable error) { errors[1] = error; }
            }
        });
        old.start(); next.start(); old.join(7000); next.join(7000);
        check(!old.isAlive() && !next.isAlive() && errors[0] == null && errors[1] == null,
                "Turn lease failure: " + errors[0] + " / " + errors[1]);
        pass("separateTurnLeases");
    }

    private static void cleanupSharesDeadlineAndPreservesUnknown() throws Exception {
        TemporaryWorkspace materials = manager(30);
        materials.beginTurn(); File temp = materials.directory();
        final long deadline = System.nanoTime() + 1000000000L;
        final long[] observed = {0};
        materials.trackProcess(temp, new TemporaryWorkspace.ProcessCleanup() {
            public boolean stop() { throw new AssertionError("Cleanup discarded its shared deadline"); }
            public boolean stop(long until) {
                observed[0] = until;
                check(!Thread.currentThread().isInterrupted(), "Cancelled operation interrupted cleanup verification");
                Thread.currentThread().interrupt();
                return false;
            }
        });
        Thread.currentThread().interrupt();
        String error;
        try {
            error = materials.finishTurn(deadline);
            check(observed[0] == deadline && error != null && temp.isDirectory(), "Unknown process lost its lease or shared deadline");
            check(Thread.currentThread().isInterrupted(), "Cleanup discarded an existing or new cancellation interrupt");
        } finally { Thread.interrupted(); }
        check(materials.cleanupRecovered(System.nanoTime() - 1) != null && temp.isDirectory(), "Expired recovery deleted unverified materials");
        pass("cleanupSharesDeadlineRetainsUnknownAndRestoresInterrupt");
    }

    private static void reloadedPendingProcessesRemainUnknown() throws Exception {
        TemporaryWorkspace original = manager(31);
        original.beginTurn(); File temp = original.directory();
        Process process = new ProcessBuilder("sh", "-c", "sleep 30").start();
        try {
            original.trackProcess(temp, new TemporaryWorkspace.ProcessCleanup() {
                public boolean stop() { return !process.isAlive(); }
            });
            JSONObject ledger = new JSONObject(new String(Files.readAllBytes(new File(state, "session-31.json").toPath()), "UTF-8"));
            check(ledger.getJSONArray("directories").getJSONObject(0).getBoolean("process_pending"), "Live process registration was not persisted before execution");
            check(original.finishTurn() != null && temp.isDirectory(), "Live process did not block cleanup");
            TemporaryWorkspace reloaded = manager(31);
            String unknown = reloaded.cleanupRecovered();
            check(unknown != null && unknown.contains("原进程身份") && temp.isDirectory() && process.isAlive(),
                    "Reload treated a missing process callback as proven exit");
            process.destroyForcibly(); process.waitFor();
            check(reloaded.cleanupRecovered() != null && temp.isDirectory(), "Reload inferred exit without retained identity evidence");
            check(original.cleanupRecovered() == null && !temp.exists(), "Original retained callback could not verify and clean the stopped process");
            pass("reloadedPendingProcessesRemainUnknownUntilOriginalEvidenceConfirmsExit");
        } finally { process.destroyForcibly(); }
    }

    private static void replacedParentCannotDeleteUserFiles() throws Exception {
        File original = new File(state, "swap-parent"), moved = new File(state, "real-parent");
        File foreign = new File(project, "foreign-parent");
        original.mkdir(); foreign.mkdir();
        TemporaryWorkspace materials = new TemporaryWorkspace(project.getPath(), false, original, 12);
        materials.beginTurn();
        File temporary = materials.directory();
        check(original.renameTo(moved), "Fixture could not move parent directory");
        File foreignMaterials = new File(foreign, "materials"); foreignMaterials.mkdir();
        File user = new File(foreignMaterials, temporary.getName()); user.mkdir();
        File keep = new File(user, "keep.txt"); Files.write(keep.toPath(), new byte[]{7});
        Files.createSymbolicLink(original.toPath(), foreign.toPath());
        check(materials.cleanup() != null && keep.isFile(), "Cleanup traversed a replaced parent symlink");
        Files.delete(original.toPath());
        check(moved.renameTo(original), "Fixture could not restore parent directory");
        check(materials.finishTurn() == null && !temporary.exists() && keep.isFile(), "Restored parent cleanup failed");
        pass("replacedParentCannotDeleteUserFiles");
    }

    private static void cancelledShellStopsBeforeCleanup() throws Exception {
        final TemporaryWorkspace materials = manager(13);
        final File[] allocated = new File[1];
        final CountDownLatch requested = new CountDownLatch(1);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TemporaryTool(materials, project.getPath(), java.util.Collections.singletonList(project.getPath())));
        registry.register(new ShellTool(false, project.getPath(), materials));
        LlmClient client = new LlmClient(new LlmClient.Config("http://localhost", "fixture", "fixture")) {
            @Override public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
                try {
                    allocated[0] = materials.directory(); requested.countDown();
                    JSONObject args = new JSONObject().put("temporary", true).put("command",
                            "touch started; sh -c 'sleep 2; mkdir -p \"$TMPDIR\"; touch \"$TMPDIR/late.tmp\"' & wait");
                    JSONArray calls = new JSONArray().put(new JSONObject().put("id", "shell-fixture")
                            .put("type", "function").put("function", new JSONObject().put("name", "shell")
                            .put("arguments", args.toString())));
                    Reply reply = new Reply(); reply.toolCalls = calls; return reply;
                } catch (Exception error) { throw new IllegalStateException(error); }
            }
        };
        final AgentLoop loop = new AgentLoop(client, registry, new AgentLoop.Quiet());
        loop.reset("fixture");
        Thread worker = new Thread(new Runnable() {
            @Override public void run() { loop.submit("temporary shell", 13, loop.generation(), 1, null); }
        });
        worker.start();
        check(requested.await(2, TimeUnit.SECONDS), "Shell fixture was not requested");
        long deadline = System.currentTimeMillis() + 2000;
        while (!new File(allocated[0], "started").exists() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        check(new File(allocated[0], "started").exists(), "Shell fixture did not start");
        loop.cancel(); worker.join(2000);
        check(!worker.isAlive() && !allocated[0].exists(), "Cancellation did not stop shell before cleanup");
        Thread.sleep(2300);
        check(!allocated[0].exists(), "A cancelled child recreated cleaned temporary material");
        pass("cancelledShellStopsBeforeCleanup");
    }

    private static void cancelledShellNeverStartsAfterDirectoryWait() throws Exception {
        final TemporaryWorkspace materials = manager(14);
        final ShellTool shell = new ShellTool(false, project.getPath(), materials);
        final File marker = new File(project, "cancelled-shell-started.txt");
        final Throwable[] errors = new Throwable[1];
        final String[] result = new String[1], cleanup = new String[1];
        final CountDownLatch entered = new CountDownLatch(1);
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                materials.beginTurn();
                try {
                    entered.countDown();
                    String path = marker.getPath().replace("'", "'\\''");
                    result[0] = shell.run(new JSONObject().put("temporary", true)
                            .put("command", "touch '" + path + "'"));
                } catch (Throwable error) { errors[0] = error; }
                finally { cleanup[0] = materials.finishTurn(); }
            }
        });
        synchronized (materials) {
            worker.start();
            check(entered.await(2, TimeUnit.SECONDS), "Shell did not enter its preparation stage");
            long deadline = System.currentTimeMillis() + 2000;
            while (worker.getState() != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) Thread.sleep(5);
            check(worker.getState() == Thread.State.BLOCKED, "Shell did not wait for temporary directory preparation");
            shell.abort();
        }
        worker.join(3000);
        check(!worker.isAlive() && errors[0] == null && cleanup[0] == null,
                "Cancelled preparation failed to finish or clean: " + errors[0] + " / " + cleanup[0]);
        check(!marker.exists() && result[0] != null && result[0].startsWith("\u5df2\u505c\u6b62"),
                "Shell started its command after cancellation during directory preparation");
        for (File file : project.listFiles()) {
            check(!file.getName().startsWith(".backcast-tmp-"), "Cancelled directory preparation left temporary material");
        }
        pass("cancelledShellNeverStartsAfterDirectoryWait");
    }

    private static void loopCleanup(final String mode, long sid) throws Exception {
        final TemporaryWorkspace materials = manager(sid);
        final File[] allocated = new File[1];
        final AgentLoop[] loopBox = new AgentLoop[1];
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TemporaryTool(materials, project.getPath(), java.util.Collections.singletonList(project.getPath())));
        LlmClient client = new LlmClient(new LlmClient.Config("http://localhost", "fixture", "fixture")) {
            @Override public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
                try {
                    allocated[0] = materials.directory();
                    Files.write(new File(allocated[0], "probe.py").toPath(), new byte[]{1});
                    if ("cancel".equals(mode)) loopBox[0].cancel();
                    if ("failure".equals(mode)) throw new IllegalStateException("fixture failure");
                    Reply reply = new Reply(); reply.content = "done"; return reply;
                } catch (Exception error) { throw new IllegalStateException(error); }
            }
        };
        AgentLoop loop = new AgentLoop(client, registry, new AgentLoop.Quiet());
        loopBox[0] = loop;
        loop.reset("fixture");
        loop.submit("create temporary material", sid, loop.generation(), 1, null);
        check(allocated[0] != null && !allocated[0].exists(), "Turn " + mode + " did not clean temporary material");
        pass("loopCleansAfter_" + mode);
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("backcast-temporary-tests-").toFile();
        project = new File(root, "project with spaces"); project.mkdir();
        state = new File(root, "private-state");
        try {
            managedMaterialsOnly();
            privateFileAccessIsScoped();
            rootAndWorkDirDoNotMovePrivateAllocation();
            legacyWorkspaceRecoveryMigrates();
            purposeIsRequiredAndShellUsesManagedPaths();
            temporaryShellRejectsProjectOutputs();
            symlinkTargetsSurvive();
            persistentRecoveryIsScopedToSession();
            recoveryNeverCleansLiveLease();
            changedOwnershipBlocksCompletion();
            unconfirmedProcessesBlockCleanupAndCanRetry();
            separateTurnLeases();
            cleanupSharesDeadlineAndPreservesUnknown();
            reloadedPendingProcessesRemainUnknown();
            replacedParentCannotDeleteUserFiles();
            cancelledShellStopsBeforeCleanup();
            cancelledShellNeverStartsAfterDirectoryWait();
            loopCleanup("success", 8);
            loopCleanup("failure", 9);
            loopCleanup("cancel", 10);
            System.out.println(passed + " temporary tests passed");
        } finally { remove(root); }
    }
}
