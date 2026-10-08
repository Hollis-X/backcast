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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Exercises actual startup and verifies Android's native multidex build wiring. */
public final class MultiDexConfigRegressionTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class Source extends SimpleJavaFileObject {
        final String body;
        Source(String body) { this("GlobalApplication", body); }
        Source(String name, String body) { super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE); this.body = body; }
        @Override public CharSequence getCharContent(boolean ignored) { return body; }
    }
    private static URLClassLoader compile(Path root, Path output) throws Exception {
        Map<String,String> methods = new HashMap<>();
        try (StandardJavaFileManager manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            JavacTask parse = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, manager, null,
                    List.of("-proc:none"), null, manager.getJavaFileObjects(root.resolve(
                    "app/src/main/java/com/mkei/backcast/GlobalApplication.java").toFile()));
            for (CompilationUnitTree unit : parse.parse()) for (Tree declaration : unit.getTypeDecls()) if (declaration instanceof ClassTree) {
                for (Tree member : ((ClassTree) declaration).getMembers()) if (member instanceof MethodTree) {
                    methods.put(((MethodTree) member).getName().toString(), member.toString());
                }
            }
        }
        check(!methods.containsKey("attachBaseContext") && methods.containsKey("onCreate"), "Android 8 startup retained an unnecessary legacy multidex hook");
        String source = "import java.util.*;class Context{}class Application extends Context {"
                + "public void onCreate(){GlobalApplication.events.add(\"super-create\");}}"
                + "public class GlobalApplication extends Application {public static final List<String> events=new ArrayList<String>();"
                + "public static final java.util.concurrent.CountDownLatch exitEvidence=new java.util.concurrent.CountDownLatch(1);"
                + "public static volatile Thread exitThread;public static volatile Object exitContext;"
                + "static class AgentService{static void recordPreviousExit(Context c){exitThread=Thread.currentThread();exitContext=c;exitEvidence.countDown();}}"
                + "static class CrashHandler{static CrashHandler getInstance(){return new CrashHandler();}"
                + "void registerGlobal(Context c){events.add(\"global-crash\");}"
                + "void registerPart(Context c){events.add(\"part-crash\");}}"
                + methods.get("onCreate")
                + "public void start(){onCreate();}}";
        try (StandardJavaFileManager manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            check(ToolProvider.getSystemJavaCompiler().getTask(null, manager, null, Arrays.asList("-proc:none", "-source", "7", "-target", "7",
                    "-Xlint:-options", "-d", output.toString()), null, List.of(new Source(source),
                    new Source("com.mkei.backcast.agent.NetworkRouting", "package com.mkei.backcast.agent; public class NetworkRouting {public static Object provider; public static void install(Object value){provider=value;}}"),
                    new Source("com.mkei.backcast.net.DeviceNetworks", "package com.mkei.backcast.net; public class DeviceNetworks {public final Object context; public DeviceNetworks(Object value){context=value;}}"))).call(), "Startup fixture did not compile as Java 7");
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, null);
    }
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]), temporary = Files.createTempDirectory("backcast-multidex-startup-");
        try (URLClassLoader loader = compile(root, temporary)) {
            Class<?> application = loader.loadClass("GlobalApplication");
            Object instance = application.getConstructor().newInstance(); application.getMethod("start").invoke(instance);
            check(application.getField("events").get(null).equals(Arrays.asList("super-create", "global-crash", "part-crash")),
                    "Application/crash runtime startup order changed");
            java.util.concurrent.CountDownLatch exitEvidence = (java.util.concurrent.CountDownLatch)
                    application.getField("exitEvidence").get(null);
            check(exitEvidence.await(2, java.util.concurrent.TimeUnit.SECONDS)
                    && application.getField("exitThread").get(null) != Thread.currentThread()
                    && application.getField("exitContext").get(null) == instance,
                    "Previous process exit lookup must run outside the application main thread");
            Object route = loader.loadClass("com.mkei.backcast.agent.NetworkRouting").getField("provider").get(null);
            check(route != null && route.getClass().getField("context").get(route) == instance, "Application omitted device network routing installation");
            System.out.println("PASS actual application startup installs network routing and crash runtime without a legacy multidex hook");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Element manifest = factory.newDocumentBuilder().parse(root.resolve("app/src/main/AndroidManifest.xml").toFile()).getDocumentElement();
            Element configured = (Element) manifest.getElementsByTagName("application").item(0);
            String className = configured.getAttributeNS("http://schemas.android.com/apk/res/android", "name");
            check(className.equals(".GlobalApplication") || className.equals("com.mkei.backcast.GlobalApplication"), "Manifest bypasses the multidex startup application");
            System.out.println("PASS manifest selects the application startup entry point");
            String gradle = Files.readString(root.resolve("app/build.gradle"));
            check(java.util.regex.Pattern.compile("(?m)^\\s*multiDexEnabled\\s+true\\s*$").matcher(gradle).find(), "DEX build does not enable multidex");
            check(java.util.regex.Pattern.compile("(?m)^\\s*minSdkVersion\\s+26\\s*$").matcher(gradle).find(), "Official SDK needs Android 8 Java 8 runtime APIs");
            check(!gradle.contains("androidx.multidex"), "Android 8 build retains a redundant legacy multidex runtime");
            check(gradle.contains("sourceCompatibility JavaVersion.VERSION_1_8")
                    && gradle.contains("targetCompatibility JavaVersion.VERSION_1_8"), "Official SDK build has not upgraded to Java 8");
            System.out.println("PASS Android 8 / Java 8 build enables native multidex without its legacy runtime");
            System.out.println("3 multidex startup/configuration tests passed");
        } finally { try (var walk = Files.walk(temporary)) { for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); } }
    }
}
