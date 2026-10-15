import com.mkei.backcast.agent.QuestionRequest;
import com.mkei.backcast.agent.UserQuestions;
import com.mkei.backcast.tool.AskUserTool;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AskUserToolRegressionTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static JSONObject question(String text, boolean multi, String... labels) throws Exception {
        JSONArray options = new JSONArray();
        for (String label : labels) options.put(new JSONObject().put("label", label).put("description", label + "的取舍"));
        return new JSONObject().put("question", text).put("header", "方案").put("multiSelect", multi).put("options", options);
    }

    private static JSONObject ask(JSONObject... questions) {
        JSONArray array = new JSONArray();
        for (JSONObject question : questions) array.put(question);
        try { return new JSONObject().put("questions", array); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public static void main(String[] args) throws Exception {
        final AtomicReference<UserQuestions> screen = new AtomicReference<UserQuestions>();
        final AskUserTool tool = new AskUserTool(new java.util.function.Supplier<UserQuestions>() {
            @Override public UserQuestions get() { return screen.get(); }
        });
        JSONObject schema = tool.parameters();
        check("ask_user_question".equals(tool.name()) && schema.getJSONArray("required").getString(0).equals("questions")
                && !schema.getBoolean("additionalProperties"), "Question tool schema is wrong");

        // No screen: the model decides by itself, and the result counts as a failed call
        String noScreen = tool.run(ask(question("用哪种方案？", false, "A（推荐）", "B")));
        check(noScreen.startsWith("当前没有界面") && com.mkei.backcast.agent.ToolOutcome.failed("ask_user_question", noScreen),
                "Missing screen was not reported: " + noScreen);

        // Invalid shapes never reach the screen
        final int[] shown = new int[1];
        screen.set(new UserQuestions() { @Override public void show(QuestionRequest request) { shown[0]++; request.cancel(); } });
        for (JSONObject bad : new JSONObject[] {
                new JSONObject(), ask(), ask(question("一个选项？", false, "只有一个")),
                ask(question("重复？", false, "x", "x")), ask(question("同题？", false, "a", "b"), question("同题？", false, "a", "b")),
                ask(question("", false, "a", "b")),
                ask(question("1？", false, "a", "b"), question("2？", false, "a", "b"), question("3？", false, "a", "b"),
                        question("4？", false, "a", "b"), question("5？", false, "a", "b")) })
            check(tool.run(bad).startsWith("错误："), "Invalid question set was accepted: " + bad);
        check(shown[0] == 0, "Invalid questions were shown to the user");

        // Answered from another thread: the tool blocks until then and returns the chosen labels
        screen.set(new UserQuestions() {
            @Override public void show(final QuestionRequest request) {
                new Thread(new Runnable() { @Override public void run() {
                    try {
                        Thread.sleep(100);
                        check(request.questions.length() == 2 && request.questions.getJSONObject(0).getJSONArray("options").length() == 2, "Screen got the wrong questions");
                        request.answer(new JSONObject().put("用哪种方案？", "B").put("要加哪些检查？", "空值、越界"));
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                } }).start();
            }
        });
        JSONObject answered = new JSONObject(tool.run(ask(question("用哪种方案？", false, "A（推荐）", "B"), question("要加哪些检查？", true, "空值", "越界", "超时"))));
        check("B".equals(answered.getJSONObject("answers").getString("用哪种方案？"))
                && "空值、越界".equals(answered.getJSONObject("answers").getString("要加哪些检查？")), "Answers were not returned: " + answered);

        // Skipped: the model is told to decide itself, and it is not an error
        screen.set(new UserQuestions() { @Override public void show(QuestionRequest request) { request.cancel(); } });
        String skipped = tool.run(ask(question("继续吗？", false, "继续", "停止")));
        check(skipped.startsWith("用户没有回答") && !com.mkei.backcast.agent.ToolOutcome.failed("ask_user_question", skipped), "Skipping was reported wrongly: " + skipped);

        // Stop button while the question is open releases the waiting tool
        final QuestionRequest[] open = new QuestionRequest[1];
        screen.set(new UserQuestions() { @Override public void show(QuestionRequest request) { open[0] = request; } });
        final String[] result = new String[1];
        Thread waiting = new Thread(new Runnable() { @Override public void run() {
            try { result[0] = tool.run(ask(question("还在吗？", false, "在", "不在"))); } catch (Exception failure) { result[0] = failure.toString(); }
        } });
        waiting.start();
        long deadline = System.currentTimeMillis() + 3000;
        while (open[0] == null && System.currentTimeMillis() < deadline) Thread.sleep(10);
        check(open[0] != null && waiting.isAlive(), "The tool did not wait for the user");
        tool.abort();
        waiting.join(3000);
        check(!waiting.isAlive() && result[0].startsWith("用户没有回答") && open[0].cancelled(), "Abort did not release the question: " + result[0]);
        System.out.println("PASS ask_user_question tool");
    }
}
