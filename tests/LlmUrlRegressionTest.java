import com.mkei.backcast.agent.LlmClient;

/** 接口地址只填网址也能用；已带版本段时不重复补 /v1。 */
public final class LlmUrlRegressionTest {
    private static void check(String input, String chat, String models) {
        LlmClient.Config config = new LlmClient.Config(input, "k", "m");
        if (!chat.equals(config.baseUrl)) throw new AssertionError(input + " -> " + config.baseUrl);
        if (!models.equals(config.modelsUrl())) throw new AssertionError(input + " -> " + config.modelsUrl());
        System.out.println("PASS " + input);
    }

    public static void main(String[] args) {
        check("https://api.deepseek.com", "https://api.deepseek.com/v1/chat/completions", "https://api.deepseek.com/v1/models");
        check("https://api.deepseek.com/", "https://api.deepseek.com/v1/chat/completions", "https://api.deepseek.com/v1/models");
        check("https://api.openai.com/v1", "https://api.openai.com/v1/chat/completions", "https://api.openai.com/v1/models");
        check("https://api.openai.com/v1/chat/completions", "https://api.openai.com/v1/chat/completions", "https://api.openai.com/v1/models");
        check("https://open.bigmodel.cn/api/paas/v4", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "https://open.bigmodel.cn/api/paas/v4/models");
        check("https://ark.cn-beijing.volces.com/api/v3/", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "https://ark.cn-beijing.volces.com/api/v3/models");
        check("https://generativelanguage.googleapis.com/v1beta/openai", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "https://generativelanguage.googleapis.com/v1beta/openai/models");
        check("http://127.0.0.1:8080", "http://127.0.0.1:8080/v1/chat/completions", "http://127.0.0.1:8080/v1/models");
        System.out.println("LLM URL regressions: passed");
    }
}
