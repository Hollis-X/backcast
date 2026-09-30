package com.mkei.backcast.tool;

import com.mkei.backcast.agent.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** 整篇覆盖或新建文件。局部修改不走这里。 */
public class WriteTool implements Tool {

    private final String workDir;
    private final boolean useRoot;
    private volatile int epoch;

    public WriteTool(String workDir, boolean useRoot) {
        this.workDir = workDir == null || workDir.length() == 0 ? null : workDir;
        this.useRoot = useRoot;
    }

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String description() {
        return "写入工作目录内的整个文件，目录外路径会被拒绝。不存在会创建，已存在会整篇覆盖，父目录一并创建。"
                + "只用于新文件或整篇重写。改已有文件里的几处用 edit。";
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

            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", new JSONArray().put("path").put("content"));
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
        int mine = epoch;
        String path = args.optString("path", "");
        if (path.length() == 0) {
            return "错误：path 为空。";
        }
        if (!args.has("content") || args.isNull("content") || !(args.opt("content") instanceof String)) {
            return "错误：content 必须是字符串。";
        }
        String content = args.optString("content", "");
        File file;
        try { file = ToolPaths.resolve(workDir, path); }
        catch (IllegalArgumentException error) { return "错误：" + error.getMessage(); }
        ToolPaths.Probe probe = ToolPaths.probe(file, useRoot);
        if (probe.directory) {
            return "错误：这是目录：" + file.getAbsolutePath();
        }
        synchronized (ToolPaths.lock(file)) {
            if (epoch != mine) {
                return "已停止。";
            }
            try {
                ToolPaths.writeBytes(file, content.getBytes("UTF-8"), useRoot);
            } catch (IllegalArgumentException e) {
                return "错误：" + e.getMessage();
            }
        }
        return "已写入 " + path;
    }
}