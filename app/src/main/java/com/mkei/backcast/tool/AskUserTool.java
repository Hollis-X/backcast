package com.mkei.backcast.tool;

import com.mkei.backcast.agent.QuestionRequest;
import com.mkei.backcast.agent.Tool;
import com.mkei.backcast.agent.UserQuestions;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** 模型卡在只有用户能定的事情上时，弹出选择题让用户选，再带着答案继续。 */
public final class AskUserTool implements Tool {
    public static final String NAME = "ask_user_question";
    private static final int MAX_QUESTIONS = 4, MIN_OPTIONS = 2, MAX_OPTIONS = 4, MAX_TEXT = 300;
    private final Supplier<UserQuestions> screen;
    private volatile QuestionRequest pending;

    public AskUserTool(Supplier<UserQuestions> screen) { this.screen = screen; }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "向用户提 1 到 4 个选择题，用户在界面上选择后你带着答案继续。只在卡在只有用户能决定的事情上时使用："
                + "靠请求本身、项目文件或合理默认值都定不下来，例如几个都合理的方案取决于用户偏好，或需求有歧义。"
                + "每题给 2 到 4 个具体选项，把你推荐的放第一个并在标签末尾写“（推荐）”；用户总能自己填写其他答案，不要加“其他”选项。"
                + "不要问能自己查证的事，不要问琐碎选择，也不要用它向用户索要已经给过的授权。";
    }

    @Override public JSONObject parameters() {
        try {
            JSONObject option = new JSONObject().put("type", "object")
                    .put("properties", new JSONObject()
                            .put("label", new JSONObject().put("type", "string").put("description", "选项名，1 到 5 个词"))
                            .put("description", new JSONObject().put("type", "string").put("description", "这个选项意味着什么、有什么取舍")))
                    .put("required", new JSONArray().put("label").put("description"))
                    .put("additionalProperties", false);
            JSONObject question = new JSONObject().put("type", "object")
                    .put("properties", new JSONObject()
                            .put("question", new JSONObject().put("type", "string").put("description", "完整的问题，以问号结尾"))
                            .put("header", new JSONObject().put("type", "string").put("description", "不超过 12 个字的短标签"))
                            .put("multiSelect", new JSONObject().put("type", "boolean").put("description", "选项不互斥时设为 true，默认 false"))
                            .put("options", new JSONObject().put("type", "array").put("items", option)
                                    .put("description", MIN_OPTIONS + " 到 " + MAX_OPTIONS + " 个选项")))
                    .put("required", new JSONArray().put("question").put("options"))
                    .put("additionalProperties", false);
            return new JSONObject().put("type", "object")
                    .put("properties", new JSONObject().put("questions", new JSONObject().put("type", "array")
                            .put("items", question).put("description", "1 到 " + MAX_QUESTIONS + " 个问题")))
                    .put("required", new JSONArray().put("questions")).put("additionalProperties", false);
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }

    @Override public String run(JSONObject args) throws Exception {
        JSONArray questions = args.optJSONArray("questions");
        if (questions == null || questions.length() == 0 || questions.length() > MAX_QUESTIONS)
            return "错误：questions 必须是 1 到 " + MAX_QUESTIONS + " 个问题。";
        JSONArray clean = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < questions.length(); i++) {
            JSONObject item = questions.optJSONObject(i);
            String text = item == null ? "" : item.optString("question", "").trim();
            JSONArray options = item == null ? null : item.optJSONArray("options");
            if (text.length() == 0 || text.length() > MAX_TEXT || !seen.add(text)) return "错误：第 " + (i + 1) + " 题的 question 不能为空、过长或重复。";
            if (options == null || options.length() < MIN_OPTIONS || options.length() > MAX_OPTIONS)
                return "错误：第 " + (i + 1) + " 题需要 " + MIN_OPTIONS + " 到 " + MAX_OPTIONS + " 个选项。";
            JSONArray cleanOptions = new JSONArray();
            java.util.HashSet<String> labels = new java.util.HashSet<String>();
            for (int j = 0; j < options.length(); j++) {
                JSONObject option = options.optJSONObject(j);
                String label = option == null ? "" : option.optString("label", "").trim();
                if (label.length() == 0 || label.length() > MAX_TEXT || !labels.add(label))
                    return "错误：第 " + (i + 1) + " 题的选项名不能为空、过长或重复。";
                cleanOptions.put(new JSONObject().put("label", label)
                        .put("description", option.optString("description", "").trim()));
            }
            clean.put(new JSONObject().put("question", text).put("header", item.optString("header", "").trim())
                    .put("multiSelect", item.optBoolean("multiSelect", false)).put("options", cleanOptions));
        }
        UserQuestions ui = screen.get();
        if (ui == null) return "当前没有界面可以提问；请自行选择最合理的方案，并在回复里说明你的选择和理由。";
        QuestionRequest request = new QuestionRequest(clean);
        pending = request;
        try {
            ui.show(request);
            JSONObject answers = request.await();
            if (answers == null) return "用户没有回答。请自行选择最合理的方案，并在回复里说明你的选择和理由。";
            return new JSONObject().put("answers", answers).toString();
        } finally { if (pending == request) pending = null; request.cancel(); }
    }

    @Override public void abort() {
        QuestionRequest request = pending;
        if (request != null) request.cancel();
    }
}
