package com.mkei.backcast.tool;

import com.mkei.backcast.agent.TemporaryCleanup;
import com.mkei.backcast.agent.Tool;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TemporaryTool implements Tool, TemporaryCleanup {
    private final TemporaryWorkspace workspace;
    private final WorkspaceRoots roots;

    public TemporaryTool(TemporaryWorkspace workspace) {
        this.workspace = workspace;
        roots = null;
    }

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
                + "目标完成前及本轮结束、失败、停止时也会自动清理。";
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
        if (roots == null) workspace.beginTurn(); else workspace.beginTurn(roots);
    }
    @Override public String cleanupTemporary() { return workspace.cleanup(); }
    @Override public String finishTurn() { return workspace.finishTurn(); }
}
