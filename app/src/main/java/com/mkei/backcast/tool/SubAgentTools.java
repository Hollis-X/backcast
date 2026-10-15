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

    /** Only main creates and closes children; a child gets the tools to talk, wait and report. */
    public static void register(ToolRegistry registry, SubAgentManager manager, String owner) {
        boolean main = SubAgentManager.ROOT.equals(owner);
        for (String name : new String[]{"spawn_agent", "send_message", "report_agent", "list_agents", "close_agent"}) {
            if (!main && ("spawn_agent".equals(name) || "close_agent".equals(name))) continue;
            if (main && "report_agent".equals(name)) continue;
            registry.register(new SubAgentTools(manager, owner, name));
        }
    }

    @Override public String name() { return name; }
    @Override public String description() {
        if ("spawn_agent".equals(name)) return "创建子代理在后台并行执行任务，立即返回它的名字和路径（/main/名字）。同级名字不会重复。"
                + "子代理跑完后，你会自动收到一条通知，里面有它的最终答复；不需要也不应该轮询或等待，可以继续做自己的事，或直接结束这一轮，完成时会自动叫醒你。"
                + "默认全新上下文；fork=true才复制你当前完整的对话历史，独立工作用新上下文，依赖已有分析才fork。"
                + "只有主会话能创建子代理；只有 ultra 允许主动派活，其他思考程度仅在真实用户明确要求子 agent 时可调用。"
                + "想让已完成的子代理继续做事，用 send_message，它会带着原来的上下文接着干。";
        if ("send_message".equals(name)) return "给会话里的 agent 发消息：main、子代理、同级子代理都可以，目标写名字、路径(/main/a)或id。"
                + "对方正在运行时，消息排进收件箱，在它下一次模型请求前送到，不打断当前的API或工具；"
                + "对方已完成或失败时，消息会唤醒它并作为它的下一个任务，沿用原来的上下文（需要有派活授权）；"
                + "对方被用户停止或已关闭则无法发送。发给 main 的消息只进收件箱。";
        if ("report_agent".equals(name)) return "保存本次任务的阶段进度、部分成果或错误，并同步通知 main。progress、partial、error分别保存；最终成果在任务正常结束时单独保存，汇报不会覆盖最终成果。";
        if ("list_agents".equals(name)) return "列出当前会话里所有子代理：名字、路径、状态、进度、最终结果和待处理消息，以及发给自己的收件箱。path_prefix可只看某个分支。";
        return "关闭已经完成、停止或失败的子代理，保留任务与成果。运行或排队中会拒绝关闭。";
    }

    @Override public JSONObject parameters() {
        try {
            JSONObject properties = new JSONObject(); JSONArray required = new JSONArray();
            if ("spawn_agent".equals(name)) {
                properties.put("task", field("string", "交给子代理的完整任务要求")); required.put("task");
                properties.put("name", field("string", "子代理名字，用作路径的一段，如 parser；省略则自动命名为 agent、agent_2…"));
                properties.put("fork", field("boolean", "由派活会话按任务需要选择；依赖已有分析或接续工作才true，独立任务false，默认false。true复制父有效完整上下文"));
            } else if ("send_message".equals(name)) {
                properties.put("target", field("string", "名字、路径(/main/a)、id，或 main")); required.put("target");
                properties.put("message", field("string", "要发送的消息文本")); required.put("message");
            } else if ("report_agent".equals(name)) {
                properties.put("kind", field("string", "progress、partial或error").put("enum", new JSONArray().put("progress").put("partial").put("error"))); required.put("kind");
                properties.put("text", field("string", "本次任务的汇报内容")); required.put("text");
                properties.put("phase", field("string", "可选阶段名称"));
            } else if ("close_agent".equals(name)) {
                properties.put("target", field("string", "要关闭的子代理名字、路径或id")); required.put("target");
            } else if ("list_agents".equals(name)) {
                properties.put("cursor", field("integer", "从上次返回nextCursor续读，默认0"));
                properties.put("path_prefix", field("string", "只列出该路径下的分支，如 /main/research；省略列出全部"));
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
                args.optString("task", ""), args.optBoolean("fork", false)).toString();
        if ("send_message".equals(name)) return manager.sendMessage(owner, args.optString("target", ""), args.optString("message", "")).toString();
        if ("report_agent".equals(name)) return manager.report(owner, args.optString("kind", ""), args.optString("text", ""), args.optString("phase", "")).toString();
        if ("list_agents".equals(name)) return manager.list(owner, args.optInt("cursor", 0), args.optString("path_prefix", "")).toString();
        return manager.close(owner, args.optString("target", "")).toString();
    }

    @Override public void abort() { if (SubAgentManager.ROOT.equals(owner)) manager.cancelAll(); }
}
