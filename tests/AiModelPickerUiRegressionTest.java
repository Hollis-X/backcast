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
    private static Object tagged(Object card, String tag) throws Exception {
        for (Object child : children(card)) if (tag.equals(get(child, "tag"))) return child;
        throw new AssertionError("Missing tagged row: " + tag);
    }
    private static Object popupCard(Object view) throws Exception {
        return get(get(get(view, "modelPopup"), "content"), "content");
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
        check(labels.equals(Arrays.asList("DeepSeek", "same-model", "other-model",
                        "OpenAI", "same-model", "gpt-model", "Grok", "grok-model", "配置供应商与模型")),
                "Provider/model grouping or current selection is ambiguous: " + labels);
        check(!labels.toString().contains("secret") && !labels.toString().contains("https://")
                        && !labels.contains("hidden-model"), "Picker exposed credentials or an unconfigured provider");
        check(invoke(view, "activeModelLabel").equals("same-model"), "Composer added a provider prefix");
        check(methods.get("showModelPage").contains("appendModelPicker(card)")
                        && methods.get("showIntelligencePage").contains("Settings.EFFORT_MAX")
                        && methods.get("showIntelligencePage").contains("Settings.EFFORT_ULTRA")
                        && methods.get("updateStatus").contains("activeModelLabel()"),
                "Actual popup/chip did not connect provider choices or dropped effort options");
        pass("providerGroupsKeepSavedModelsAndDistinguishSameNamedModels");
    }
    private static void twoPagePopupKeepsHierarchyAndSelection() throws Exception {
        Object view = configuredView();
        addProfile(view, "custom-ready", "自定义", "https://custom.test/v1", "custom-secret", "local-model", "local-model");
        invoke(view, "showModelPopup");
        Object popup = get(view, "modelPopup"), card = popupCard(view);
        List<String> labels = new ArrayList<>();
        for (Object child : children(card)) if (get(child, "text") != null) labels.add((String) get(child, "text"));
        check(labels.equals(Arrays.asList("智能", "低", "中", "高", "极高", "Max", "Ultra")),
                "First page contains models, off, speed or permission choices: " + labels);
        check((Integer) get(row(card, "智能"), "color") == 2, "Intelligence title is not secondary text");
        for (String label : Arrays.asList("低", "中", "高", "极高", "Max", "Ultra")) {
            Object effort = row(card, label);
            check(((Integer) get(effort, "rightIcon") != 0) == label.equals("高"), "Selected check is missing/on wrong effort");
            check((Integer) get(effort, "leftIcon") == 0, "Selected effort check is on the left");
        }
        Object entry = tagged(card, "model-menu-entry"), column = children(entry).get(0);
        check(children(column).size() == 2 && get(children(column).get(0), "text").equals("模型")
                        && get(children(column).get(1), "text").equals("same-model")
                        && (Integer) get(children(column).get(1), "color") == 2,
                "Model entry lost its separate gray current-model line");
        invoke(entry, "click");
        check(get(view, "modelPopup") == popup && (Boolean) get(popup, "showing")
                        && !(Boolean) get(popup, "dismissed"), "Model navigation created/dismissed the popup");
        check((Integer) get(row(card, "same-model"), "rightIcon") != 0
                        && row(card, "自定义") != null && row(card, "local-model") != null,
                "Model page lost active check or configured custom provider");
        invoke(tagged(card, "model-menu-back"), "click");
        check(get(view, "modelPopup") == popup && (Integer) get(row(card, "高"), "rightIcon") != 0,
                "Back navigation lost popup/selection state");
        invoke(tagged(card, "model-menu-entry"), "click");
        invoke(row(card, "local-model"), "click");
        check((Boolean) get(popup, "dismissed") && invoke(view, "activeModelLabel").equals("local-model"),
                "Custom model selection did not close or leaked custom prefix into composer");
        pass("twoPagePopupRetainsChecksBackNavigationAndCustomModels");
    }
    private static void effortChangeRetargetsWithoutCancellingAndOffHasNoCheck() throws Exception {
        Object view = configuredView(); Client client = new Client(); AgentLoop loop = liveLoop(client);
        set(view, "loop", loop); invoke(view, "showModelPopup");
        invoke(row(popupCard(view), "极高"), "click");
        check(get(get(view, "settings"), "effort").equals("xhigh") && (Integer) get(view, "retargets") == 1
                        && (Integer) get(view, "statusUpdates") == 1 && !(Boolean) get(loop, "cancelled")
                        && loop.goalActive() && client.calls == 0 && (Integer) get(view, "liveToken") == 0
                        && get(view, "dialog") == null, "Effort selection restarted/cancelled a running request");
        check(invoke(view, "effortLabel", "xhigh").equals("极高"), "Composer cannot label xhigh");
        invoke(view, "showModelPopup");
        check((Integer) get(row(popupCard(view), "极高"), "rightIcon") != 0, "Reopened popup lost xhigh selection");
        invoke(view, "showModelPopup");
        set(get(view, "settings"), "effort", "off"); invoke(view, "showModelPopup");
        for (Object child : children(popupCard(view))) check((Integer) get(child, "rightIcon") == 0,
                "Off preference falsely selected one of the six menu efforts");
        pass("effortSelectionTargetsNextRequestWithoutInterruptingCurrentWork");
    }
    private static void popupPagesFitSmallScreensAndScroll() throws Exception {
        Object view = configuredView(), metrics = get(get(view, "resources"), "metrics");
        set(metrics, "widthPixels", 220); set(metrics, "heightPixels", 320);
        for (int i = 0; i < 30; i++) addProfile(view, "provider" + i, "Provider " + i, "https://fixture", "key", "model" + i);
        invoke(view, "showModelPopup"); Object popup = get(view, "modelPopup"), card = popupCard(view);
        invoke(tagged(card, "model-menu-entry"), "click");
        check((Integer) get(popup, "width") <= 204 && (Integer) get(popup, "height") <= 288
                        && (Integer) get(popup, "x") + (Integer) get(popup, "width") <= 220
                        && (Integer) get(popup, "y") + (Integer) get(popup, "height") <= 320,
                "Popup escaped screen bounds");
        Object scroll = get(popup, "content");
        check(scroll.getClass().getSimpleName().equals("ScrollView") && (Integer) get(scroll, "scrollY") == 0
                        && children(card).size() > 50 && (Integer) get(card, "measuredHeight") > (Integer) get(popup, "height"),
                "Long provider list was clipped without scrolling or navigation retained stale scroll position");
        pass("bothPopupPagesUseScreenBoundsAndLongModelListScrolls");
    }
    private static void emptyConfigurationRoutesToAiConfiguration() throws Exception {
        Object view = view(), card = create("LinearLayout");
        addProfile(view, "deepseek", "DeepSeek", "https://deepseek.test/v1", "", "same-model", "same-model");
        invoke(view, "appendModelPicker", card);
        check(children(card).size() == 1, "Unconfigured provider received selectable model rows");
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
        StringBuilder source = new StringBuilder("""
                import java.util.*;import com.mkei.backcast.agent.*;public class ModelPickerFixture{
                static class View {
                    static class MeasureSpec {static final int EXACTLY=1,UNSPECIFIED=0;static int makeMeasureSpec(int n,int mode){return n;}}
                    interface OnClickListener{void onClick(View v);} OnClickListener listener;
                    String text;Object tag;int background,minHeight,measuredHeight;boolean selected;int leftIcon,rightIcon;Object description;
                    View(){}View(Object context){}void setOnClickListener(OnClickListener l){listener=l;}void click(){if(listener!=null)listener.onClick(this);}
                    void setTag(Object t){tag=t;}void setSelected(boolean b){selected=b;}void setPadding(int a,int b,int c,int d){}
                    void setBackgroundResource(int n){background=n;}void setBackgroundColor(int n){background=n;}
                    void setMinimumHeight(int n){minHeight=n;}void setContentDescription(Object d){description=d;}
                    void startAnimation(Object a){}void measure(int width,int height){measuredHeight=Math.max(minHeight,32);}
                    int getMeasuredHeight(){return measuredHeight;}void getLocationOnScreen(int[] a){a[0]=290;a[1]=720;}
                }
                static class ViewGroup extends View{static class LayoutParams{static final int MATCH_PARENT=-1,WRAP_CONTENT=-2;}}
                static class TextView extends View{int color,maxLines;TextView(){}TextView(Object o){}void setText(String v){text=v;}
                    void setMinHeight(int n){minHeight=n;}void setGravity(int n){}void setMaxLines(int n){maxLines=n;}void setEllipsize(Object o){}}
                static class LinearLayout extends ViewGroup{static final int VERTICAL=1,HORIZONTAL=0;int orientation;
                    List<View> children=new ArrayList<View>();LinearLayout(){}LinearLayout(Object c){}void setOrientation(int n){orientation=n;}
                    void addView(View v,Object p){children.add(v);}void removeAllViews(){children.clear();}void setGravity(int n){}
                    @Override void measure(int width,int height){measuredHeight=24;for(View v:children){v.measure(width,height);measuredHeight+=v.getMeasuredHeight();}}
                    static class LayoutParams extends ViewGroup.LayoutParams{LayoutParams(int w,int h){}LayoutParams(int w,int h,int weight){}void setMargins(int a,int b,int c,int d){}}
                }
                static class ScrollView extends View{View content;int scrollY=5;boolean fillViewport;ScrollView(Object c){}void addView(View v){content=v;}
                    void setFillViewport(boolean b){fillViewport=b;}void scrollTo(int x,int y){scrollY=y;}}
                static class PopupWindow{boolean dismissed,showing;View content;int width,height,x,y,updates;
                    PopupWindow(){}PopupWindow(View c,int w,int h,boolean focus){content=c;width=w;height=h;}
                    void dismiss(){dismissed=true;showing=false;}boolean isShowing(){return showing;}View getContentView(){return content;}
                    void setBackgroundDrawable(Object d){}void setOutsideTouchable(boolean b){}void setWidth(int n){width=n;}void setHeight(int n){height=n;}
                    void showAtLocation(View a,int gravity,int xx,int yy){showing=true;x=xx;y=yy;}void update(int xx,int yy,int w,int h){x=xx;y=yy;width=w;height=h;updates++;}}
                PopupWindow modelPopup=new PopupWindow();View modelChipAnchor=new View(),modelChip=new View();
                static class Metrics{int widthPixels=320,heightPixels=760;}static class Resources{Metrics metrics=new Metrics();Metrics getDisplayMetrics(){return metrics;}}
                Resources resources=new Resources();Resources getResources(){return resources;}
                static class R{static class color{static final int text_primary=1,text_secondary=2;}
                    static class drawable{static final int bg_popup_selected=3,bg_popup=10,ic_ds_checkmark_lg_regular_24=11;}
                    static class anim{static final int popup_in=12;}
                    static class string{static final int popup_providers=4,popup_configure_ai=5,popup_models_empty=6,popup_switch_running_title=7,popup_switch_running_body=8,popup_switch_confirm=9,popup_intelligence=13,popup_model=14,popup_model_back=15;}}
                static class Gravity{static final int CENTER_VERTICAL=1,NO_GRAVITY=0;}static class Color{static final int TRANSPARENT=0;}
                static class ColorDrawable{ColorDrawable(int c){}}static class AnimationUtils{static Object loadAnimation(Object c,int n){return null;}}
                static class Icons{static final int BACK=20;static void left(View v,int res,int color,int size){v.leftIcon=res;}static void right(View v,int res,int color,int size){v.rightIcon=res;}}
                static class AiConfigActivity{}static class Intent{Class<?> target;Intent(Object c,Class<?> t){target=t;}}Intent launched;void startActivity(Intent i){launched=i;}
                long sessionId=7;AgentLoop loop;int liveToken;boolean activityDestroyed,sessionOpening,compactLive;int retargets,statusUpdates,settles;
                boolean isFinishing(){return false;}int dp(int n){return n;}Object wrapParams(){return null;}String displayModelName(String raw){return raw;}
                TextView popupText(String t,int n,int c){TextView v=new TextView();v.text=t;v.color=c;return v;}
                String getString(int n,Object...args){if(n==4)return "供应商与模型";if(n==5)return "配置供应商与模型";if(n==6)return "尚未保存模型，前往 AI 配置";
                    if(n==8)return "停止当前轮并切换到 "+args[0]+" · "+args[1];if(n==13)return "智能";if(n==14)return "模型";if(n==15)return "返回智能菜单";return Integer.toString(n);}
                static class Settings{static final String EFFORT_OFF="off",EFFORT_LOW="low",EFFORT_MEDIUM="medium",EFFORT_HIGH="high",EFFORT_XHIGH="xhigh",EFFORT_MAX="max",EFFORT_ULTRA="ultra";
                    static class AiProfile{final String id,name,baseUrl,apiKey,model;final List<String> modelList;AiProfile(String i,String n,String u,String k,String m,List<String> list){id=i;name=n;baseUrl=u;apiKey=k;model=m;modelList=list;}}
                    List<AiProfile> profiles=new ArrayList<AiProfile>();String active="deepseek",effort="high";int selections;
                    String effectiveReasoningEffort(){return effort;}void setReasoningEffort(String e){effort=e;}
                    List<AiProfile> aiProfiles(){return profiles;}String activeProviderId(){return active;}AiProfile activeAiProfile(){for(AiProfile p:profiles)if(p.id.equals(active))return p;throw new AssertionError(active);}
                    String model(){return activeAiProfile().model;}void selectAiModel(String id,String model){for(int n=0;n<profiles.size();n++){AiProfile p=profiles.get(n);if(p.id.equals(id)){profiles.set(n,new AiProfile(p.id,p.name,p.baseUrl,p.apiKey,model,p.modelList));active=id;selections++;return;}}throw new AssertionError(id);}}
                Settings settings=new Settings();static class RunHub{ModelPickerFixture owner;static RunHub get(ModelPickerFixture v){RunHub h=new RunHub();h.owner=v;return h;}void retargetIfNeeded(){owner.retargets++;}}
                void updateStatus(){statusUpdates++;}void hidePending(){}void settleWork(){settles++;}void dropCompactRow(){}void settleCompact(){}void setBusy(boolean busy){}void refreshGoal(){}void cancelApprovals(){}
                AlertDialog dialog;static class AlertDialog{String message;android.content.DialogInterface.OnClickListener positive;void confirm(){positive.onClick(null,1);}
                    static class Builder{ModelPickerFixture owner;AlertDialog d=new AlertDialog();Builder(ModelPickerFixture o){owner=o;}Builder setTitle(int n){return this;}Builder setMessage(String s){d.message=s;return this;}
                    Builder setNegativeButton(int n,Object l){return this;}Builder setPositiveButton(int n,android.content.DialogInterface.OnClickListener l){d.positive=l;return this;}void show(){owner.dialog=d;}}}
                """);
        for (String name : Arrays.asList("showModelPopup", "showIntelligencePage", "showModelPage", "layoutModelPopup", "addEffortOption", "effortLabel", "appendModelPicker", "savedModels", "addModelOption", "modelSelectionCurrent",
                "modelChoice", "chooseAiModel", "applyAiModelSelection", "cancelForModelSwitch", "activeModelLabel")) {
            check(methods.containsKey(name), "Missing actual picker method: " + name);
            source.append(methods.get(name).replace("MainActivity.this", "ModelPickerFixture.this"));
        }
        source.append('}');
        List<JavaFileObject> sources = Arrays.asList(new Source("ModelPickerFixture", source.toString()),
                new Source("android/R", "package android;public final class R{public static class string{public static final int cancel=0;}}"),
                new Source("android/text/TextUtils", "package android.text;public final class TextUtils{public enum TruncateAt{END}}"),
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
                twoPagePopupKeepsHierarchyAndSelection();
                effortChangeRetargetsWithoutCancellingAndOffHasNoCheck();
                popupPagesFitSmallScreensAndScroll();
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
