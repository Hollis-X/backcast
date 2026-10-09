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
        for (String name : new String[]{"spawn_agent", "send_message", "report_agent", "list_agents", "wait_agent", "close_agent", "read_agent_result"})
            registry.register(new SubAgentTools(manager, owner, name));
    }

    @Override public String name() { return name; }
    @Override public String description() {
        if ("spawn_agent".equals(name)) return "创建独立子任务并并行执行。默认全新上下文；fork=true才复制父有效完整历史与已配对工具记录。"
                + "派活时由主会话按任务需要选择：独立工作使用新上下文，依赖已有分析或接续工作才fork；不要求每个子任务fork。两种方式都继承必要运行配置和已授权目录。"
                + "只有 ultra 允许主动派活；其他思考程度仅在真实用户明确要求子 agent 时可调用。"
                + "返回任务 id 后可发送追加任务复用空闲子 agent，最终交付前必须等待并收集结果，不要重复自己已委派的工作。";
        if ("send_message".equals(name)) return "向同会话 agent 通信。kind=message只发送消息，不启动新任务；kind=task才明确追加任务并复用子会话。目标main只接收消息。运行中在下一次模型请求前送达，不中断正在执行的API或工具。";
        if ("report_agent".equals(name)) return "保存本次子任务的阶段进度、部分成果或错误。progress、partial、error分别保存；最终成果在任务正常结束时单独保存，汇报不会覆盖最终成果。";
        if ("list_agents".equals(name)) return "查看当前会话子任务状态、最终结果、待处理消息及发给自己的收件箱。";
        if ("wait_agent".equals(name)) return "等待子任务的新阶段、消息或完成，最长60秒。返回实时阶段、当前工具、进度和cursor；下次传cursor避免重复旧状态。pending仍真时任务没有全部完成，不可最终交付。";
        if ("read_agent_result".equals(name)) return "分片读取子任务完整成果，包括最终与部分输出；返回nextOffset时继续读取。list=true分页列出历次成果taskId/resultId索引，可凭result_id重读旧成果；索引不算送达确认。";
        return "关闭已经完成、停止或失败的子 agent 实例，保留任务与成果。运行或排队中会拒绝关闭；应等待成果，不得用关闭跳过未完成的工作。";
    }

    @Override public JSONObject parameters() {
        try {
            JSONObject properties = new JSONObject(); JSONArray required = new JSONArray();
            if ("spawn_agent".equals(name)) {
                properties.put("task", field("string", "独立子任务的完整要求")); required.put("task");
                properties.put("name", field("string", "简短任务名"));
                properties.put("fork", field("boolean", "由派活会话按任务需要选择；依赖已有分析或接续工作才true，独立任务false，默认false。true复制父有效完整上下文"));
            } else if ("send_message".equals(name)) {
                properties.put("target", field("string", "目标agent id或main")); required.put("target");
                properties.put("message", field("string", "通信文本或明确追加的任务要求")); required.put("message");
                properties.put("kind", field("string", "message或task，默认message").put("enum", new JSONArray().put("message").put("task")));
            } else if ("report_agent".equals(name)) {
                properties.put("kind", field("string", "progress、partial或error").put("enum", new JSONArray().put("progress").put("partial").put("error"))); required.put("kind");
                properties.put("text", field("string", "本次任务的汇报内容")); required.put("text");
                properties.put("phase", field("string", "可选阶段名称"));
            } else if ("wait_agent".equals(name)) {
                properties.put("target", field("string", "目标agent id，省略表示等待所有子任务"));
                properties.put("timeout_ms", field("integer", "0到60000，默认30000"));
                properties.put("cursor", field("integer", "上一次wait返回的cursor；仅等待该版本之后的新进度"));
            } else if ("close_agent".equals(name)) {
                properties.put("target", field("string", "要关闭的子任务id")); required.put("target");
            } else if ("list_agents".equals(name)) {
                properties.put("cursor", field("integer", "从上次返回nextCursor续读，默认0"));
            } else if ("read_agent_result".equals(name)) {
                properties.put("target", field("string", "目标子任务id")); required.put("target");
                properties.put("result_id", field("string", "稳定成果编号，省略读取最新成果"));
                properties.put("list", field("boolean", "true只列历次成果索引，不读取或确认成果，默认false"));
                properties.put("cursor", field("integer", "list=true时从上次nextCursor续列，每页最多20条"));
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
                args.optString("task", ""), args.optBoolean("fork", false)).toString();
        if ("send_message".equals(name)) return manager.send(owner, args.optString("target", ""), args.optString("message", ""), args.optString("kind", "message")).toString();
        if ("report_agent".equals(name)) return manager.report(owner, args.optString("kind", ""), args.optString("text", ""), args.optString("phase", "")).toString();
        if ("list_agents".equals(name)) return manager.list(owner, args.optInt("cursor", 0)).toString();
        if ("read_agent_result".equals(name)) {
            if (args.optBoolean("list", false)) return manager.resultIndex(owner, args.optString("target", ""), args.optInt("cursor", 0)).toString();
            return manager.readResult(owner, args.optString("target", ""), args.optString("result_id", ""),
                    args.optInt("offset", 0), args.optInt("limit", 8000)).toString();
        }
        if ("wait_agent".equals(name)) {
            String target = args.optString("target", "");
            JSONObject update = manager.waitForUpdate(owner, target, args.optLong("timeout_ms", 30000L), args.optLong("cursor", -1L));
            JSONObject collected = manager.collectResults(owner, target);
            update.put("results", collected.getJSONArray("agents")).put("inbox", collected.getJSONArray("inbox"))
                    .put("moreResults", collected.optBoolean("moreResults"));
            return update.toString();
        }
        return manager.close(owner, args.optString("target", "")).toString();
    }

    @Override public void abort() { manager.abortOwned(owner); }
}
