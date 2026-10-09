package com.mkei.backcast.tool;

import com.mkei.backcast.agent.TemporaryCleanup;
import com.mkei.backcast.agent.Tool;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TemporaryTool implements Tool, TemporaryCleanup, com.mkei.backcast.agent.ToolRegistry.WorkspaceScoped {
    private final TemporaryWorkspace workspace;
    private final WorkspaceRoots roots;

    /** Capture the registry's authorization before a later settings change retargets it. */
    public TemporaryTool(TemporaryWorkspace workspace, String directory, java.util.List<String> directories) {
        this.workspace = workspace;
        roots = new WorkspaceRoots(directory, directories);
    }

    @Override public String name() { return "temporary"; }

    @Override public String description() {
        return "管理本轮临时材料。directory 返回 App 私有存储中的专用临时目录，与项目工作目录分开；cleanup 立即删除本轮登记的临时材料。"
                + "临时脚本、临时验证辅助文件、中间结果必须放入此目录，用完立即 cleanup。"
                + "正式测试和用户交付物不要放入此目录；正式测试应归类到项目已有测试目录或 tests/。"
                + "目标完成前及本轮结束、失败、停止时也会自动清理。"
                + ToolPaths.workspaceDescription(roots.primary(), workspace)
                + "权限失败、空目录或文件中的链接都不能授权访问其父级或兄弟目录。";
    }

    @Override public JSONObject parameters() {
        try {
            JSONObject action = new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("directory").put("cleanup"));
            return new JSONObject().put("type", "object")
                    .put("properties", new JSONObject().put("action", action))
                    .put("required", new JSONArray().put("action")).put("additionalProperties", false);
        } catch (Exception failure) { return new JSONObject(); }
    }

    @Override public String run(JSONObject args) throws Exception {
        String action = args.optString("action", "");
        if ("directory".equals(action)) {
            return new JSONObject().put("directory", workspace.directory().getPath()).toString();
        }
        if ("cleanup".equals(action)) {
            String error = workspace.cleanup();
            return error == null ? "本轮临时材料已清理。" : "错误：临时清理失败：" + error;
        }
        return "错误：action 只能是 directory 或 cleanup。";
    }

    @Override public void abort() { }
    @Override public void beginTurn() {
        workspace.beginTurn(roots);
    }
    @Override public void restrictWorkspace(java.util.List<String> paths) { workspace.restrictToTask(roots, paths); }
    @Override public JSONObject workspaceDiagnostic() {
        try {
            WorkspaceRoots current = workspace.projectRoots(roots.primary());
            return new JSONObject().put("primary", roots.primary() == null ? "" : roots.primary())
                    .put("authorized_snapshot", paths(roots.directories()))
                    .put("allowed_roots", paths(current.directories()))
                    .put("task_focus", paths(current.focusDirectories()));
        } catch (Exception failure) { return null; }
    }
    private static JSONArray paths(java.util.List<java.io.File> directories) {
        JSONArray result = new JSONArray();
        for (java.io.File directory : directories) result.put(directory.getPath());
        return result;
    }
    @Override public String cleanupTemporary() { return workspace.cleanup(); }
    @Override public String finishTurn() { return workspace.finishTurn(); }
}
