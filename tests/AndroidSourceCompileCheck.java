import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.tools.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Optional javac/ECJ/D8 checks; AppCompat stubs validate types and bytecode, not APK packaging. */
public final class AndroidSourceCompileCheck {
    private static final Map<String, Set<String>> resources = new TreeMap<>();
    private static final class Source extends SimpleJavaFileObject {
        final String body;
        Source(String name, String body) {
            super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE); this.body = body;
        }
        @Override public CharSequence getCharContent(boolean ignore) { return body; }
    }
    private static void resource(String type, String name) {
        resources.computeIfAbsent(type, k -> new TreeSet<>()).add(name.replace('.', '_'));
    }
    private static void verify(String type, String name, Path file) {
        if (!resources.getOrDefault(type, Collections.emptySet()).contains(name.replace('.', '_')))
            throw new AssertionError("Missing resource " + type + "/" + name + " in " + file);
    }
    private static List<String> javaCommand() {
        return new ArrayList<>(List.of(Paths.get(System.getProperty("java.home"), "bin", "java").toString()));
    }
    private static void execute(List<String> command, Path log, String label) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError(label + " exceeded 180 seconds\n" + Files.readString(log));
            }
            if (process.exitValue() != 0) {
                throw new AssertionError(label + " failed (exit " + process.exitValue() + ")\n" + Files.readString(log));
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }
    private static String sdkClasspath(Path root) throws Exception {
        String value = System.getenv("BACKCAST_SDK_CLASSPATH");
        if (value == null || value.isBlank()) {
            Path exported = root.resolve("app/build/agent-test-classpath.txt");
            if (!Files.isRegularFile(exported)) throw new AssertionError("Run Gradle :app:writeAgentTestClasspath first, or set BACKCAST_SDK_CLASSPATH");
            value = Files.readString(exported).trim();
        }
        boolean sdk = false;
        for (String entry : value.split(Pattern.quote(java.io.File.pathSeparator))) {
            Path artifact = Paths.get(entry);
            if (!Files.isRegularFile(artifact)) throw new AssertionError("Missing official SDK dependency: " + artifact);
            try (var archive = new java.util.zip.ZipFile(artifact.toFile())) {
                sdk |= archive.getEntry("com/openai/client/OpenAIClient.class") != null;
            }
        }
        if (!sdk) throw new AssertionError("Runtime classpath does not include the official OpenAI SDK");
        return value;
    }
    private static void dex(Path classes, Path output, Path api, String sdk, Path d8, Path log, String compiler) throws Exception {
        Files.createDirectories(output);
        List<Path> inputs;
        try (var walk = Files.walk(classes)) {
            inputs = walk.filter(file -> file.toString().endsWith(".class")).sorted().toList();
        }
        if (inputs.isEmpty()) throw new AssertionError(compiler + " produced no class files");
        for (Path file : inputs) {
            try (var input = new java.io.DataInputStream(Files.newInputStream(file))) {
                if (input.readInt() != 0xcafebabe) throw new AssertionError("Invalid class file: " + file);
                input.readUnsignedShort();
                int major = input.readUnsignedShort();
                if (major != 52) throw new AssertionError(compiler + " did not target Java 8: " + file + " major=" + major);
            }
        }
        for (String mode : List.of("debug", "release")) {
            Path destination = Files.createDirectory(output.resolve(mode));
            List<String> command = javaCommand();
            command.addAll(List.of("-cp", d8.toString(), "com.android.tools.r8.D8", "--" + mode, "--min-api", "26",
                    "--lib", api.toString(), "--output", destination.toString()));
            for (String entry : sdk.split(Pattern.quote(java.io.File.pathSeparator))) command.addAll(List.of("--classpath", entry));
            for (Path file : inputs) command.add(file.toString());
            execute(command, log.resolveSibling(log.getFileName() + "." + mode), compiler + " bytecode to D8/" + mode + "/min-api26");
            Path primary = destination.resolve("classes.dex");
            if (!Files.isRegularFile(primary) || Files.size(primary) < 112) throw new AssertionError("D8 produced no valid primary DEX");
            byte[] magic = new byte[8];
            try (var input = Files.newInputStream(primary)) {
                if (input.read(magic) != magic.length || !new String(magic, java.nio.charset.StandardCharsets.US_ASCII).startsWith("dex\n"))
                    throw new AssertionError("D8 output has no DEX header");
            }
            System.out.println("PASS " + compiler + " -> D8/" + mode + "/min-api26: " + inputs.size() + " Java8 classes; " + Files.size(primary)
                    + " bytes. AppCompat type stubs; dependencies and APK packaging are not covered.");
        }
    }
    private static void ecj(List<JavaFileObject> sources, Path workspace, Path classes, Path api, Path json, String sdk, Path compiler) throws Exception {
        Files.createDirectories(classes);
        List<String> command = javaCommand();
        command.addAll(List.of("-jar", compiler.toString(), "-proc:none", "-encoding", "UTF-8", "-source", "8",
                "-target", "8", "-nowarn", "-bootclasspath", api.toString(), "-classpath", json + java.io.File.pathSeparator + sdk, "-d", classes.toString()));
        for (JavaFileObject source : sources) {
            if (source instanceof Source) {
                Path file = workspace.resolve("generated-sources").resolve(source.toUri().getPath().substring(1));
                Files.createDirectories(file.getParent());
                Files.writeString(file, ((Source) source).body, java.nio.charset.StandardCharsets.UTF_8);
                command.add(file.toString());
            } else command.add(Paths.get(source.toUri()).toString());
        }
        execute(command, workspace.resolve("ecj.log"), "ECJ Java8/API30 compilation");
    }
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]), api = Paths.get(args[1]), json = Paths.get(args[2]);
        String sdk = sdkClasspath(root);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder parser = factory.newDocumentBuilder();
        List<Path> xml = new ArrayList<>(), production = new ArrayList<>();
        int count;
        try (var walk = Files.walk(root.resolve("app/src/main/res"))) {
            List<Path> files = walk.filter(Files::isRegularFile).toList(); count = files.size();
            for (Path file : files) {
                String type = file.getParent().getFileName().toString().split("-")[0];
                String name = file.getFileName().toString();
                if (name.endsWith(".xml")) xml.add(file);
                if (!type.equals("values")) resource(type, name.substring(0, name.indexOf('.')));
                else if (name.endsWith(".xml")) {
                    NodeList nodes = parser.parse(file.toFile()).getDocumentElement().getChildNodes();
                    for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element) {
                        Element item = (Element) nodes.item(i); String kind = item.getTagName();
                        if (kind.endsWith("-array")) kind = "array";
                        if (kind.equals("item")) kind = item.getAttribute("type");
                        if (!item.getAttribute("name").isEmpty()) resource(kind, item.getAttribute("name"));
                    }
                }
            }
        }
        for (Path file : xml) {
            parser.parse(file.toFile());
            Matcher ids = Pattern.compile("@\\+id/(\\w+)").matcher(Files.readString(file));
            while (ids.find()) resource("id", ids.group(1));
        }
        parser.parse(root.resolve("app/src/main/AndroidManifest.xml").toFile());
        try (var walk = Files.walk(root.resolve("app/src/main/java"))) {
            production.addAll(walk.filter(p -> p.toString().endsWith(".java")).toList());
        }
        for (Path file : production) {
            Matcher refs = Pattern.compile("(?<![A-Za-z0-9_.])R\\.([a-z]+)\\.(\\w+)").matcher(Files.readString(file));
            while (refs.find()) verify(refs.group(1), refs.group(2), file);
        }
        for (Path file : xml) {
            Matcher refs = Pattern.compile("@(?!android:)([a-z]+)/([A-Za-z0-9_.]+)").matcher(Files.readString(file));
            while (refs.find()) verify(refs.group(1), refs.group(2), file);
        }
        List<JavaFileObject> sources = new ArrayList<>();
        StringBuilder r = new StringBuilder("package com.mkei.backcast; public final class R {"); int value = 1;
        for (var entry : resources.entrySet()) {
            r.append("public static final class ").append(entry.getKey()).append("{");
            for (String name : entry.getValue()) r.append("public static final int ").append(name).append('=').append(value++).append(';');
            r.append('}');
        }
        sources.add(new Source("com.mkei.backcast.R", r.append('}').toString()));
        sources.add(new Source("androidx.multidex.MultiDex",
                "package androidx.multidex; public final class MultiDex { public static void install(android.content.Context context){} }"));
        sources.add(new Source("androidx.appcompat.app.AppCompatActivity",
                "package androidx.appcompat.app; public class AppCompatActivity extends android.app.Activity { public void setSupportActionBar(androidx.appcompat.widget.Toolbar t){} }"));
        sources.add(new Source("androidx.appcompat.widget.Toolbar",
                "package androidx.appcompat.widget; public class Toolbar extends android.view.ViewGroup { public Toolbar(android.content.Context c){super(c);} protected void onLayout(boolean changed,int l,int t,int r,int b){} public void setTitle(int title){} public void setNavigationIcon(android.graphics.drawable.Drawable d){} public void setNavigationOnClickListener(android.view.View.OnClickListener l){} }"));
        sources.add(new Source("androidx.appcompat.app.AlertDialog",
                "package androidx.appcompat.app; public class AlertDialog extends android.app.AlertDialog { protected AlertDialog(android.content.Context c){super(c);}"
                + "public static class Builder { public Builder(android.content.Context c){}"
                + "public Builder setTitle(CharSequence s){return this;} public Builder setTitle(int s){return this;}"
                + "public Builder setMessage(CharSequence s){return this;} public Builder setMessage(int s){return this;}"
                + "public Builder setView(android.view.View v){return this;}"
                + "public Builder setItems(CharSequence[] a,android.content.DialogInterface.OnClickListener l){return this;}"
                + "public Builder setPositiveButton(int s,android.content.DialogInterface.OnClickListener l){return this;}"
                + "public Builder setPositiveButton(CharSequence s,android.content.DialogInterface.OnClickListener l){return this;}"
                + "public Builder setNegativeButton(int s,android.content.DialogInterface.OnClickListener l){return this;}"
                + "public Builder setNegativeButton(CharSequence s,android.content.DialogInterface.OnClickListener l){return this;}"
                + "public Builder setNeutralButton(int s,android.content.DialogInterface.OnClickListener l){return this;}"
                + "public Builder setNeutralButton(CharSequence s,android.content.DialogInterface.OnClickListener l){return this;}"
                + "public Builder setSingleChoiceItems(CharSequence[] a,int n,android.content.DialogInterface.OnClickListener l){return this;}"
                + "public Builder setOnDismissListener(android.content.DialogInterface.OnDismissListener l){return this;}"
                + "public AlertDialog create(){return null;} public AlertDialog show(){return null;} } }"));
        Path workspace = Files.createTempDirectory("backcast-android-compile-");
        Path output = Files.createDirectory(workspace.resolve("javac-classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            files.getJavaFileObjectsFromPaths(production).forEach(sources::add);
            boolean ok = compiler.getTask(null, files, null, List.of("-proc:none", "-encoding", "UTF-8",
                    "-source", "8", "-target", "8", "-Xlint:-options", "-classpath",
                    api + java.io.File.pathSeparator + json + java.io.File.pathSeparator + sdk, "-d", output.toString()), null, sources).call();
            if (!ok) throw new AssertionError("Android source compile failed");
            System.out.println("PASS Java8/API30 with official SDK JARs: " + production.size() + " production files; XML/resources: " + count
                    + ". AppCompat type stubs, not an APK build.");
            if (args.length >= 4) {
                Path d8 = Paths.get(args[3]);
                dex(output, workspace.resolve("javac-dex"), api, sdk, d8, workspace.resolve("javac-d8.log"), "javac");
                if (args.length >= 5) {
                    Path ecjClasses = workspace.resolve("ecj-classes");
                    ecj(sources, workspace, ecjClasses, api, json, sdk, Paths.get(args[4]));
                    dex(ecjClasses, workspace.resolve("ecj-dex"), api, sdk, d8, workspace.resolve("ecj-d8.log"), "ECJ");
                }
            }
        } finally {
            try (var walk = Files.walk(workspace)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
