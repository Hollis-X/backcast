package com.mkei.backcast.tool;

import com.mkei.backcast.agent.Tool;
import org.json.JSONArray;
import org.json.JSONObject;

/** 把较长的任务拆成可见的小步骤，随进度更新。清单只给用户看，工具本身不保存状态。 */
public final class PlanTool implements Tool {
    public static final String NAME = "update_plan";
    private static final int MAX_STEPS = 12, MAX_TEXT = 200;

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "把需要三步以上的任务拆成几个小步骤，用户能在界面看到这份清单和进度。"
                + "开始前调用一次列出全部步骤，第一步标 in_progress；每完成一步立刻再调用，把它标 completed，"
                + "把下一步标 in_progress。同一时间只能有一个 in_progress；每次都传完整清单。"
                + "简单问答和一两步能做完的事不要用。";
    }

    @Override public JSONObject parameters() {
        try {
            JSONObject item = new JSONObject().put("type", "object")
                    .put("properties", new JSONObject()
                            .put("content", new JSONObject().put("type", "string").put("description", "这一步要做什么，一句话"))
                            .put("status", new JSONObject().put("type", "string")
                                    .put("enum", new JSONArray().put("pending").put("in_progress").put("completed"))))
                    .put("required", new JSONArray().put("content").put("status"))
                    .put("additionalProperties", false);
            return new JSONObject().put("type", "object")
                    .put("properties", new JSONObject().put("items", new JSONObject().put("type", "array")
                            .put("description", "完整的步骤清单，按执行顺序，1 到 " + MAX_STEPS + " 项").put("items", item)))
                    .put("required", new JSONArray().put("items")).put("additionalProperties", false);
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }

    /**
     * 把模型传来的清单统一成 [{content,status}]。模型常按别的工具的习惯传
     * plan/step、todos/title 之类的名字，这里都认；解析不了返回 null。
     */
    public static JSONArray normalize(JSONObject args) {
        if (args == null) return null;
        JSONArray raw = null;
        for (String key : new String[] { "items", "plan", "todos", "steps", "tasks" }) {
            raw = args.optJSONArray(key);
            if (raw != null) break;
        }
        if (raw == null) return null;
        JSONArray out = new JSONArray();
        try {
            for (int i = 0; i < raw.length(); i++) {
                Object one = raw.opt(i);
                String content = "", status = "pending";
                if (one instanceof JSONObject) {
                    JSONObject o = (JSONObject) one;
                    for (String key : new String[] { "content", "step", "title", "text", "task", "description" }) {
                        content = o.optString(key, "").trim();
                        if (content.length() > 0) break;
                    }
                    String s = o.optString("status", "pending").trim().toLowerCase(java.util.Locale.ROOT);
                    status = "completed".equals(s) || "done".equals(s) || "complete".equals(s) ? "completed"
                            : "in_progress".equals(s) || "in-progress".equals(s) || "doing".equals(s) || "active".equals(s)
                            ? "in_progress" : "pending";
                } else if (one instanceof String) {
                    content = ((String) one).trim();
                }
                out.put(new JSONObject().put("content", content).put("status", status));
            }
        } catch (Exception invalid) { return null; }
        return out;
    }

    /** 参数文本（可能还在流式生成）→ 统一清单；解析不了返回 null。 */
    public static JSONArray normalize(String args) {
        try { return normalize(new JSONObject(args)); } catch (Exception partial) { return null; }
    }

    /** 清单不合规时的错误文字，合规返回 null。工具和界面共用：界面只画这里放行的计划。 */
    public static String problem(JSONArray items) {
        if (items == null || items.length() == 0 || items.length() > MAX_STEPS)
            return "错误：items 必须是 1 到 " + MAX_STEPS + " 项的清单。";
        int doing = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            String content = item == null ? "" : item.optString("content", "").trim();
            String status = item == null ? "" : item.optString("status", "");
            if (content.length() == 0 || content.length() > MAX_TEXT) return "错误：第 " + (i + 1) + " 步的 content 不能为空或超过 " + MAX_TEXT + " 字。";
            if ("in_progress".equals(status)) doing++;
            else if (!"completed".equals(status) && !"pending".equals(status)) return "错误：第 " + (i + 1) + " 步的 status 必须是 pending、in_progress 或 completed。";
        }
        return doing > 1 ? "错误：同一时间只能有一个 in_progress。" : null;
    }

    @Override public String run(JSONObject args) {
        JSONArray items = normalize(args);
        String problem = problem(items);
        if (problem != null) return problem;
        int done = 0;
        String current = "";
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            String status = item.optString("status", "");
            if ("completed".equals(status)) done++;
            else if ("in_progress".equals(status)) current = item.optString("content", "").trim();
        }
        String summary = "计划已更新：共 " + items.length() + " 步，已完成 " + done + " 步";
        if (done == items.length()) return summary + "，全部完成。";
        return summary + (current.length() == 0 ? "，没有进行中的步骤，请开始下一步。" : "，进行中：" + current + "。");
    }

    @Override public void abort() { }
}
