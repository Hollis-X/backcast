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
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Executes the production settings navigation and refreshed summaries. */
public final class SettingsNavigationRegressionTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final class Source extends SimpleJavaFileObject {
        final String text;
        Source(String text) { super(URI.create("string:///SettingsActivity.java"), Kind.SOURCE); this.text = text; }
        @Override public CharSequence getCharContent(boolean ignored) { return text; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void call(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
    }
    private static Element xml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
    }
    private static URLClassLoader compile(Path root, Path output) throws Exception {
        StringBuilder production = new StringBuilder();
        try (StandardJavaFileManager fm = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, fm, null,
                    Arrays.asList("-proc:none"), null, fm.getJavaFileObjects(root.resolve(
                    "app/src/main/java/com/mkei/backcast/SettingsActivity.java").toFile()));
            for (CompilationUnitTree unit : task.parse()) for (Tree type : unit.getTypeDecls()) {
                if (type instanceof ClassTree) for (Tree member : ((ClassTree) type).getMembers()) {
                    if (member instanceof MethodTree) production.append(member.toString());
                }
            }
        }
        String fixture = "import java.util.*; class Host { protected void onCreate(SettingsActivity.Bundle b){} protected void onResume(){} }"
                + "public class SettingsActivity extends Host {"
                + "static class Bundle{} static class View{interface OnClickListener{void onClick(View v);}OnClickListener click;"
                + "void setOnClickListener(OnClickListener c){click=c;}void click(){click.onClick(this);}}"
                + "static class TextView extends View{String value;void setText(String s){value=s;}}"
                + "static class ImageView extends View{void setImageDrawable(Object d){}}"
                + "static class Toolbar extends View{OnClickListener back;void setNavigationIcon(Object d){}"
                + "void setNavigationOnClickListener(OnClickListener l){back=l;}}"
                + "static class Intent{Class<?> destination;Intent(SettingsActivity a,Class<?> c){destination=c;}}"
                + "static class AiConfigActivity{}static class UserPreferencesActivity{}static class ToolConfigActivity{}"
                + "static class Icons{static final int BACK=1,CHAT=2,SETTINGS=3,TERMINAL=4,CHEVRON_RIGHT=5;"
                + "static Object tinted(SettingsActivity a,int r,int c,int s){return r;}}"
                + "static class R{static class layout{static final int activity_settings=1;}static class array{static final int output_language_labels=1;}"
                + "static class id{static final int toolbar=1,settings_ai_row=2,settings_ai_icon=3,settings_ai_arrow=4,"
                + "settings_preferences_row=5,settings_preferences_icon=6,settings_preferences_arrow=7,settings_tools_row=8,"
                + "settings_tools_icon=9,settings_tools_arrow=10,settings_ai_summary=11,settings_preferences_summary=12,settings_tools_summary=13;}"
                + "static class string{static final int status_no_model=1,settings_preferences_summary=2,access_full=3,access_guarded=4,"
                + "access_strict=5,settings_tools_summary=6,settings_root_on=7,settings_root_off=8;}}"
                + "static class Resources{String[] getStringArray(int id){return new String[]{\"zh\",\"tw\",\"en\",\"ja\",\"ko\",\"es\",\"fr\",\"de\"};}"
                + "Metrics getDisplayMetrics(){return new Metrics();}}static class Metrics{float density=2;}"
                + "static class Settings{static final String ACCESS_FULL=\"full\",ACCESS_GUARDED=\"guarded\";static String model=\"\",language=\"zh-CN\",effort=\"max\",access=\"full\";"
                + "static boolean root;Settings(SettingsActivity a){}static final class AiProfile{final String model;AiProfile(String value){model=value;}}AiProfile activeAiProfile(){return new AiProfile(model);}String outputLanguage(){return language;}"
                + "String reasoningEffort(){return effort;}String accessLevel(){return access;}boolean useRoot(){return root;}}"
                + "Map<Integer,View> views=new HashMap<Integer,View>();List<Class<?>> opened=new ArrayList<Class<?>>();int finishes;"
                + "SettingsActivity(){views.put(1,new Toolbar());for(int i=2;i<=10;i++)views.put(i,(i==2||i==5||i==8)?new View():new ImageView());"
                + "for(int i=11;i<=13;i++)views.put(i,new TextView());}"
                + "View findViewById(int id){return views.get(id);}void setContentView(int id){}void setSupportActionBar(Toolbar t){}"
                + "Resources getResources(){return new Resources();}void startActivity(Intent i){opened.add(i.destination);}void finish(){finishes++;}"
                + "String getString(int id,Object...args){return id+Arrays.toString(args);}void create(){onCreate(new Bundle());}"
                + production + "}";
        try (StandardJavaFileManager fm = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            check(ToolProvider.getSystemJavaCompiler().getTask(null, fm, null, Arrays.asList("-proc:none", "-source", "7",
                    "-target", "7", "-Xlint:-options", "-d", output.toString()), null, Arrays.asList(new Source(fixture))).call(),
                    "Settings navigation must compile as Java 7");
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, null);
    }
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]), output = Files.createTempDirectory("backcast-settings-navigation-");
        try (URLClassLoader loader = compile(root, output)) {
            Class<?> type = loader.loadClass("SettingsActivity");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            Object activity = constructor.newInstance(); call(activity, "create");
            @SuppressWarnings("unchecked") Map<Integer, Object> views = (Map<Integer, Object>) field(activity, "views");
            for (int id : new int[]{2, 5, 8}) call(views.get(id), "click");
            @SuppressWarnings("unchecked") List<Class<?>> opened = (List<Class<?>>) field(activity, "opened");
            check(opened.size() == 3 && opened.get(0).getSimpleName().equals("AiConfigActivity")
                    && opened.get(1).getSimpleName().equals("UserPreferencesActivity")
                    && opened.get(2).getSimpleName().equals("ToolConfigActivity"), "Settings rows did not open separate editor pages");
            System.out.println("PASS settingsRowsOpenThreeIndependentEditors");
            call(activity, "onResume"); check(field(views.get(11), "value").equals("1[]"), "Missing model state was not displayed");
            Class<?> settings = loader.loadClass("SettingsActivity$Settings");
            for (String[] change : new String[][]{{"model", "chosen-model"}, {"language", "en"}, {"effort", "ultra"}, {"access", "strict"}}) {
                Field field = settings.getDeclaredField(change[0]); field.setAccessible(true); field.set(null, change[1]);
            }
            Field rootField = settings.getDeclaredField("root"); rootField.setAccessible(true); rootField.set(null, true);
            call(activity, "onResume");
            check(field(views.get(11), "value").equals("chosen-model") && field(views.get(12), "value").equals("2[en, ultra]")
                    && field(views.get(13), "value").equals("6[5[], 7[]]"), "Returning from an editor kept stale settings summaries");
            System.out.println("PASS returningFromEditorsRefreshesCurrentConfiguration");
            Object toolbar = views.get(1), back = field(toolbar, "back");
            Class<?> click = loader.loadClass("SettingsActivity$View$OnClickListener"), view = loader.loadClass("SettingsActivity$View");
            Method handler = click.getDeclaredMethod("onClick", view); handler.setAccessible(true); handler.invoke(back, toolbar);
            check(field(activity, "finishes").equals(1), "Toolbar back did not return without saving");
            System.out.println("PASS settingsNavigationBackFinishesWithoutSaving");
            Element layout = xml(root.resolve("app/src/main/res/layout/activity_settings.xml"));
            NodeList nodes = layout.getElementsByTagName("*"); int rows = 0;
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i); String id = node.getAttributeNS(ANDROID, "id");
                check(!node.getTagName().equals("EditText") && !node.getTagName().equals("Spinner"), "Settings home still contains an inline editor");
                if (id.endsWith("_row")) {
                    rows++; check(node.getAttributeNS(ANDROID, "focusable").equals("true")
                            && node.getAttributeNS(ANDROID, "layout_height").equals("wrap_content"), "Settings row cannot be focused or grow for large text");
                }
            }
            check(rows == 3, "Home categories missing");
            Element manifest = xml(root.resolve("app/src/main/AndroidManifest.xml"));
            for (String name : new String[]{"AiConfigActivity", "UserPreferencesActivity", "ToolConfigActivity"}) {
                boolean registered = false; NodeList activities = manifest.getElementsByTagName("activity");
                for (int i = 0; i < activities.getLength(); i++) {
                    Element declared = (Element) activities.item(i);
                    if (declared.getAttributeNS(ANDROID, "name").equals("." + name)) {
                        registered = declared.getAttributeNS(ANDROID, "exported").equals("false");
                    }
                }
                check(registered, "Editor must be registered and private: " + name);
            }
            System.out.println("PASS categoryLayoutWrapsAndAllEditorsAreRegisteredPrivately");
            System.out.println("4 settings navigation tests passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }
}
