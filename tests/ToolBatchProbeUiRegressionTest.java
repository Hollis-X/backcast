import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.json.JSONObject;

/** Runs production lifecycle/progress rendering against view-only fixtures. */
public final class ToolBatchProbeUiRegressionTest {
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static final class Source extends SimpleJavaFileObject {
        final String value; Source(String value){super(URI.create("string:///BatchUiFixture.java"),Kind.SOURCE);this.value=value;}
        public CharSequence getCharContent(boolean ignore){return value;}
    }
    private static Map<String,String> methods(Path path) throws Exception {
        Map<String,String> out=new LinkedHashMap<>();
        try(var manager=ToolProvider.getSystemJavaCompiler().getStandardFileManager(null,null,null)) {
            JavacTask task=(JavacTask)ToolProvider.getSystemJavaCompiler().getTask(null,manager,null,List.of("-proc:none"),null,manager.getJavaFileObjects(path));
            for(CompilationUnitTree unit:task.parse())for(Tree declaration:unit.getTypeDecls())if(declaration instanceof ClassTree)
                for(Tree member:((ClassTree)declaration).getMembers())if(member instanceof MethodTree)out.put(((MethodTree)member).getName().toString(),member.toString());
        }return out;
    }
    private static URLClassLoader fixture(Path root,Path directory) throws Exception {
        Map<String,String> real=methods(root.resolve("app/src/main/java/com/mkei/backcast/ToolConfigActivity.java"));
        String code="import java.util.*;import org.json.*;import com.mkei.backcast.tool.*;"
            +"class Activity{protected void onResume(){}protected void onStop(){}}public class BatchUiFixture extends Activity{"
            +"static class View{static final int VISIBLE=0,GONE=8;int visibility=GONE;void setVisibility(int value){visibility=value;}}"
            +"static class TextView extends View{String text=\"\";void setText(CharSequence value){text=value.toString();}void setText(int value){text=label(value);}CharSequence getText(){return text;}}"
            +"static class Button extends TextView{boolean enabled;void setEnabled(boolean value){enabled=value;}}"
            +"static class CheckBox extends Button{boolean checked;void setChecked(boolean value){checked=value;}}"
            +"static class ProgressBar extends View{int progress,max;void setMax(int value){max=value;}int getMax(){return max;}void setProgress(int value){progress=value;}int getProgress(){return progress;}void setIndeterminate(boolean value){}}"
            +"static class R{static class string{static final int toolkit_batch_progress=1,toolkit_batch_current=2,toolkit_batch_checked=3,toolkit_batch_summary=4,toolkit_cancelled=5,toolkit_failed=6,toolkit_batch_not_checked=7;}}"
            +"static String label(int value){return new String[]{\"\",\"checked %1$d/%2$d\",\"running %1$s\",\"done %1$s\",\"checked %1$d/%2$d ready %3$d failed %4$d\",\"cancelled\",\"failed\",\"not completed\"}[value];}"
            +"String getString(int value,Object...args){return String.format(Locale.US,label(value),args);}String toolkitState(String value){return value;}"
            +"static class Settings{boolean root;int writes;boolean useRoot(){return root;}void setUseRoot(boolean value){root=value;writes++;}}Settings settings=new Settings();"
            +"static class RunHub{int retargets;static RunHub get(BatchUiFixture page){return page.hub;}void retargetTools(){retargets++;}}RunHub hub=new RunHub();"
            +"static class ToolkitOperationManager{static class Snapshot{long id,revision;String action=\"\",tool=\"\",context=\"old\",state=\"idle\";boolean busy,refreshing;EmbeddedToolchain.Progress installProgress;List<ToolBatchProbe.Progress> probes=new ArrayList<ToolBatchProbe.Progress>();JSONObject response,listing;JSONObject result(){return response;}JSONObject inventory(){return listing;}}"
            +"Snapshot value=new Snapshot();int subscriptions,unsubscriptions,refreshes,cancels;String context=\"old\";void subscribe(Object listener){subscriptions++;}void unsubscribe(Object listener){unsubscriptions++;}void refreshInventory(){refreshes++;}boolean busy(){return value.busy;}Snapshot snapshot(){return value;}void cancel(long id){cancels++;}String configurationContext(){return context;}}"
            +"ToolkitOperationManager operations=new ToolkitOperationManager();ToolkitOperationManager.Snapshot active;Object operationListener=new Object();boolean activityStarted;long renderedRevision=-1,renderedOperation;String renderedInventory=\"\",probeContext=\"old\",toolId=\"\";"
            +"Map<String,JSONObject> probeResults=new LinkedHashMap<String,JSONObject>();Map<String,TextView> toolRows=new LinkedHashMap<String,TextView>();JSONObject bundle=new JSONObject();"
            +"ProgressBar batchProgress=new ProgressBar();TextView batchProgressText=new TextView(),operationStatus=new TextView(),detailOutput=new TextView();View installProgressContainer=new View(),batchProgressContainer=new View();"
            +"CheckBox useRoot=new CheckBox();Button install=new Button(),remove=new Button(),probe=new Button(),batchProbe=new Button(),cancel=new Button();"
            +"int renders,details;void renderTools(JSONObject listing){renders++;bundle=listing.optJSONObject(\"package\");if(bundle==null)bundle=new JSONObject();TextView row=new TextView();row.setText(\"not probed\");toolRows.put(\"readelf\",row);}void resetProbeRows(){}void beginInstallProgress(boolean removing){}void applyInstallProgress(EmbeddedToolchain.Progress progress){}void finishInstallProgress(JSONObject result,boolean removing){}void showProbe(JSONObject result){details++;}"
            +"public void resume(){onResume();}public void stop(){onStop();}public void root(boolean value){updateRoot(value);}public void cancel(){cancelActiveToolkit();}"
            +"public void busy(boolean value){operations.value.busy=value;}public int cancels(){return operations.cancels;}public int detached(){return operations.unsubscriptions;}public int attached(){return operations.subscriptions;}public boolean started(){return activityStarted;}public int writes(){return settings.writes;}"
            +"public String summary(){return batchProgressText.text;}public boolean buttons(){return batchProbe.enabled&&useRoot.enabled&&cancel.visibility==View.GONE;}public int cached(){return probeResults.size();}"
            +"public String row(){return toolRows.get(\"readelf\").text;}public void detail(){toolId=\"readelf\";}public int details(){return details;}"
            +"public void snapshot(long revision,boolean running,String state,String context,int completed)throws Exception{ToolkitOperationManager.Snapshot s=new ToolkitOperationManager.Snapshot();s.id=1;s.revision=revision;s.busy=running;s.action=\"batch_status\";s.state=state;s.context=context;s.listing=new JSONObject().put(\"package\",new JSONObject().put(\"installed\",true));"
            +"s.response=new JSONObject().put(\"state\",state).put(\"completed\",completed).put(\"total\",13).put(\"ready_count\",completed).put(\"failed_count\",0);"
            +"java.lang.reflect.Constructor<ToolBatchProbe.Progress> c=ToolBatchProbe.Progress.class.getDeclaredConstructor(String.class,String.class,String.class,int.class,int.class,JSONObject.class);c.setAccessible(true);s.probes.add(c.newInstance(\"finished\",\"readelf\",\"readelf\",completed,13,new JSONObject().put(\"id\",\"readelf\").put(\"state\",\"ready\").put(\"ready\",true)));renderOperation(s);}"
            +"public void changedContext(){operations.context=\"new\";}";
        for(String name:List.of("onResume","onStop","renderOperation","currentProbeContext","invalidateProbeContext","applyBatchProgress","probeRowStatus","finishBatchProbe","cancelActiveToolkit","setBusy","updateRoot","shortText"))code+=real.get(name);
        code+="}";
        try(var manager=ToolProvider.getSystemJavaCompiler().getStandardFileManager(null,null,null)) {
            check(ToolProvider.getSystemJavaCompiler().getTask(null,manager,null,List.of("-proc:none","-classpath",System.getProperty("java.class.path"),"-d",directory.toString()),null,List.of(new Source(code))).call(),"Production tool page fixture does not compile");
        }
        return new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},ToolBatchProbeUiRegressionTest.class.getClassLoader());
    }
    private static Object call(Object page,String name,Object...args)throws Exception {
        for(Method method:page.getClass().getMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length)return method.invoke(page,args);
        throw new NoSuchMethodException(name);
    }
    public static void main(String[] args)throws Exception {
        Path root=Paths.get(args[0]),temporary=Files.createTempDirectory("backcast-batch-ui-");
        try(URLClassLoader loader=fixture(root,temporary)) {
            Class<?> type=loader.loadClass("BatchUiFixture");Object page=type.getConstructor().newInstance();
            call(page,"busy",true);call(page,"resume");call(page,"stop");
            check((int)call(page,"cancels")==0&&(int)call(page,"detached")==1&&!(boolean)call(page,"started"),"Leaving page cancelled operation");
            call(page,"resume");check((int)call(page,"attached")==2,"Returning page failed to resubscribe");
            call(page,"root",true);check((int)call(page,"writes")==0&&(int)call(page,"cancels")==0,"Root toggle cancelled/reconfigured active probes");
            call(page,"cancel");check((int)call(page,"cancels")==1,"Explicit cancel did not reach application manager");
            call(page,"snapshot",5L,false,"batch_complete","old",13);
            check((boolean)call(page,"buttons")&&call(page,"summary").equals("checked 13/13 ready 13 failed 0"),"13/13 failed to automatically release controls");
            call(page,"snapshot",4L,true,"running","old",2);
            check((boolean)call(page,"buttons")&&call(page,"summary").toString().contains("13/13"),"Late running state revived completed operation");
            call(page,"changedContext");call(page,"snapshot",6L,false,"batch_complete","old",13);
            check((int)call(page,"cached")==0&&call(page,"row").equals("not probed"),"Old probe results/rows survived changed authorization/root context");
            Object detail=type.getConstructor().newInstance();call(detail,"detail");call(detail,"snapshot",1L,true,"running","old",1);
            check((int)call(detail,"details")==1,"Tool details opened mid-batch did not receive the eventual output");
            String source=Files.readString(root.resolve("app/src/main/java/com/mkei/backcast/ToolConfigActivity.java"));
            check(!source.contains("shutdownNow")&&!source.contains("cancelToolkitOperation")&&source.contains("operations.unsubscribe(operationListener)"),"Page still owns worker cancellation");
            System.out.println("PASS production page detaches without cancellation and restores terminal controls");
            System.out.println("PASS stale progress and changed configuration cannot restore obsolete ready results");
        } finally {try(var files=Files.walk(temporary)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
