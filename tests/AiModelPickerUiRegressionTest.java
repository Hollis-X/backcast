import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.ToolRegistry;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual MainActivity picker callbacks, with UI surfaces replaced and the real AgentLoop retained. */
public final class AiModelPickerUiRegressionTest {
    private static final Map<String, String> methods = new HashMap<>();
    private static Class<?> fixture;
    private static int passed;
    private static final class Source extends SimpleJavaFileObject {
        final String source;
        Source(String name, String source) {
            super(URI.create("string:///" + name + ".java"), Kind.SOURCE); this.source = source;
        }
        @Override public CharSequence getCharContent(boolean ignored) { return source; }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }
    private static Field field(Object value, String name) throws Exception {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try { Field result = type.getDeclaredField(name); result.setAccessible(true); return result; }
            catch (NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object get(Object value, String name) throws Exception { return field(value, name).get(value); }
    private static void set(Object value, String name, Object data) throws Exception { field(value, name).set(value, data); }
    private static Object invoke(Object value, String name, Object... args) throws Exception {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass())
            for (Method method : type.getDeclaredMethods())
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    method.setAccessible(true); return method.invoke(value, args);
                }
        throw new NoSuchMethodException(name);
    }
    private static Class<?> nested(String name) {
        for (Class<?> type : fixture.getDeclaredClasses()) if (type.getSimpleName().equals(name)) return type;
        throw new AssertionError(name);
    }
    private static Object create(String name) throws Exception {
        var constructor = nested(name).getDeclaredConstructor(); constructor.setAccessible(true); return constructor.newInstance();
    }
    private static Object view() throws Exception { return fixture.getConstructor().newInstance(); }
    private static void addProfile(Object view, String id, String name, String url, String key, String model,
                                   String... models) throws Exception {
        Class<?> profile = null;
        for (Class<?> type : nested("Settings").getDeclaredClasses()) if (type.getSimpleName().equals("AiProfile")) profile = type;
        var constructor = profile.getDeclaredConstructor(String.class, String.class, String.class, String.class,
                String.class, List.class); constructor.setAccessible(true);
        @SuppressWarnings("unchecked") List<Object> profiles = (List<Object>) get(get(view, "settings"), "profiles");
        profiles.add(constructor.newInstance(id, name, url, key, model, Arrays.asList(models)));
    }
    private static List<?> children(Object card) throws Exception { return (List<?>) get(card, "children"); }
    private static Object row(Object card, String text) throws Exception {
        for (Object child : children(card)) if (text.equals(get(child, "text"))) return child;
        throw new AssertionError("Missing row: " + text);
    }
    private static Object configuredView() throws Exception {
        Object view = view();
        addProfile(view, "deepseek", "DeepSeek", "https://deepseek.test/v1", "deepseek-secret", "same-model", "same-model", "other-model");
        addProfile(view, "openai", "OpenAI", "https://openai.test/v1", "openai-secret", "same-model", "same-model", "gpt-model");
        addProfile(view, "grok", "Grok", "https://grok.test/v1", "grok-secret", "grok-model");
        addProfile(view, "custom", "自定义", "https://custom.test/v1", "", "hidden-model", "hidden-model");
        return view;
    }
    private static final class Client extends LlmClient {
        int calls;
        Client() { super(new Config("http://localhost", "fixture", "fixture")); }
        @Override public Reply send(List<Message> messages, JSONArray schema, Sink sink) {
            calls++; Reply reply = new Reply(); reply.content = "done"; return reply;
        }
    }
    private static AgentLoop liveLoop(Client client) throws Exception {
        AgentLoop loop = new AgentLoop(client, new ToolRegistry(), new AgentLoop.Quiet());
        loop.bindSession(7L);
        JSONArray calls = new JSONArray().put(new JSONObject().put("id", "done-call").put("type", "function")
                .put("function", new JSONObject().put("name", "write").put("arguments", "{\"path\":\"done.txt\"}")));
        loop.loadHistory("fixture", Arrays.asList(Message.user("do work"), Message.assistant("", calls),
                Message.toolResult("done-call", "already-executed")));
        loop.setGoal("keep completed evidence");
        set(loop, "busy", true); set(loop, "cancelled", false); set(loop, "acceptedUi", 0);
        return loop;
    }
    private static void groupedSavedChoicesDoNotLeakCredentials() throws Exception {
        Object view = configuredView(), card = create("LinearLayout");
        invoke(view, "appendModelPicker", card);
        List<String> labels = new ArrayList<>();
        for (Object child : children(card)) labels.add((String) get(child, "text"));
        check(labels.equals(Arrays.asList("供应商与模型", "DeepSeek", "✓ same-model", "other-model",
                        "OpenAI", "same-model", "gpt-model", "Grok", "grok-model", "配置供应商与模型")),
                "Provider/model grouping or current selection is ambiguous: " + labels);
        check(!labels.toString().contains("secret") && !labels.toString().contains("https://")
                        && !labels.contains("hidden-model"), "Picker exposed credentials or an unconfigured provider");
        check(invoke(view, "activeModelLabel").equals("DeepSeek · same-model"), "Composer omitted active provider");
        check(methods.get("showModelPopup").contains("appendModelPicker(card)")
                        && methods.get("showModelPopup").contains("Settings.EFFORT_MAX")
                        && methods.get("showModelPopup").contains("Settings.EFFORT_ULTRA")
                        && methods.get("updateStatus").contains("activeModelLabel()"),
                "Actual popup/chip did not connect provider choices or dropped effort options");
        pass("providerGroupsKeepSavedModelsAndDistinguishSameNamedModels");
    }
    private static void emptyConfigurationRoutesToAiConfiguration() throws Exception {
        Object view = view(), card = create("LinearLayout");
        addProfile(view, "deepseek", "DeepSeek", "https://deepseek.test/v1", "", "same-model", "same-model");
        invoke(view, "appendModelPicker", card);
        check(children(card).size() == 2, "Unconfigured provider received selectable model rows");
        invoke(row(card, "尚未保存模型，前往 AI 配置"), "click");
        check(((Class<?>) get(get(view, "launched"), "target")).getSimpleName().equals("AiConfigActivity")
                        && (Boolean) get(get(view, "modelPopup"), "dismissed"),
                "Empty picker did not open the actual AI configuration entry");
        pass("emptyProviderPickerOpensAiConfigurationWithoutCreatingModels");
    }
    private static void idleSelectionIsAtomicAndRejectsStalePopupRows() throws Exception {
        Object view = configuredView(), card = create("LinearLayout");
        invoke(view, "appendModelPicker", card);
        invoke(row(card, "gpt-model"), "click");
        Object settings = get(view, "settings");
        check(get(settings, "active").equals("openai") && invoke(settings, "model").equals("gpt-model")
                        && (Integer) get(settings, "selections") == 1 && (Integer) get(view, "retargets") == 1,
                "Provider and model were not selected in one settings operation");
        check((Integer) get(view, "statusUpdates") == 1 && (Integer) get(view, "settles") == 0,
                "Idle selection fabricated a cancellation or did not refresh the composer");
        Object profile = invoke(settings, "activeAiProfile");
        check(get(profile, "apiKey").equals("openai-secret") && get(profile, "baseUrl").equals("https://openai.test/v1"),
                "Selecting a model changed its provider credentials");
        Object stale = row(card, "other-model"); set(view, "sessionId", 8L); invoke(stale, "click");
        check((Integer) get(settings, "selections") == 1, "A previous session's popup selected a model");
        set(view, "sessionId", 7L); set(view, "liveToken", 1); invoke(stale, "click");
        check((Integer) get(settings, "selections") == 1, "A previous turn's popup changed a new turn");
        pass("idleModelSelectionIsAtomicAndRejectsStaleSessionAndTurnCallbacks");
    }
    private static void runningSwitchRequiresConfirmationAndPreservesCompletedTools() throws Exception {
        Object view = configuredView(); Client client = new Client(); AgentLoop loop = liveLoop(client);
        set(view, "loop", loop); Object card = create("LinearLayout"); invoke(view, "appendModelPicker", card);
        invoke(row(card, "gpt-model"), "click"); Object dialog = get(view, "dialog");
        check(dialog != null && !(Boolean) get(loop, "cancelled")
                        && (Integer) get(get(view, "settings"), "selections") == 0,
                "Running model changed before explicit UI confirmation");
        String message = (String) get(dialog, "message");
        check(message.contains("OpenAI") && message.contains("gpt-model") && !message.contains("secret"),
                "Confirmation did not identify the selected provider/model safely");
        invoke(dialog, "confirm");
        check((Boolean) get(loop, "cancelled") && !loop.goalActive()
                        && (Integer) get(view, "liveToken") == 1 && (Integer) get(view, "settles") == 1
                        && (Integer) get(view, "retargets") == 1 && (Integer) get(view, "statusUpdates") == 1,
                "Confirmed model switch did not cancel old callbacks and preserve stopped state");
        List<Message> history = loop.historySnapshot();
        check(history.size() == 4 && history.get(3).content.equals("already-executed") && client.calls == 0,
                "Switch cleared completed tool history or repeated an execution/request");
        Object staleView = configuredView(); Client staleClient = new Client(); AgentLoop staleLoop = liveLoop(staleClient);
        set(staleView, "loop", staleLoop); Object staleCard = create("LinearLayout"); invoke(staleView, "appendModelPicker", staleCard);
        invoke(row(staleCard, "gpt-model"), "click"); set(staleView, "sessionId", 8L);
        invoke(get(staleView, "dialog"), "confirm");
        check(!(Boolean) get(staleLoop, "cancelled") && (Integer) get(get(staleView, "settings"), "selections") == 0,
                "Stale confirmation cancelled another session or activated a provider");
        pass("runningSwitchConfirmsStopsOldWorkAndKeepsCompletedToolEvidence");
    }
    private static void compile(Path root, Path output) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler(); check(compiler != null, "Use a JDK");
        Path main = root.resolve("app/src/main/java/com/mkei/backcast/MainActivity.java");
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            JavacTask parse = (JavacTask) compiler.getTask(null, files, null, Arrays.asList("-proc:none"), null,
                    files.getJavaFileObjects(main.toFile()));
            for (CompilationUnitTree unit : parse.parse()) for (Tree declaration : unit.getTypeDecls())
                if (declaration instanceof ClassTree) for (Tree member : ((ClassTree) declaration).getMembers())
                    if (member instanceof MethodTree) methods.put(((MethodTree) member).getName().toString(), member.toString());
        }
        StringBuilder source = new StringBuilder("import java.util.*;import com.mkei.backcast.agent.*;public class ModelPickerFixture{")
                .append("static class View{interface OnClickListener{void onClick(View v);}OnClickListener listener;void setOnClickListener(OnClickListener l){listener=l;}void click(){if(listener!=null)listener.onClick(this);}}").append("static class TextView extends View{String text;int background;void setText(String v){text=v;}void setPadding(int a,int b,int c,int d){}void setMinHeight(int n){}void setGravity(int n){}void setBackgroundResource(int n){background=n;}}").append("static class LinearLayout{List<TextView> children=new ArrayList<TextView>();void addView(TextView v,Object p){children.add(v);}}").append("static class PopupWindow{boolean dismissed;void dismiss(){dismissed=true;}}PopupWindow modelPopup=new PopupWindow();")
                .append("static class R{static class color{static final int text_primary=1,text_secondary=2;}static class drawable{static final int bg_popup_selected=3;}static class string{static final int popup_providers=4,popup_configure_ai=5,popup_models_empty=6,popup_switch_running_title=7,popup_switch_running_body=8,popup_switch_confirm=9;}}static class Gravity{static final int CENTER_VERTICAL=1;}")
                .append("static class AiConfigActivity{}static class Intent{Class<?> target;Intent(Object c,Class<?> t){target=t;}}Intent launched;void startActivity(Intent i){launched=i;}")
                .append("long sessionId=7;AgentLoop loop;int liveToken;boolean activityDestroyed,sessionOpening,compactLive;int retargets,statusUpdates,settles;boolean isFinishing(){return false;}int dp(int n){return n;}Object wrapParams(){return null;}String displayModelName(String raw){return raw;}TextView popupText(String t,int n,int c){TextView v=new TextView();v.text=t;return v;}")
                .append("String getString(int n,Object...args){if(n==4)return \"供应商与模型\";if(n==5)return \"配置供应商与模型\";if(n==6)return \"尚未保存模型，前往 AI 配置\";if(n==8)return \"停止当前轮并切换到 \"+args[0]+\" · \"+args[1];return Integer.toString(n);}")
                .append("static class Settings{static class AiProfile{final String id,name,baseUrl,apiKey,model;final List<String> modelList;AiProfile(String i,String n,String u,String k,String m,List<String> list){id=i;name=n;baseUrl=u;apiKey=k;model=m;modelList=list;}}List<AiProfile> profiles=new ArrayList<AiProfile>();String active=\"deepseek\";int selections;List<AiProfile> aiProfiles(){return profiles;}String activeProviderId(){return active;}AiProfile activeAiProfile(){for(AiProfile p:profiles)if(p.id.equals(active))return p;throw new AssertionError(active);}String model(){return activeAiProfile().model;}void selectAiModel(String id,String model){for(int n=0;n<profiles.size();n++){AiProfile p=profiles.get(n);if(p.id.equals(id)){profiles.set(n,new AiProfile(p.id,p.name,p.baseUrl,p.apiKey,model,p.modelList));active=id;selections++;return;}}throw new AssertionError(id);}}Settings settings=new Settings();")
                .append("static class RunHub{ModelPickerFixture owner;static RunHub get(ModelPickerFixture v){RunHub h=new RunHub();h.owner=v;return h;}void retargetIfNeeded(){owner.retargets++;}}void updateStatus(){statusUpdates++;}void hidePending(){}void settleWork(){settles++;}void dropCompactRow(){}void settleCompact(){}void setBusy(boolean busy){}void refreshGoal(){}void cancelApprovals(){}")
                .append("AlertDialog dialog;static class AlertDialog{String message;android.content.DialogInterface.OnClickListener positive;void confirm(){positive.onClick(null,1);}static class Builder{ModelPickerFixture owner;AlertDialog d=new AlertDialog();Builder(ModelPickerFixture o){owner=o;}Builder setTitle(int n){return this;}Builder setMessage(String s){d.message=s;return this;}Builder setNegativeButton(int n,Object l){return this;}Builder setPositiveButton(int n,android.content.DialogInterface.OnClickListener l){d.positive=l;return this;}void show(){owner.dialog=d;}}}");
        for (String name : Arrays.asList("appendModelPicker", "savedModels", "addModelOption", "modelSelectionCurrent",
                "modelChoice", "chooseAiModel", "applyAiModelSelection", "cancelForModelSwitch", "activeModelLabel")) {
            check(methods.containsKey(name), "Missing actual picker method: " + name);
            source.append(methods.get(name).replace("MainActivity.this", "ModelPickerFixture.this"));
        }
        source.append('}');
        List<JavaFileObject> sources = Arrays.asList(new Source("ModelPickerFixture", source.toString()),
                new Source("android/R", "package android;public final class R{public static class string{public static final int cancel=0;}}"),
                new Source("android/content/DialogInterface", "package android.content;public interface DialogInterface{interface OnClickListener{void onClick(DialogInterface d,int n);}}"));
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            check(compiler.getTask(null, files, null, Arrays.asList("-proc:none", "-encoding", "UTF-8", "-source", "8",
                    "-target", "8", "-Xlint:-options", "-cp", System.getProperty("java.class.path"), "-d", output.toString()),
                    null, sources).call(), "Actual model picker helpers did not compile");
        }
    }
    public static void main(String[] args) throws Exception {
        Path output = Files.createTempDirectory("backcast-model-picker-ui-");
        try {
            compile(Paths.get(args[0]), output);
            try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, AiModelPickerUiRegressionTest.class.getClassLoader())) {
                fixture = loader.loadClass("ModelPickerFixture");
                groupedSavedChoicesDoNotLeakCredentials();
                emptyConfigurationRoutesToAiConfiguration();
                idleSelectionIsAtomicAndRejectsStalePopupRows();
                runningSwitchRequiresConfirmationAndPreservesCompletedTools();
            }
            System.out.println(passed + " model picker UI tests passed");
        } finally {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
