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
        Client() { super(new Config("http://localhost", "fixture", "fixture")); }
        @Override public Reply send(List<Message> messages, JSONArray tools, Sink sink) {
            if (next != null) { Check action = next; next = null;
                try { action.run(registry); } catch (Exception error) { throw new AssertionError(error); }
            }
            Reply reply = new Reply(); reply.content = "done"; return reply;
        }
    }
    static final class Rig {
        final Client client = new Client();
        final ToolRegistry registry = new ToolRegistry();
        final AgentLoop loop;
        Rig() {
            TemporaryWorkspace temp = new TemporaryWorkspace(storage.getPath(), false, new File(fixture, "private-" + System.nanoTime()), 1);
            registry.register(new ReadTool(storage.getPath(), false, temp));
            registry.register(new FindFilesTool(storage.getPath(), false, temp));
            registry.register(new ShellTool(false, storage.getPath(), temp));
            registry.register(new WriteTool(storage.getPath(), false, temp));
            registry.register(new TemporaryTool(temp, storage.getPath(), Collections.singletonList(storage.getPath())));
            client.registry = registry;
            loop = new AgentLoop(client, registry, new AgentLoop.Quiet());
            loop.reset("Only do the user's task."); loop.setEnvironment("Only do the user's task.", storage.getPath());
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
            require(many.forTask(Collections.emptyList()).directories().equals(Collections.singletonList(storage)), "Default scope included unrelated saved roots");
            require(many.forTask(Arrays.asList(project.getPath(), operations.getPath())).directories().size() == 2, "Explicit multiple task directories were lost");
            WorkspaceRoots android = new WorkspaceRoots("/storage/emulated/0", null);
            require(android.forTask(Collections.singletonList("/sdcard/wh/mcp/s0165/")).directories().equals(Collections.singletonList(new File("/storage/emulated/0/wh/mcp/s0165"))), "Android /sdcard alias did not narrow project");
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
            System.out.println(passed + " task workspace tests passed");
        } finally { remove(fixture); }
    }
    static void remove(File file) throws Exception {
        if (!Files.isSymbolicLink(file.toPath())) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); }
        Files.deleteIfExists(file.toPath());
    }
}
