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
                    Arrays.asList("-proc:none", "-encoding", "UTF-8", "-source", "8", "-target", "8",
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
        if ("setOutputVerbosity".equals(method) || "setReasoningSummary".equals(method) || "setOutputLanguage".equals(method)) {
            preferences(settings, "setOutputVerbosity".equals(method) ? value : get(settings, "outputVerbosity"),
                    "setReasoningSummary".equals(method) ? value : get(settings, "reasoningSummary"),
                    "setOutputLanguage".equals(method) ? value : get(settings, "outputLanguage"),
                    concurrency(settings), get(settings, "systemPrompt"));
            return;
        }
        settingsType.getMethod(method, String.class).invoke(settings, value);
    }
    private static void preferences(Object settings, String verbosity, String summary, String language,
                                    int concurrency, String prompt) throws Exception {
        settingsType.getMethod("saveUserPreferences", String.class, String.class, String.class, int.class, String.class)
                .invoke(settings, verbosity, summary, language, concurrency, prompt);
    }
    private static String policy(String method, String value) throws Exception {
        return (String) policyType.getMethod(method, String.class).invoke(null, value);
    }
    private static String instructions(String verbosity, String language) throws Exception {
        return (String) policyType.getMethod("instructions", String.class, String.class)
                .invoke(null, verbosity, language);
    }
    private static void customPrompt(Object settings, String prompt) throws Exception {
        preferences(settings, get(settings, "outputVerbosity"), get(settings, "reasoningSummary"),
                get(settings, "outputLanguage"), concurrency(settings), prompt);
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
        preferences(settings, get(settings, "outputVerbosity"), get(settings, "reasoningSummary"),
                get(settings, "outputLanguage"), value, get(settings, "systemPrompt"));
    }
    private static void childDefaultsAndEveryChoicePersist() throws Exception {
        Object context=context(), settings=settings(context);
        check("manual".equals(get(settings,"agentMode")) && concurrency(settings)==3,
                "Fresh settings do not choose manual mode and three children");
        for(String effort:new String[]{"off","low","xhigh","max","ultra"}) {
            set(settings,"setReasoningEffort",effort);
            check(("ultra".equals(effort)?"ultra":"manual").equals(get(settings(context),"agentMode")),"Delegation mode did not derive from the saved effort");
        }
        for(int count=1;count<=4;count++) {
            concurrency(settings,count);
            check(concurrency(settings(context))==count,"Child concurrency did not persist");
        }
        for(String effort:new String[]{"off","low","medium","high","xhigh","max","ultra"}) {
            set(settings,"setReasoningEffort",effort);
            check(effort.equals(get(settings(context),"reasoningEffort")),"Reasoning effort did not persist");
        }
    }
    private static void invalidChildAndEffortValuesReturnDefaults() throws Exception {
        Object settings=settings(context());
        for(String invalid:new String[]{null,"","all","ULTRA","<script>"}) {
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
        for(String effort:new String[]{"off","xhigh","max","ultra","max","xhigh","low"}) {
            set(settings,"setReasoningEffort",effort);
            check(effort.equals(get(settings,"reasoningEffort")),"Selected effort was changed");
            check(effort.equals(get(settings,"effectiveReasoningEffort")),"Selected effort was mapped to another value");
            check(("ultra".equals(effort)?"ultra":"manual").equals(get(settings,"agentMode")),"Max inherited automatic delegation");
            check("ja".equals(get(settings,"outputLanguage")) && get(settings,"fullSystemPrompt")
                    .contains(policy("languageInstruction","ja")),"Mode switch changed the output language");
        }
    }
    private static void childPoliciesRequireEvidenceReviewAndSelectiveDelegation() throws Exception {
        Object settings=settings(context());
        set(settings,"setReasoningEffort","ultra");concurrency(settings,4);
        String ultra=get(settings,"fullSystemPrompt");
        check(ultra.contains("At most 4 child agents") && ultra.contains("Proactively identify independent subtasks")
                && ultra.contains("Reuse an existing child") && ultra.contains("review and verify")
                && ultra.contains("simple question or indivisible task does not require a child"),
                "Ultra lacks limits, reuse, independence or parent evidence review");
        set(settings,"setReasoningEffort","low");
        check(!get(settings,"fullSystemPrompt").contains("Proactively identify independent subtasks")
                && get(settings,"fullSystemPrompt").contains("unless the actual user explicitly"),"Manual inherited automatic delegation");
        set(settings,"setReasoningEffort","off");
        check(get(settings,"fullSystemPrompt").contains("unless the actual user explicitly"),"Off effort bypassed explicit-user policy");
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

    @SuppressWarnings("unchecked")
    private static void groupedAiSavePreservesPreferencesPermissionsAndWorkspace() throws Exception {
        Object context = context(), settings = settings(context);
        set(settings, "setReasoningEffort", "ultra");
        preferences(settings, "high", "none", "ja", 4, "User-owned prompt");
        settingsType.getMethod("setUseRoot", boolean.class).invoke(settings, false);
        set(settings, "setAccessLevel", "strict"); set(settings, "setPrimaryWorkDir", "/storage/emulated/0/project");
        java.util.Map<String,Object> stored = (java.util.Map<String,Object>) contextType.getField("values").get(context);
        java.util.Map<String,Object> before = new java.util.HashMap<String,Object>(stored);
        saveProfile(settings, get(settings, "activeProviderId"), " https://provider.example/v1 ", " secret ", " chosen ",
                Arrays.asList("chosen", "other", "chosen", " ", null, "bad\nline"));
        check("https://provider.example/v1".equals(activeText(settings, "baseUrl")) && "secret".equals(activeText(settings, "apiKey"))
                && "chosen".equals(activeText(settings, "model")), "AI fields were not saved and trimmed");
        check(Arrays.asList("chosen", "other").equals(activeModels(settings)), "AI preview model list was not safely saved");
        for (String key : before.keySet()) check(before.get(key).equals(stored.get(key)), "AI save changed another group: " + key);
    }

    @SuppressWarnings("unchecked")
    private static void groupedPreferenceSavePreservesLatestAiAndToolSettings() throws Exception {
        Object context = context(), settings = settings(context);
        saveProfile(settings, get(settings, "activeProviderId"), "https://new.example/v1", "new-key", "new-model", Arrays.asList("new-model", "available"));
        settingsType.getMethod("setUseRoot", boolean.class).invoke(settings, false);
        set(settings, "setAccessLevel", "guarded"); set(settings, "setPrimaryWorkDir", "/storage/emulated/0/current");
        set(settings, "setReasoningEffort", "max");
        java.util.Map<String,Object> stored = (java.util.Map<String,Object>) contextType.getField("values").get(context);
        java.util.Map<String,Object> before = new java.util.HashMap<String,Object>(stored);
        preferences(settings, "medium", "detailed", "en", 2, "My preference draft");
        for (String key : before.keySet()) check(before.get(key).equals(stored.get(key)), "Preference save changed another group: " + key);
        check("medium".equals(get(settings, "outputVerbosity")) && "detailed".equals(get(settings, "reasoningSummary"))
                && "en".equals(get(settings, "outputLanguage")) && "max".equals(get(settings, "effectiveReasoningEffort"))
                && concurrency(settings) == 2 && "My preference draft".equals(get(settings, "systemPrompt")), "Preference group did not save every field");
    }

    private static void groupedPreferenceSaveNormalizesValuesAndDoesNotResurrectOldUltra() throws Exception {
        Object context = context(), settings = settings(context);
        @SuppressWarnings("unchecked") java.util.Map<String,Object> values =
                (java.util.Map<String,Object>) contextType.getField("values").get(context);
        values.put("agent_mode", "ultra"); values.put("reasoning_effort", "off");
        preferences(settings, "invalid", null, "unknown", 100, null);
        check("default".equals(get(settings, "outputVerbosity")) && "auto".equals(get(settings, "reasoningSummary"))
                && "zh-CN".equals(get(settings, "outputLanguage")) && concurrency(settings) == 3,
                "Grouped save bypassed validation");
        check("off".equals(values.get("reasoning_effort")) && !values.containsKey("effort_policy_migrated"),
                "Response preference save wrote the independently owned effort or migration marker");
        check("ultra".equals(get(settings(context), "effectiveReasoningEffort")), "Response preference save suppressed legacy ultra migration");
        for (String effort : new String[]{"off", "low", "xhigh", "max", "ultra"}) {
            set(settings, "setReasoningEffort", effort);
            preferences(settings, "high", "none", "en", 2, "Saved independently");
            check(effort.equals(get(settings(context), "effectiveReasoningEffort")),
                    "Response preference save changed the current effort: " + effort);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> profiles(Object settings) throws Exception {
        return (List<Object>) settingsType.getMethod("aiProfiles").invoke(settings);
    }
    private static Object profile(Object settings, String id) throws Exception {
        for (Object item : profiles(settings)) if (id.equals(item.getClass().getField("id").get(item))) return item;
        throw new AssertionError("Missing provider " + id);
    }
    private static String profileText(Object profile, String field) throws Exception {
        return (String) profile.getClass().getField(field).get(profile);
    }
    private static Object activeProfile(Object settings) throws Exception {
        return settingsType.getMethod("activeAiProfile").invoke(settings);
    }
    private static String activeText(Object settings, String field) throws Exception {
        return profileText(activeProfile(settings), field);
    }
    private static Object activeModels(Object settings) throws Exception {
        Object profile = activeProfile(settings);
        return profile.getClass().getField("modelList").get(profile);
    }
    private static void activate(Object settings, String id) throws Exception {
        settingsType.getMethod("saveAiProfiles", List.class, String.class).invoke(settings, java.util.Collections.emptyList(), id);
    }
    private static void saveProfile(Object settings, String id, String url, String key, String model, List<String> models) throws Exception {
        Object profile = settingsType.getClassLoader().loadClass("com.mkei.backcast.Settings$AiProfile")
                .getConstructor(String.class, String.class, String.class, String.class, List.class)
                .newInstance(id, url, key, model, models);
        settingsType.getMethod("saveAiProfiles", List.class, String.class)
                .invoke(settings, Arrays.asList(profile), get(settings, "activeProviderId"));
    }
    @SuppressWarnings("unchecked")
    private static void oldAiConfigurationMigratesOnceWithoutLosingProfilesOrModelLists() throws Exception {
        String[] urls={"https://api.deepseek.com/v1", "https://api.openai.com/v1/chat/completions", "https://api.x.ai/v1",
                "https://gateway.example/v1", "https://api.openai.com.evil.example/v1", ""};
        String[] expected={"deepseek","openai","grok","custom","custom","custom"};
        for(int i=0;i<urls.length;i++) {
            Object context=context(); java.util.Map<String,Object> values=(java.util.Map<String,Object>)contextType.getField("values").get(context);
            values.put("base_url",urls[i]); values.put("api_key","legacy-key"); values.put("model","legacy-model");
            values.put("model_list","legacy-model\nother-model"); Object settings=settings(context);
            check(expected[i].equals(get(settings,"activeProviderId"))&&urls[i].equals(activeText(settings,"baseUrl"))
                            &&"legacy-key".equals(activeText(settings,"apiKey"))&&"legacy-model".equals(activeText(settings,"model"))
                            &&Arrays.asList("legacy-model","other-model").equals(activeModels(settings)),
                    "Legacy AI migration lost a field or trusted a lookalike host");
            check(urls[i].equals(values.get("base_url"))&&"legacy-key".equals(values.get("api_key")),"Migration destroyed the original saved configuration");
            activate(settings,"grok"); values.put("base_url","https://api.openai.com/v1");
            check("grok".equals(get(settings(context),"activeProviderId")),"Legacy data replaced an already migrated provider selection");
        }
    }
    @SuppressWarnings("unchecked")
    private static void providersKeepSeparateCredentialsAndImmutableOfficialDefaults() throws Exception {
        Object context=context(),settings=settings(context); List<Object> providers=profiles(settings);
        check(providers.size()==4&&"https://api.deepseek.com/v1".equals(profileText(profile(settings,"deepseek"),"baseUrl"))
                        &&"https://api.openai.com/v1".equals(profileText(profile(settings,"openai"),"baseUrl"))
                        &&"https://api.x.ai/v1".equals(profileText(profile(settings,"grok"),"baseUrl")),"Official preset endpoints are incorrect");
        for(Object p:providers)check("".equals(profileText(p,"apiKey"))&&"".equals(profileText(p,"model")),"Fresh presets claimed credentials or account models");
        boolean immutable=false;try{providers.clear();}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"Provider snapshots can be changed externally");
        for(String id:new String[]{"deepseek","openai","grok","custom"})saveProfile(settings,id,"https://"+id+".example/v1",id+"-key",id+"-model",Arrays.asList(id+"-model","common"));
        for(String id:new String[]{"deepseek","openai","grok","custom"}){
            activate(settings,id);Object restored=settings(context);
            check((id+"-key").equals(activeText(restored,"apiKey"))&&(id+"-model").equals(activeText(restored,"model")),"Switching providers mixed credentials");
            List<String> modelList=(List<String>)profile(restored,id).getClass().getField("modelList").get(profile(restored,id));
            immutable=false;try{modelList.add("mutated");}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"Model snapshot aliases persisted storage");
        }
    }
    private static void modelSelectionActivatesOnlyItsProviderAndKeepsProfilesIndependent() throws Exception {
        Object context=context(),settings=settings(context),other=settings(context);
        saveProfile(settings,"openai","https://api.openai.com/v1","openai-key","existing",Arrays.asList("existing"));
        saveProfile(other,"grok","https://api.x.ai/v1","grok-key","grok-original",Arrays.asList("grok-original"));
        settingsType.getMethod("selectAiModel",String.class,String.class).invoke(other,"openai","hand-entered-model");
        check("openai".equals(get(settings,"activeProviderId"))&&"hand-entered-model".equals(activeText(settings,"model"))
                        &&"openai-key".equals(activeText(settings,"apiKey"))&&(Boolean)settingsType.getMethod("isConfigured").invoke(settings),"Model selection failed to atomically activate its provider");
        check(Arrays.asList("existing","hand-entered-model").equals(activeModels(settings)),"Selected manual model was not retained for switching");
        saveProfile(settings,"openai","https://openai-gateway.example/v1","changed-key","changed-model",Arrays.asList("changed-model"));
        check("grok-key".equals(profileText(profile(other,"grok"),"apiKey"))
                        &&"grok-original".equals(profileText(profile(other,"grok"),"model")),"Profile save wrote through another provider");
        boolean rejected=false;try{settingsType.getMethod("selectAiModel",String.class,String.class).invoke(settings,"grok","");}
        catch(java.lang.reflect.InvocationTargetException expected){rejected=expected.getCause() instanceof IllegalArgumentException;}
        check(rejected&&"openai".equals(get(settings,"activeProviderId")),"Invalid model changed active provider");
    }
    private static void independentSettingsWritesAndPartialDraftCommitDoNotClobberProviders() throws Exception {
        final Object context=context(),first=settings(context),second=settings(context);
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread a=new Thread(()->{try{for(int i=0;i<40;i++)saveProfile(first,"openai","https://api.openai.com/v1","openai-"+i,"model-a",Arrays.asList("model-a"));}catch(Throwable t){failure.set(t);}});
        Thread b=new Thread(()->{try{for(int i=0;i<40;i++)saveProfile(second,"grok","https://api.x.ai/v1","grok-"+i,"model-b",Arrays.asList("model-b"));}catch(Throwable t){failure.set(t);}});
        a.start();b.start();a.join(3000);b.join(3000);check(!a.isAlive()&&!b.isAlive()&&failure.get()==null,"Concurrent settings writes failed");
        check("openai-39".equals(profileText(profile(first,"openai"),"apiKey"))&&"grok-39".equals(profileText(profile(first,"grok"),"apiKey")),"Concurrent provider writes lost a profile");
        Object edited=profile(first,"openai");saveProfile(second,"grok","https://api.x.ai/v1","newer-grok-key","newer-grok-model",Arrays.asList("newer-grok-model"));
        settingsType.getMethod("saveAiProfiles",List.class,String.class).invoke(first,Arrays.asList(edited),"openai");
        check("newer-grok-key".equals(profileText(profile(first,"grok"),"apiKey"))&&"openai".equals(get(first,"activeProviderId")),"Partial draft commit overwrote an untouched concurrent profile");
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
                    "unsavedModePreviewDoesNotPersistOrAlterPrompt", "groupedAiSavePreservesPreferencesPermissionsAndWorkspace",
                    "groupedPreferenceSavePreservesLatestAiAndToolSettings",
                    "groupedPreferenceSaveNormalizesValuesAndDoesNotResurrectOldUltra",
                    "oldAiConfigurationMigratesOnceWithoutLosingProfilesOrModelLists",
                    "providersKeepSeparateCredentialsAndImmutableOfficialDefaults",
                    "modelSelectionActivatesOnlyItsProviderAndKeepsProfilesIndependent",
                    "independentSettingsWritesAndPartialDraftCommitDoNotClobberProviders"}) run(name);
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
