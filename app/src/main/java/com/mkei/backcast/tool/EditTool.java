package com.mkei.backcast.tool;

import com.mkei.backcast.agent.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 按原文替换一个文件里的若干处。对不上就不写盘。
 */
public class EditTool implements Tool {

    private static final int MAX_LOAD = 8 * 1024 * 1024;

    private final String workDir;
    private final boolean useRoot;
    private final TemporaryWorkspace temporary;
    private volatile int epoch;

    public EditTool(String workDir, boolean useRoot, TemporaryWorkspace temporary) {
        this.workDir = workDir == null || workDir.length() == 0 ? null : workDir;
        this.useRoot = useRoot;
        this.temporary = temporary;
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "按原文替换项目文件或本轮 temporary 登记的 App 私有临时文件（使用返回的绝对路径）。其他目录会被拒绝。edits 是数组，每项有 oldText 和 newText。"
                + "每一处都对着调用前的原文匹配，不是对着前一处替换之后的文本。"
                + "oldText 不能为空，必须唯一，且各处互不重叠。对不上、不唯一、重叠、或替换后没有变化，都不会写盘。"
                + "同一文件里分开的几处修改放进同一次 edits，不要连着调用多次。"
                + "oldText 尽量短，但要能唯一对上，不要为了连接远处的修改带上大段没变的内容。挨在一起的改动合成一处。"
                + "局部修改用这个工具，不要用 write，也不要靠 shell 重定向。";
    }

    @Override
    public JSONObject parameters() {
        try {
            JSONObject oldText = new JSONObject();
            oldText.put("type", "string");
            oldText.put("description", "原文里要替换的一段，必须唯一，且不与其它 oldText 重叠");

            JSONObject newText = new JSONObject();
            newText.put("type", "string");
            newText.put("description", "替换成的文本，空字符串表示删掉这一段");

            JSONObject itemProps = new JSONObject();
            itemProps.put("oldText", oldText);
            itemProps.put("newText", newText);

            JSONObject item = new JSONObject();
            item.put("type", "object");
            item.put("properties", itemProps);
            item.put("required", new JSONArray().put("oldText").put("newText"));

            JSONObject edits = new JSONObject();
            edits.put("type", "array");
            edits.put("items", item);
            edits.put("description", "一处或多处替换。都对着原文匹配，不要重叠");

            JSONObject path = new JSONObject();
            path.put("type", "string");
            path.put("description", "要改的文件，相对工作目录或绝对路径");

            JSONObject props = new JSONObject();
            props.put("path", path);
            props.put("edits", edits);

            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", new JSONArray().put("path").put("edits"));
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
        JSONArray rawEdits = normalizeEdits(args);
        if (rawEdits == null) {
            return "错误：edits 解析失败。它应是数组，每项含 oldText 和 newText。";
        }
        if (rawEdits.length() == 0) {
            return "错误：edits 至少要有一处替换。";
        }
        List<EditDiff.Edit> edits = new ArrayList<EditDiff.Edit>();
        for (int i = 0; i < rawEdits.length(); i++) {
            if (!(rawEdits.opt(i) instanceof JSONObject)) {
                return "错误：edits[" + i + "] 必须是对象。";
            }
            JSONObject item = rawEdits.getJSONObject(i);
            if (!item.has("oldText") || !item.has("newText")
                    || item.isNull("oldText") || item.isNull("newText")) {
                return "错误：edits[" + i + "] 需要 oldText 和 newText。";
            }
            edits.add(new EditDiff.Edit(item.optString("oldText", ""),
                    item.optString("newText", "")));
        }

        File file;
        try {
            file = ToolPaths.resolve(workDir, path, temporary);
            if (temporary != null && temporary.isOwnershipMarker(file)) {
                return "错误：不能修改临时目录所有权标记。";
            }
        }
        catch (Exception error) { return "错误：" + error.getMessage(); }
        ToolPaths.Probe probe = ToolPaths.probe(file, useRoot);
        if (!probe.exists) {
            if (probe.denied) {
                return "错误：没有权限读取：" + file.getAbsolutePath();
            }
            return "错误：不存在：" + file.getAbsolutePath();
        }
        if (probe.directory) {
            return "错误：这是目录：" + file.getAbsolutePath();
        }
        synchronized (ToolPaths.lock(file)) {
            if (epoch != mine) {
                return "已停止。";
            }
            byte[] previous;
            try {
                previous = ToolPaths.readBytes(file, MAX_LOAD, useRoot);
            } catch (IllegalArgumentException e) {
                return "错误：" + e.getMessage();
            }
            if (epoch != mine) {
                return "已停止。";
            }
            String raw = new String(previous, "UTF-8");
            EditDiff.Outcome outcome = EditDiff.apply(raw, edits, path);
            if (outcome.error != null) {
                return "错误：" + outcome.error;
            }
            if (epoch != mine) {
                return "已停止。";
            }
            try {
                ToolPaths.writeBytes(file, outcome.content.getBytes("UTF-8"), useRoot);
            } catch (IllegalArgumentException e) {
                return "错误：" + e.getMessage();
            }
        }
        return "已替换 " + edits.size() + " 处：" + path;
    }

    /**
     * 有的模型会把 edits 当成 JSON 字符串，或只传一个对象，
     * 或把 oldText / newText 放在顶层。这里收成数组。
     */
    private static JSONArray normalizeEdits(JSONObject args) {
        Object raw = args.opt("edits");
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            try {
                if (text.startsWith("[")) {
                    raw = new JSONArray(text);
                } else if (text.startsWith("{")) {
                    raw = new JSONObject(text);
                }
            } catch (Exception e) {
                return null;
            }
        }
        JSONArray edits = new JSONArray();
        if (raw instanceof JSONArray) {
            JSONArray incoming = (JSONArray) raw;
            for (int i = 0; i < incoming.length(); i++) {
                edits.put(incoming.opt(i));
            }
        } else if (raw instanceof JSONObject) {
            edits.put(raw);
        } else if (raw != null) {
            return null;
        }
        if (args.has("oldText") && args.has("newText")
                && !args.isNull("oldText") && !args.isNull("newText")) {
            JSONObject one = new JSONObject();
            try {
                one.put("oldText", args.get("oldText"));
                one.put("newText", args.get("newText"));
            } catch (Exception e) {
                return null;
            }
            edits.put(one);
        }
        return edits;
    }
}
