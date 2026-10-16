import com.mkei.backcast.tool.PlanTool;
import com.mkei.backcast.ui.TurnTrace;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PlanToolRegressionTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static JSONObject plan(String... pairs) throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < pairs.length; i += 2)
            items.put(new JSONObject().put("content", pairs[i]).put("status", pairs[i + 1]));
        return new JSONObject().put("items", items);
    }

    public static void main(String[] args) throws Exception {
        PlanTool tool = new PlanTool();
        JSONObject schema = tool.parameters();
        check("update_plan".equals(tool.name()) && schema.getJSONArray("required").getString(0).equals("items")
                && schema.getBoolean("additionalProperties") == false, "Plan tool schema is wrong");
        String running = tool.run(plan("读代码", "completed", "改代码", "in_progress", "跑测试", "pending"));
        check(running.contains("共 3 步") && running.contains("已完成 1 步") && running.contains("进行中：改代码"), "Progress summary is wrong: " + running);
        check(tool.run(plan("a", "completed", "b", "completed")).contains("全部完成"), "A finished plan was not reported complete");
        check(tool.run(plan("a", "in_progress", "b", "in_progress")).startsWith("错误："), "Two active steps were accepted");
        check(!tool.run(plan("a", "doing")).startsWith("错误："), "A common status synonym was rejected");
        JSONObject codex = new JSONObject("{\"plan\":[{\"step\":\"写脚本\",\"status\":\"completed\"},{\"step\":\"运行\",\"status\":\"in_progress\"}]}");
        check(tool.run(codex).contains("共 2 步") && tool.run(codex).contains("进行中：运行"), "plan/step shape was rejected: " + tool.run(codex));
        check(PlanTool.normalize(codex.toString()).getJSONObject(0).getString("content").equals("写脚本"), "normalize lost step text");
        check(PlanTool.normalize("{\"plan\":[{\"ste") == null, "partial arguments should not parse");
        check(tool.run(plan("", "pending")).startsWith("错误："), "An empty step was accepted");
        check(tool.run(new JSONObject()).startsWith("错误：") && tool.run(new JSONObject().put("items", new JSONArray())).startsWith("错误："),
                "A missing or empty list was accepted");
        JSONArray many = new JSONArray();
        for (int i = 0; i < 13; i++) many.put(new JSONObject().put("content", "s" + i).put("status", "pending"));
        check(tool.run(new JSONObject().put("items", many)).startsWith("错误："), "More than twelve steps were accepted");
        // What the timeline shows for it
        String[] view = TurnTrace.planView(plan("读代码", "completed", "改代码", "in_progress", "跑测试", "pending").toString());
        check("已完成 1/3".equals(view[0]) && view[1].equals("\u2713 读代码\n\u25cf 改代码\n\u25cb 跑测试"), "Checklist text is wrong: " + view[0] + " | " + view[1]);
        String[] partial = TurnTrace.planView("{\"items\":[{\"content\":\"读");
        check("正在整理计划".equals(partial[0]) && partial[1].length() == 0, "Half-streamed arguments were not handled");
        // The folded row counts plan calls like any other tool call; the card shows the latest plan
        TurnTrace trace = new TurnTrace();
        trace.appendThink("想一下"); trace.sealThink();
        trace.addStep("p1", TurnTrace.PLAN, plan("读代码", "in_progress", "改代码", "pending").toString());
        trace.addStep("r1", "read", "{}");
        trace.addStep("p2", TurnTrace.PLAN, plan("读代码", "completed", "改代码", "in_progress", "跑测试", "pending").toString());
        String caption = trace.activityCaption(0, trace.order.size());
        check("思考 1 段 · 工具 3 次".equals(caption), "Plan calls were not counted in the folded caption: " + caption);
        check(trace.latestPlan().contains("跑测试"), "Latest plan was not the newest snapshot");
        TurnTrace onlyPlan = new TurnTrace();
        onlyPlan.addStep("p", TurnTrace.PLAN, plan("a", "pending").toString());
        TurnTrace.Range planRange = new TurnTrace.Range(onlyPlan, 0); planRange.end = 1;
        check(planRange.hasActivity(), "A plan-only range hid the plan call");
        // Line deltas from edit/write results are summed for the folded row
        TurnTrace edits = new TurnTrace();
        edits.addStep("e1", "edit", "{}"); edits.addStep("w1", "write", "{}"); edits.addStep("s1", "shell", "{}");
        edits.steps.get(0).done = true; edits.steps.get(0).result = "已修改 1 处：a.txt\n变更：+3 -1";
        edits.steps.get(1).done = true; edits.steps.get(1).result = "已写入 b.txt\n变更：+9 -0";
        edits.steps.get(2).done = true; edits.steps.get(2).result = "exit=0\n变更：+100 -100 is just output";
        int[] total = edits.lineDelta(0, edits.order.size());
        check(total != null && total[0] == 12 && total[1] == 1, "Line delta total is wrong");
        check(new TurnTrace().lineDelta(0, 0) == null, "A turn without file changes reported a delta");
        System.out.println("PASS plan tool and checklist text");
    }
}
