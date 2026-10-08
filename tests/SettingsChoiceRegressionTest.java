import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
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
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Executes production bindChoices/ChoiceAdapter against small widget fixtures. */
public final class SettingsChoiceRegressionTest {
    private static int passed;
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final class Source extends SimpleJavaFileObject {
        final String text;
        Source(String text) { super(URI.create("string:///UserPreferencesActivity.java"), Kind.SOURCE); this.text=text; }
        @Override public CharSequence getCharContent(boolean ignored) { return text; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }
    private static Object field(Object target, String name) throws Exception {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass()) {
            try { Field f=type.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
            catch(NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void field(Object target, String name, Object value) throws Exception {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass()) {
            try { Field f=type.getDeclaredField(name); f.setAccessible(true); f.set(target,value); return; }
            catch(NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m=target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target,args);
    }
    private static Element xml(Path path) throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
    }
    private static String[] array(Element strings, String name) {
        NodeList arrays=strings.getElementsByTagName("string-array");
        for(int i=0;i<arrays.getLength();i++) {
            Element array=(Element)arrays.item(i);
            if(!name.equals(array.getAttribute("name"))) continue;
            NodeList items=array.getElementsByTagName("item"); String[] values=new String[items.getLength()];
            for(int j=0;j<values.length;j++) values[j]=items.item(j).getTextContent();
            return values;
        }
        throw new AssertionError("Missing array " + name);
    }
    private static Element view(Element root,String id) {
        NodeList elements=root.getElementsByTagName("*");
        for(int i=0;i<elements.getLength();i++) {
            Element element=(Element)elements.item(i);
            if(("@+id/"+id).equals(element.getAttributeNS(ANDROID,"id"))) return element;
        }
        throw new AssertionError("Missing view " + id);
    }
    private static URLClassLoader compile(Path root, Path build) throws Exception {
        Map<String,String> members=new HashMap<String,String>();
        try(StandardJavaFileManager fm=ToolProvider.getSystemJavaCompiler().getStandardFileManager(null,null,null)) {
            JavacTask task=(JavacTask)ToolProvider.getSystemJavaCompiler().getTask(null,fm,null,
                    Arrays.asList("-proc:none"),null,fm.getJavaFileObjects(
                    root.resolve("app/src/main/java/com/mkei/backcast/UserPreferencesActivity.java").toFile()));
            for(CompilationUnitTree unit:task.parse()) for(Tree top:unit.getTypeDecls()) {
                if(!(top instanceof ClassTree)) continue;
                for(Tree member:((ClassTree)top).getMembers()) {
                    if(member instanceof MethodTree) members.put(((MethodTree)member).getName().toString(),member.toString());
                    if(member instanceof ClassTree) members.put(((ClassTree)member).getSimpleName().toString(),member.toString());
                    if(member instanceof VariableTree && ((VariableTree)member).getName().toString().endsWith("_VALUES")) {
                        members.put(((VariableTree)member).getName().toString(),member.toString()+";");
                    }
                }
                new TreeScanner<Void,Void>() {
                    @Override public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        String select=node.getMethodSelect().toString();
                        if(select.equals("save.setOnClickListener")) members.put("saveAction",node.getArguments().get(0).toString());
                        if(select.equals("toolbar.setNavigationOnClickListener")) members.put("backAction",node.getArguments().get(0).toString());
                        return super.visitMethodInvocation(node,unused);
                    }
                }.scan(top,null);
            }
        }
        String fixture="import java.util.*; public class UserPreferencesActivity {"
                + "static class View { static final int VISIBLE=0,INVISIBLE=4; Object tag; int background,visibility; boolean selected,enabled=true;"
                + "interface OnClickListener{void onClick(View v);}void setEnabled(boolean e){enabled=e;}"
                + "Map<Integer,View> views=new HashMap<Integer,View>(); View findViewById(int id){return views.get(id);}"
                + "Object getTag(){return tag;} void setTag(Object t){tag=t;} void setSelected(boolean s){selected=s;}"
                + "void setBackgroundResource(int b){background=b;} void setVisibility(int v){visibility=v;} }"
                + "static class ViewGroup extends View {}"
                + "static class TextView extends View { String text; boolean singleLine=true; int color; float size;"
                + "void setText(String t){text=t;} void setTextColor(int c){color=c;} void setTextSize(float s){size=s;}"
                + "void setSingleLine(boolean s){singleLine=s;} }"
                + "static class EditText extends TextView {String getText(){return text;}}"
                + "static class CheckBox extends View {boolean checked;boolean isChecked(){return checked;}}"
                + "static class ImageView extends View { void setImageDrawable(Object d){} }"
                + "static class AdapterView<T> extends ViewGroup { interface OnItemSelectedListener {"
                + "void onItemSelected(AdapterView<?> p, View v,int position,long id);void onNothingSelected(AdapterView<?> p); } }"
                + "static class Spinner extends AdapterView<Object> { int position=-1; ArrayAdapter<String> adapter; OnItemSelectedListener listener;"
                + "void setAdapter(ArrayAdapter<String> a){adapter=a;} void setSelection(int p){position=p;if(listener!=null)listener.onItemSelected(this,null,p,p);}"
                + "int getSelectedItemPosition(){return position;} void setOnItemSelectedListener(OnItemSelectedListener l){listener=l;} }"
                + "static class ArrayAdapter<T> { T[] values; int notifications; ArrayAdapter(UserPreferencesActivity a,int layout,T[] v){values=v;}"
                + "T getItem(int p){return values[p];} void notifyDataSetChanged(){notifications++;}"
                + "View getView(int p,View recycled,ViewGroup parent){TextView t=recycled==null?new TextView():(TextView)recycled;t.setText((String)getItem(p));return t;}"
                + "View getDropDownView(int p,View v,ViewGroup parent){return getView(p,v,parent);} }"
                + "static class Resources { Map<Integer,String[]> arrays=new HashMap<Integer,String[]>();"
                + "String[] getStringArray(int id){return arrays.get(id);} int getColor(int id){return id;} Metrics getDisplayMetrics(){return new Metrics();} }"
                + "static class Metrics { float density=1; }"
                + "static class Inflater { View inflate(int id,ViewGroup parent,boolean attach){View v=new View();"
                + "v.views.put(R.id.choice_title,new TextView());v.views.put(R.id.choice_description,new TextView());"
                + "v.views.put(R.id.choice_check,new ImageView());return v;} }"
                + "static class Icons { static Object tinted(UserPreferencesActivity a,int id,int color,int size){return new Object();} }"
                + "static class android { static class R { static class layout { static final int simple_spinner_item=1; } } }"
                + "static class R { static class layout { static final int settings_choice_item=2; }"
                + "static class string {static final int toast_saved=20,agent_status_ultra=21,agent_status_normal=22;}"
                + "static class color { static final int text_primary=3; }"
                + "static class drawable { static final int bg_settings_choice_selected=4,ic_ds_checkmark_lg_regular_24=5; }"
                + "static class id { static final int choice_title=6,choice_description=7,choice_check=8; } }"
                + "static class Toast{static final int LENGTH_SHORT=0;static Toast makeText(UserPreferencesActivity a,int r,int d){return new Toast();}void show(){}}"
                + "static class Settings{static final String AGENT_ULTRA=\"ultra\",AGENT_MANUAL=\"manual\",EFFORT_ULTRA=\"ultra\";"
                + "Map<String,Object> preferences=new HashMap<String,Object>();int writes;Settings(){}Settings(UserPreferencesActivity a){preferences=a.settings.preferences;}"
                + "void saveUserPreferences(String v,String s,String l,String e,int c,String p){preferences.put(\"verbosity\",v);"
                + "preferences.put(\"summary\",s);preferences.put(\"language\",l);preferences.put(\"effort\",e);"
                + "preferences.put(\"concurrency\",c);preferences.put(\"prompt\",p);writes++;}boolean useRoot(){return false;}"
                + "String environmentContext(boolean r,String m,int c){return r+\"/\"+m+\"/\"+c;}}"
                + "Resources resources=new Resources(); TextView description=new TextView(),envContext=new TextView(),agentStatus=new TextView();"
                + "Settings settings=new Settings();int finishes,lastStatus;Object[] lastStatusArgs;void finish(){finishes++;}"
                + "String getString(int r,Object...args){lastStatus=r;lastStatusArgs=args;return r+Arrays.toString(args);}"
                + "EditText baseUrl=new EditText(),apiKey=new EditText(),model=new EditText(),systemPrompt=new EditText();CheckBox useRoot=new CheckBox();"
                + "Spinner outputVerbosity=new Spinner(),reasoningSummary=new Spinner(),outputLanguage=new Spinner(),agentConcurrency=new Spinner(),reasoningEffort=new Spinner();"
                + "Resources getResources(){return resources;} View findViewById(int id){return description;} Inflater getLayoutInflater(){return new Inflater();}"
                + members.get("OUTPUT_VERBOSITY_VALUES")+members.get("REASONING_SUMMARY_VALUES")+members.get("OUTPUT_LANGUAGE_VALUES")
                + members.get("AGENT_CONCURRENCY_VALUES")+members.get("REASONING_EFFORT_VALUES")
                + members.get("saveSettings")+members.get("refreshAgentPreview")+members.get("onChoiceChanged")
                + "View.OnClickListener saveAction="+members.get("saveAction")+";View.OnClickListener backAction="+members.get("backAction")+";"
                + members.get("bindChoices") + members.get("ChoiceAdapter") + members.get("ChoiceRow")
                + members.get("selectedValue") + "}";
        try(StandardJavaFileManager fm=ToolProvider.getSystemJavaCompiler().getStandardFileManager(null,null,null)) {
            check(ToolProvider.getSystemJavaCompiler().getTask(null,fm,null,
                    Arrays.asList("-proc:none","-encoding","UTF-8","-source","7","-target","7","-Xlint:-options","-d",build.toString()),
                    null,Arrays.asList(new Source(fixture))).call(),"production choice methods compile as Java 7");
        }
        pass("production choice methods compile as Java 7");
        return new URLClassLoader(new URL[]{build.toUri().toURL()},null);
    }
    private static void run(Path root, URLClassLoader loader) throws Exception {
        Element strings=xml(root.resolve("app/src/main/res/values/strings.xml"));
        Element layout=xml(root.resolve("app/src/main/res/layout/activity_user_preferences.xml"));
        Element row=xml(root.resolve("app/src/main/res/layout/settings_choice_item.xml"));
        Class<?> activityType=loader.loadClass("UserPreferencesActivity"), spinnerType=loader.loadClass("UserPreferencesActivity$Spinner"),
                viewType=loader.loadClass("UserPreferencesActivity$View"),groupType=loader.loadClass("UserPreferencesActivity$ViewGroup");
        java.lang.reflect.Constructor<?> spinnerConstructor=spinnerType.getDeclaredConstructor();spinnerConstructor.setAccessible(true);
        String[] keys={"output_verbosity","reasoning_summary","output_language","agent_concurrency","reasoning_effort"};
        String[][] values={{"default","low","medium","high"},{"auto","concise","detailed","none"},
                {"zh-CN","zh-TW","en","ja","ko","es","fr","de"},
                {"1","2","3","4"},{"off","low","medium","high","xhigh","max","ultra"}};
        for(int k=0;k<keys.length;k++) {
            String[] labels=array(strings,keys[k]+"_labels"),descriptions=array(strings,keys[k]+"_descriptions");
            check(labels.length==values[k].length && descriptions.length==labels.length,"aligned arrays "+keys[k]);
            for(String description:descriptions) check(!description.trim().isEmpty(),"nonempty description");
            pass(keys[k]+" has a description for every persisted value");
            Element selectedDescription=view(layout,keys[k]+"_description");
            check("wrap_content".equals(selectedDescription.getAttributeNS(ANDROID,"layout_height")),"selected explanation wraps");
            check(!"true".equals(selectedDescription.getAttributeNS(ANDROID,"singleLine")),"selected explanation multiline");
            pass(keys[k]+" selected explanation permits wrapping");
            Object activity=activityType.getConstructor().newInstance(),spinner=spinnerConstructor.newInstance();
            Object resources=field(activity,"resources");
            @SuppressWarnings("unchecked") Map<Integer,String[]> arrays=(Map<Integer,String[]>)field(resources,"arrays");
            arrays.put(1,labels);arrays.put(2,descriptions);
            for(int i=0;i<labels.length;i++) {
                call(activity,"bindChoices",new Class[]{spinnerType,int.class,int.class,int.class,String[].class,String.class},
                        spinner,1,2,3,values[k],values[k][i]);
                check((Integer)field(spinner,"position")==i,"restore selected value");
                check(descriptions[i].equals(field(field(activity,"description"),"text")),"restore selected description");
                check(values[k][i].equals(call(activity,"selectedValue",new Class[]{spinnerType,String[].class},spinner,values[k])),"stable persistence value");
            }
            pass(keys[k]+" restores every saved value and description");
            Object adapter=field(spinner,"adapter");
            call(spinner,"setSelection",new Class[]{int.class},0);
            check(descriptions[0].equals(field(field(activity,"description"),"text")),"change updates description");
            Object dropdown=call(adapter,"getDropDownView",new Class[]{int.class,viewType,groupType},0,null,null);
            check((Boolean)field(dropdown,"selected"),"selected dropdown");
            Object fields=field(dropdown,"tag");
            check((Integer)field(field(fields,"check"),"visibility")==0,"selected check visible");
            check(descriptions[0].equals(field(field(fields,"description"),"text")),"dropdown description");
            Object recycled=call(adapter,"getDropDownView",new Class[]{int.class,viewType,groupType},1,dropdown,null);
            check(recycled==dropdown,"dropdown reused");
            check(!(Boolean)field(recycled,"selected") && (Integer)field(recycled,"background")==0,"selection highlight reset");
            check((Integer)field(field(fields,"check"),"visibility")==4,"recycled check hidden");
            check(labels[1].equals(field(field(fields,"title"),"text")) && descriptions[1].equals(field(field(fields,"description"),"text")),"recycled text replaced");
            pass(keys[k]+" selection updates description and recycled rows reset");
            Object collapsed=call(adapter,"getView",new Class[]{int.class,viewType,groupType},0,null,null);
            check(!(Boolean)field(collapsed,"singleLine"),"collapsed title multiline");
            pass(keys[k]+" collapsed title permits wrapping");
            call(activity,"bindChoices",new Class[]{spinnerType,int.class,int.class,int.class,String[].class,String.class},
                    spinner,1,2,3,values[k],"invalid-value");
            check((Integer)field(spinner,"position")==0 && descriptions[0].equals(field(field(activity,"description"),"text")),"unknown selection fallback");
            pass(keys[k]+" invalid saved value falls back with matching description");
        }
        Element explanation=view(row,"choice_description");
        check("wrap_content".equals(explanation.getAttributeNS(ANDROID,"layout_height"))
                && !explanation.hasAttributeNS(ANDROID,"maxLines") && !explanation.hasAttributeNS(ANDROID,"ellipsize"),"long explanation is not truncated");
        pass("dropdown explanations have no fixed height or truncation");
        saveAndPreviewBehaviors(activityType);
    }

    private static void saveAndPreviewBehaviors(Class<?> type) throws Exception {
        Object activity=type.getConstructor().newInstance(), settings=field(activity,"settings");
        field(field(activity,"systemPrompt"),"text","systemPrompt");
        String[] spinners={"outputVerbosity","reasoningSummary","outputLanguage","agentConcurrency","reasoningEffort"};
        int[] selected={2,3,2,3,6};
        for(int i=0;i<spinners.length;i++) field(field(activity,spinners[i]),"position",selected[i]);
        call(activity,"refreshAgentPreview",new Class[]{settings.getClass()},settings);
        @SuppressWarnings("unchecked") Map<String,Object> values=(Map<String,Object>)field(settings,"preferences");
        check(values.isEmpty() && (Integer)field(settings,"writes")==0,"unsaved preview wrote settings");
        check((Boolean)field(field(activity,"reasoningEffort"),"enabled")
                && "ultra".equals(((Object[])field(activity,"lastStatusArgs"))[1]),"ultra was mapped to another effort or locked the selector");
        check((Integer)field(field(activity,"reasoningEffort"),"position")==6,"ultra was not an independent effort choice");
        check("false/ultra/4".equals(field(field(activity,"envContext"),"text")),"unsaved mode/count preview was stale");
        pass("ultra preview keeps the selected ultra value without persisting the draft");
        Object back=field(activity,"backAction");
        call(back,"onClick",new Class[]{Class.forName("UserPreferencesActivity$View",true,type.getClassLoader())},(Object)null);
        check(values.isEmpty() && (Integer)field(activity,"finishes")==1,"returning from settings persisted drafts");
        pass("returning from settings discards the unsaved agent draft");
        Object save=field(activity,"saveAction");
        call(save,"onClick",new Class[]{Class.forName("UserPreferencesActivity$View",true,type.getClassLoader())},(Object)null);
        check(!values.containsKey("mode") && Integer.valueOf(4).equals(values.get("concurrency"))
                && "ultra".equals(values.get("effort")) && "en".equals(values.get("language"))
                && "none".equals(values.get("summary")) && "medium".equals(values.get("verbosity")),"save ignored one of the choices");
        check((Integer)field(activity,"finishes")==2,"save did not retain the existing return behavior");
        pass("explicit save writes all agent and response choices before returning");
        field(field(activity,"reasoningEffort"),"position",5);
        call(activity,"refreshAgentPreview",new Class[]{settings.getClass()},settings);
        check((Boolean)field(field(activity,"reasoningEffort"),"enabled")
                && (Boolean)field(field(activity,"agentConcurrency"),"enabled")
                && "max".equals(((Object[])field(activity,"lastStatusArgs"))[1])
                && "false/manual/4".equals(field(field(activity,"envContext"),"text")),"max preview still used ultra delegation");
        field(field(activity,"reasoningEffort"),"position",0);
        call(activity,"refreshAgentPreview",new Class[]{settings.getClass()},settings);
        check((Boolean)field(field(activity,"reasoningEffort"),"enabled")
                && (Boolean)field(field(activity,"agentConcurrency"),"enabled")
                && "off".equals(((Object[])field(activity,"lastStatusArgs"))[1]),"manual did not restore the user's configured effort");
        pass("max and off remain independently selectable and require explicit delegation");
    }
    public static void main(String[] args) throws Exception {
        Path root=Paths.get(args[0]),build=Files.createTempDirectory("backcast-settings-choice-");
        try(URLClassLoader loader=compile(root,build)) { run(root,loader); }
        finally { try(var paths=Files.walk(build)) { for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); } }
        System.out.println("SettingsChoiceRegressionTest: "+passed+" passed");
    }
}
