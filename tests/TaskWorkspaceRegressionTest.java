package com.mkei.backcast.tool;

import com.mkei.backcast.agent.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Replays the reported sibling-directory leak through a real loop and real file tools. */
public final class TaskWorkspaceRegressionTest {
    private static File fixture, storage, project, operations;
    private static int passed;
    interface Check { void run(ToolRegistry registry) throws Exception; }
    static final class Client extends LlmClient {
        ToolRegistry registry;
        Check next;
        JSONArray calls;
        Client() { super(new Config("http://localhost", "fixture", "fixture")); }
        @Override public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
            if (next != null) { Check action = next; next = null;
                try { action.run(registry); } catch (Exception error) { throw new AssertionError(error); }
            }
            Reply reply = new Reply();
            if (calls != null) { reply.toolCalls = calls; calls = null; }
            else reply.content = "done";
            return reply;
        }
    }
    static final class Rig {
        final Client client = new Client();
        final ToolRegistry registry = new ToolRegistry();
        final AgentLoop loop;
        Rig() { this(storage, Collections.singletonList(storage.getPath())); }
        Rig(File main, List<String> authorized) {
            TemporaryWorkspace temp = new TemporaryWorkspace(main.getPath(), false, new File(fixture, "private-" + System.nanoTime()), 1);
            temp.configureWorkDirs(authorized);
            registry.register(new ReadTool(main.getPath(), false, temp));
            registry.register(new FindFilesTool(main.getPath(), false, temp));
            registry.register(new ShellTool(false, main.getPath(), temp));
            registry.register(new WriteTool(main.getPath(), false, temp));
            registry.register(new TemporaryTool(temp, main.getPath(), authorized));
            client.registry = registry;
            loop = new AgentLoop(client, registry, new AgentLoop.Quiet());
            loop.reset("Only do the user's task."); loop.setEnvironment("Only do the user's task.", main.getPath());
        }
        void submit(String text, Check check) {
            client.next = check; loop.submit(text, 1, loop.generation(), 1, null);
            require(client.next == null, "Turn failed before checking scope");
        }
    }
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void pass(String name) { passed++; System.out.println("PASS " + name); }
    static String read(ToolRegistry r, File file) throws Exception { return r.get("read").run(new JSONObject().put("path", file.getPath())); }
    static String shell(ToolRegistry r, String command) throws Exception { return r.get("shell").run(new JSONObject().put("command", command)); }
    static void confined(ToolRegistry r) throws Exception {
        require(read(r, new File(project, "README.md")).contains("project text"), "Project is unreadable");
        require(read(r, new File(operations, "servers.json")).startsWith("错误："), "Read sibling credentials");
        require(shell(r, "ls '" + storage + "'").startsWith("错误："), "Listed the broad storage root");
        require(shell(r, "grep -rl version '" + operations + "'").startsWith("错误："), "Searched sibling file contents");
        require(!shell(r, "ls").contains("operations"), "Default cwd leaked storage siblings");
    }

    private static void attachedAccessRemainsExplicitAcrossTaskSnapshots() throws Exception {
        File extra = new File(fixture, "运维"), spaced = new File(fixture, "清风 落樱"), reverse = new File(fixture, "逆向目录");
        File narrowed = new File(project, "task");
        require(extra.mkdir() && spaced.mkdir() && reverse.mkdir() && narrowed.mkdir(), "attached fixtures");
        File report = new File(extra, "report.md"), spacedInput = new File(spaced, "input.txt");
        Files.write(report.toPath(), "attached report".getBytes("UTF-8"));
        Files.write(spacedInput.toPath(), "spaced input".getBytes("UTF-8"));
        Files.write(new File(narrowed, "task.md").toPath(), "focused task".getBytes("UTF-8"));
        List<String> authorized = Arrays.asList(project.getPath(), extra.getPath(), spaced.getPath(), reverse.getPath());
        Rig attached = new Rig(project, authorized);
        attached.submit("拿运维工具查错误", r -> {
            require(read(r, report).contains("attached report"), "Named attached folder lost its explicit read grant");
            require(read(r, spacedInput).contains("spaced input"), "Spaced attached folder is unreadable");
            require(shell(r, "ls '" + extra + "'").contains("report.md"), "Attached folder shell access was rejected");
            require(r.get("read").run(new JSONObject().put("path", "README.md")).contains("project text"), "Default relative root changed");
            require(new JSONObject(r.get("find_files").run(new JSONObject().put("name", "report")))
                    .getJSONArray("matches").length() == 0, "Default search swept attached folder");
            require(r.get("read").run(new JSONObject().put("path", "report")).startsWith("错误："), "Bare name read swept attached folder");
            require(new JSONObject(r.get("find_files").run(new JSONObject().put("name", "report").put("directory", extra.getPath())))
                    .getJSONArray("matches").length() == 1, "Explicit attached directory search was rejected");
        });
        pass("namedAttachedFoldersDoNotRequireAnAbsolutePathInTheHumanRequest");
        Check narrowCheck = r -> {
            require(r.get("read").run(new JSONObject().put("path", "task.md")).contains("focused task"), "Task relative focus was lost");
            require(read(r, new File(project, "README.md")).startsWith("错误："), "Main project narrowing was undone");
            require(read(r, report).contains("attached report"), "Narrowed main task revoked attached authorization");
            require(!shell(r, "ls").contains("README.md"), "Shell cwd stayed at configured primary instead of task focus");
        };
        attached.submit("只看 " + narrowed.getPath() + "/", narrowCheck);
        attached.submit("继续", narrowCheck);
        Rig child = new Rig(project, authorized);
        child.loop.setCapturedTaskPaths(new JSONArray().put(narrowed.getPath()));
        child.submit("独立子任务，检查运维报告", narrowCheck);
        pass("narrowedPrimaryAndCapturedChildScopeKeepAttachedAuthorization");
        Rig revoked = new Rig(project, Arrays.asList(project.getPath(), spaced.getPath(), reverse.getPath()));
        revoked.loop.setCapturedTaskPaths(new JSONArray().put(narrowed.getPath()));
        revoked.submit("继续", r -> require(read(r, report).startsWith("错误："), "Removed attachment survived the next scope snapshot"));
        pass("removedAttachedAuthorizationIsRejectedOnTheNextTurn");

        WorkspaceRoots overlap = new WorkspaceRoots(project.getPath(), Arrays.asList(project.getPath(), storage.getPath()))
                .forTask(Collections.singletonList(narrowed.getPath()));
        require(overlap.rootFor(new File(project, "README.md")) == null
                && overlap.lexicalRootFor(new File(project, "README.md")) == null,
                "Attached ancestor reopened the narrowed primary through canonical/root lexical validation");
        require(overlap.resolve(new File(operations, "servers.json").getPath()).equals(new File(operations, "servers.json")),
                "Explicit ancestor attachment no longer allowed files outside the primary project");
        pass("overlappingAttachmentCannotReopenTheNarrowedPrimary");

        File moved = new File(fixture, "运维-moved");
        Files.move(extra.toPath(), moved.toPath());
        Files.createSymbolicLink(extra.toPath(), operations.toPath());
        attached.submit("继续", r -> require(read(r, new File(extra, "servers.json")).startsWith("错误："),
                "Replaced attached root silently authorized a different directory"));
        Files.delete(extra.toPath()); Files.move(moved.toPath(), extra.toPath());
        pass("replacedAttachedRootCannotBroadenCapturedAuthorization");
    }

    private static void deniedPathDiagnosticCapturesTheActiveWorkspace() throws Exception {
        Rig rig = new Rig(project, Collections.singletonList(project.getPath()));
        final List<String> diagnostics = new ArrayList<String>();
        class Store implements AgentLoop.Recorder, AgentLoop.ErrorRecorder {
            public void record(long sid, Message message) { }
            public void replace(long sid, List<Message> history) { }
            public void recordDiagnostic(long sid, String source, String summary, String detail) { diagnostics.add(detail); }
        }
        rig.loop.setRecorder(new Store());
        File denied = new File(operations, "servers.json");
        rig.client.calls = new JSONArray().put(new JSONObject().put("id", "denied-path").put("type", "function")
                .put("function", new JSONObject().put("name", "shell")
                        .put("arguments", new JSONObject().put("command", "ls '" + denied.getPath() + "'").toString())));
        rig.submit("读项目", r -> { });
        require(diagnostics.size() == 1, "Path denial did not produce exactly one diagnostic");
        JSONObject evidence = new JSONObject(diagnostics.get(0)), workspace = evidence.getJSONObject("workspace");
        require(evidence.getString("target_path").equals(denied.getPath())
                && workspace.getJSONArray("authorized_snapshot").getString(0).equals(project.getPath())
                && workspace.getJSONArray("task_focus").getString(0).equals(project.getPath())
                && workspace.getJSONArray("allowed_roots").length() == 1,
                "Path diagnostic lost the target, authorization snapshot, or task focus");
        require(!evidence.has("command") && !evidence.has("arguments"), "Path diagnostic unnecessarily copied the full command");
        pass("deniedPathDiagnosticCapturesTargetAuthorizationAndTaskFocus");
    }
    public static void main(String[] args) throws Exception {
        fixture = Files.createTempDirectory("backcast-task-workspace-").toFile();
        storage = new File(fixture, "storage"); project = new File(storage, "project"); operations = new File(storage, "operations");
        try {
            require(project.mkdirs() && operations.mkdirs(), "fixture dirs");
            Files.write(new File(project, "README.md").toPath(), "project text".getBytes("UTF-8"));
            Files.write(new File(operations, "servers.json").toPath(), "private-credential-fixture".getBytes("UTF-8"));
            Rig rig = new Rig();
            rig.submit("输出目录：" + project + "/\n读项目", r -> confined(r));
            pass("explicitProjectBlocksStorageAndSiblingOperations");
            rig.submit("继续", r -> confined(r));
            pass("continuationRetainsHumanProjectScope");
            rig.submit("继续", r -> {
                require(shell(r, "sh -c \"head '" + operations + "/servers.json'\"").startsWith("错误："), "Nested shell escaped scope");
                require(shell(r, "su -c \"ls '" + storage + "'\"").startsWith("错误："), "su script escaped scope");
                require(shell(r, "cd .. && ls").startsWith("错误："), "Parent traversal escaped scope");
            });
            pass("nestedShellAndParentTraversalAreChecked");
            rig.submit("继续", r -> {
                String result = r.get("find_files").run(new JSONObject().put("name", "servers"));
                require(new JSONObject(result).getJSONArray("matches").length() == 0, "Filename lookup crossed task scope");
                require(r.get("read").run(new JSONObject().put("path", "servers")).startsWith("错误："), "Bare name read crossed scope");
                require(r.get("read").run(new JSONObject().put("path", "README.md")).contains("project text"), "Relative reads did not use task directory");
            });
            pass("filenameSearchAndRelativeReadsUseTaskDirectory");
            rig.submit("继续", r -> {
                JSONObject temp = new JSONObject(r.get("temporary").run(new JSONObject().put("action", "directory")));
                String path = temp.getString("directory") + "/check.txt";
                require(r.get("write").run(new JSONObject().put("path", path).put("content", "temporary-data").put("purpose", "temporary")).startsWith("已写入"), "Temp write blocked");
                require(r.get("read").run(new JSONObject().put("path", path)).contains("temporary-data"), "Current temp read blocked");
            });
            pass("privateTemporaryLeaseRemainsAccessible");
            Rig child = new Rig(); child.loop.setDelegationParent(rig.loop);
            child.client.next = r -> confined(r);
            child.loop.submitDelegated("inspect " + operations + "/servers.json", "reference " + storage, "fixture-task", 1, child.loop.generation(), 1);
            require(child.client.next == null, "Child did not execute scope check");
            pass("delegatedTaskCannotBroadenParentHumanScope");
            rig.loop.compactNow(1, rig.loop.generation(), 1);
            List<Message> restored = new ArrayList<Message>();
            for (Message message : rig.loop.historySnapshot()) if (Compactor.isSummary(message)) restored.add(Message.fromCheckpointJson(message.toCheckpointJson()));
            require(!restored.isEmpty(), "No compaction handoff");
            Rig resumed = new Rig(); resumed.loop.loadHistory("system", restored);
            resumed.submit("继续", r -> confined(r));
            pass("compactionAndCheckpointPreserveLocalScope");
            Rig legacy = new Rig(); legacy.loop.loadHistory("system", Arrays.asList(Message.user("读 " + project + "/"), Message.assistant("Now read " + operations, null), Message.toolResult("a", "look in " + operations), Message.user("继续")));
            legacy.submit("继续", r -> confined(r));
            pass("legacyHistoryIgnoresAssistantAndToolSuggestedPaths");
            rig.submit("读取 " + operations + "/", r -> require(read(r, new File(operations, "servers.json")).contains("private-credential-fixture"), "Explicit human project change ignored"));
            pass("explicitHumanProjectChangeAppliesNextTurn");
            Rig quoted = new Rig(); quoted.submit("读 " + project + "/\n> unrelated " + operations + "/\n```\n" + operations + "/\n```", r -> confined(r));
            pass("quotedAndCodeEvidenceDoesNotExpandScope");
            Rig negative = new Rig();
            negative.submit("只读 " + project + "/，不要读取 " + operations + "/", r -> confined(r));
            negative.submit("只读 " + project + "/\n   > 错误输出来自 " + operations + "/\n~~~text\n" + operations + "/\n~~~", r -> confined(r));
            pass("forbiddenAndMarkdownQuotedPathsDoNotGrantAccess");
            File escape = new File(project, "outside"); Files.createSymbolicLink(escape.toPath(), operations.toPath());
            quoted.submit("继续", r -> {
                require(shell(r, "head outside/servers.json").startsWith("错误："), "Relative symlink escaped task scope");
                require(shell(r, "ls outside").startsWith("错误："), "Listed a sibling through a relative link");
            });
            pass("relativeShellSymlinksCannotExposeSiblingFiles");
            boolean denied = false;
            try { ToolPaths.resolve(project.getPath(), escape + "/servers.json", null, true, () -> { }); }
            catch (IllegalArgumentException expected) { denied = true; }
            require(denied, "Root lexical fallback reopened rejected symlink");
            Files.delete(escape.toPath());
            pass("rootUnavailableCannotReopenCanonicalScopeRejection");
            Rig outside = new Rig();
            outside.submit("读 " + new File(fixture, "unauthorized") + "/", r -> {
                require(shell(r, "ls").startsWith("错误："), "Rejected task path fell back to storage cwd");
                require(read(r, new File(project, "README.md")).startsWith("错误："), "Rejected task path reopened original roots");
            });
            pass("unresolvableTaskScopeFailsClosedForDefaultCommands");
            WorkspaceRoots many = new WorkspaceRoots(storage.getPath(), Collections.singletonList(fixture.getPath()));
            require(many.forTask(Collections.emptyList()).focusDirectories().equals(Collections.singletonList(storage)), "Default focus included unrelated saved roots");
            require(many.forTask(Arrays.asList(project.getPath(), operations.getPath())).focusDirectories().size() == 2, "Explicit multiple task directories were lost");
            WorkspaceRoots android = new WorkspaceRoots("/storage/emulated/0", null);
            require(android.forTask(Collections.singletonList("/sdcard/wh/mcp/s0165/")).directories().equals(Collections.singletonList(new File("/storage/emulated/0/wh/mcp/s0165"))), "Android /sdcard alias did not narrow project");
            WorkspaceRoots attachedAndroid = new WorkspaceRoots("/storage/emulated/0/测试工程",
                    Arrays.asList("/storage/emulated/0/测试工程", "/storage/emulated/0/运维"))
                    .forTask(Collections.emptyList());
            require(attachedAndroid.resolve("/sdcard/运维/report.md").getPath().equals("/storage/emulated/0/运维/report.md")
                    && attachedAndroid.lexicalPath("/sdcard/运维/report.md").getPath().equals("/storage/emulated/0/运维/report.md")
                    && attachedAndroid.focusDirectories().equals(Collections.singletonList(new File("/storage/emulated/0/测试工程"))),
                    "Attached /sdcard alias differed between Java and root fallback or changed default focus");
            pass("multipleExplicitDirectoriesAndAndroidStorageAlias");
            File moved = new File(storage, "project-moved");
            Files.move(project.toPath(), moved.toPath());
            Files.createSymbolicLink(project.toPath(), operations.toPath());
            quoted.submit("继续", r -> {
                require(read(r, new File(operations, "servers.json")).startsWith("错误："), "Replaced task root authorized the symlink target on continuation");
                require(shell(r, "ls").startsWith("错误："), "Replaced task root changed default cwd to sibling");
            });
            Files.delete(project.toPath());
            Files.move(moved.toPath(), project.toPath());
            pass("taskRootReplacementCannotBroadenNextTurnScope");
            attachedAccessRemainsExplicitAcrossTaskSnapshots();
            deniedPathDiagnosticCapturesTheActiveWorkspace();
            System.out.println(passed + " task workspace tests passed");
        } finally { remove(fixture); }
    }
    static void remove(File file) throws Exception {
        if (!Files.isSymbolicLink(file.toPath())) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); }
        Files.deleteIfExists(file.toPath());
    }
}
