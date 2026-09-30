import com.mkei.backcast.agent.FileSubAgentStore;
import com.mkei.backcast.agent.SubAgentManager;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONObject;

/** Exercises real private checkpoints and interrupted atomic commits. */
public final class FileSubAgentStoreRegressionTest {
    private static File root;
    private static int passed;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static File folder(String name) { return new File(root, name); }
    private static SubAgentManager.Record record() {
        SubAgentManager.Record r = new SubAgentManager.Record();
        r.id = "agent_fixture"; r.parentId = "main"; r.name = "Fixture"; r.task = "Inspect";
        r.status = SubAgentManager.RUNNING; r.inFlight = "Inspect"; r.sessionId = 8; r.revision = 3;
        r.tokensUsed = 123; r.resume = true;
        return r;
    }
    private static void write(File file, String text) throws Exception { Files.write(file.toPath(), text.getBytes("UTF-8")); }

    private static void roundTripPreservesTaskAndMailbox() throws Exception {
        FileSubAgentStore store = new FileSubAgentStore(folder("round-trip"));
        SubAgentManager.Record r = record();
        r.pending.put(new JSONObject().put("from", "main").put("text", "next"));
        r.history.put(new JSONObject().put("role", "user").put("content", "task"));
        store.save(r);
        SubAgentManager.Record loaded = new FileSubAgentStore(folder("round-trip")).load().get(0);
        check(loaded.sessionId == 8 && loaded.tokensUsed == 123 && loaded.resume
                && loaded.pending.length() == 1 && loaded.history.length() == 1, "Checkpoint lost child state");
        check(folder("round-trip").list().length == 1, "Atomic save left temporary files");
    }
    private static void staleRevisionCannotOverwriteNewerCheckpoint() throws Exception {
        FileSubAgentStore store = new FileSubAgentStore(folder("revision"));
        SubAgentManager.Record r = record(); store.save(r); r.revision = 1; r.task = "stale"; store.save(r);
        check(store.load().get(0).revision == 3 && "Inspect".equals(store.load().get(0).task), "Stale write replaced current state");
    }
    private static void interruptedRenameRestoresBackup() throws Exception {
        File dir = folder("recover"); dir.mkdir();
        write(new File(dir, "agent_fixture.json.bak"), record().toJson().toString());
        write(new File(dir, "agent_fixture-123.pending"), "partial");
        check(new FileSubAgentStore(dir).load().size() == 1, "Previous committed record was lost");
        check(dir.list().length == 1 && new File(dir, "agent_fixture.json").exists(), "Recovery left staging or backup");
    }
    private static void committedFileWinsOverOldBackup() throws Exception {
        File dir = folder("committed"); FileSubAgentStore store = new FileSubAgentStore(dir);
        SubAgentManager.Record r = record(); store.save(r); r.revision = 1;
        write(new File(dir, "agent_fixture.json.bak"), r.toJson().toString());
        check(store.load().get(0).revision == 3 && dir.list().length == 1, "Old backup replaced committed state");
    }
    private static void draftMovesWithoutLosingHistory() throws Exception {
        FileSubAgentStore store = new FileSubAgentStore(folder("draft")); store.save(record());
        store.bindDirectory(folder("session"));
        check(!folder("draft").exists() && store.load().size() == 1, "Adoption lost child records");
    }
    private static void invalidIdCannotEscapePrivateDirectory() throws Exception {
        FileSubAgentStore store = new FileSubAgentStore(folder("invalid-id"));
        SubAgentManager.Record r = record(); r.id = "../escape";
        boolean refused = false; try { store.save(r); } catch (IllegalStateException expected) { refused = true; }
        check(refused && !new File(root, "escape.json").exists(), "Invalid child id escaped private storage");
    }
    private static void corruptedRecordsStayForDiagnosis() throws Exception {
        File dir = folder("corrupt"); dir.mkdir(); File file = new File(dir, "agent_fixture.json"); write(file, "broken");
        boolean refused = false; try { new FileSubAgentStore(dir).load(); } catch (IllegalStateException expected) { refused = true; }
        check(refused && file.exists(), "Unreadable task was silently discarded");
    }
    private static void symlinkRecordsAreRejected() throws Exception {
        File dir = folder("link"); dir.mkdir(); File target = new File(root, "outside-record"); write(target, record().toJson().toString());
        Files.createSymbolicLink(new File(dir, "agent_fixture.json").toPath(), target.toPath());
        boolean refused = false; try { new FileSubAgentStore(dir).load(); } catch (IllegalStateException expected) { refused = true; }
        check(refused && target.exists(), "Record symlink was followed or removed");
    }
    private static void deletedSessionCannotBeRecreatedByLateWorker() throws Exception {
        File dir = folder("deleted"); FileSubAgentStore store = new FileSubAgentStore(dir); store.save(record()); store.remove();
        boolean refused = false; try { store.save(record()); } catch (IllegalStateException expected) { refused = true; }
        check(refused && !dir.exists(), "Late worker recreated a deleted session");
    }
    private static void remove(File file) throws Exception {
        if (!Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        }
        Files.deleteIfExists(file.toPath());
    }
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("backcast-child-store-").toFile();
        try {
            for (String name : new String[]{"roundTripPreservesTaskAndMailbox", "staleRevisionCannotOverwriteNewerCheckpoint",
                    "interruptedRenameRestoresBackup", "committedFileWinsOverOldBackup", "draftMovesWithoutLosingHistory",
                    "invalidIdCannotEscapePrivateDirectory", "corruptedRecordsStayForDiagnosis", "symlinkRecordsAreRejected",
                    "deletedSessionCannotBeRecreatedByLateWorker"}) {
                FileSubAgentStoreRegressionTest.class.getDeclaredMethod(name).invoke(null); passed++; System.out.println("PASS " + name);
            }
            System.out.println(passed + " child store tests passed");
        } finally { remove(root); }
    }
}
