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
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Runs the production fetch callback, lifecycle and grouped-save methods with queued UI delivery. */
public final class AiConfigRegressionTest {
    private static int passed;
    private static Class<?> type;
    private static Path root;
    private static final class Source extends SimpleJavaFileObject {
        final String body;
        Source(String body) { super(URI.create("string:///AiConfigActivity.java"), Kind.SOURCE); this.body = body; }
        @Override public CharSequence getCharContent(boolean ignored) { return body; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static Object field(Object object, String name) throws Exception {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) try {
            Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(object);
        } catch (NoSuchFieldException absent) { }
        throw new NoSuchFieldException(name);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) try {
            Field f = c.getDeclaredField(name); f.setAccessible(true); f.set(object, value); return;
        } catch (NoSuchFieldException absent) { }
        throw new NoSuchFieldException(name);
    }
    private static Object call(Object object, String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, signature); method.setAccessible(true); return method.invoke(object, args);
    }
    private static URLClassLoader compile(Path root, Path build) throws Exception {
        Map<String,String> methods = new HashMap<String,String>();
        try (StandardJavaFileManager manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, manager, null,
                    Arrays.asList("-proc:none"), null, manager.getJavaFileObjects(root.resolve(
                    "app/src/main/java/com/mkei/backcast/AiConfigActivity.java").toFile()));
            for (CompilationUnitTree unit : task.parse()) for (Tree tree : unit.getTypeDecls()) if (tree instanceof ClassTree) {
                for (Tree member : ((ClassTree) tree).getMembers()) if (member instanceof MethodTree) {
                    methods.put(((MethodTree) member).getName().toString(), member.toString());
                }
            }
        }
        String source = "import java.util.*; class Activity {boolean finishing;protected void onDestroy(){}"
                + "public void finish(){finishing=true;}protected void onSaveInstanceState(AiConfigActivity.Bundle b){}}"
                + "public class AiConfigActivity extends Activity {"
                + "static class View{static final int GONE=8,VISIBLE=0;boolean enabled=true;int visibility;void setEnabled(boolean e){enabled=e;}"
                + "void setVisibility(int v){visibility=v;}}static class TextView extends View{String text=\"\";void setText(String s){text=s;}String getText(){return text;}}"
                + "static class EditText extends TextView{}static class Button extends View{}"
                + "static class Bundle{Map<String,Object> values=new HashMap<String,Object>();void putString(String k,String v){values.put(k,v);}"
                + "String getString(String k){return (String)values.get(k);}void putStringArrayList(String k,ArrayList<String> v){values.put(k,v);}"
                + "ArrayList<String>getStringArrayList(String k){return(ArrayList<String>)values.get(k);}void putBundle(String k,Bundle b){values.put(k,b);}Bundle getBundle(String k){return(Bundle)values.get(k);}}"
                + "static class Settings{int writes;String url,key,model;List<String> saved;String active=\"custom\";Map<String,AiProfile>profiles=new LinkedHashMap<String,AiProfile>();"
                + "static class AiProfile{final String id,name,baseUrl,apiKey,model;final List<String>modelList;AiProfile(String i,String u,String k,String m,List<String>v)"
                + "{id=i;name=i;baseUrl=u.trim();apiKey=k.trim();model=m.trim();modelList=Collections.unmodifiableList(new ArrayList<String>(v));}}"
                + "Settings(){profiles.put(\"custom\",new AiProfile(\"custom\",\"https://provider.example/v1\",\"secret\",\"selected\",Arrays.asList(\"stored-model\")));"
                + "profiles.put(\"grok\",new AiProfile(\"grok\",\"https://api.x.ai/v1\",\"grok-key\",\"grok-model\",Arrays.asList(\"grok-model\")));}"
                + "List<AiProfile>aiProfiles(){return new ArrayList<AiProfile>(profiles.values());}String activeProviderId(){return active;}"
                + "void saveAiProfiles(List<AiProfile>edited,String id){writes++;for(AiProfile p:edited)profiles.put(p.id,p);active=id;AiProfile p=profiles.get(id);"
                + "url=p.baseUrl;key=p.apiKey;model=p.model;saved=new ArrayList<String>(p.modelList);}}"
                + "static class LlmClient{static String nextError;static class ModelsResult{String error,userMessage;Object diagnostic;List<String> models=Arrays.asList(\"remote-a\",\"remote-b\");}"
                + "static ModelsResult fetchModels(String u,String k,String provider){ModelsResult r=new ModelsResult();r.error=nextError;r.diagnostic=\"bounded-private-diagnostic\";return r;}}"
                + "static class ChatStore{static int logged;static String detail;ChatStore(Object c){}void recordDiagnostic(long sid,String source,String summary,String d){logged++;detail=d;}void close(){}}"
                + "static class TextUtils{static boolean isEmpty(String s){return s==null||s.length()==0;}}"
                + "static class Toast{static final int LENGTH_SHORT=0;static Toast makeText(AiConfigActivity a,int i,int d){return new Toast();}"
                + "static Toast makeText(AiConfigActivity a,String s,int d){a.toasts.add(s);return new Toast();}void show(){}}"
                + "static class R{static class string{static final int toast_need_url_key=1,fetching=2,fetch_failed_summary=3,fetch_count=4,toast_need_model=5;}}"
                + "EditText baseUrl=new EditText(),apiKey=new EditText(),model=new EditText();Button fetchModels=new Button();TextView fetchStatus=new TextView();"
                + "ArrayList<String> previewModels=new ArrayList<String>();int requestGeneration;boolean destroyed,bindingDraft;Thread activeFetch;int renders;String selectedProvider=\"custom\";"
                + "Map<String,Settings.AiProfile>drafts=new LinkedHashMap<String,Settings.AiProfile>();Set<String>editedProfiles=new LinkedHashSet<String>();"
                + "List<Runnable> callbacks=Collections.synchronizedList(new ArrayList<Runnable>());Settings settings=new Settings();"
                + "List<String>toasts=new ArrayList<String>();"
                + "public AiConfigActivity(){baseUrl.text=\"https://provider.example/v1\";apiKey.text=\"secret\";model.text=\"selected\";previewModels.add(\"stored-model\");for(Settings.AiProfile p:settings.aiProfiles())drafts.put(p.id,p);}"
                + "Object getApplicationContext(){return this;}boolean isFinishing(){return finishing;}void runOnUiThread(Runnable r){callbacks.add(r);}"
                + "String getString(int id,Object...args){return id+Arrays.toString(args);}void renderModels(List<String> m){renders++;}"
                + "void drain(){while(!callbacks.isEmpty())callbacks.remove(0).run();}"
                + methods.get("doFetch") + methods.get("isCurrentFetch") + methods.get("invalidateFetch") + methods.get("saveSettings")
                + methods.get("draft") + methods.get("onSaveInstanceState") + methods.get("onDestroy") + methods.get("finish")
                + methods.get("showStatus") + methods.get("onConnectionChanged") + methods.get("restoreDrafts")
                + methods.get("captureDraft") + methods.get("loadDraft") + methods.get("switchProvider") + methods.get("addSavedModel") + "}";
        try (StandardJavaFileManager manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            check(ToolProvider.getSystemJavaCompiler().getTask(null, manager, null, Arrays.asList("-proc:none", "-encoding", "UTF-8",
                    "-source", "8", "-target", "8", "-Xlint:-options", "-d", build.toString()), null, Arrays.asList(new Source(source))).call(),
                    "AI production lifecycle/fetch methods did not compile as Java 8");
        }
        URLClassLoader loader = new URLClassLoader(new URL[]{build.toUri().toURL()}, null);
        type = loader.loadClass("AiConfigActivity"); return loader;
    }
    private static Object fetch() throws Exception {
        Object activity = type.getConstructor().newInstance(); call(activity, "doFetch", new Class[0]);
        long deadline = System.currentTimeMillis() + 2000;
        while (((List<?>) field(activity, "callbacks")).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        check(!((List<?>) field(activity, "callbacks")).isEmpty(), "Model request did not return");
        return activity;
    }
    private static void unchanged(Object activity) throws Exception {
        call(activity, "drain", new Class[0]);
        check(Arrays.asList("stored-model").equals(field(activity, "previewModels")) && (Integer) field(activity, "renders") == 0,
                "Expired callback changed the preview");
        check((Integer) field(field(activity, "settings"), "writes") == 0, "Expired fetch persisted settings");
    }
    private static void successOnlyUpdatesThePreviewUntilExplicitSave() throws Exception {
        Object activity = fetch(); call(activity, "drain", new Class[0]); Object settings = field(activity, "settings");
        check(Arrays.asList("remote-a", "remote-b").equals(field(activity, "previewModels"))
                && (Integer) field(activity, "renders") == 1 && (Integer) field(settings, "writes") == 0, "Fetch wrote through preferences");
        call(activity, "saveSettings", new Class[]{settings.getClass()}, settings);
        check((Integer) field(settings, "writes") == 1 && "selected".equals(field(settings, "model"))
                && Arrays.asList("remote-a", "remote-b").equals(field(settings, "saved")), "Explicit AI save missed the model preview");
    }
    private static void editedConnectionRejectsAnOldResponse() throws Exception {
        Object activity = fetch(); set(field(activity, "baseUrl"), "text", "https://changed.example/v1"); unchanged(activity);
        activity = fetch(); set(field(activity, "apiKey"), "text", "new-key"); unchanged(activity);
    }
    private static void newRequestGenerationRejectsAnOldResponse() throws Exception {
        Object activity = fetch(); call(activity, "invalidateFetch", new Class[0]); unchanged(activity);
    }
    private static void editingConnectionClearsOldProviderModelsWithoutSaving() throws Exception {
        Object activity = fetch(); set(field(activity, "baseUrl"), "text", "https://new-provider.example/v1");
        call(activity, "onConnectionChanged", new Class[0]); call(activity, "drain", new Class[0]);
        check(((List<?>) field(activity, "previewModels")).isEmpty() && (Integer) field(activity, "renders") == 1
                && (Integer) field(field(activity, "settings"), "writes") == 0, "New provider retained or saved an old provider model list");
        check("selected".equals(field(field(activity, "model"), "text")) && (Boolean) field(field(activity, "fetchModels"), "enabled"),
                "Connection edits discarded the manually entered model or blocked fetching");
    }
    private static void leavingOrDestroyingThePageRejectsLateResponses() throws Exception {
        Object activity = fetch(); call(activity, "finish", new Class[0]); unchanged(activity);
        activity = fetch(); call(activity, "onDestroy", new Class[0]); unchanged(activity);
    }
    private static void saveDoesNotCommitAnUnfinishedFetch() throws Exception {
        Object activity = fetch(), settings = field(activity, "settings");
        call(activity, "saveSettings", new Class[]{settings.getClass()}, settings); call(activity, "drain", new Class[0]);
        check((Integer) field(settings, "writes") == 1 && Arrays.asList("stored-model").equals(field(settings, "saved"))
                && Arrays.asList("stored-model").equals(field(activity, "previewModels")), "Pending callback overwrote the saved AI draft");
    }
    private static void rotationKeepsIndependentUnsavedInputAndModelPreview() throws Exception {
        Object activity = fetch(); call(activity, "drain", new Class[0]);
        set(field(activity, "baseUrl"), "text", ""); set(field(activity, "model"), "text", "typed-model");
        Class<?> bundleType = type.getClassLoader().loadClass("AiConfigActivity$Bundle");
        java.lang.reflect.Constructor<?> constructor = bundleType.getDeclaredConstructor(); constructor.setAccessible(true); Object bundle = constructor.newInstance();
        call(activity, "onSaveInstanceState", new Class[]{bundleType}, bundle);
        Object savedDraft = call(bundle, "getBundle", new Class[]{String.class}, "draft_custom");
        check("".equals(call(activity, "draft", new Class[]{bundleType, String.class, String.class}, savedDraft, "url", "saved-url"))
                && "typed-model".equals(call(activity, "draft", new Class[]{bundleType, String.class, String.class}, savedDraft, "model", "saved-model")),
                "Rotation lost or replaced unsaved input");
        @SuppressWarnings("unchecked") Map<String,Object> saved = (Map<String,Object>) field(savedDraft, "values");
        @SuppressWarnings("unchecked") List<String> preview = (List<String>) field(activity, "previewModels"); preview.add("later");
        check(Arrays.asList("remote-a", "remote-b").equals(saved.get("models")) && (Integer) field(field(activity, "settings"), "writes") == 0,
                "Rotation persisted or aliased the model preview");
    }
    private static void explicitDraftRestorationAvoidsDuplicateConnectionWatchers() throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        org.w3c.dom.NodeList inputs = factory.newDocumentBuilder().parse(root.resolve(
                "app/src/main/res/layout/activity_ai_config.xml").toFile()).getElementsByTagName("EditText");
        int verified = 0;
        for (int i = 0; i < inputs.getLength(); i++) {
            org.w3c.dom.Element input = (org.w3c.dom.Element) inputs.item(i);
            String id = input.getAttributeNS("http://schemas.android.com/apk/res/android", "id");
            if (Arrays.asList("@+id/base_url", "@+id/api_key", "@+id/model").contains(id)) {
                check("false".equals(input.getAttributeNS("http://schemas.android.com/apk/res/android", "saveEnabled")),
                        "Automatic widget replay could clear the explicitly restored provider model list");
                if (id.equals("@+id/api_key")) check("textPassword".equals(input.getAttributeNS(
                        "http://schemas.android.com/apk/res/android", "inputType")), "API key is visible by default");
                verified++;
            }
        }
        check(verified == 3, "AI draft widgets are incomplete");
    }
    private static void providerSwitchKeepsIndependentUnsavedDraftsAndCredentials() throws Exception {
        Object activity = type.getConstructor().newInstance();
        set(field(activity, "apiKey"), "text", "custom-draft-key");
        set(field(activity, "model"), "text", "custom-draft-model");
        call(activity, "switchProvider", new Class[]{String.class}, "grok");
        check("grok-key".equals(field(field(activity,"apiKey"),"text"))
                        && Arrays.asList("grok-model").equals(field(activity,"previewModels")), "Provider inherited another provider's draft");
        set(field(activity, "apiKey"), "text", "grok-draft-key");
        set(field(activity, "model"), "text", "grok-draft-model");
        call(activity, "switchProvider", new Class[]{String.class}, "custom");
        check("custom-draft-key".equals(field(field(activity,"apiKey"),"text"))
                        && "custom-draft-model".equals(field(field(activity,"model"),"text")), "Switching back discarded the draft");
        call(activity, "switchProvider", new Class[]{String.class}, "grok");
        check("grok-draft-key".equals(field(field(activity,"apiKey"),"text"))
                        && (Integer)field(field(activity,"settings"),"writes") == 0, "Provider switching persisted or mixed credentials");
    }
    private static void providerIdentityRejectsOldFetchEvenWithMatchingEndpoint() throws Exception {
        Object activity = fetch(); call(activity,"switchProvider",new Class[]{String.class},"grok");
        set(field(activity,"baseUrl"),"text","https://provider.example/v1");
        set(field(activity,"apiKey"),"text","secret");
        call(activity,"drain",new Class[0]);
        check(Arrays.asList("grok-model").equals(field(activity,"previewModels"))
                        && (Integer)field(field(activity,"settings"),"writes") == 0, "Old provider callback crossed provider identity");
    }
    @SuppressWarnings("unchecked")
    private static void savingEditedDraftsPreservesAnUntouchedConcurrentProfile() throws Exception {
        Object activity = fetch(); call(activity,"drain",new Class[0]); Object settings = field(activity,"settings");
        Map<String,Object> profiles = (Map<String,Object>)field(settings,"profiles");
        Class<?> profile = type.getClassLoader().loadClass("AiConfigActivity$Settings$AiProfile");
        java.lang.reflect.Constructor<?> ctor = profile.getDeclaredConstructor(String.class,String.class,String.class,String.class,List.class);
        ctor.setAccessible(true);
        profiles.put("grok",ctor.newInstance("grok","https://api.x.ai/v1","concurrent-key","concurrent-model",Arrays.asList("concurrent-model")));
        call(activity,"saveSettings",new Class[]{settings.getClass()},settings);
        check("concurrent-key".equals(field(profiles.get("grok"),"apiKey"))
                        && "concurrent-model".equals(field(profiles.get("grok"),"model")), "Saving an untouched draft overwrote concurrent provider changes");
    }
    private static void allProviderDraftsAndManualModelsSurviveRotationWithoutSaving() throws Exception {
        Object activity = type.getConstructor().newInstance();
        set(field(activity,"model"),"text","manual-model"); call(activity,"addSavedModel",new Class[0]);
        call(activity,"addSavedModel",new Class[0]);
        check(Arrays.asList("stored-model","manual-model").equals(field(activity,"previewModels")),"Manual common-model insertion duplicated or failed");
        call(activity,"switchProvider",new Class[]{String.class},"grok");
        set(field(activity,"model"),"text","new-grok-model");
        Class<?> bundleType=type.getClassLoader().loadClass("AiConfigActivity$Bundle");
        java.lang.reflect.Constructor<?> ctor=bundleType.getDeclaredConstructor();ctor.setAccessible(true);Object bundle=ctor.newInstance();
        call(activity,"onSaveInstanceState",new Class[]{bundleType},bundle);
        Object restored=type.getConstructor().newInstance(),settings=field(restored,"settings");
        call(restored,"restoreDrafts",new Class[]{settings.getClass(),bundleType},settings,bundle);
        call(restored,"loadDraft",new Class[]{String.class},field(restored,"selectedProvider"));
        check("grok".equals(field(restored,"selectedProvider"))&&"new-grok-model".equals(field(field(restored,"model"),"text")),"Rotation lost active provider draft");
        call(restored,"switchProvider",new Class[]{String.class},"custom");
        check(Arrays.asList("stored-model","manual-model").equals(field(restored,"previewModels"))
                        && ((java.util.Set<?>)field(restored,"editedProfiles")).containsAll(Arrays.asList("custom","grok"))
                        && (Integer)field(settings,"writes")==0,"Rotation lost another provider or saved preferences");
    }
    private static void failedFetchWritesBackgroundDiagnosticsWithoutDisplayingProviderDetails() throws Exception {
        Class<?> client=type.getClassLoader().loadClass("AiConfigActivity$LlmClient");
        Field error=client.getDeclaredField("nextError");error.setAccessible(true);error.set(null,"private-provider-error-secret");
        try {
            Object activity=fetch();call(activity,"drain",new Class[0]);
            Class<?> store=type.getClassLoader().loadClass("AiConfigActivity$ChatStore");
            Field logged=store.getDeclaredField("logged"), detail=store.getDeclaredField("detail");logged.setAccessible(true);detail.setAccessible(true);
            check((Integer)logged.get(null)>0&&"bounded-private-diagnostic".equals(detail.get(null)),"Failed model request was not recorded in backend");
            check(!field(field(activity,"fetchStatus"),"text").toString().contains("private-provider-error")
                            && (Integer)field(field(activity,"fetchStatus"),"visibility")==8
                            && ((List<?>)field(activity,"toasts")).size()==1
                            && !((List<?>)field(activity,"toasts")).get(0).toString().contains("private-provider-error")
                            && (Integer)field(field(activity,"settings"),"writes")==0,"UI leaked raw provider error or fetch wrote preferences");
        } finally {error.set(null,null);}
    }
    public static void main(String[] args) throws Exception {
        Path build = Files.createTempDirectory("backcast-ai-config-");
        root = Paths.get(args[0]);
        try (URLClassLoader loader = compile(root, build)) {
            for (String name : new String[]{"successOnlyUpdatesThePreviewUntilExplicitSave", "editedConnectionRejectsAnOldResponse",
                    "newRequestGenerationRejectsAnOldResponse", "editingConnectionClearsOldProviderModelsWithoutSaving",
                    "leavingOrDestroyingThePageRejectsLateResponses",
                    "saveDoesNotCommitAnUnfinishedFetch", "rotationKeepsIndependentUnsavedInputAndModelPreview",
                    "explicitDraftRestorationAvoidsDuplicateConnectionWatchers", "providerSwitchKeepsIndependentUnsavedDraftsAndCredentials",
                    "providerIdentityRejectsOldFetchEvenWithMatchingEndpoint", "savingEditedDraftsPreservesAnUntouchedConcurrentProfile",
                    "allProviderDraftsAndManualModelsSurviveRotationWithoutSaving", "failedFetchWritesBackgroundDiagnosticsWithoutDisplayingProviderDetails"}) {
                try { AiConfigRegressionTest.class.getDeclaredMethod(name).invoke(null); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError(name, failure.getCause()); }
                passed++; System.out.println("PASS " + name);
            }
            System.out.println(passed + " AI configuration lifecycle tests passed");
        } finally { try (var paths = Files.walk(build)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
}
