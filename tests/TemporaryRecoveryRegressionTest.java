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
        // 登记开着 root 又没有 root：确认不了、也结束不了，是个持续失败，正好用来验证只报一次。
        File state = state("once");
        File directory = leftover(state, Boolean.TRUE);
        TemporaryWorkspace temporary = new TemporaryWorkspace(fixture.getPath(), false, state, 1);
        temporary.beginTurn();
        File own = temporary.directory();
        String first = temporary.finishTurn();
        check(first != null && first.contains("重启前留下") && first.contains("root") && !own.exists(),
                "First turn cleanup hid the leftover or failed its own directory: " + first);
        temporary.beginTurn();
        String second = temporary.finishTurn();
        check(second == null, "Leftover failure was repeated as this turn's cleanup failure: " + second);
        check(directory.exists(), "Unverifiable leftover was deleted");
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
                    "oldLedgerWithoutRootFlagNeedsRoot", "staleFailureIsReportedOnceInTurnCleanup"}) {
                try { TemporaryRecoveryRegressionTest.class.getDeclaredMethod(name).invoke(null); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError(name, failure.getCause()); }
                System.out.println("PASS " + name); passed++;
            }
            System.out.println(passed + " temporary recovery tests passed");
        } finally { remove(fixture); }
    }
}
