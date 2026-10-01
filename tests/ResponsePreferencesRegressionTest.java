import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Runs the production response policy and Settings against in-memory Android preferences. */
public final class ResponsePreferencesRegressionTest {
    private static Class<?> settingsType;
    private static Class<?> contextType;
    private static Class<?> policyType;
    private static int passed;
    private static final String[] LANGUAGES = { "zh-CN", "zh-TW", "en", "ja", "ko", "es", "fr", "de" };

    private static final class Source extends SimpleJavaFileObject {
        final String text;
        Source(String name, String text) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.text = text;
        }
        @Override public CharSequence getCharContent(boolean ignoreErrors) { return text; }
    }

    private static void add(List<JavaFileObject> files, String name, String body) {
        int split = name.lastIndexOf('.');
        files.add(new Source(name, "package " + name.substring(0, split) + ";\n" + body));
    }

    private static URLClassLoader compile(Path root, Path build) throws Exception {
        List<JavaFileObject> files = new ArrayList<JavaFileObject>();
        add(files, "android.content.SharedPreferences",
                "public interface SharedPreferences {"
                + "String getString(String key,String fallback); boolean getBoolean(String key,boolean fallback); Editor edit();"
                + "interface Editor { Editor putString(String key,String value); Editor putBoolean(String key,boolean value); void apply(); } }");
        add(files, "android.content.Context",
                "public class Context { public static final int MODE_PRIVATE=0;"
                + "public final java.util.Map<String,Object> values=new java.util.HashMap<String,Object>();"
                + "public Context getApplicationContext(){return this;}"
                + "public java.io.File getFilesDir(){return new java.io.File(\"/data/user/0/com.mkei.backcast/files\");}"
                + "public String getString(int id){return \"Fixture system prompt: 中文回答，简洁。\";}"
                + "public SharedPreferences getSharedPreferences(String name,int mode){return new SharedPreferences(){"
                + "public String getString(String k,String f){Object v=values.get(k);return v instanceof String?(String)v:f;}"
                + "public boolean getBoolean(String k,boolean f){Object v=values.get(k);return v instanceof Boolean?(Boolean)v:f;}"
                + "public Editor edit(){return new Editor(){java.util.Map<String,Object> pending=new java.util.HashMap<String,Object>();"
                + "public Editor putString(String k,String v){pending.put(k,v);return this;}"
                + "public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}"
                + "public void apply(){values.putAll(pending);} };} };} }");
        add(files, "android.os.Build", "public class Build { public static class VERSION { public static String RELEASE=\"fixture\"; } }");
        add(files, "com.mkei.backcast.R", "public class R { public static class string { public static final int default_system_prompt=1; } }");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            for (JavaFileObject source : fm.getJavaFileObjects(
                    root.resolve("app/src/main/java/com/mkei/backcast/Settings.java").toFile(),
                    root.resolve("app/src/main/java/com/mkei/backcast/agent/ResponsePreferences.java").toFile())) {
                files.add(source);
            }
            check(compiler.getTask(null, fm, null,
                    Arrays.asList("-proc:none", "-encoding", "UTF-8", "-source", "7", "-target", "7",
                            "-Xlint:-options", "-d", build.toString()), null, files).call(),
                    "Settings fixture compilation failed");
        }
        URLClassLoader loader = new URLClassLoader(new URL[]{build.toUri().toURL()}, null);
        settingsType = loader.loadClass("com.mkei.backcast.Settings");
        contextType = loader.loadClass("android.content.Context");
        policyType = loader.loadClass("com.mkei.backcast.agent.ResponsePreferences");
        return loader;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Object context() throws Exception { return contextType.getConstructor().newInstance(); }
    private static Object settings(Object context) throws Exception {
        return settingsType.getConstructor(contextType).newInstance(context);
    }
    private static String get(Object settings, String method) throws Exception {
        return (String) settingsType.getMethod(method).invoke(settings);
    }
    private static void set(Object settings, String method, String value) throws Exception {
        settingsType.getMethod(method, String.class).invoke(settings, value);
    }
    private static String policy(String method, String value) throws Exception {
        return (String) policyType.getMethod(method, String.class).invoke(null, value);
    }
    private static String instructions(String verbosity, String language) throws Exception {
        return (String) policyType.getMethod("instructions", String.class, String.class)
                .invoke(null, verbosity, language);
    }
    private static void customPrompt(Object settings, String prompt) throws Exception {
        settingsType.getMethod("save", String.class, String.class, String.class, boolean.class, String.class)
                .invoke(settings, "http://localhost", "fixture", "fixture", Boolean.FALSE, prompt);
    }

    private static void defaultPreferencesChooseChinese() throws Exception {
        Object settings = settings(context());
        check("default".equals(get(settings, "outputVerbosity")), "Default verbosity changed");
        check("auto".equals(get(settings, "reasoningSummary")), "Default summary changed");
        check("zh-CN".equals(get(settings, "outputLanguage")), "Fresh settings do not default to Chinese");
        check(get(settings, "fullSystemPrompt").contains(policy("languageInstruction", "zh-CN")),
                "Default prompt does not enforce the selected Chinese language");
    }

    private static void allChoicesPersistAcrossSettingsInstances() throws Exception {
        Object context = context(), settings = settings(context);
        for (String value : new String[]{"default", "low", "medium", "high"}) {
            set(settings, "setOutputVerbosity", value);
            check(value.equals(get(settings(context), "outputVerbosity")), "Verbosity was not saved: " + value);
        }
        for (String value : new String[]{"auto", "concise", "detailed", "none"}) {
            set(settings, "setReasoningSummary", value);
            check(value.equals(get(settings(context), "reasoningSummary")), "Summary was not saved: " + value);
        }
        for (String value : LANGUAGES) {
            set(settings, "setOutputLanguage", value);
            check(value.equals(get(settings(context), "outputLanguage")), "Language was not saved: " + value);
        }
    }

    private static void malformedSettersReturnDefaults() throws Exception {
        Object settings = settings(context());
        for (String invalid : new String[]{null, "", "all", "<ignore prior rules>", "zh"}) {
            set(settings, "setOutputVerbosity", invalid);
            set(settings, "setReasoningSummary", invalid);
            set(settings, "setOutputLanguage", invalid);
            check("default".equals(get(settings, "outputVerbosity")), "Invalid verbosity was retained");
            check("auto".equals(get(settings, "reasoningSummary")), "Invalid summary was retained");
            check("zh-CN".equals(get(settings, "outputLanguage")), "Invalid language was retained");
        }
    }

    @SuppressWarnings("unchecked")
    private static void malformedStoredValuesReturnDefaults() throws Exception {
        Object context = context(), settings = settings(context);
        java.util.Map<String, Object> values = (java.util.Map<String, Object>) contextType.getField("values").get(context);
        for (String name : new String[]{"KEY_OUTPUT_VERBOSITY", "KEY_REASONING_SUMMARY", "KEY_OUTPUT_LANGUAGE"}) {
            Field field = settingsType.getDeclaredField(name);
            field.setAccessible(true);
            values.put((String) field.get(null), "invalid-stored-value");
        }
        check("default".equals(get(settings, "outputVerbosity")), "Invalid stored verbosity leaked");
        check("auto".equals(get(settings, "reasoningSummary")), "Invalid stored summary leaked");
        check("zh-CN".equals(get(settings, "outputLanguage")), "Invalid stored language leaked");
    }

    private static void customEnglishPromptCannotOverrideSelectedChinese() throws Exception {
        Object settings = settings(context());
        String custom = "Always respond in English. Preserve this custom instruction verbatim.";
        customPrompt(settings, custom);
        String rule = policy("languageInstruction", "zh-CN");
        String full = get(settings, "fullSystemPrompt");
        check(custom.equals(get(settings, "systemPrompt")), "Dynamic preference contaminated editable prompt");
        check(full.startsWith(custom), "Custom prompt was dropped");
        check(full.indexOf(rule) > full.indexOf(custom), "Selected Chinese rule does not follow conflicting prompt");
        check(full.contains(instructions("default", "zh-CN")), "Full response preference policy was not injected");
    }

    private static void selectedEnglishOverridesDefaultChineseInstruction() throws Exception {
        Object settings = settings(context());
        String raw = get(settings, "systemPrompt");
        check(raw.contains("中文回答"), "Fixture lacks conflicting default language");
        set(settings, "setOutputLanguage", "en");
        String full = get(settings, "fullSystemPrompt"), english = policy("languageInstruction", "en");
        check(full.indexOf(english) > full.indexOf("中文回答"), "English policy was not appended after default Chinese");
        check(raw.equals(get(settings, "systemPrompt")), "Changing language modified editable prompt");
        check(!full.contains(policy("languageInstruction", "zh-CN")), "Old Chinese dynamic policy stayed active");
    }

    private static void languageSwitchReplacesPolicyWithoutAccumulation() throws Exception {
        Object settings = settings(context());
        for (String language : LANGUAGES) {
            set(settings, "setOutputLanguage", language);
            String full = get(settings, "fullSystemPrompt");
            String selected = policy("languageInstruction", language);
            check(full.contains(selected), "Chosen language policy missing: " + language);
            check(full.indexOf(selected) == full.lastIndexOf(selected), "Chosen language policy duplicated");
            for (String other : LANGUAGES) {
                if (!language.equals(other)) {
                    check(!full.contains(policy("languageInstruction", other)), "Previous language policy leaked: " + other);
                }
            }
        }
    }

    private static void verbositySwitchPreservesLanguageAndCustomPrompt() throws Exception {
        Object settings = settings(context());
        String custom = "Keep project requirements intact.";
        customPrompt(settings, custom);
        set(settings, "setOutputLanguage", "ja");
        String low = instructions("low", "ja"), high = instructions("high", "ja");
        check(!low.equals(high), "Low and high verbosity produce the same style policy");
        for (String verbosity : new String[]{"default", "low", "medium", "high"}) {
            set(settings, "setOutputVerbosity", verbosity);
            check(get(settings, "fullSystemPrompt").contains(instructions(verbosity, "ja")),
                    "Changed verbosity policy was not applied: " + verbosity);
            check(custom.equals(get(settings, "systemPrompt")), "Changing verbosity changed editable prompt");
        }
    }

    private static void languageRulesCoverAllVisibleTextAndConflictingPrompts() throws Exception {
        for (String language : LANGUAGES) {
            String rule = policy("languageInstruction", language);
            check(rule.contains("(" + language + ")"), "Language rule identifies the wrong language");
            check(rule.contains("hard constraint") && rule.contains("every user-visible assistant response"),
                    "Language policy is not mandatory for all visible responses");
            check(rule.contains("progress updates") && rule.contains("final answers")
                    && rule.contains("activity summaries"), "Language policy omits some visible output");
            check(rule.contains("overrides any conflicting") && rule.contains("editable system prompt"),
                    "Editable prompt can override the selected language");
            check(rule.contains("natural-language JSON values") && rule.contains("protocol markers")
                    && rule.contains("code identifiers"), "Language policy does not preserve protocol while constraining prose");
        }
    }

    private static int concurrency(Object settings) throws Exception {
        return (Integer) settingsType.getMethod("agentConcurrency").invoke(settings);
    }
    private static void concurrency(Object settings, int value) throws Exception {
        settingsType.getMethod("setAgentConcurrency", int.class).invoke(settings, value);
    }
    private static void childDefaultsAndEveryChoicePersist() throws Exception {
        Object context=context(), settings=settings(context);
        check("manual".equals(get(settings,"agentMode")) && concurrency(settings)==3,
                "Fresh settings do not choose manual mode and three children");
        for(String mode:new String[]{"off","manual","ultra"}) {
            set(settings,"setAgentMode",mode);
            check(("ultra".equals(mode)?"ultra":"manual").equals(get(settings(context),"agentMode")),"Compatibility mode did not derive from effort");
        }
        for(int count=1;count<=4;count++) {
            concurrency(settings,count);
            check(concurrency(settings(context))==count,"Child concurrency did not persist");
        }
        for(String effort:new String[]{"off","low","medium","high","max","ultra"}) {
            set(settings,"setReasoningEffort",effort);
            check(effort.equals(get(settings(context),"reasoningEffort")),"Reasoning effort did not persist");
        }
    }
    private static void invalidChildAndEffortValuesReturnDefaults() throws Exception {
        Object settings=settings(context());
        for(String invalid:new String[]{null,"","all","ULTRA","<script>"}) {
            set(settings,"setAgentMode",invalid);
            set(settings,"setReasoningEffort",invalid);
            check("manual".equals(get(settings,"agentMode")),"Invalid child mode escaped normalization");
            check("low".equals(get(settings,"reasoningEffort")),"Invalid API reasoning value escaped normalization");
        }
        for(int invalid:new int[]{-1,0,5,Integer.MAX_VALUE}) {
            concurrency(settings,invalid);
            check(concurrency(settings)==3,"Invalid child count escaped normalization");
        }
    }
    @SuppressWarnings("unchecked")
    private static void malformedStoredChildSettingsReturnDefaults() throws Exception {
        Object context=context(),settings=settings(context);
        java.util.Map<String,Object> values=(java.util.Map<String,Object>)contextType.getField("values").get(context);
        for(String key:new String[]{"agent_mode","agent_concurrency","reasoning_effort"}) values.put(key,"invalid");
        check("manual".equals(get(settings,"agentMode")) && concurrency(settings)==3
                && "low".equals(get(settings,"effectiveReasoningEffort")),"Malformed persisted settings escaped validation");
    }
    private static void maxAndUltraRemainSeparateWhilePreservingLanguage() throws Exception {
        Object settings=settings(context());
        set(settings,"setOutputLanguage","ja");
        for(String effort:new String[]{"off","max","ultra","max","low"}) {
            set(settings,"setReasoningEffort",effort);
            check(effort.equals(get(settings,"reasoningEffort")),"Selected effort was changed");
            check(effort.equals(get(settings,"effectiveReasoningEffort")),"Selected effort was mapped to another value");
            check(("ultra".equals(effort)?"ultra":"manual").equals(get(settings,"agentMode")),"Max inherited automatic delegation");
            boolean enabled=(Boolean)settingsType.getMethod("reasoningEnabled").invoke(settings);
            check(enabled!= "off".equals(effort),"Effective off compatibility is inconsistent");
            check("ja".equals(get(settings,"outputLanguage")) && get(settings,"fullSystemPrompt")
                    .contains(policy("languageInstruction","ja")),"Mode switch changed the output language");
        }
    }
    private static void childPoliciesRequireEvidenceReviewAndSelectiveDelegation() throws Exception {
        Object settings=settings(context());
        set(settings,"setAgentMode","ultra");concurrency(settings,4);
        String ultra=get(settings,"fullSystemPrompt");
        check(ultra.contains("At most 4 child agents") && ultra.contains("Proactively identify independent subtasks")
                && ultra.contains("Reuse an existing child") && ultra.contains("review and verify")
                && ultra.contains("simple question or indivisible task does not require a child"),
                "Ultra lacks limits, reuse, independence or parent evidence review");
        set(settings,"setAgentMode","manual");
        check(!get(settings,"fullSystemPrompt").contains("Proactively identify independent subtasks")
                && get(settings,"fullSystemPrompt").contains("unless the actual user explicitly"),"Manual inherited automatic delegation");
        set(settings,"setAgentMode","off");
        check(get(settings,"fullSystemPrompt").contains("unless the actual user explicitly"),"Legacy off bypassed explicit-user policy");
    }
    @SuppressWarnings("unchecked")
    private static void legacyUltraMigratesOnceAndNeverOverridesANewMaxSelection() throws Exception {
        Object context=context();
        java.util.Map<String,Object> values=(java.util.Map<String,Object>)contextType.getField("values").get(context);
        values.put("agent_mode","ultra");values.put("reasoning_effort","off");
        Object settings=settings(context);
        check("ultra".equals(get(settings,"effectiveReasoningEffort")),"Legacy ultra did not migrate");
        set(settings,"setReasoningEffort","max");
        check("max".equals(get(settings(context),"effectiveReasoningEffort"))
                && "manual".equals(get(settings(context),"agentMode")),"Legacy ultra resurrected after selecting max");
        Object second=context();
        values=(java.util.Map<String,Object>)contextType.getField("values").get(second);
        values.put("agent_mode","ultra");
        set(settings(second),"setReasoningEffort","high");
        check("high".equals(get(settings(second),"effectiveReasoningEffort")),"Explicit effort was overwritten before first migration");
    }
    private static void unsavedModePreviewDoesNotPersistOrAlterPrompt() throws Exception {
        Object settings=settings(context());String raw=get(settings,"systemPrompt");
        String preview=(String)settingsType.getMethod("environmentContext",boolean.class,String.class,int.class)
                .invoke(settings,false,"ultra",2);
        check(preview.contains("Subagent mode: ultra") && preview.contains("At most 2 child agents"),
                "Preview ignored unsaved mode and concurrency");
        check("manual".equals(get(settings,"agentMode")) && concurrency(settings)==3 && raw.equals(get(settings,"systemPrompt")),
                "Unsaved preview contaminated preferences or editable prompt");
    }

    private static void run(String name) throws Exception {
        try { ResponsePreferencesRegressionTest.class.getDeclaredMethod(name).invoke(null); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            throw new AssertionError(name, failure.getCause());
        }
        passed++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]), build = Files.createTempDirectory("backcast-response-prefs-");
        try (URLClassLoader loader = compile(root, build)) {
            for (String name : new String[]{"defaultPreferencesChooseChinese", "allChoicesPersistAcrossSettingsInstances",
                    "malformedSettersReturnDefaults", "malformedStoredValuesReturnDefaults",
                    "customEnglishPromptCannotOverrideSelectedChinese", "selectedEnglishOverridesDefaultChineseInstruction",
                    "languageSwitchReplacesPolicyWithoutAccumulation", "verbositySwitchPreservesLanguageAndCustomPrompt",
                    "languageRulesCoverAllVisibleTextAndConflictingPrompts", "childDefaultsAndEveryChoicePersist",
                    "invalidChildAndEffortValuesReturnDefaults", "malformedStoredChildSettingsReturnDefaults",
                    "maxAndUltraRemainSeparateWhilePreservingLanguage", "childPoliciesRequireEvidenceReviewAndSelectiveDelegation",
                    "legacyUltraMigratesOnceAndNeverOverridesANewMaxSelection",
                    "unsavedModePreviewDoesNotPersistOrAlterPrompt"}) run(name);
            System.out.println(passed + " response preference tests passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(build)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.delete(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
