import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;

/** Executes the production long-press menu and original-request retry binding. */
public final class MessageActionsUiRegressionTest {
    private static final class Source extends SimpleJavaFileObject {
        final String source;
        Source(String name, String source) {
            super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE);
            this.source = source;
        }
        @Override public CharSequence getCharContent(boolean ignored) { return source; }
    }

    private static String method(JavaCompiler compiler, Path root) throws Exception {
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null,
                    manager.getJavaFileObjects(root.resolve("app/src/main/java/com/mkei/backcast/MainActivity.java")));
            for (CompilationUnitTree unit : task.parse()) for (Tree declaration : unit.getTypeDecls()) {
                if (!(declaration instanceof ClassTree)) continue;
                for (Tree member : ((ClassTree) declaration).getMembers())
                    if (member instanceof MethodTree && ((MethodTree) member).getName().contentEquals("enableMessageActions"))
                        return member.toString().replace("private ", "public ");
            }
        }
        throw new AssertionError("Production message actions not found");
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), build = Files.createTempDirectory("backcast-message-ui-");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<javax.tools.JavaFileObject> sources = new ArrayList<>();
        sources.add(new Source("android.content.Context", """
            package android.content; public class Context {
              public static final String CLIPBOARD_SERVICE="clipboard";
              public final ClipboardManager clipboard=new ClipboardManager();
              public Object getSystemService(String name){return clipboard;}
            }
            """));
        sources.add(new Source("android.content.ClipboardManager", """
            package android.content; public class ClipboardManager {public ClipData clip;public void setPrimaryClip(ClipData c){clip=c;}}
            """));
        sources.add(new Source("android.content.ClipData", """
            package android.content; public class ClipData {public CharSequence text; public static ClipData newPlainText(String label,CharSequence text){ClipData c=new ClipData();c.text=text;return c;}}
            """));
        sources.add(new Source("android.content.DialogInterface", """
            package android.content; public interface DialogInterface {interface OnClickListener{void onClick(DialogInterface d,int i);}}
            """));
        sources.add(new Source("android.view.View", """
            package android.view; public class View {
              public static final int VISIBLE=0,GONE=8;private int visibility=GONE;
              public interface OnLongClickListener{boolean onLongClick(View v);}public OnLongClickListener longClick;
              public void setOnLongClickListener(OnLongClickListener listener){longClick=listener;}
              public boolean performLongClick(){return longClick.onLongClick(this);}
              public int getVisibility(){return visibility;}public void setVisibility(int v){visibility=v;}
            }
            """));
        sources.add(new Source("android.widget.TextView", """
            package android.widget;public class TextView extends android.view.View {
              private CharSequence text="";public void setText(CharSequence s){text=s;}public CharSequence getText(){return text;}public int length(){return text.length();}public void setSelection(int i){}
            }
            """));
        sources.add(new Source("android.widget.Toast", """
            package android.widget;public class Toast {public static final int LENGTH_SHORT=0;public static int shown;
              public static Toast makeText(android.content.Context context,int resource,int duration){return new Toast();}public void show(){shown++;}}
            """));
        sources.add(new Source("androidx.appcompat.app.AlertDialog", """
            package androidx.appcompat.app;import android.content.*;public class AlertDialog implements DialogInterface {
              public static AlertDialog last;public String[] items;public DialogInterface.OnClickListener listener;
              public void choose(int index){listener.onClick(this,index);}
              public static class Builder{final AlertDialog dialog=new AlertDialog();public Builder(Context c){}
                public Builder setItems(String[] items,DialogInterface.OnClickListener l){dialog.items=items;dialog.listener=l;return this;}
                public AlertDialog show(){last=dialog;return dialog;}}
            }
            """));
        sources.add(new Source("com.mkei.backcast.R", "package com.mkei.backcast;public class R{public static class string{public static final int copied=1;}}"));
        sources.add(new Source("com.mkei.backcast.MessageFixture", """
            package com.mkei.backcast;import android.widget.TextView;import android.view.View;
            import com.mkei.backcast.agent.Message;import com.mkei.backcast.ui.MessageActions;import org.json.JSONObject;
            public class MessageFixture extends android.content.Context{
              public long sessionId=7;public int historyToken=3;public boolean activityDestroyed,finishing,sessionOpening,initialHistoryLoading;
              public final View stop=new View();public final Loop loop=new Loop();public Message sent;public int starts,toasts;public boolean goal,reject;
              public final TextView prompt=new TextView();public final Picker mcpToolPicker=new Picker();public com.mkei.backcast.mcp.McpSelection restored;
              public class Picker{public void restoreDraft(String json){try{JSONObject value=new JSONObject(json);restored=com.mkei.backcast.mcp.McpSelection.fromJson(value.getJSONObject("selection"));prompt.setText(value.getString("text"));}catch(Exception invalid){throw new IllegalStateException(invalid);}}}
              void recordUiFailure(long sid,String source,Throwable error){throw new AssertionError(error);}
              public static class Loop{public boolean active;public boolean busy(){return active;}}
              public boolean isFinishing(){return finishing;}void toast(String text){toasts++;}
              void startRequest(Message request,boolean asGoal){if(reject)return;sent=request;goal=asGoal;starts++;prompt.setText("");stop.setVisibility(View.VISIBLE);}
            """ + method(compiler, root) + "\n}") );
        sources.add(new Source("com.mkei.backcast.MessageChecks", """
            package com.mkei.backcast;
            import android.view.View;import android.widget.TextView;import androidx.appcompat.app.AlertDialog;
            import com.mkei.backcast.agent.Message;import com.mkei.backcast.ui.MessageActions;
            import com.mkei.backcast.mcp.*;import java.nio.file.*;import java.util.*;import org.json.*;
            public class MessageChecks{
              static int passed;static void check(boolean yes,String failure){if(!yes)throw new AssertionError(failure);}
              static void pass(String name){passed++;System.out.println("PASS "+name);}
              static TextView bubble(String text){TextView v=new TextView();v.setText(text);return v;}
              static void menu(){
                MessageFixture f=new MessageFixture();int[] retried={0};TextView text=bubble("actual visible answer");
                MessageActions.install(f,text,()->retried[0]++);check(text.performLongClick(),"Long press was not consumed");
                check(Arrays.equals(AlertDialog.last.items,new String[]{"复制","重试"}),"Missing copy/retry choices");
                check(retried[0]==0,"Opening menu automatically retried");AlertDialog.last.choose(0);
                check(f.clipboard.clip.text.toString().equals("actual visible answer")&&retried[0]==0,"Copy changed request");
                text.performLongClick();AlertDialog.last.choose(1);check(retried[0]==1,"Explicit retry did not invoke action");
                pass("longPressListsCopyAndRetryWithoutExecutingOnOpen");
              }
              static void noRequest(){
                MessageFixture f=new MessageFixture();TextView text=bubble("unassociated text");MessageActions.install(f,text,null);text.performLongClick();
                check(Arrays.equals(AlertDialog.last.items,new String[]{"复制"}),"No original request fabricated retry");
                AlertDialog.last.choose(0);check(f.clipboard.clip.text.toString().equals("unassociated text"),"Copy-only failed");
                pass("unassociatedTextOnlyCopies");
              }
              static void original(Path files)throws Exception{
                McpStore store=new McpStore(files.toFile());McpServer server=new McpServer("s","service","http://127.0.0.1:1/mcp","secret",true,10);
                store.save(server);store.cacheTools(server,List.of(new McpToolInfo(new JSONObject().put("name","inspect").put("description","inspect files").put("inputSchema",new JSONObject().put("type","object")))));
                Message request=Message.user("inspect original project");request.workDir="/project";request.mcpSelection=McpCatalog.cached(store).get(0).tools.get(0);
                MessageFixture f=new MessageFixture();TextView answer=bubble("model answer, never a task");f.enableMessageActions(answer,request);answer.performLongClick();AlertDialog.last.choose(1);
                check(f.starts==1&&!f.goal&&f.sent!=request&&f.sent.content.equals(request.content),"Retry did not copy original human request");
                check(f.sent.mcpSelection==request.mcpSelection&&f.sent.workDir.equals("/project"),"Retry lost structured tool choice/workspace");
                check(request.content.equals("inspect original project")&&answer.getText().toString().startsWith("model answer"),"Retry mutated history");
                AlertDialog.last.choose(1);check(f.starts==1&&f.toasts==1,"Repeated retry replaced unclaimed task");
                pass("assistantRetryResendsOriginalHumanRequestWithMetadataAndKeepsHistory");
                MessageFixture empty=new MessageFixture();empty.reject=true;TextView stale=bubble("answer");empty.enableMessageActions(stale,request);stale.performLongClick();AlertDialog.last.choose(1);
                check(empty.starts==0&&empty.prompt.getText().toString().equals(request.content)&&empty.restored!=null
                  &&empty.restored.mappedName.equals(request.mcpSelection.mappedName),"Rejected MCP retry lost original editable draft and selection");
                MessageFixture draft=new MessageFixture();draft.reject=true;draft.prompt.setText("new unsent request");TextView blocked=bubble("answer");draft.enableMessageActions(blocked,request);blocked.performLongClick();AlertDialog.last.choose(1);
                check(draft.prompt.getText().toString().equals("new unsent request")&&draft.restored==null,"Rejected retry overwrote existing draft");
                pass("rejectedRetryKeepsOriginalMcpDraftWithoutOverwritingExistingInput");
              }
              static void ownership(){
                Message request=Message.user("do work");MessageFixture changed=new MessageFixture();TextView answer=bubble("answer");
                changed.enableMessageActions(answer,request);answer.performLongClick();changed.sessionId++;AlertDialog.last.choose(1);check(changed.starts==0,"Old dialog retried in different session");
                MessageFixture page=new MessageFixture();TextView old=bubble("answer");page.enableMessageActions(old,request);old.performLongClick();page.historyToken++;AlertDialog.last.choose(1);check(page.starts==0,"Old page retried after replacement");
                MessageFixture gone=new MessageFixture();TextView detached=bubble("answer");gone.enableMessageActions(detached,request);detached.performLongClick();gone.activityDestroyed=true;AlertDialog.last.choose(1);check(gone.starts==0,"Destroyed page retried");
                pass("retryRejectsOldSessionPageAndDestroyedActivity");
              }
              static void busy(){
                for(int state=0;state<4;state++){
                  MessageFixture f=new MessageFixture();TextView answer=bubble("answer");f.enableMessageActions(answer,Message.user("do work"));
                  if(state==0)f.loop.active=true;if(state==1)f.stop.setVisibility(View.VISIBLE);if(state==2)f.sessionOpening=true;if(state==3)f.initialHistoryLoading=true;
                  answer.performLongClick();AlertDialog.last.choose(1);check(f.starts==0&&f.toasts==1,"Busy/loading retry replaced request");
                }
                pass("retryWhileBusyOrLoadingOnlyNotifies");
              }
              public static void run(String directory)throws Exception{menu();noRequest();original(Path.of(directory));ownership();busy();System.out.println(passed+" message action UI tests passed");}
            }
            """));
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            for (var source : manager.getJavaFileObjects(root.resolve("app/src/main/java/com/mkei/backcast/ui/MessageActions.java"))) sources.add(source);
            boolean ok = compiler.getTask(null, manager, null, List.of("-proc:none", "-encoding", "UTF-8", "-cp",
                    System.getProperty("java.class.path"), "-d", build.toString()), null, sources).call();
            if (!ok) throw new AssertionError("Actual message actions did not compile");
            try (URLClassLoader loader = new URLClassLoader(new URL[]{build.toUri().toURL()}, MessageActionsUiRegressionTest.class.getClassLoader())) {
                loader.loadClass("com.mkei.backcast.MessageChecks").getMethod("run", String.class).invoke(null, build.resolve("mcp").toString());
            }
        } finally {
            try (var paths = Files.walk(build)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
