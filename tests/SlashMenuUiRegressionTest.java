import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Executes production slash popup/picker with real cached MCP identities and controllable Android surfaces. */
public final class SlashMenuUiRegressionTest {
    record Source(String name, String code) { JavaFileObject file() { return new SimpleJavaFileObject(
            URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
        @Override public CharSequence getCharContent(boolean ignored) { return code; }
    }; } }
    static void add(List<JavaFileObject> files, String name, String code) { files.add(new Source(name, code).file()); }
    static String method(Path root, String name) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
            var source = manager.getJavaFileObjects(root.resolve("app/src/main/java/com/mkei/backcast/MainActivity.java").toFile());
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null, source);
            for (CompilationUnitTree unit : task.parse()) for (Tree type : unit.getTypeDecls()) if (type instanceof ClassTree)
                for (Tree member : ((ClassTree) type).getMembers()) if (member instanceof MethodTree
                        && ((MethodTree) member).getName().contentEquals(name)) return member.toString();
        }
        throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), build = Files.createTempDirectory("backcast-slash-ui-");
        List<JavaFileObject> sources = new ArrayList<>();
        add(sources,"android.util.DisplayMetrics", "package android.util; public class DisplayMetrics {public float density=1;}");
        add(sources,"android.content.res.Resources", "package android.content.res; public class Resources {public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();} public int getColor(int v){return v;}}");
        add(sources,"android.content.Context", "package android.content; public class Context {public java.io.File directory; public Context getApplicationContext(){return this;}public java.io.File getFilesDir(){return directory;} public android.content.res.Resources getResources(){return new android.content.res.Resources();} public void startActivity(Intent i){}}");
        add(sources,"android.content.Intent", "package android.content; public class Intent {public Intent(Context c,Class<?> type){}}");
        add(sources,"android.content.DialogInterface", "package android.content; public interface DialogInterface {interface OnClickListener{void onClick(DialogInterface d,int i);}interface OnShowListener{void onShow(DialogInterface d);}interface OnDismissListener{void onDismiss(DialogInterface d);}}");
        add(sources,"android.os.Looper", "package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}");
        add(sources,"android.os.Handler", """
            package android.os; public class Handler {
              static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> pending=new java.util.concurrent.ConcurrentLinkedQueue<>();
              public Handler(Looper l){}public boolean post(Runnable r){pending.add(r);return true;}
              public static void drain(){Runnable r;while((r=pending.poll())!=null)r.run();}
            }
            """);
        add(sources,"android.graphics.Rect", "package android.graphics; public class Rect{public int left,top,right,bottom; public int width(){return right-left;}public int height(){return bottom-top;}}");
        add(sources,"android.graphics.Color", "package android.graphics;public class Color{public static final int TRANSPARENT=0;}");
        add(sources,"android.graphics.drawable.ColorDrawable", "package android.graphics.drawable;public class ColorDrawable{public ColorDrawable(int color){}}");
        add(sources,"android.view.Gravity", "package android.view;public class Gravity{public static final int NO_GRAVITY=0;}");
        add(sources,"android.view.ViewTreeObserver", """
            package android.view;public class ViewTreeObserver{
              public interface OnGlobalLayoutListener{void onGlobalLayout();}
              public java.util.List<OnGlobalLayoutListener> listeners=new java.util.ArrayList<>();
              public void addOnGlobalLayoutListener(OnGlobalLayoutListener l){listeners.add(l);}
              public void removeOnGlobalLayoutListener(OnGlobalLayoutListener l){listeners.remove(l);}
              public boolean isAlive(){return true;}public void fire(){for(var l:new java.util.ArrayList<>(listeners))l.onGlobalLayout();}
            }
            """);
        add(sources,"android.view.View", """
            package android.view; public class View {
              public static final int VISIBLE=0,GONE=8; public int visibility=VISIBLE; public int x=20,y=600,width=360,frameBottom=700;
              public ViewTreeObserver observer=new ViewTreeObserver(); public interface OnClickListener{void onClick(View v);}public OnClickListener click;
              public View(android.content.Context c){} public void setOnClickListener(OnClickListener c){click=c;}public void click(){if(click!=null)click.onClick(this);}
              public void setPadding(int a,int b,int c,int d){} public void setBackgroundResource(int id){}public void setVisibility(int v){visibility=v;}public int getVisibility(){return visibility;}
              public ViewTreeObserver getViewTreeObserver(){return observer;}public int getWidth(){return width;}
              public void getWindowVisibleDisplayFrame(android.graphics.Rect r){r.left=0;r.top=24;r.right=400;r.bottom=frameBottom;}
              public void getLocationOnScreen(int[] out){out[0]=x;out[1]=y;}public void measure(int w,int h){}public int getMeasuredHeight(){return 32;}
              public static class MeasureSpec{public static final int EXACTLY=1,UNSPECIFIED=0;public static int makeMeasureSpec(int s,int m){return s;}}
            }
            """);
        add(sources,"android.widget.LinearLayout", """
            package android.widget;public class LinearLayout extends android.view.View{
              public static final int VERTICAL=1;public java.util.List<android.view.View> children=new java.util.ArrayList<>();
              public LinearLayout(android.content.Context c){super(c);}public void setOrientation(int o){}public void addView(android.view.View v){children.add(v);}
              public void addView(android.view.View v,LayoutParams p){children.add(v);}public void removeAllViews(){children.clear();}
              public int getMeasuredHeight(){int n=16;for(var v:children)n+=v.getMeasuredHeight()+16;return n;}
              public static class LayoutParams{public LayoutParams(int w,int h){}}
            }
            """);
        add(sources,"android.widget.ScrollView", "package android.widget;public class ScrollView extends android.view.View{public android.view.View child;public ScrollView(android.content.Context c){super(c);}public void addView(android.view.View c){child=c;}public void scrollTo(int x,int y){}}");
        add(sources,"android.widget.TextView", """
            package android.widget;public class TextView extends android.view.View{public String text="";public TextView(android.content.Context c){super(c);}
              public void setText(CharSequence t){text=t.toString();}public CharSequence getText(){return text;}public void setTextSize(float s){}public void setTextColor(int c){}
              public void setMaxLines(int l){}public void setEllipsize(Object e){}
            }
            """);
        add(sources,"android.widget.EditText", "package android.widget;public class EditText extends TextView{public EditText(android.content.Context c){super(c);}public void setInputType(int t){}public void setMinLines(int l){} public int length(){return text.length();}public void setSelection(int p){}public void setChipped(boolean a,boolean b){}}");
        add(sources,"android.widget.Toast", "package android.widget;public class Toast{public static final int LENGTH_SHORT=0;public static volatile String last=" + "\"\"" + "; public static Toast makeText(android.content.Context c,String s,int n){last=s;return new Toast();}public void show(){}}");
        add(sources,"android.text.InputType", "package android.text;public class InputType{public static final int TYPE_CLASS_TEXT=1,TYPE_TEXT_FLAG_MULTI_LINE=2,TYPE_TEXT_FLAG_NO_SUGGESTIONS=4;}");
        add(sources,"android.text.TextUtils", "package android.text;public class TextUtils{public static boolean isEmpty(String s){return s==null||s.isEmpty();} public enum TruncateAt{END}}");
        add(sources,"android.widget.PopupWindow", """
            package android.widget;public class PopupWindow{
              public static final int INPUT_METHOD_NEEDED=1;public static PopupWindow last; public android.view.View content;public int width,height,x,y,shows,updates;public boolean showing,focusable,outside;
              public interface OnDismissListener{void onDismiss();}public OnDismissListener dismiss;
              public PopupWindow(android.view.View c,int w,int h,boolean f){last=this;content=c;width=w;height=h;focusable=f;}
              public void setBackgroundDrawable(Object d){}public void setOutsideTouchable(boolean b){outside=b;}public void setFocusable(boolean b){focusable=b;}
              public void setInputMethodMode(int i){}public void setAnimationStyle(int s){}public void setOnDismissListener(OnDismissListener d){dismiss=d;}
              public boolean isShowing(){return showing;}public void setWidth(int w){width=w;}public void setHeight(int h){height=h;}
              public void showAtLocation(android.view.View a,int g,int x,int y){showing=true;shows++;this.x=x;this.y=y;}
              public void update(int x,int y,int w,int h){updates++;this.x=x;this.y=y;width=w;height=h;}
              public void dismiss(){showing=false;if(dismiss!=null)dismiss.onDismiss();}
            }
            """);
        add(sources,"androidx.appcompat.app.AlertDialog", """
            package androidx.appcompat.app;import android.content.*; public class AlertDialog implements DialogInterface{
              public static final int BUTTON_POSITIVE=-1;public static AlertDialog latest;public android.view.View view;public android.widget.TextView positive;
              public DialogInterface.OnShowListener show;public DialogInterface.OnDismissListener dismiss;public boolean showing;
              public void setOnShowListener(DialogInterface.OnShowListener l){show=l;}public void setOnDismissListener(DialogInterface.OnDismissListener l){dismiss=l;}
              public android.widget.TextView getButton(int i){return positive;}public void show(){showing=true;if(show!=null)show.onShow(this);}
              public void dismiss(){showing=false;if(dismiss!=null)dismiss.onDismiss(this);}
              public static class Builder{AlertDialog dialog=new AlertDialog();public Builder(Context c){dialog.positive=new android.widget.TextView(c);}
                public Builder setTitle(String s){return this;}public Builder setView(android.view.View v){dialog.view=v;return this;}
                public Builder setNegativeButton(String t,DialogInterface.OnClickListener l){return this;}public Builder setPositiveButton(String t,DialogInterface.OnClickListener l){return this;}
                public AlertDialog create(){latest=dialog;return dialog;}}
            }
            """);
        add(sources,"com.mkei.backcast.R", "package com.mkei.backcast;public class R{public static class drawable{public static int bg_slash_card=1,bg_slash_item=2;}public static class color{public static int text_secondary=1,text_primary=2;}public static class style{public static int SlashPopupAnimation=1;}public static class string{public static int slash_unknown=1,slash_title=2;}}");
        add(sources,"com.mkei.backcast.McpConfigActivity", "package com.mkei.backcast;public class McpConfigActivity{}");
        add(sources,"com.mkei.backcast.ChatStore", """
            package com.mkei.backcast; public class ChatStore implements AutoCloseable{
              public static final java.util.List<String> diagnostics=new java.util.concurrent.CopyOnWriteArrayList<>();
              public ChatStore(android.content.Context c){} public void recordDiagnostic(long sid,String source,String title,String detail){diagnostics.add(sid+":"+source+":"+title+":"+detail);}public void close(){}
            }
            """);
        add(sources,"com.mkei.backcast.RunHub", """
            package com.mkei.backcast;import com.mkei.backcast.mcp.*;public class RunHub{
              public static final RunHub INSTANCE=new RunHub();public static RunHub get(android.content.Context c){return INSTANCE;}
              public McpStore store;public volatile int reads,refreshes;public boolean invalid;public volatile java.util.concurrent.CountDownLatch gate;
              public java.util.List<McpCatalog.Server> cachedMcpCatalog(){reads++;if(gate!=null)try{gate.await();}catch(Exception e){throw new RuntimeException(e);}return McpCatalog.cached(store);}
              public McpCatalog.Refresh newMcpRefresh(String id){synchronized(this){refreshes++;}return new McpCatalog.Refresh(store,id);}
              public void validateMcpSelection(McpSelection s){if(invalid)throw new IllegalStateException("Bearer fixture-secret unavailable");store.validateSelection(s);}
            }
            """);
        String mainFixture = """
            package com.mkei.backcast.ui;import java.util.*;import android.widget.*;import android.view.View;import android.text.TextUtils;import com.mkei.backcast.R;
            class MainSlashFixture extends android.content.Context{
              boolean sessionOpening,activityDestroyed,slashResumed=true;McpToolPicker mcpToolPicker;SlashMenuPopup slashPopup;long slashContext;View inputBar;int starts,commands;
              EditText prompt=new EditText(new android.content.Context());View send=new View(new android.content.Context()),stop=new View(new android.content.Context());
              static class Loop{boolean busy=true;boolean busy(){return busy;}}Loop loop=new Loop();
              static class SlashCmd{String name,detail="";SlashCmd(String n){name=n;}}SlashCmd[] slashCmds(){return new SlashCmd[]{new SlashCmd("compact"),new SlashCmd("goal")};}
              boolean isWholeCmd(String s){return s.equals("/compact")||s.equals("/goal");}boolean isGoalCommand(String s){return s.equals("/goal")||s.startsWith("/goal ");}
              void tintSend(boolean b){} void editGoal(boolean b){commands++;}void commitGoal(String s,boolean b){commands++;}void runSlash(SlashCmd c){commands++;}
              SlashCmd matchSlash(String s){return null;}void toast(String s){}String getString(int i){return "unknown";}void startText(String s,boolean b){starts++;}
            """ + method(root,"syncSlashPopup").replace("private ","public ") + method(root,"setBusy").replace("private ","public ")
                + method(root,"onSend").replace("private ","public ") + method(root,"showSlashPopup").replace("private ","public ")
                + method(root,"hideSlashPopup").replace("private ","public ") + "}";
        add(sources,"com.mkei.backcast.ui.MainSlashFixture",mainFixture);
        add(sources,"com.mkei.backcast.ui.SlashUiChecks", checks());
        try (StandardJavaFileManager manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null,null,null)) {
            for (String name : List.of("SlashMenuPopup","McpToolPicker")) for (var source : manager.getJavaFileObjects(
                    root.resolve("app/src/main/java/com/mkei/backcast/ui/"+name+".java").toFile())) sources.add(source);
            boolean ok=ToolProvider.getSystemJavaCompiler().getTask(null,manager,null,List.of("-proc:none","-classpath",System.getProperty("java.class.path"),"-d",build.toString()),null,sources).call();
            if(!ok)throw new AssertionError("Actual slash UI did not compile");
            try(URLClassLoader loader=new URLClassLoader(new URL[]{build.toUri().toURL()},SlashMenuUiRegressionTest.class.getClassLoader())){
                loader.loadClass("com.mkei.backcast.ui.SlashUiChecks").getMethod("run").invoke(null);
            }
        } finally {try(var paths=Files.walk(build)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}});}}
    }
    static String checks(){return """
        package com.mkei.backcast.ui;
        import android.content.Context;import android.os.Handler;import android.widget.*;import android.view.View;
        import androidx.appcompat.app.AlertDialog;import com.mkei.backcast.*;import com.mkei.backcast.mcp.*;
        import java.nio.file.*;import java.util.*;import java.util.function.BooleanSupplier;import org.json.*;
        public class SlashUiChecks{
          static int passed;static Context context;static RunHub hub=RunHub.INSTANCE;static Host host;static McpToolPicker picker;static McpServer server;static Remote remote;
          static void check(boolean b,String message){if(!b)throw new AssertionError(message);}static void pass(String s){passed++;System.out.println("PASS "+s);}
          static void await(BooleanSupplier b)throws Exception{long end=System.nanoTime()+3_000_000_000L;while(System.nanoTime()<end){Handler.drain();if(b.getAsBoolean())return;Thread.sleep(5);}throw new AssertionError("UI callback timed out");}
          static class Host implements McpToolPicker.Host{long epoch=1;String text="/";int updates,drafts,hides;McpToolPicker picker;MainSlashFixture ui;
            public long context(){return epoch;}public long session(){return 41;}public boolean current(long c){return c==epoch;}
            public String text(){return text;}public void draft(String value){text=value;drafts++;if(picker!=null)picker.textChanged(value);}
            public void hideMenu(){hides++;if(ui!=null)ui.hideSlashPopup();else picker.menuClosed();}public void refreshMenu(){updates++;if(ui!=null)ui.syncSlashPopup(ui.prompt.getText().toString());}}
          static List<SlashMenuPopup.Item> rows(String query){var rows=new ArrayList<SlashMenuPopup.Item>();picker.append(rows,query);return rows;}
          static SlashMenuPopup.Item row(List<SlashMenuPopup.Item> rows,String name){return rows.stream().filter(r->r.title.equals(name)).findFirst().orElseThrow(()->new AssertionError("Missing "+name));}
          static final class Remote implements AutoCloseable{
            final com.sun.net.httpserver.HttpServer http=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newCachedThreadPool();
            final java.util.concurrent.atomic.AtomicInteger initializes=new java.util.concurrent.atomic.AtomicInteger(),lists=new java.util.concurrent.atomic.AtomicInteger(),calls=new java.util.concurrent.atomic.AtomicInteger(),active=new java.util.concurrent.atomic.AtomicInteger(),peak=new java.util.concurrent.atomic.AtomicInteger();
            final java.util.Set<String> deleted=java.util.concurrent.ConcurrentHashMap.newKeySet();
            volatile java.util.concurrent.CountDownLatch gate=new java.util.concurrent.CountDownLatch(1);volatile JSONArray tools=new JSONArray();volatile boolean fail;
            Remote()throws Exception{http.setExecutor(executor);http.createContext("/mcp",exchange->{
              try{
                if(exchange.getRequestMethod().equals("DELETE")){deleted.add(exchange.getRequestHeaders().getFirst("MCP-Session-Id"));reply(exchange,204,"");return;}
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));String method=request.optString("method");
                if(method.equals("initialize")){String session="ui-session-"+initializes.incrementAndGet();exchange.getResponseHeaders().set("MCP-Session-Id",session);send(exchange,request,new JSONObject().put("protocolVersion","2025-11-25").put("capabilities",new JSONObject().put("tools",new JSONObject())));return;}
                if(method.startsWith("notifications/")){reply(exchange,202,"");return;}
                if(method.equals("tools/list")){lists.incrementAndGet();int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);try{gate.await();if(fail)reply(exchange,500,"fixture-secret unavailable");else send(exchange,request,new JSONObject().put("tools",tools));}finally{active.decrementAndGet();}return;}
                if(method.equals("tools/call")){calls.incrementAndGet();send(exchange,request,new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","ok"))));return;}
                reply(exchange,400,"unexpected method");
              }catch(Exception expectedOnCancellation){exchange.close();}
            });http.start();}
            String url(){return "http://127.0.0.1:"+http.getAddress().getPort()+"/mcp";}
            static void reply(com.sun.net.httpserver.HttpExchange exchange,int status,String text)throws Exception{byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);if(bytes.length>0)exchange.getResponseBody().write(bytes);exchange.close();}
            static void send(com.sun.net.httpserver.HttpExchange exchange,JSONObject request,JSONObject result)throws Exception{reply(exchange,200,new JSONObject().put("jsonrpc","2.0").put("id",request.get("id")).put("result",result).toString());}
            public void close(){gate.countDown();http.stop(0);executor.shutdownNow();}
          }
          static void setup()throws Exception{
            context=new Context();context.directory=Files.createTempDirectory("mcp-ui-data-").toFile();hub.store=new McpStore(new java.io.File(context.directory,"mcp"));hub.reads=0;hub.refreshes=0;hub.invalid=false;hub.gate=null;
            remote=new Remote();server=new McpServer("db","Database",remote.url(),"fixture-secret",true,5);hub.store.save(server);
            JSONObject schema=new JSONObject().put("type","object").put("required",new JSONArray().put("table")).put("properties",new JSONObject()
              .put("table",new JSONObject().put("type","string").put("description","Table to inspect"))
              .put("limit",new JSONObject().put("type","integer")).put("filter",new JSONObject().put("type","object")));
            hub.store.cacheTools(server,List.of(new McpToolInfo(new JSONObject().put("name","inspect").put("description","Inspect a table").put("inputSchema",schema))));
            remote.tools=new JSONArray().put(new JSONObject().put("name","inspect").put("description","Inspect a table").put("inputSchema",schema));ChatStore.diagnostics.clear();
            host=new Host();picker=new McpToolPicker(context,host);host.picker=picker;
          }
          static void loaded()throws Exception{picker.menuOpened();rows("");await(()->host.updates>0);}
          static void cleanup()throws Exception{picker.cancelUi();remote.close();Handler.drain();try(var files=Files.walk(context.directory.toPath())){for(Path p:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}}
          static LinearLayout panel(){return (LinearLayout)((ScrollView)AlertDialog.latest.view).child;}
          static EditText editor(){return (EditText)panel().children.stream().filter(v->v instanceof EditText).findFirst().get();}
          static void popupLayout(){
            View anchor=new View(context);SlashMenuPopup menu=new SlashMenuPopup(context,anchor);int[] clicked={0};
            menu.show(List.of(new SlashMenuPopup.Item("compact","",()->clicked[0]++)));PopupWindow popup=PopupWindow.last;View content=popup.content;
            var many=new ArrayList<SlashMenuPopup.Item>();for(int i=0;i<100;i++)many.add(new SlashMenuPopup.Item("tool"+i,"description",()->clicked[0]++));menu.show(many);
            check(popup.content==content&&popup.shows==1,"Visible content replaced instead of persistent scroll container");
            LinearLayout body=(LinearLayout)((ScrollView)content).child;check(body.children.size()==100,"Filtering did not update visible rows");body.children.get(0).click();check(clicked[0]==1,"Row callback lost");
            anchor.y=340;anchor.frameBottom=400;anchor.observer.fire();check(popup.y>=32&&popup.y+popup.height<=332&&popup.height<=300,"Menu overlaps keyboard/composer");
            check(popup.x>=8&&popup.x+popup.width<=392&&!popup.focusable&&!popup.outside,"Popup stole input focus or exceeded visible frame");
            menu.dismiss();check(anchor.observer.listeners.isEmpty(),"Dismiss leaked global layout listener");pass("fixedPopupContainerRefreshesAndFitsKeyboardViewport");
          }
          static void busySlash(){
            MainSlashFixture ui=new MainSlashFixture();ui.prompt.setText("/");ui.setBusy(true);check(ui.slashPopup.isShowing(),"Busy loop hid slash menu");
            ui.syncSlashPopup("/mcp inspect");check(ui.slashPopup.isShowing(),"MCP filtering rejected spaces");
            ui.onSend();check(ui.starts==0&&ui.commands==0&&ui.prompt.getText().toString().equals("/"),"Busy send changed current task or draft");
            ui.loop.busy=false;ui.setBusy(false);check(ui.slashPopup.isShowing(),"Completion did not refresh slash menu");
            ui.prompt.setText("Please use selected MCP tool");ui.onSend();check(ui.starts==1,"Tool draft was rejected as slash command");
            ui.sessionOpening=true;ui.syncSlashPopup("/");check(!ui.slashPopup.isShowing(),"Session transition exposed old menu");pass("busySlashRemainsVisibleAndDraftingNeverSubmitsOrCancels");
          }
          static void cachedFiltering()throws Exception{
            picker.menuOpened();check(rows("").get(0).title.contains("读取"),"Initial cache load did not yield to UI");await(()->host.updates>0);
            check(row(rows(""),"MCP · Database")!=null&&row(rows("inspect"),"inspect")!=null,"Real cached server/tool not shown");
            for(int i=0;i<30;i++)rows(i%2==0?"mcp insp":"Database");
            await(()->remote.lists.get()==1);check(hub.reads==1&&hub.refreshes==1,"Typing reread cache or repeated automatic refresh");
            check(rows("").stream().noneMatch(i->i.title.contains("刷新")||i.title.contains("取消")||i.detail.contains("已缓存")),"Manual refresh/status row remains");
            check(rows("missing").isEmpty(),"Filter ignored typed name");pass("cachedToolsAppearBeforeAutomaticRefreshAndFilteringDoesNotRepeatWork");
          }
          static void boundedRows()throws Exception{
            var tools=new ArrayList<McpToolInfo>();for(int i=0;i<125;i++)tools.add(new McpToolInfo(new JSONObject().put("name","tool"+i).put("inputSchema",new JSONObject().put("type","object"))));
            hub.store.cacheTools(server,tools);picker.reloadCatalog();int old=host.updates;picker.menuOpened();rows("");await(()->host.updates>old);
            var items=rows("");check(items.stream().filter(i->i.title.startsWith("tool")).count()==80&&items.stream().anyMatch(i->i.title.contains("45 个")),"Large cache created unbounded Views");
            pass("largeCatalogRendersBoundedToolRows");
          }
          static void parametersAndDraft()throws Exception{
            loaded();row(rows(""),"inspect").action.run();check(picker.editing()&&host.hides==1,"Tool selection did not open parameter dialog");
            check(panel().children.stream().filter(v->v instanceof TextView).map(v->((TextView)v).text).anyMatch(s->s.contains("table · 必填 · string")&&s.contains("Table to inspect")),"Parameter descriptions missing");
            String[] bad={"[]","{}","{\\\"table\\\":9}","{\\\"table\\\":\\\"orders\\\",\\\"limit\\\":1.5}","{} trailing"};
            for(String value:bad){editor().setText(value);AlertDialog.latest.positive.click();check(host.drafts==0&&picker.editing(),"Invalid JSON/schema accepted: "+value);}
            editor().setText("{\\\"table\\\":\\\"orders\\\",\\\"limit\\\":2,\\\"filter\\\":{\\\"active\\\":true}}");AlertDialog.latest.positive.click();
            McpSelection selected=picker.selection(host.text);check(selected!=null&&selected.toolName.equals("inspect")&&host.text.contains("orders")&&!picker.editing(),"Valid selection did not become editable draft");
            check(remote.calls.get()==0,"Picking tool executed a remote tool");String saved=picker.saveDraft();check(!saved.contains("fixture-secret")&&!saved.contains("http://"),"Rotation state exposed credentials");
            picker.cancelUi();check(picker.selection(host.text)!=null,"Configuration/lifecycle pause discarded draft selection");
            Host restored=new Host();McpToolPicker next=new McpToolPicker(context,restored);restored.picker=next;next.restoreDraft(saved);check(next.selection(restored.text)!=null,"Rotation dropped selected tool identity");
            restored.draft("ordinary request");check(next.selection(restored.text)==null&&next.saveDraft()==null,"Editing draft retained unrelated MCP selection");
            picker.reset();check(host.text.isEmpty()&&picker.saveDraft()==null,"Session change retained selected draft");pass("parameterValidationCreatesOnlyDraftAndSelectionLifecycleIsScoped");
          }
          static void invalidSelectionDiagnosed()throws Exception{
            loaded();row(rows(""),"inspect").action.run();hub.invalid=true;ChatStore.diagnostics.clear();editor().setText("{\\\"table\\\":\\\"orders\\\"}");AlertDialog.latest.positive.click();
            check(host.drafts==0&&picker.editing(),"Changed tool configuration accepted");await(()->!ChatStore.diagnostics.isEmpty());
            String detail=ChatStore.diagnostics.get(0);check(detail.contains("ui:mcp_picker")&&!detail.contains("fixture-secret"),"Failure diagnostics missing or credential leaked");
            check(!Toast.last.contains("fixture-secret"),"Detailed failure leaked into toast");pass("staleSelectionKeepsPanelAndRecordsRedactedDiagnostic");
          }
          static void staleCallbacks()throws Exception{
            hub.gate=new java.util.concurrent.CountDownLatch(1);picker.menuOpened();rows("");await(()->hub.reads==1);picker.cancelUi();host.epoch++;hub.gate.countDown();
            Thread.sleep(30);Handler.drain();check(host.updates==0,"Old cache callback refreshed new session");
            hub.gate=null;picker.reloadCatalog();loaded();SlashMenuPopup.Item old=row(rows(""),"inspect");host.epoch++;old.action.run();check(!picker.editing(),"Old menu row opened in new session");
            pass("cancelledCacheCallbacksAndOldRowsCannotAffectNewSession");
          }
          static void readAndRefreshFailures()throws Exception{
            ChatStore.diagnostics.clear();remote.fail=true;remote.gate.countDown();loaded();
            await(()->!ChatStore.diagnostics.isEmpty());check(host.text.equals("/"),"Automatic refresh changed draft");
            check(rows("").stream().noneMatch(i->i.detail.contains("失败")||i.title.contains("刷新")),"Refresh error/status persisted in menu");
            check(row(rows(""),"inspect")!=null,"Failed refresh discarded usable cached tools");
            picker.reloadCatalog();Files.writeString(context.directory.toPath().resolve("mcp/connections.json"),"broken");int previous=host.updates;picker.menuOpened();rows("");await(()->host.updates>previous);await(()->ChatStore.diagnostics.size()>=2);
            check(rows("").stream().noneMatch(i->i.title.contains("重新读取")),"Manual cache retry action survived automatic lifecycle");
            pass("automaticRefreshAndCacheFailuresToastPrivatelyWithoutChangingDraft");
          }
          static MainSlashFixture mainMenu(){MainSlashFixture ui=new MainSlashFixture();ui.mcpToolPicker=picker;ui.prompt.setText("/");host.ui=ui;return ui;}
          static void automaticOpenRound()throws Exception{
            for(int i=1;i<4;i++)hub.store.save(new McpServer("server"+i,"Server"+i,remote.url(),"fixture-secret",true,5));
            hub.store.save(new McpServer("disabled","Disabled",remote.url(),"fixture-secret",false,5));
            MainSlashFixture ui=mainMenu();ui.syncSlashPopup("/");await(()->remote.lists.get()==2);
            check(hub.refreshes==2&&remote.peak.get()==2,"Refresh concurrency is not bounded at two");
            for(int i=0;i<30;i++){ui.syncSlashPopup(i%2==0?"/mcp inspect":"/");host.refreshMenu();}
            check(hub.refreshes==2&&hub.reads==1,"Keyboard/menu redraw started another round");
            remote.tools=new JSONArray().put(new JSONObject().put("name","fresh").put("inputSchema",new JSONObject().put("type","object")));remote.gate.countDown();
            await(()->remote.lists.get()==4&&rows("fresh").stream().anyMatch(i->i.title.equals("fresh")));check(hub.refreshes==4&&remote.peak.get()<=2,"Enabled servers were skipped/disabled server refreshed");
            check(row(rows("fresh"),"fresh")!=null,"Automatic list result never replaced cached menu");
            ui.hideSlashPopup();int updates=host.updates;remote.gate=new java.util.concurrent.CountDownLatch(1);ui.syncSlashPopup("/");
            check(row(rows("fresh"),"fresh")!=null,"Reopen discarded immediately usable memory cache");
            await(()->host.updates>updates&&remote.lists.get()==6);check(hub.refreshes==6,"Second real opening did not refresh anew");
            ui.hideSlashPopup();int count=host.updates;remote.gate.countDown();Thread.sleep(60);Handler.drain();
            check(host.updates==count&&remote.lists.get()==6,"Closed menu published stale result or started queued servers");
            pass("eachActualPopupOpeningRefreshesAllEnabledServersOnceWithTwoClients");
          }
          static void cancellingMenuPreservesModelClient()throws Exception{
            remote.gate.countDown();McpClient model=new McpClient(server);try{
              model.discover();remote.gate=new java.util.concurrent.CountDownLatch(1);MainSlashFixture ui=mainMenu();ui.syncSlashPopup("/");await(()->remote.lists.get()==2);
              ui.hideSlashPopup();model.call("inspect",new JSONObject());check(remote.calls.get()==1,"Menu close affected active model client");
              await(()->remote.deleted.size()>0);check(!remote.deleted.contains("ui-session-1"),"Menu cancellation deleted the model session");
              int updates=host.updates;remote.gate.countDown();Thread.sleep(40);Handler.drain();check(host.updates==updates,"Cancelled network response repainted closed popup");
            }finally{model.close();}
            pass("closingPopupCancelsOnlyIndependentRefreshAndRejectsLateResults");
          }
          static void lifecycleAndGeometryCancellation()throws Exception{
            MainSlashFixture ui=mainMenu();ui.syncSlashPopup("/");await(()->remote.lists.get()==1);
            SlashMenuPopup.Item stale=row(rows(""),"inspect");int updates=host.updates;
            ui.prompt.y=28;ui.prompt.observer.fire();check(!ui.slashPopup.isShowing(),"Insufficient keyboard space did not dismiss menu");
            stale.action.run();check(!picker.editing(),"A dismissed row opened an old parameter panel");
            remote.gate.countDown();Thread.sleep(50);Handler.drain();check(host.updates==updates,"Geometry dismissal kept its refresh callback alive");
            for(boolean switchSession:new boolean[]{false,true}){
              int lists=remote.lists.get();ui.prompt.y=600;remote.gate=new java.util.concurrent.CountDownLatch(1);ui.syncSlashPopup("/");await(()->remote.lists.get()==lists+1);
              updates=host.updates;if(switchSession){host.epoch++;picker.reset();}else picker.cancelUi();
              remote.gate.countDown();Thread.sleep(50);Handler.drain();check(host.updates==updates,"Pause/session reset accepted old refresh results");ui.hideSlashPopup();
            }
            int requests=hub.refreshes;ui.slashResumed=false;ui.setBusy(false);Handler.drain();
            check(!ui.slashPopup.isShowing()&&hub.refreshes==requests,"Paused activity was reopened by the running loop completion");
            check(remote.calls.get()==0,"Lifecycle cleanup executed an MCP tool");pass("viewportDismissPauseAndSessionResetCancelTheirAutomaticRefresh");
          }
          public static void run()throws Exception{
            setup();try{popupLayout();busySlash();cachedFiltering();}finally{cleanup();}
            setup();try{boundedRows();}finally{cleanup();}setup();try{parametersAndDraft();}finally{cleanup();}
            setup();try{invalidSelectionDiagnosed();}finally{cleanup();}setup();try{staleCallbacks();}finally{cleanup();}
            setup();try{readAndRefreshFailures();}finally{cleanup();}
            setup();try{automaticOpenRound();}finally{cleanup();}
            setup();try{cancellingMenuPreservesModelClient();}finally{cleanup();}
            setup();try{lifecycleAndGeometryCancellation();}finally{cleanup();}
            System.out.println("Passed "+passed+" slash menu UI regression tests");
          }
        }
        """;}
}
