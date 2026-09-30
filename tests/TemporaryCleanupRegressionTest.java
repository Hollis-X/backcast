import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.Goal;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.ToolRegistry;
import com.mkei.backcast.tool.TemporaryTool;
import com.mkei.backcast.tool.TemporaryWorkspace;
import com.mkei.backcast.tool.WriteTool;
import com.mkei.backcast.tool.ShellTool;
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
        registry.register(new TemporaryTool(materials));
        AgentLoop loop = new AgentLoop(null, registry, new AgentLoop.Quiet());
        loop.setGoal("deliver the fixture");
        check(loop.closeGoal(Goal.COMPLETE, "").startsWith(ERROR), "Goal completed despite failed cleanup");
        check(Goal.ACTIVE.equals(loop.goalStatus()) && temp.isDirectory(), "Failed cleanup changed goal or deleted material");
        Files.write(marker.toPath(), original);
        check(!loop.closeGoal(Goal.COMPLETE, "").startsWith(ERROR) && !temp.exists(), "Repaired cleanup could not complete goal");
        check(Goal.COMPLETE.equals(loop.goalStatus()), "Clean goal did not become complete");
        pass("changedOwnershipBlocksCompletion");
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

    private static void replacedParentCannotDeleteUserFiles() throws Exception {
        File original = new File(project, "swap-parent"), moved = new File(project, "real-parent");
        File foreign = new File(project, "foreign-parent");
        original.mkdir(); foreign.mkdir();
        TemporaryWorkspace materials = new TemporaryWorkspace(original.getPath(), false, state, 12);
        materials.beginTurn();
        File temporary = materials.directory();
        check(original.renameTo(moved), "Fixture could not move parent directory");
        File user = new File(foreign, temporary.getName()); user.mkdir();
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
        registry.register(new TemporaryTool(materials));
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
            @Override public void run() { loop.submit("temporary shell", 13, loop.generation(), 1); }
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
        registry.register(new TemporaryTool(materials));
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
        loop.submit("create temporary material", sid, loop.generation(), 1);
        check(allocated[0] != null && !allocated[0].exists(), "Turn " + mode + " did not clean temporary material");
        pass("loopCleansAfter_" + mode);
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("backcast-temporary-tests-").toFile();
        project = new File(root, "project with spaces"); project.mkdir();
        state = new File(root, "private-state");
        try {
            managedMaterialsOnly();
            purposeIsRequiredAndShellUsesManagedPaths();
            temporaryShellRejectsProjectOutputs();
            symlinkTargetsSurvive();
            persistentRecoveryIsScopedToSession();
            recoveryNeverCleansLiveLease();
            changedOwnershipBlocksCompletion();
            separateTurnLeases();
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
