import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.tools.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Optional API-JAR compile check; AppCompat stubs validate types, not runtime rendering. */
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
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]), api = Paths.get(args[1]), json = Paths.get(args[2]);
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
        Path output = Files.createTempDirectory("backcast-android-compile-");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            files.getJavaFileObjectsFromPaths(production).forEach(sources::add);
            boolean ok = compiler.getTask(null, files, null, List.of("-proc:none", "-encoding", "UTF-8",
                    "-source", "7", "-target", "7", "-Xlint:-options", "-classpath",
                    api + java.io.File.pathSeparator + json, "-d", output.toString()), null, sources).call();
            if (!ok) throw new AssertionError("Android source compile failed");
            System.out.println("PASS Java7/API30: " + production.size() + " production files; XML/resources: " + count
                    + ". AppCompat type stubs, not an APK build.");
        } finally {
            try (var walk = Files.walk(output)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
