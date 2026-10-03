import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/** Uses the real official SDK and its resolved runtime graph. No network credentials are needed. */
public class OpenAiSdkRegressionTest {
    public static void main(String[] args) throws Exception {
        String gradle = new String(Files.readAllBytes(new File(args[0], "app/build.gradle").toPath()),
                StandardCharsets.UTF_8);
        if (!gradle.contains("implementation 'com.openai:openai-java:4.75.1'")) {
            throw new AssertionError("Official SDK version not pinned in the application");
        }
        File libs = new File(args[0], "app/libs");
        String[] files = libs.list();
        if (files != null) for (String name : files) {
            if (name.startsWith("okhttp-3.") || name.startsWith("okio-1.")) {
                throw new AssertionError("Conflicting old HTTP runtime: " + name);
            }
        }
        System.out.println("PASS official SDK dependency and absence of duplicate old runtimes");
        OpenAIClient client = OpenAIOkHttpClient.builder().apiKey("test-only").maxRetries(0).build();
        try {
            if (client.chat().completions() == null || client.models() == null) {
                throw new AssertionError("Official runtime services cannot initialize");
            }
        } finally { client.close(); }
        System.out.println("PASS actual official SDK and transitive runtime initialization");
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder().model("provider-model")
                .addUserMessage("中文")
                .putAdditionalBodyProperty("reasoning_effort", JsonValue.from("ultra")).build();
        Map<String, JsonValue> extra = params._additionalBodyProperties();
        if (!JsonValue.from("ultra").equals(extra.get("reasoning_effort"))) {
            throw new AssertionError("Provider reasoning extension was discarded");
        }
        System.out.println("PASS provider extensions use the official SDK extension API");
        System.out.println("3 official SDK checks passed");
    }
}
