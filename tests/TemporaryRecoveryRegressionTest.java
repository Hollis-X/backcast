package com.mkei.backcast.tool;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** 重启前留下、登记着「有进程没结束」的临时目录：查 /proc 确认没人占用才删，有人占用就保留并报出进程。 */
public final class TemporaryRecoveryRegressionTest {
    private static File fixture;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    /** 模拟上次进程留下的登记：目录、所有权标记、process_pending=true。processRoot 为 null 表示旧登记没有这一项。 */
    private static File leftover(File state, Boolean processRoot) throws Exception {
        File materials = new File(state, "materials");
        check(materials.mkdirs(), "Cannot create materials");
        String owner = UUID.randomUUID().toString();
        File directory = new File(materials, ".backcast-tmp-" + owner);
        check(directory.mkdir(), "Cannot create leftover directory");
        Files.write(new File(directory, ".backcast-owner").toPath(), owner.getBytes("UTF-8"));
        Files.write(new File(directory, "scan.awk").toPath(), "{ print }".getBytes("UTF-8"));
        JSONObject entry = new JSONObject().put("path", directory.getPath()).put("workspace", materials.getCanonicalPath())
                .put("owner", owner).put("process_pending", true).put("storage", "private");
        if (processRoot != null) entry.put("process_root", processRoot.booleanValue());
        Files.write(new File(state, "session-1.json").toPath(),
                new JSONObject().put("directories", new JSONArray().put(entry)).toString().getBytes("UTF-8"));
        return directory;
    }

    private static File state(String name) throws Exception {
        File state = new File(fixture, name).getCanonicalFile();
        check(state.mkdirs(), "Cannot create state dir");
        return state;
    }

    private static void leftoverWithoutHoldersIsRemoved() throws Exception {
        File state = state("free");
        File directory = leftover(state, Boolean.FALSE);
        TemporaryWorkspace temporary = new TemporaryWorkspace(fixture.getPath(), false, state, 1);
        String error = temporary.cleanupRecovered();
        check(error == null, "Unused leftover was not cleaned: " + error);
        check(!directory.exists(), "Unused leftover directory survived");
        check(!new File(state, "session-1.json").exists(), "Ledger kept a removed directory");
    }

    private static void leftoverInUseIsTerminatedThenRemoved() throws Exception {
        File state = state("busy");
        File directory = leftover(state, Boolean.FALSE);
        Process holder = new ProcessBuilder("sleep", "600").directory(directory).start();
        try {
            check(holder.isAlive(), "Fixture holder did not start");
            TemporaryWorkspace temporary = new TemporaryWorkspace(fixture.getPath(), false, state, 1);
            String error = temporary.cleanupRecovered();
            check(error == null, "Leftover with a killable holder was not cleaned: " + error);
            check(!directory.exists(), "Leftover directory survived after its process was terminated");
            check(holder.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "Holder process was not terminated");
            check(!new File(state, "session-1.json").exists(), "Ledger kept a removed directory");
        } finally { holder.destroyForcibly(); }
    }

    private static void oldLedgerWithoutRootFlagNeedsRoot() throws Exception {
        File state = state("legacy");
        File directory = leftover(state, null);
        TemporaryWorkspace temporary = new TemporaryWorkspace(fixture.getPath(), false, state, 1);
        String error = temporary.cleanupRecovered();
        check(error != null && error.contains("root") && directory.exists(),
                "Possibly root-owned leftover was deleted without root verification: " + error);
    }

    private static void staleFailureIsReportedOnceInTurnCleanup() throws Exception {
        // 登记开着 root 又没有 root：确认不了，是个持续失败。它不进本轮清理结果，只经 staleFailures() 交出一次。
        File state = state("once");
        File directory = leftover(state, Boolean.TRUE);
        TemporaryWorkspace temporary = new TemporaryWorkspace(fixture.getPath(), false, state, 1);
        temporary.beginTurn();
        File own = temporary.directory();
        String first = temporary.finishTurn();
        check(first == null && !own.exists(), "Leftover failure leaked into this turn's cleanup result: " + first);
        String note = temporary.staleFailures();
        check(note != null && note.contains("root") && note.contains(directory.getName()),
                "Leftover failure was not handed over for diagnostics: " + note);
        check(temporary.staleFailures() == null, "Leftover note was handed over twice");
        temporary.beginTurn();
        check(temporary.finishTurn() == null && temporary.staleFailures() == null,
                "Leftover failure was repeated on the next turn");
        check(directory.exists(), "Unverifiable leftover was deleted");
    }

    private static void rootScanParserMatchesDirectoryBoundaries() throws Exception {
        String directory = "/data/user/0/app/cache/.backcast-tmp-a";
        String output = "/proc/120/fd:\n"
                + "lrwx------ 1 root root 64 2026-10-18 12:00 0 -> " + directory + "/input.bin\n"
                + "lrwx------ 1 root root 64 2026-10-18 12:00 1 -> " + directory + "-sibling/output.bin\n"
                + "/proc/121/fd:\n"
                + "lrwx------ 1 root root 64 2026-10-18 12:00 0 -> /data/user/0/app/cache/other.bin\n"
                + "lrwxrwxrwx 1 root root 0 2026-10-18 12:00 /proc/122/cwd -> " + directory + "/nested\n"
                + "lrwxrwxrwx 1 root root 0 2026-10-18 12:00 /proc/123/cwd -> " + directory + "-sibling\n";
        java.util.List<String> holders = TemporaryWorkspace.parseRootScan(output, directory);
        check(holders.size() == 2 && "120".equals(holders.get(0)) && "122".equals(holders.get(1)),
                "Root scan parser confused fd headers or directory prefixes: " + holders);
    }

    /** 登记的是 /data/data/包名，/proc 链接里读到的是 /data/user/0/包名（或反过来）：两种写法都要认出来。 */
    private static void rootScanParserMatchesDataAliases() throws Exception {
        String directory = "/data/data/app/files/.backcast-tmp-a";
        String output = "/proc/130/fd:\n"
                + "lrwx------ 1 root root 64 2026-10-18 12:00 3 -> /data/user/0/app/files/.backcast-tmp-a/log.txt\n"
                + "lrwxrwxrwx 1 root root 0 2026-10-18 12:00 /proc/131/cwd -> /data/data/app/files/.backcast-tmp-a\n"
                + "lrwxrwxrwx 1 root root 0 2026-10-18 12:00 /proc/132/cwd -> /data/user/10/app/files/.backcast-tmp-a\n";
        java.util.List<String> holders = TemporaryWorkspace.parseRootScan(output, directory);
        check(holders.size() == 2 && "130".equals(holders.get(0)) && "131".equals(holders.get(1)),
                "Root scan missed a holder that used the other spelling of the private directory: " + holders);
        java.util.List<String> back = TemporaryWorkspace.aliases("/data/user/0/app/files/x");
        check(back.size() == 2 && back.get(1).equals("/data/data/app/files/x"), "Alias of /data/user/0 was not /data/data: " + back);
    }

    private static void rootScanAcceptsProcRacesButRejectsMissingScan() throws Exception {
        String directory = "/data/user/0/app/cache/.backcast-tmp-a";
        String partial = "/proc/120/fd:\n"
                + "lrwx------ 1 root root 64 2026-10-18 12:00 0 -> " + directory + "/input.bin\n"
                + "__backcast_root_scan_status:1\n";
        check(TemporaryWorkspace.rootScanStatusAccepts(partial),
                "A partial /proc scan caused by a disappearing process was rejected");
        String noEvidence = "__backcast_root_scan_status:2\n";
        check(!TemporaryWorkspace.rootScanStatusAccepts(noEvidence),
                "A scan with no /proc evidence was accepted");
        String invalid = "__backcast_root_scan_status:127\n";
        check(!TemporaryWorkspace.rootScanStatusAccepts(invalid),
                "A failed ls command was accepted as a partial scan");
    }

    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        file.delete();
    }

    public static void main(String[] args) throws Exception {
        fixture = Files.createTempDirectory("backcast-temporary-recovery-").toFile().getCanonicalFile();
        try {
            int passed = 0;
            for (String name : new String[]{"leftoverWithoutHoldersIsRemoved", "leftoverInUseIsTerminatedThenRemoved",
                    "oldLedgerWithoutRootFlagNeedsRoot", "staleFailureIsReportedOnceInTurnCleanup",
                    "rootScanParserMatchesDirectoryBoundaries", "rootScanParserMatchesDataAliases", "rootScanAcceptsProcRacesButRejectsMissingScan"}) {
                try { TemporaryRecoveryRegressionTest.class.getDeclaredMethod(name).invoke(null); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError(name, failure.getCause()); }
                System.out.println("PASS " + name); passed++;
            }
            System.out.println(passed + " temporary recovery tests passed");
        } finally { remove(fixture); }
    }
}
