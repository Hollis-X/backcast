package com.mkei.backcast.tool;

import com.mkei.backcast.agent.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** 整篇覆盖或新建文件。局部修改不走这里。 */
public class WriteTool implements Tool {

    private final String workDir;
    private final boolean useRoot;
    private final TemporaryWorkspace temporary;
    private volatile int epoch;

    public WriteTool(String workDir, boolean useRoot, TemporaryWorkspace temporary) {
        this.workDir = workDir == null || workDir.length() == 0 ? null : workDir;
        this.useRoot = useRoot;
        this.temporary = temporary;
    }

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String description() {
        return "写入项目工作目录内的整个文件；purpose=temporary 时写入 App 私有的本轮临时目录。其他目录会被拒绝。不存在会创建，已存在会整篇覆盖，父目录一并创建。"
                + "只用于新文件或整篇重写。改已有文件里的几处用 edit。"
                + "purpose 必须按真实用途填写：temporary 临时材料、test 正式测试、deliverable 项目文件或交付物。"
                + "temporary 的相对路径按专用临时目录解析，绝对路径必须在该临时目录内；用完立即 temporary cleanup。"
                + "test 必须是需要长期保留的正式测试，归类到项目已有测试目录或 tests/，不要把一次性验证脚本标成 test。";
    }

    @Override
    public JSONObject parameters() {
        try {
            JSONObject path = new JSONObject();
            path.put("type", "string");
            path.put("description", "要写的文件，相对工作目录或绝对路径");

            JSONObject content = new JSONObject();
            content.put("type", "string");
            content.put("description", "文件的完整内容");

            JSONObject props = new JSONObject();
            props.put("path", path);
            props.put("content", content);
            props.put("purpose", new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("temporary").put("test").put("deliverable")));

            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("properties", props);
            JSONArray required = new JSONArray().put("path").put("content");
            if (temporary != null) required.put("purpose");
            schema.put("required", required);
            return schema;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public void abort() {
        epoch++;
    }

    @Override
    public String run(JSONObject args) throws Exception {
        final int mine = epoch;
        ToolchainInstaller.Cancellation cancellation = new ToolchainInstaller.Cancellation() {
            @Override public void check() throws Exception {
                if (epoch != mine || Thread.currentThread().isInterrupted()) throw new InterruptedException("已停止。");
            }
        };
        String path = args.optString("path", "");
        if (path.length() == 0) {
            return "错误：path 为空。";
        }
        if (!args.has("content") || args.isNull("content") || !(args.opt("content") instanceof String)) {
            return "错误：content 必须是字符串。";
        }
        String content = args.optString("content", "");
        String purpose = args.optString("purpose", temporary == null ? "deliverable" : "");
        if (!"temporary".equals(purpose) && !"test".equals(purpose) && !"deliverable".equals(purpose)) {
            return "错误：purpose 必须是 temporary、test 或 deliverable。一次性验证材料必须用 temporary。";
        }
        File file;
        try {
            if ("temporary".equals(purpose)) {
                if (temporary == null) return "错误：当前没有临时材料管理器。";
                file = temporary.resolveTemporary(path);
            } else {
                file = ToolPaths.resolve(workDir, path, temporary, useRoot, cancellation);
                if (temporary != null && temporary.contains(file)) {
                    return "错误：专用临时目录只能存放 purpose=temporary 的材料。";
                }
                if ("test".equals(purpose) && !ToolPaths.organizedTest(workDir, file, temporary)) {
                    return "错误：正式测试必须归类到项目已有测试目录或 tests/，不要散放在项目根目录。"
                            + "一次性验证脚本请用 purpose=temporary。";
                }
            }
        } catch (Exception error) { return "错误：" + error.getMessage(); }
        ToolPaths.Probe probe = ToolPaths.probe(file, useRoot, cancellation);
        if (probe.directory) {
            return "错误：这是目录：" + file.getAbsolutePath();
        }
        synchronized (ToolPaths.lock(file)) {
            if (epoch != mine) {
                return "已停止。";
            }
            try {
                if (!file.equals(ToolPaths.resolve(workDir, file.getPath(), temporary, useRoot, cancellation)))
                    return "错误：文件路径在写入前发生变化。";
                ToolPaths.writeBytes(file, content.getBytes("UTF-8"), useRoot, cancellation);
            } catch (IllegalArgumentException e) {
                return "错误：" + e.getMessage();
            }
        }
        return "已写入 " + ("temporary".equals(purpose) ? file.getAbsolutePath() : path);
    }
}
