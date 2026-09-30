package com.mkei.backcast.tool;

import com.mkei.backcast.agent.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

/** 查看持久化目标及累计用量，不改变目标状态。 */
public class GetGoalTool implements Tool {

    private final com.mkei.backcast.agent.AgentLoop loop;

    public GetGoalTool(com.mkei.backcast.agent.AgentLoop loop) {
        this.loop = loop;
    }

    @Override
    public String name() {
        return "get_goal";
    }

    @Override
    public String description() {
        return "查看当前会话目标，包括正文、状态、token 预算、累计已用 token、剩余 token 和累计用时。";
    }

    @Override
    public JSONObject parameters() {
        try {
            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("properties", new JSONObject());
            schema.put("required", new JSONArray());
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
        return loop.goalReport();
    }
}
