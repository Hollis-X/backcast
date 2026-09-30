package com.mkei.backcast.tool;

import com.mkei.backcast.agent.SubAgentManager;
import com.mkei.backcast.agent.Tool;
import com.mkei.backcast.agent.ToolRegistry;
import org.json.JSONArray;
import org.json.JSONObject;

/** Coordination tools share one session-owned manager, including in child loops. */
public final class SubAgentTools implements Tool {
    private final SubAgentManager manager;
    private final String owner, name;

    private SubAgentTools(SubAgentManager manager, String owner, String name) {
        this.manager = manager; this.owner = owner; this.name = name;
    }

    public static void register(ToolRegistry registry, SubAgentManager manager, String owner) {
        for (String name : new String[]{"spawn_agent", "send_message", "list_agents", "wait_agent", "close_agent", "read_agent_result"})
            registry.register(new SubAgentTools(manager, owner, name));
    }

    @Override public String name() { return name; }
    @Override public String description() {
        if ("spawn_agent".equals(name)) return "创建独立子任务并并行执行。只委派可独立推进的具体工作；可选携带有界父上下文。"
                + "返回任务 id 后可发送追加任务复用空闲子 agent，最终交付前必须等待并收集结果，不要重复自己已委派的工作。";
        if ("send_message".equals(name)) return "向同会话 agent 发送消息。目标 id=main 表示父会话；忙碌子任务排队处理，空闲子任务复用已有上下文继续执行。";
        if ("list_agents".equals(name)) return "查看当前会话子任务状态、最终结果、待处理消息及发给自己的收件箱。";
        if ("wait_agent".equals(name)) return "等待指定子任务或所有子任务完成，最长60秒。返回结果与pending状态；仍有pending时继续等待或推进其他工作。";
        if ("read_agent_result".equals(name)) return "分片读取子任务完整最终结果；list或wait返回resultTruncated时用本工具取剩余片段并核验。";
        return "关闭不再需要的子任务及其后代，取消模型与工具执行。不能关闭父会话；关闭后不能再复用。";
    }

    @Override public JSONObject parameters() {
        try {
            JSONObject properties = new JSONObject(); JSONArray required = new JSONArray();
            if ("spawn_agent".equals(name)) {
                properties.put("task", field("string", "独立子任务的完整要求")); required.put("task");
                properties.put("name", field("string", "简短任务名"));
                properties.put("fork", field("boolean", "携带父最近有界上下文，默认true"));
            } else if ("send_message".equals(name)) {
                properties.put("target", field("string", "目标agent id或main")); required.put("target");
                properties.put("message", field("string", "消息或追加独立任务")); required.put("message");
            } else if ("wait_agent".equals(name)) {
                properties.put("target", field("string", "目标agent id，省略表示等待所有子任务"));
                properties.put("timeout_ms", field("integer", "0到60000，默认30000"));
            } else if ("close_agent".equals(name)) {
                properties.put("target", field("string", "要关闭的子任务id")); required.put("target");
            } else if ("list_agents".equals(name)) {
                properties.put("cursor", field("integer", "从上次返回nextCursor续读，默认0"));
            } else if ("read_agent_result".equals(name)) {
                properties.put("target", field("string", "目标子任务id")); required.put("target");
                properties.put("offset", field("integer", "读取起点字符数，默认0"));
                properties.put("limit", field("integer", "每片1到8000字符，默认8000"));
            }
            return new JSONObject().put("type", "object").put("properties", properties)
                    .put("required", required).put("additionalProperties", false);
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }

    private static JSONObject field(String type, String description) throws Exception {
        return new JSONObject().put("type", type).put("description", description);
    }

    @Override public String run(JSONObject args) throws Exception {
        if ("spawn_agent".equals(name)) return manager.spawn(owner, args.optString("name", ""),
                args.optString("task", ""), args.optBoolean("fork", true)).toString();
        if ("send_message".equals(name)) return manager.send(owner, args.optString("target", ""), args.optString("message", "")).toString();
        if ("list_agents".equals(name)) return manager.list(owner, args.optInt("cursor", 0)).toString();
        if ("read_agent_result".equals(name)) return manager.readResult(owner, args.optString("target", ""),
                args.optInt("offset", 0), args.optInt("limit", 8000)).toString();
        if ("wait_agent".equals(name)) {
            String target = args.optString("target", "");
            manager.waitFor(owner, target, args.optLong("timeout_ms", 30000L));
            return manager.collectResults(owner, target).toString();
        }
        return manager.close(owner, args.optString("target", "")).toString();
    }

    @Override public void abort() { manager.abortOwned(owner); }
}
