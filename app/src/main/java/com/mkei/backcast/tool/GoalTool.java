package com.mkei.backcast.tool;

import com.mkei.backcast.agent.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 模型在完成或阻塞审计通过后更新目标，或按用户明确要求暂停。
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
                + "status 只能是 complete、blocked 或 paused。"
                + "只有整个目标确实达成、每条要求均有当前证据且没有剩余必需工作时，才用 complete。"
                + "只有同一阻塞条件连续至少三轮重复出现（含用户发起轮和自动续跑轮），"
                + "且没有用户输入或外部状态变化就无法取得实质进展时，才用 blocked；reason 写明阻塞。"
                + "用户恢复 blocked 目标后重新开始三轮阻塞审计。工作难、慢、不确定、未完成或希望澄清，"
                + "都不足以标 blocked。paused 仅用于用户明确要求暂停，不能自行暂停；预算限制优先于暂停。"
                + "不能用这个工具恢复、设置预算限制、清空或改写目标，也不能因为预算将尽或打算停手而标完成。"
                + "调用成功后报告结果并停止目标工作；完成有 token 预算的目标时，报告返回的最终已用 token。";
    }

    @Override
    public JSONObject parameters() {
        try {
            JSONObject status = new JSONObject();
            status.put("type", "string");
            status.put("enum", new JSONArray().put("complete").put("blocked").put("paused"));
            status.put("description", "complete 仅在目标全部完成且已验证时使用；blocked 要求同一阻塞连续至少三轮；"
                    + "paused 必须有用户明确的暂停要求");

            JSONObject reason = new JSONObject();
            reason.put("type", "string");
            reason.put("description", "blocked 时必填：连续至少三轮重复出现的阻塞条件及为何无法继续取得实质进展");

            JSONObject props = new JSONObject();
            props.put("status", status);
            props.put("reason", reason);

            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", new JSONArray().put("status"));
            schema.put("additionalProperties", false);
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
