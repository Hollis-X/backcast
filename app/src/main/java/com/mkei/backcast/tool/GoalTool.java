package com.mkei.backcast.tool;

import com.mkei.backcast.agent.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 模型只能把目标标成完成或达不到。暂停、改写、清空都由用户来。
 */
public class GoalTool implements Tool {

    private final com.mkei.backcast.agent.AgentLoop loop;

    public GoalTool(com.mkei.backcast.agent.AgentLoop loop) {
        this.loop = loop;
    }

    @Override
    public String name() {
        return "update_goal";
    }

    @Override
    public String description() {
        return "更新当前目标的状态。只有用户设了目标时才能用。"
                + "status 只能是 complete 或 blocked。"
                + "complete 表示完成审计已经通过，每一条交付物都有证据。"
                + "blocked 表示目标太空、没有可核验的标准，或者现有条件下确实做不到，reason 要写清原因。"
                + "不能用这个工具暂停、清空或改写目标。";
    }

    @Override
    public JSONObject parameters() {
        try {
            JSONObject status = new JSONObject();
            status.put("type", "string");
            status.put("enum", new JSONArray().put("complete").put("blocked"));
            status.put("description", "complete 或 blocked");

            JSONObject reason = new JSONObject();
            reason.put("type", "string");
            reason.put("description", "blocked 时必填：目标为什么太泛或达不到");

            JSONObject props = new JSONObject();
            props.put("status", status);
            props.put("reason", reason);

            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", new JSONArray().put("status"));
            return schema;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public void abort() {
    }

    @Override
    public String run(JSONObject args) {
        String status = args.optString("status", "");
        String reason = args.optString("reason", "");
        return loop.closeGoal(status, reason);
    }
}
