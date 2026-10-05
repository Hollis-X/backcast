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
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Runs the actual editor callback/cancellation methods with delayed network replies. */
public final class McpConfigUiRegressionTest {
    private static final class Source extends SimpleJavaFileObject {
        final String text;
        Source(String text) { super(URI.create("string:///Editor.java"), Kind.SOURCE); this.text = text; }
        @Override public CharSequence getCharContent(boolean ignored) { return text; }
    }
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]), output = Files.createTempDirectory("backcast-mcp-ui-");
        Set<String> required = new HashSet<String>(Arrays.asList("discover", "draft", "current", "sameConnection", "cancelProbe", "invalidateProbe"));
        StringBuilder methods = new StringBuilder();
        try {
            try (StandardJavaFileManager fm = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
                JavacTask parser = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, fm, null,
                        Arrays.asList("-proc:none"), null, fm.getJavaFileObjects(root.resolve(
                        "app/src/main/java/com/mkei/backcast/McpConfigActivity.java").toFile()));
                for (CompilationUnitTree unit : parser.parse()) for (Tree type : unit.getTypeDecls())
                    if (type instanceof ClassTree) for (Tree member : ((ClassTree) type).getMembers())
                        if (member instanceof MethodTree && required.remove(((MethodTree) member).getName().toString()))
                            methods.append(member.toString());
            }
            if (!required.isEmpty()) throw new AssertionError("Missing production editor methods " + required);
            String fixture = "import java.util.*;import java.util.concurrent.*;public class Editor {"
                    + "static class View{static final int GONE=8,VISIBLE=0;}static class TextView{String text=\"\";void setText(CharSequence s){text=s.toString();}String getText(){return text;}}"
                    + "static class EditText extends TextView{}static class Button{boolean enabled=true;int visibility;void setEnabled(boolean b){enabled=b;}void setVisibility(int i){visibility=i;}}"
                    + "static class ProgressBar extends Button{}static class CheckBox{boolean checked=true;boolean isChecked(){return checked;}}"
                    + "static class Handler{final BlockingQueue<Runnable> queue=new LinkedBlockingQueue<>();void post(Runnable r){queue.add(r);}void flush()throws Exception{Runnable r=queue.poll(2,TimeUnit.SECONDS);check(r!=null,\"callback missing\");r.run();}}"
                    + "static class Toast{static final int LENGTH_SHORT=0;static Toast makeText(Editor e,String s,int i){return new Toast();}void show(){}}"
                    + "static class McpServer{String id,name,endpoint,bearerToken;boolean enabled;int timeoutSeconds;McpServer(String i,String n,String u,String t,boolean e,int s){id=i;name=n;endpoint=u;bearerToken=t;enabled=e;timeoutSeconds=s;}}"
                    + "static class McpToolInfo{String name=\"remote_read\",description=\"description\";}"
                    + "static class McpStore{McpServer saved;int writes;List<McpServer> servers(){return saved==null?Collections.emptyList():Arrays.asList(saved);}boolean cacheTools(McpServer s,List<McpToolInfo> t){if(saved==null||!sameConnection(saved,s))return false;writes++;return true;}}"
                    + "static class RunHub{static final RunHub instance=new RunHub();int retargets;static RunHub get(Editor e){return instance;}void retargetTools(){retargets++;}}"
                    + "static class McpClient{static volatile McpClient latest;final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);boolean closed;McpClient(McpServer s){latest=this;}"
                    + "List<McpToolInfo> discover()throws Exception{started.countDown();release.await(2,TimeUnit.SECONDS);return Arrays.asList(new McpToolInfo());}void close(){closed=true;}}"
                    + "final Handler main=new Handler();McpStore store=new McpStore();String id=\"server\";EditText name=new EditText(),endpoint=new EditText(),token=new EditText(),timeout=new EditText();"
                    + "CheckBox enabled=new CheckBox();TextView status=new TextView(),tools=new TextView();Button probe=new Button(),cancel=new Button();ProgressBar progress=new ProgressBar();"
                    + "McpClient probing;int generation,errors;boolean destroyed;void report(Exception e,String m){errors++;}"
                    + "Editor(){name.text=\"server\";endpoint.text=\"http://server/mcp\";timeout.text=\"60\";}"
                    + "static void check(boolean c,String m){if(!c)throw new AssertionError(m);}McpClient start()throws Exception{discover();McpClient c=McpClient.latest;check(c.started.await(2,TimeUnit.SECONDS),\"probe not started\");return c;}"
                    + "void reply(McpClient c)throws Exception{c.release.countDown();main.flush();}"
                    + methods
                    + "public static void runTests()throws Exception{"
                    + "Editor e=new Editor();McpClient c=e.start();e.cancelProbe();e.reply(c);check(c.closed&&e.probing==null&&e.probe.enabled&&e.store.writes==0&&!e.status.text.contains(\"连接成功\"),\"cancel allowed a late reply\");System.out.println(\"PASS cancelledProbeDropsLateSuccess\");"
                    + "e=new Editor();c=e.start();e.endpoint.text=\"http://changed/mcp\";e.invalidateProbe();e.reply(c);check(e.tools.text.contains(\"重新检测\")&&e.store.writes==0,\"edited connection displayed old tools\");System.out.println(\"PASS editingAConnectionInvalidatesItsOldProbe\");"
                    + "e=new Editor();c=e.start();e.reply(c);check(e.status.text.contains(\"连接成功\")&&e.tools.text.contains(\"remote_read\")&&e.store.writes==0,\"probe silently saved a draft\");System.out.println(\"PASS unsavedDraftProbeShowsToolsWithoutSaving\");"
                    + "e=new Editor();e.store.saved=e.draft();c=e.start();e.store.saved=new McpServer(\"server\",\"server\",\"http://changed/mcp\",\"\",true,60);e.reply(c);check(e.store.writes==0,\"stale reply overwrote concurrent configuration\");System.out.println(\"PASS changedSavedIdentityRejectsProbeCache\");"
                    + "e=new Editor();e.store.saved=e.draft();c=e.start();e.reply(c);check(e.store.writes==1&&e.probing==null&&e.cancel.visibility==8&&e.progress.visibility==8&&e.errors==0,\"accepted reply failed to cache or end progress\");System.out.println(\"PASS acceptedProbeCachesOnlyItsCurrentSavedConnection\");"
                    + "System.out.println(\"5 MCP configuration UI tests passed\");}}";
            try (StandardJavaFileManager fm = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
                boolean compiled = ToolProvider.getSystemJavaCompiler().getTask(null, fm, null,
                        Arrays.asList("-proc:none", "-source", "8", "-target", "8", "-Xlint:-options", "-d", output.toString()),
                        null, Arrays.asList(new Source(fixture))).call();
                if (!compiled) throw new AssertionError("Production MCP UI callback compilation failed");
            }
            try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, null)) {
                loader.loadClass("Editor").getMethod("runTests").invoke(null);
            }
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }
}
