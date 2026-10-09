import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Executes the production progress UI methods, including coalescing and operation/lifecycle guards. */
public final class ToolInstallProgressUiRegressionTest {
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    private static int passed;
    private static void pass(String text) { passed++; System.out.println("PASS " + text); }
    private static final class Source extends SimpleJavaFileObject {
        final String text;
        Source(String text) { super(URI.create("string:///ProgressUiFixture.java"), Kind.SOURCE); this.text = text; }
        @Override public CharSequence getCharContent(boolean ignore) { return text; }
    }
    private static Map<String,List<String>> members(Path file, String typeName) throws Exception {
        Map<String,List<String>> result = new LinkedHashMap<>();
        try (StandardJavaFileManager manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, manager, null,
                    List.of("-proc:none", "-encoding", "UTF-8"), null, manager.getJavaFileObjects(file.toFile()));
            for (CompilationUnitTree unit : task.parse()) for (Tree declaration : unit.getTypeDecls()) {
                if (!(declaration instanceof ClassTree) || !((ClassTree) declaration).getSimpleName().contentEquals(typeName)) continue;
                for (Tree member : ((ClassTree) declaration).getMembers()) {
                    String name = member instanceof MethodTree ? ((MethodTree) member).getName().toString()
                            : member instanceof ClassTree ? ((ClassTree) member).getSimpleName().toString() : "";
                    if (!name.isEmpty()) result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(member.toString());
                }
            }
        }
        return result;
    }
    private static URLClassLoader fixture(Path root, Path output) throws Exception {
        Map<String,List<String>> ui = members(root.resolve("app/src/main/java/com/mkei/backcast/ToolConfigActivity.java"), "ToolConfigActivity");
        Map<String,List<String>> backend = members(root.resolve("app/src/main/java/com/mkei/backcast/tool/EmbeddedToolchain.java"), "EmbeddedToolchain");
        StringBuilder source = new StringBuilder("import java.util.*;import java.util.concurrent.*;"
                + "class Activity{protected void onResume(){}protected void onStop(){}protected void onDestroy(){}}"
                + "public class ProgressUiFixture extends Activity{"
                + "static class View{static final int VISIBLE=0,GONE=8;int visibility=GONE;void setVisibility(int v){visibility=v;}}"
                + "static class ProgressBar extends View{int progress,max;boolean indeterminate;int mutations;"
                + "void setMax(int v){max=v;}void setProgress(int v){progress=v;mutations++;}int getProgress(){return progress;}"
                + "void setIndeterminate(boolean v){indeterminate=v;mutations++;}}"
                + "static class TextView extends View{String text=\"\";void setText(String v){text=v;}void setText(int v){text=getLabel(v);}}"
                + "static class Handler{List<Runnable> tasks=new ArrayList<Runnable>();long delay;"
                + "synchronized void postDelayed(Runnable r,long d){tasks.add(r);delay=d;}synchronized int size(){return tasks.size();}"
                + "void drain(){List<Runnable> current;synchronized(this){current=new ArrayList<Runnable>(tasks);tasks.clear();}for(Runnable r:current)r.run();}}"
                + "static class JSONObject{Map<String,Object> values=new HashMap<String,Object>();JSONObject put(String n,Object v){values.put(n,v);return this;}"
                + "String optString(String n){Object v=values.get(n);return v==null?\"\":String.valueOf(v);}boolean optBoolean(String n){return Boolean.TRUE.equals(values.get(n));}long optLong(String n){Object v=values.get(n);return v instanceof Number?((Number)v).longValue():0;}}"
                + "static class android{static class text{static class format{static class Formatter{static String formatFileSize(Object context,long bytes){return bytes+\" B\";}}}}}"
                + "static class R{static class string{static final int toolkit_loading=1,toolkit_cancelled=2,toolkit_failed=3,"
                + "toolkit_progress_preparing=4,toolkit_progress_value=5,toolkit_progress_checking=6,toolkit_progress_verifying=7,"
                + "toolkit_progress_unpacking=8,toolkit_progress_publishing=9,toolkit_progress_registering=10,toolkit_progress_complete=11,"
                + "toolkit_progress_refresh_failed=12,toolkit_progress_common=13,toolkit_progress_device=14,toolkit_progress_phase_artifact=15,"
                + "toolkit_progress_probing=16,toolkit_progress_downloading=17,toolkit_progress_resuming=18,toolkit_progress_switching=19,toolkit_progress_bytes=20,"
                + "toolkit_progress_remove_preparing=21,toolkit_progress_removing=22,toolkit_progress_removed=23,toolkit_progress_files=24,toolkit_bundle_bytes=25,toolkit_busy=26,"
                + "toolkit_not_installed=27,toolkit_installed=28,toolkit_ready=29,toolkit_unsupported=30,toolkit_configured=31,toolkit_needs_runtime=32,toolkit_unconfigured=33,toolkit_unavailable=34,toolkit_bundle_size_unavailable=35;}}"
                + "static String getLabel(int id){String[] labels={\"\",\"loading\",\"cancelled\",\"failed\",\"preparing\",\"%1$d%% · %2$s\","
                + "\"checking\",\"verifying\",\"unpacking\",\"publishing\",\"registering\",\"complete\",\"refresh error\",\"common\",\"device\",\"%1$s · %2$s\","
                + "\"probing\",\"downloading\",\"resuming\",\"switching\",\"%1$s / %2$s\",\"counting files\",\"removing\",\"removed\",\"%1$d / %2$d items\",\"installed size: %1$s\",\"busy\","
                + "\"not installed\",\"installed\",\"ready\",\"unsupported\",\"configured\",\"needs runtime\",\"unconfigured\",\"unavailable\",\"size unavailable\"};return labels[id];}"
                + "String getString(int id,Object...args){return String.format(java.util.Locale.US,getLabel(id),args);}"
                + "static class ToolkitOperationManager{static class Snapshot{String action;}}ToolkitOperationManager.Snapshot active;"
                + "View installProgressContainer=new View();ProgressBar installProgress=new ProgressBar();TextView installProgressText=new TextView(),operationStatus=new TextView();"
                + "static class EmbeddedToolchain{" + backend.get("Progress").get(0) + "}"
                + "public void start(){active=new ToolkitOperationManager.Snapshot();active.action=\"package_install\";beginInstallProgress(false);}"
                + "public void remove(){active=new ToolkitOperationManager.Snapshot();active.action=\"package_remove\";beginInstallProgress(true);}"
                + "public void event(String stage,String artifact,long completed,long total){applyInstallProgress(new EmbeddedToolchain.Progress(stage,artifact,completed,total));}"
                + "public int percent(){return installProgress.progress;}public boolean unknown(){return installProgress.indeterminate;}public String label(){return installProgressText.text;}"
                + "public String status(){return operationStatus.text;}public boolean statusVisible(){return operationStatus.visibility==View.VISIBLE;}public boolean visible(){return installProgressContainer.visibility==View.VISIBLE;}"
                + "public void respond(String state,boolean installed,String error,long size){finishInstallProgress(new JSONObject().put(\"state\",state).put(\"installed\",installed).put(\"error\",error).put(\"installed_bytes\",size),active.action.equals(\"package_remove\"));}"
                + "public String summary(String state,boolean installed,long size){return packageSummary(new JSONObject().put(\"state\",state).put(\"installed\",installed).put(\"installed_bytes\",size),\"private storage\");}");
        for (String name : List.of("beginInstallProgress", "applyInstallProgress", "installProgressPhase", "finishInstallProgress", "packageSummary", "toolkitState")) {
            check(ui.containsKey(name), "Missing real progress UI member " + name);
            for (String member : ui.get(name)) source.append(member);
        }
        source.append('}');
        try (StandardJavaFileManager manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            check(ToolProvider.getSystemJavaCompiler().getTask(null, manager, null, List.of("-proc:none", "-source", "7", "-target", "7",
                    "-Xlint:-options", "-encoding", "UTF-8", "-d", output.toString()), null, List.of(new Source(source.toString()))).call(), "Production progress UI fixture failed compilation");
        }
        return new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, null);
    }
    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) if (method.getName().equals(name) && method.getParameterCount() == args.length)
            return method.invoke(target, args);
        throw new NoSuchMethodException(name);
    }
    private static Object start(Class<?> type) throws Exception { Object target = type.getConstructor().newInstance(); call(target,"start"); return target; }
    private static void layout(Path root) throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        Element layout=factory.newDocumentBuilder().parse(root.resolve("app/src/main/res/layout/activity_tool_config.xml").toFile()).getDocumentElement();
        String ns="http://schemas.android.com/apk/res/android";boolean horizontal=false,label=false;
        for(int i=0;i<layout.getElementsByTagName("ProgressBar").getLength();i++){
            Element bar=(Element)layout.getElementsByTagName("ProgressBar").item(i);
            horizontal |= bar.getAttributeNS(ns,"id").equals("@+id/tool_install_progress")
                    && bar.getAttribute("style").equals("?android:attr/progressBarStyleHorizontal") && bar.getAttributeNS(ns,"max").equals("100");
        }
        for(int i=0;i<layout.getElementsByTagName("TextView").getLength();i++)
            label |= ((Element)layout.getElementsByTagName("TextView").item(i)).getAttributeNS(ns,"id").equals("@+id/tool_install_progress_text");
        check(horizontal&&label,"Tool configuration has no real horizontal progress bar and readable percentage/stage label");
        pass("tool package layout exposes an overall horizontal percentage/stage control");
    }
    public static void main(String[] args) throws Exception {
        Path root=Paths.get(args[0]),temporary=Files.createTempDirectory("backcast-tool-progress-ui-");
        try(URLClassLoader loader=fixture(root,temporary)){
            Class<?> type=loader.loadClass("ProgressUiFixture");layout(root);
            Object install=start(type);
            check((boolean)call(install,"unknown")&&(boolean)call(install,"visible")&&(int)call(install,"percent")==0,"Unknown package size fabricated progress");
            for(String stage:List.of("probing","downloading","resuming","switching","unpacking")) {
                call(install,"event",stage,"any",20L,100L);
                check(call(install,"label").toString().contains(stage+" · common")&&call(install,"label").toString().contains("20 B / 100 B"),"Network stage/bytes omitted");
            }
            call(install,"event","complete","",100L,100L);check((int)call(install,"percent")==99,"Backend progress claimed terminal before manager outcome");
            call(install,"respond","installed",true,"",1234L);
            check((int)call(install,"percent")==100&&call(install,"label").equals("100% · complete")&&!(boolean)call(install,"statusVisible"),"Accepted installation retained failure/progress");
            pass("installation stages and bytes render; accepted terminal outcome reaches 100%");
            for(String outcome:List.of("error","cancelled","installed")) {
                Object failed=start(type);call(failed,"event","complete","",100L,100L);call(failed,"respond",outcome,false,"",0L);
                check((int)call(failed,"percent")<100&&!(boolean)call(failed,"unknown"),"Unconfirmed installation claimed completion");
            }
            Object remove=type.getConstructor().newInstance();call(remove,"remove");
            check(call(remove,"label").equals("counting files"),"Delete preparation missing");
            call(remove,"event","removing","",400L,1000L);
            check(call(remove,"label").equals("40% · removing\n400 / 1000 items"),"Deletion did not show real item count");
            call(remove,"respond","not_installed",false,"",0L);
            check((int)call(remove,"percent")==100&&call(remove,"label").equals("100% · removed"),"Confirmed deletion did not settle");
            Object incomplete=type.getConstructor().newInstance();call(incomplete,"remove");call(incomplete,"respond","not_installed",false,"",1L);
            check((int)call(incomplete,"percent")<100,"Residual bytes accepted as deleted");
            pass("deletion counts require accepted zero-residue terminal result");
            for(String state:List.of("removed","not_installed")) check(call(remove,"summary",state,false,270000000L).equals("not installed\nprivate storage"),"Uninstalled package showed stale size/deleted label");
            check(call(install,"summary","installed",true,1234L).toString().contains("1234 B"),"Installed package lost measured byte count");
            pass("inventory suppresses stale size and removed-state label");
        } finally {try(var walk=Files.walk(temporary)){for(Path file:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
