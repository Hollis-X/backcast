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
                + "boolean activityDestroyed,finishing,progressTerminal,batchTerminal,busy;ToolkitOperation active;"
                + "String probeContext=\"\";Map<String,JSONObject> probeResults=new HashMap<String,JSONObject>();void finishBatchProbe(JSONObject result){}"
                + "View installProgressContainer=new View(),batchProgressContainer=new View();ProgressBar installProgress=new ProgressBar();"
                + "TextView installProgressText=new TextView(),operationStatus=new TextView();Handler main=new Handler();"
                + "ExecutorService toolkitReader=Executors.newSingleThreadExecutor(),toolkitCancellation=Executors.newSingleThreadExecutor();"
                + "List<ToolkitOperation> toolkitOperations=new ArrayList<ToolkitOperation>();ToolkitResult response;String action;int renders;"
                + "boolean isFinishing(){return finishing;}void setBusy(boolean v){busy=v;}void renderTools(JSONObject r){renders++;}"
                + "List<ToolkitOperation> pendingOperations(){return new ArrayList<ToolkitOperation>(toolkitOperations);}"
                + "void cancelToolkitOperation(ToolkitOperation operation){operation.cancelled=true;toolkitOperations.remove(operation);}"
                + "static class RunHub{static class ToolkitSession{}}"
                + "static class EmbeddedToolchain{" + backend.get("Progress").get(0) + "}"
                + "JSONObject toolkitArguments(String a,String id){return new JSONObject().put(\"action\",a);}String toolId=\"\";"
                + "ToolkitOperation requestToolkit(JSONObject args,ToolkitResult callback){ToolkitOperation operation=new ToolkitOperation();"
                + "operation.installing=\"package_install\".equals(args.optString(\"action\"));operation.removing=\"package_remove\".equals(args.optString(\"action\"));toolkitOperations.add(operation);"
                + "response=callback;action=args.optString(\"action\");return operation;}"
                + "public void start(){manage(\"package_install\",R.string.toolkit_loading);}"
                + "public void remove(){manage(\"package_remove\",R.string.toolkit_loading);}"
                + "public Object operation(){return active;}public void replaceOperation(){active=new ToolkitOperation();active.installing=true;}"
                + "public void event(Object operation,String stage,String artifact,long completed,long total){"
                + "queueInstallProgress((ToolkitOperation)operation,new EmbeddedToolchain.Progress(stage,artifact,completed,total));}"
                + "public void drain(){main.drain();}public int queued(){return main.size();}public long delay(){return main.delay;}"
                + "public int percent(){return installProgress.progress;}public boolean unknown(){return installProgress.indeterminate;}"
                + "public String label(){return installProgressText.text;}public String status(){return operationStatus.text;}"
                + "public boolean statusVisible(){return operationStatus.visibility==View.VISIBLE;}"
                + "public boolean visible(){return installProgressContainer.visibility==View.VISIBLE;}public boolean busy(){return busy;}"
                + "public boolean terminal(){return progressTerminal;}public int mutations(){return installProgress.mutations;}"
                + "public String action(){return action;}public int renders(){return renders;}"
                + "public void respond(String state,boolean installed,String error){response.apply(new JSONObject().put(\"state\",state)"
                + ".put(\"installed\",installed).put(\"error\",error));}"
                + "public void respondSized(String state,boolean installed,String error,long size){response.apply(new JSONObject().put(\"state\",state)"
                + ".put(\"installed\",installed).put(\"error\",error).put(\"installed_bytes\",size));}"
                + "public String summary(String state,boolean installed,long size){return packageSummary(new JSONObject().put(\"state\",state).put(\"installed\",installed).put(\"installed_bytes\",size),\"private storage\");}"
                + "public void finishing(){finishing=true;}public void cancel(){cancelActiveToolkit();}"
                + "public void resume(){onResume();}public void stop(){onStop();}public void destroy(){onDestroy();}");
        for (String name : List.of("ToolkitOperation", "ToolkitResult", "begin", "beginInstallProgress", "queueInstallProgress", "applyInstallProgress",
                "installProgressPhase", "finishInstallProgress", "finishOperation", "cancelActiveToolkit", "manage", "loadTools", "packageSummary", "toolkitState", "onResume", "onStop", "onDestroy")) {
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
    private static void event(Object target, Object operation, String stage, String artifact, long completed, long total) throws Exception {
        call(target,"event",operation,stage,artifact,completed,total);
    }
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
            Object unknown=start(type),operation=call(unknown,"operation");
            check((boolean)call(unknown,"unknown")&&(boolean)call(unknown,"visible")&&(int)call(unknown,"percent")==0,"Preparation must be visible and indeterminate, not fabricated progress");
            event(unknown,operation,"checking","",0,0);call(unknown,"drain");check((boolean)call(unknown,"unknown"),"Unknown total became determinate");
            pass("unknown package sizes display preparation without a fabricated percentage");

            Object coalesced=start(type),coalescedOperation=call(coalesced,"operation");int mutations=(int)call(coalesced,"mutations");
            Thread worker=new Thread(()->{try{for(int i=0;i<10000;i++)event(coalesced,coalescedOperation,"unpacking","any",i,20000);}catch(Exception error){throw new RuntimeException(error);}});
            worker.start();worker.join();
            check((int)call(coalesced,"queued")==1&&(long)call(coalesced,"delay")==100&&(int)call(coalesced,"mutations")==mutations,"Worker flooded UI callbacks or mutated widgets before main delivery");
            call(coalesced,"drain");check((int)call(coalesced,"percent")==49&&call(coalesced,"label").equals("49% · unpacking · common\n9999 B / 20000 B"),"Coalescing did not render the newest aggregate snapshot");
            event(coalesced,coalescedOperation,"unpacking","arm64-v8a",10,20000);call(coalesced,"drain");
            check((int)call(coalesced,"percent")==49&&call(coalesced,"label").toString().contains("device"),"Displayed overall progress regressed or lost device stage");
            pass("worker callbacks coalesce to one main-thread update and retain newest overall progress");

            for(String phase:List.of("probing","downloading","resuming","switching")){
                Object network=start(type),networkOperation=call(network,"operation");
                event(network,networkOperation,phase,"any",20,100);call(network,"drain");
                check(call(network,"label").toString().contains(phase+" · common")&&call(network,"label").toString().contains("20 B / 100 B"),"Network installation stage or bytes omitted: "+phase);
                call(network,"cancel");
            }
            pass("network probing, downloading, resuming and failover expose readable stage and byte progress");

            Object successful=start(type),successfulOperation=call(successful,"operation");
            event(successful,successfulOperation,"complete","",100,100);call(successful,"drain");
            check((int)call(successful,"percent")==99,"Backend completion bypassed UI-session cleanup and success result");
            call(successful,"respond","installed",true,"");
            check((int)call(successful,"percent")==100&&(boolean)call(successful,"terminal")&&call(successful,"action").equals("list"),"Accepted installation did not finish at 100 and refresh inventory");
            check(call(successful,"status").equals("complete")&&call(successful,"label").equals("100% · complete")&&!(boolean)call(successful,"statusVisible"),"Successful operation retained duplicate or old failure status");
            event(successful,successfulOperation,"unpacking","any",60,100);call(successful,"drain");
            check((int)call(successful,"percent")==100,"A completed operation's delayed event overwrote the inventory refresh/terminal state");
            call(successful,"respond","",false,"");
            check((int)call(successful,"percent")==100&&call(successful,"status").equals("complete")&&(int)call(successful,"renders")==1&&!(boolean)call(successful,"busy"),"Inventory rendering discarded completion or left buttons busy");
            pass("100% requires accepted installed result and remains visible across inventory refresh");

            for(String failure:List.of("error","cancelled","installed")){
                Object failed=start(type),failedOperation=call(failed,"operation");event(failed,failedOperation,"complete","",100,100);call(failed,"drain");
                call(failed,"respond",failure,false,failure.equals("error")?"cleanup failed":"");
                check((int)call(failed,"percent")<100&&!(boolean)call(failed,"unknown")&&(boolean)call(failed,"visible"),"Failed/cancelled/unconfirmed install claimed 100%: "+failure);
                if(failure.equals("error"))check(call(failed,"status").equals("cleanup failed"),"Cleanup failure evidence was hidden");
            }
            pass("cancelled, cleanup-failed and unconfirmed installs never claim 100%");

            Object removal=type.getConstructor().newInstance();call(removal,"remove");Object removalOperation=call(removal,"operation");
            check((boolean)call(removal,"visible")&&(boolean)call(removal,"unknown")&&call(removal,"label").equals("counting files"),"Removal has no initial progress or wrong preparation label");
            event(removal,removalOperation,"removing","",400,1000);call(removal,"drain");
            check((int)call(removal,"percent")==40&&call(removal,"label").equals("40% · removing\n400 / 1000 items"),"Removal progress invented byte counts or failed to render actual deleted items");
            event(removal,removalOperation,"complete","",1000,1000);call(removal,"drain");
            check((int)call(removal,"percent")==99,"Deletion claimed completion before accepted cleanup result");
            call(removal,"respondSized","not_installed",false,"",0L);
            check((int)call(removal,"percent")==100&&call(removal,"label").equals("100% · removed")&&!(boolean)call(removal,"statusVisible")&&call(removal,"action").equals("list"),"Confirmed removal failed to refresh inventory or clear operation state");
            event(removal,removalOperation,"removing","",400,1000);call(removal,"drain");
            check((int)call(removal,"percent")==100,"Late removal progress replaced accepted completion");
            pass("deletion reports real item counts and requires accepted cleanup before 100%");

            for(String outcome:List.of("error","cancelled","incomplete")){
                Object failed=type.getConstructor().newInstance();call(failed,"remove");Object failedOperation=call(failed,"operation");
                event(failed,failedOperation,"removing","",200,1000);call(failed,"drain");
                if(outcome.equals("cancelled"))call(failed,"cancel");
                else call(failed,"respondSized",outcome.equals("incomplete")?"not_installed":outcome,false,outcome.equals("error")?"failed":"",outcome.equals("incomplete")?1L:0L);
                check((int)call(failed,"percent")<100&&call(failed,"action").equals("list"),"Unconfirmed deletion succeeded or failed to reread residue: "+outcome);
                event(failed,failedOperation,"complete","",1000,1000);call(failed,"drain");
                check((int)call(failed,"percent")<100,"Cancelled/deleted operation accepted a late 100% callback");
                call(failed,"start");
                check((int)call(failed,"percent")==0&&call(failed,"label").equals("preparing")&&call(failed,"status").equals("loading"),"Next install retained previous deletion failure or progress");
            }
            pass("failed or cancelled deletion refreshes residue and the next operation starts clean");

            Object inventory=type.getConstructor().newInstance();
            check(call(inventory,"summary","installed",true,12345L).toString().contains("installed size: 12345 B"),"Installed package did not display supplied measured bytes");
            for(String state:List.of("removed","not_installed")){
                String text=call(inventory,"summary",state,false,270000000L).toString();
                check(text.equals("not installed\nprivate storage")&&!text.contains("270"),"Uninstalled package displayed removed-state label or stale installed size");
            }
            pass("inventory uses measured installed bytes and never shows stale size for uninstalled packages");

            Object reopened=start(type);call(reopened,"respond","error",false,"old failed install");call(reopened,"respond","",false,"");
            call(reopened,"resume");
            check(!(boolean)call(reopened,"visible")&&call(reopened,"label").equals("")&&call(reopened,"status").equals("loading"),"Reopened tools page retained old operation failure and percentage");
            pass("reopening the configuration page clears the previous operation status and progress");

            for(String boundary:List.of("replaceOperation","finishing","cancel","stop","destroy")){
                Object stale=start(type),staleOperation=call(stale,"operation");event(stale,staleOperation,"unpacking","any",70,100);
                call(stale,boundary);int before=(int)call(stale,"percent");call(stale,"drain");
                check((int)call(stale,"percent")==before,"Late callback crossed operation/lifecycle boundary "+boundary);
                if(boundary.equals("cancel")||boundary.equals("stop"))check(call(stale,"status").equals("cancelled")&&(boolean)call(stale,"terminal"),"Cancelled installation lost its readable terminal state");
            }
            pass("late progress cannot update cancelled, replaced, stopped, destroyed or finishing pages");
            System.out.println("Tool install progress UI tests passed: "+passed);
        } finally {try(var walk=Files.walk(temporary)){for(Path file:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
