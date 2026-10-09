import com.mkei.backcast.tool.ToolBatchProbe;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.JSONObject;
import org.w3c.dom.Element;

/** Compiles and executes the actual batch-page methods, without duplicating their decisions. */
public final class ToolBatchProbeUiRegressionTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Source extends SimpleJavaFileObject {
        final String value; Source(String value) { super(URI.create("string:///BatchUiFixture.java"), Kind.SOURCE); this.value = value; }
        @Override public CharSequence getCharContent(boolean ignore) { return value; }
    }
    private static Map<String, String> members(Path path) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, manager, null, List.of("-proc:none"), null, manager.getJavaFileObjects(path));
            for (CompilationUnitTree unit : task.parse()) for (Tree declaration : unit.getTypeDecls()) if (declaration instanceof ClassTree && ((ClassTree) declaration).getSimpleName().contentEquals("ToolConfigActivity"))
                for (Tree member : ((ClassTree) declaration).getMembers()) {
                    String name = member instanceof MethodTree ? ((MethodTree) member).getName().toString() : member instanceof ClassTree ? ((ClassTree) member).getSimpleName().toString() : "";
                    if (!name.isEmpty()) result.put(name, member.toString());
                }
        }
        return result;
    }
    private static URLClassLoader fixture(Path root, Path output) throws Exception {
        Map<String,String> methods = members(root.resolve("app/src/main/java/com/mkei/backcast/ToolConfigActivity.java"));
        StringBuilder code = new StringBuilder("import java.util.*;import java.util.concurrent.*;import org.json.*;import com.mkei.backcast.tool.*;"
                + "class Activity{protected void onStop(){}protected void onDestroy(){}}public class BatchUiFixture extends Activity{"
                + "static class View{static final int VISIBLE=0,GONE=8;int visibility;void setVisibility(int v){visibility=v;}}"
                + "static class TextView extends View{String text=\"\";void setText(CharSequence s){text=s.toString();}void setText(int r){text=getLabel(r);}CharSequence getText(){return text;}}"
                + "static class ProgressBar extends View{int max,progress;void setMax(int v){max=v;}int getMax(){return max;}void setProgress(int v){progress=v;}int getProgress(){return progress;}void setIndeterminate(boolean v){}}"
                + "static class R{static class string{static final int toolkit_batch_preparing=1,toolkit_batch_pending=2,toolkit_batch_progress=3,toolkit_batch_current=4,"
                + "toolkit_batch_checked=5,toolkit_batch_summary=6,toolkit_cancelled=7,toolkit_failed=8,toolkit_batch_not_checked=9;}}"
                + "static String getLabel(int r){return new String[]{\"\",\"preparing\",\"waiting\",\"checked %1$d/%2$d\",\"running %1$s\",\"done %1$s\",\"checked %1$d/%2$d ready %3$d failed %4$d\",\"cancelled\",\"failed\",\"not completed\"}[r];}"
                + "String getString(int r,Object...args){return String.format(Locale.US,getLabel(r),args);}String toolkitState(String state){return state;}"
                + "boolean progressTerminal,activityDestroyed,finishing,busy;ToolkitOperation active;String action,probeContext=\"\",toolId=\"\";ToolkitResult callback;"
                + "View installProgressContainer=new View(),batchProgressContainer=new View();ProgressBar installProgress=new ProgressBar(),batchProgress=new ProgressBar();"
                + "TextView installProgressText=new TextView(),batchProgressText=new TextView(),operationStatus=new TextView(),detailOutput=new TextView();"
                + "static class Settings{boolean root;int writes;String access=\"full\";List<String> roots=new ArrayList<String>(Arrays.asList(\"/project\"));boolean useRoot(){return root;}void setUseRoot(boolean value){root=value;writes++;}String accessLevel(){return access;}List<String> authorizedWorkDirs(){return roots;}}"
                + "static class Bundle{Map<String,String> values=new HashMap<String,String>();String getString(String key,String fallback){String v=values.get(key);return v==null?fallback:v;}}"
                + "static class Intent{Map<String,String> extras=new HashMap<String,String>();String getStringExtra(String key){return extras.get(key);}}"
                + "Settings settings=new Settings();JSONObject bundle=new JSONObject();Intent intent=new Intent();Intent getIntent(){return intent;}"
                + "static final String EXTRA_PROBE_RESULT=\"probe_result\",EXTRA_PROBE_CONTEXT=\"probe_context\";"
                + "Map<String,JSONObject> probeResults=new LinkedHashMap<String,JSONObject>();Map<String,TextView> toolRows=new LinkedHashMap<String,TextView>();"
                + "List<ToolkitOperation> operations=new ArrayList<ToolkitOperation>();List<Runnable> callbacks=new ArrayList<Runnable>();"
                + "ExecutorService toolkitReader=Executors.newSingleThreadExecutor(),toolkitCancellation=Executors.newSingleThreadExecutor();"
                + "static class RunHub{int retargets;static RunHub get(BatchUiFixture a){return a.hub;}void retargetTools(){retargets++;}static class ToolkitSession{}}RunHub hub=new RunHub();int reloads;void loadTools(boolean preserve){reloads++;}"
                + "boolean isFinishing(){return finishing;}void setBusy(boolean v){busy=v;}void finishInstallProgress(JSONObject r,boolean removing){}"
                + "List<ToolkitOperation> pendingOperations(){return new ArrayList<ToolkitOperation>(operations);}"
                + "void cancelToolkitOperation(ToolkitOperation op){op.cancelled=true;operations.remove(op);}"
                + "void ui(Runnable callback){synchronized(callbacks){callbacks.add(callback);}}"
                + "ToolkitOperation requestToolkit(JSONObject args,ToolkitResult result){ToolkitOperation op=new ToolkitOperation();op.probingAll=true;action=args.optString(\"action\");callback=result;operations.add(op);return op;}"
                + "public void start()throws Exception{JSONArray all=ToolCatalog.list();for(int i=0;i<all.length();i++)toolRows.put(all.getJSONObject(i).getString(\"id\"),new TextView());probeAllTools();}"
                + "public Object operation(){return active;}public void event(Object op,ToolBatchProbe.Progress progress){queueBatchProgress((ToolkitOperation)op,progress);}"
                + "public void drain(){List<Runnable> queued;synchronized(callbacks){queued=new ArrayList<Runnable>(callbacks);callbacks.clear();}for(Runnable callback:queued)callback.run();}"
                + "public String row(String id){return toolRows.get(id).text;}public String label(){return batchProgressText.text;}public int completed(){return batchProgress.progress;}"
                + "public int cached(){return probeResults.size();}public boolean busy(){return busy;}public boolean terminal(){return !busy&&operationStatus.visibility==View.VISIBLE;}public String action(){return action;}"
                + "public void root(boolean value){updateRoot(value);}public boolean rootEnabled(){return settings.root;}public int rootWrites(){return settings.writes;}public int reloads(){return reloads;}public int retargets(){return hub.retargets;}public String access(){return settings.access;}public String cachedContext(){return probeContext;}public boolean cancelled(Object op){return ((ToolkitOperation)op).cancelled;}"
                + "public void response(JSONObject response){callback.apply(response);}public void cancel(){cancelActiveToolkit();}public void replace(){active=new ToolkitOperation();}"
                + "public void finishing(){finishing=true;}public void stop(){onStop();}public void destroy(){onDestroy();}");
        code.append("public void restoreSnapshots()throws Exception{toolId=\"readelf\";Bundle saved=new Bundle();saved.values.put(\"probe_results\",new JSONArray().put(new JSONObject().put(\"id\",toolId).put(\"state\",\"new\")).toString());"
                + "saved.values.put(\"probe_context\",currentProbeContext());intent.extras.put(EXTRA_PROBE_RESULT,new JSONObject().put(\"id\",toolId).put(\"state\",\"old\").toString());intent.extras.put(EXTRA_PROBE_CONTEXT,\"old-context\");restoreProbeResults(saved);}"
                + "public String restoredState(){return probeResults.get(\"readelf\").optString(\"state\");}"
                + "public void invalidate(String kind)throws Exception{bundle.put(\"installed\",true);if(kind.equals(\"root\"))settings.root=!settings.root;"
                + "if(kind.equals(\"access\"))settings.access=\"strict\";if(kind.equals(\"workspace\"))settings.roots.add(\"/additional\");"
                + "if(kind.equals(\"package\"))bundle.put(\"version\",\"replacement\");if(kind.equals(\"removed\"))bundle.put(\"installed\",false);invalidateProbeContext();}");
        for (String name : List.of("ToolkitOperation", "ToolkitResult", "toolkitArguments", "begin", "probeAllTools", "resetProbeRows", "queueBatchProgress", "applyBatchProgress",
                "probeRowStatus", "currentProbeContext", "invalidateProbeContext", "restoreProbeResults", "finishBatchProbe", "finishOperation", "cancelActiveToolkit", "updateRoot", "shortText", "onStop", "onDestroy")) code.append(methods.get(name));
        code.append('}');
        try (var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            check(ToolProvider.getSystemJavaCompiler().getTask(null, manager, null, List.of("-proc:none", "-source", "7", "-target", "7", "-Xlint:-options",
                    "-classpath", System.getProperty("java.class.path"), "-d", output.toString()), null, List.of(new Source(code.toString()))).call(), "Real batch page methods failed to compile");
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, ToolBatchProbeUiRegressionTest.class.getClassLoader());
    }
    private static Object call(Object view, String name, Object...args) throws Exception {
        for (Method method : view.getClass().getMethods()) if (method.getName().equals(name) && method.getParameterCount()==args.length) return method.invoke(view,args);
        throw new NoSuchMethodException(name);
    }
    private static ToolBatchProbe.Progress progress(String stage,String id,int completed,JSONObject result) throws Exception {
        Constructor<ToolBatchProbe.Progress> constructor=ToolBatchProbe.Progress.class.getDeclaredConstructor(String.class,String.class,String.class,int.class,int.class,JSONObject.class);
        constructor.setAccessible(true);return constructor.newInstance(stage,id,id,completed,13,result);
    }
    public static void main(String[] args) throws Exception {
        Path root=Paths.get(args[0]),temporary=Files.createTempDirectory("backcast-batch-ui-");
        try(URLClassLoader loader=fixture(root,temporary)) {
            Class<?> type=loader.loadClass("BatchUiFixture");Object view=type.getConstructor().newInstance();call(view,"start");Object operation=call(view,"operation");
            check(call(view,"action").equals("batch_status")&&(boolean)call(view,"busy")&&call(view,"row","readelf").toString().contains("waiting"),"Batch button did not create a real batch request/reset inventory");
            System.out.println("PASS batch control dispatches an independent batch request with visible pending rows");
            Thread worker=new Thread(()->{try{call(view,"event",operation,progress("running","readelf",0,null));}catch(Exception error){throw new RuntimeException(error);}});worker.start();worker.join();
            check(call(view,"row","readelf").toString().contains("waiting"),"Worker mutated UI before main delivery");call(view,"drain");
            check(call(view,"label").toString().contains("running readelf"),"Active tool stage is invisible");
            JSONObject error=new JSONObject().put("id","readelf").put("state","error").put("error","missing dependency");
            call(view,"event",operation,progress("finished","readelf",1,error));call(view,"drain");
            check((int)call(view,"completed")==1&&(int)call(view,"cached")==1&&call(view,"row","readelf").toString().contains("missing dependency"),"Actual failed row progress/evidence was discarded");
            System.out.println("PASS real item progress reaches the main thread and retains per-tool error details");
            call(view,"response",new JSONObject().put("state","batch_complete").put("completed",13).put("total",13).put("ready_count",12).put("failed_count",1));
            check((boolean)call(view,"terminal")&&!(boolean)call(view,"busy")&&call(view,"label").equals("checked 13/13 ready 12 failed 1"),"Accepted batch summary lost counts or stranded controls");
            System.out.println("PASS full batch summary reports ready/failure totals after accepted completion");
            for(String boundary:List.of("cancel","stop","destroy","replace","finishing")) {
                Object stale=type.getConstructor().newInstance();call(stale,"start");Object token=call(stale,"operation");
                call(stale,"event",token,progress("finished","readelf",1,error));call(stale,boundary);call(stale,"drain");
                check((int)call(stale,"cached")==0&&(int)call(stale,"completed")==0,"Late item result crossed "+boundary+" boundary");
                if (boundary.equals("cancel") || boundary.equals("stop")) check(call(stale,"row","readelf").toString().contains("not completed"), "Cancelled row retained a misleading running state");
            }
            System.out.println("PASS cancelled, stopped and replaced batches reject all late row/progress callbacks");
            Object changedRoot=type.getConstructor().newInstance();call(changedRoot,"start");Object oldOperation=call(changedRoot,"operation");
            call(changedRoot,"event",oldOperation,progress("finished","readelf",1,error));call(changedRoot,"drain");
            check((int)call(changedRoot,"cached")==1,"Root test did not begin with actual cached probe evidence");
            call(changedRoot,"root",false);
            check(!(boolean)call(changedRoot,"cancelled",oldOperation)&&(int)call(changedRoot,"rootWrites")==0,"Unchanged Root reconfigured or cancelled work");
            call(changedRoot,"event",oldOperation,progress("finished","objdump",2,error));call(changedRoot,"root",true);call(changedRoot,"drain");
            check((boolean)call(changedRoot,"rootEnabled")&&(boolean)call(changedRoot,"cancelled",oldOperation)
                    &&(int)call(changedRoot,"rootWrites")==1&&(int)call(changedRoot,"retargets")==1&&(int)call(changedRoot,"reloads")==1,
                    "Root toggle did not immediately save, cancel, retarget and reload");
            check((int)call(changedRoot,"cached")==0&&call(changedRoot,"cachedContext").equals("")&&call(changedRoot,"access").equals("full"),
                    "Root toggle retained old evidence, accepted a stale callback or changed permissions");
            System.out.println("PASS Root saves immediately, cancels old work and invalidates evidence without changing permissions");
            Object failed=type.getConstructor().newInstance();call(failed,"start");call(failed,"response",new JSONObject().put("state","error").put("error","cleanup failed"));
            check((boolean)call(failed,"terminal")&&!(boolean)call(failed,"busy")&&!call(failed,"label").toString().contains("ready"),"Cleanup failure displayed a successful batch summary");
            String source=Files.readString(root.resolve("app/src/main/java/com/mkei/backcast/ToolConfigActivity.java"));
            check(source.contains("intent.putExtra(EXTRA_PROBE_RESULT, result.toString())")&&source.contains("restoreProbeResults(savedInstanceState)")&&!source.contains("putBoolean(\"ready\""),"Batch details are not passed as a page-only evidence snapshot");
            for(String changed:List.of("root","access","workspace","package","removed")) {
                Object restored=type.getConstructor().newInstance();call(restored,"restoreSnapshots");
                check(call(restored,"restoredState").equals("new"),"Rotation replaced a new actual single-tool probe with an older navigation snapshot");
                call(restored,"invalidate",changed);check((int)call(restored,"cached")==0,"Old probe evidence survived changed "+changed+" context");
            }
            DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            Element xml=factory.newDocumentBuilder().parse(root.resolve("app/src/main/res/layout/activity_tool_config.xml").toFile()).getDocumentElement();
            String layout=Files.readString(root.resolve("app/src/main/res/layout/activity_tool_config.xml"));
            check(xml.getElementsByTagName("ProgressBar").getLength()==2&&layout.contains("@+id/tool_batch_probe"),"Batch controls replaced or omitted installation progress");
            check(!layout.contains("@+id/save")&&!layout.contains("permission")&&!source.contains("draft_root")&&!source.contains("draft_permission")
                    &&layout.contains("android:saveEnabled=\"false\"")&&source.indexOf("useRoot.setChecked(settings.useRoot())")<source.indexOf("useRoot.setOnCheckedChangeListener"),
                    "Tool page still duplicates access/save or restores a stale Root draft");
            System.out.println("PASS cleanup failure is visible and selected probe evidence navigates without persistent ready state");
        } finally {try(var files=Files.walk(temporary)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
