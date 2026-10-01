import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.tools.*;

/** Reproduce AIDE's 19 full generated R copies crossing the DEX field limit. */
public final class AideDexFieldLimitRegressionTest {
    private static final class Source extends SimpleJavaFileObject {
        final String body;
        Source(String name, String body) { super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE); this.body = body; }
        @Override public CharSequence getCharContent(boolean ignore) { return body; }
    }
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
    private static String fields(String name, int count) {
        StringBuilder body = new StringBuilder("package ").append(name).append("; public class R {");
        for (int i = 0; i < count; i++) body.append("public static int field_").append(i).append(';');
        return body.append('}').toString();
    }
    private static String output(Process process) throws Exception {
        String value = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        process.waitFor(); return value;
    }
    private static List<String> command(Path d8, String main) {
        return new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java", "-cp", d8.toString(), main));
    }
    private static Set<String> classNames(byte[] dex) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(dex).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        Set<String> names = new HashSet<>();
        int count = buffer.getInt(96), offset = buffer.getInt(100), types = buffer.getInt(68), strings = buffer.getInt(60);
        for (int i = 0; i < count; i++) {
            int type = buffer.getInt(offset + 32 * i), string = buffer.getInt(types + 4 * type), position = buffer.getInt(strings + 4 * string);
            while ((dex[position++] & 128) != 0) { }
            int end = position; while (dex[end] != 0) end++;
            names.add(new String(dex, position, end - position, java.nio.charset.StandardCharsets.UTF_8));
        }
        return names;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: AideDexFieldLimitRegressionTest.java <repository> <D8-JAR> <Android-API-JAR> <official-multidex-AAR>");
        Path repo = Paths.get(args[0]), d8 = Paths.get(args[1]), api = Paths.get(args[2]), multidex = Paths.get(args[3]);
        Path work = Files.createTempDirectory("backcast-aide-field-limit-");
        try {
            Path runtime = work.resolve("multidex.jar");
            try (ZipFile aar = new ZipFile(multidex.toFile()); InputStream classes = aar.getInputStream(aar.getEntry("classes.jar"))) { Files.copy(classes, runtime); }
            List<JavaFileObject> sources = new ArrayList<>();
            // Actual evidence contains 19 resource packages, each with 3,349
            // generated fields. The dependency fixture adds 10,000 distinct
            // fields without depending on private device build files.
            for (int i = 0; i < 19; i++) sources.add(new Source("fixture.resources" + i + ".R", fields("fixture.resources" + i, 3349)));
            sources.add(new Source("fixture.dependencies.R", fields("fixture.dependencies", 10000)));
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            Path classes = work.resolve("classes"); Files.createDirectory(classes);
            try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
                manager.getJavaFileObjects(repo.resolve("app/src/main/java/com/mkei/backcast/GlobalApplication.java").toFile()).forEach(sources::add);
                check(compiler.getTask(null, manager, null, List.of("-proc:none", "-source", "7", "-target", "7", "-Xlint:-options",
                        "-classpath", api + File.pathSeparator + runtime, "-d", classes.toString()), null, sources).call(), "Resource/bootstrap fixture did not compile");
            }
            Path application = work.resolve("fixture.jar");
            try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(application)); var files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    jar.putNextEntry(new ZipEntry(classes.relativize(file).toString().replace(File.separatorChar, '/'))); Files.copy(file, jar); jar.closeEntry();
                }
            }
            Path mono = work.resolve("mono"); Files.createDirectory(mono);
            List<String> single = command(d8, "com.android.tools.r8.D8");
            single.addAll(List.of("--debug", "--min-api", "16", "--lib", api.toString(), "--output", mono.toString(), application.toString(), runtime.toString()));
            Process failed = new ProcessBuilder(single).redirectErrorStream(true).start(); String error = output(failed);
            check(failed.exitValue() != 0 && error.contains("# fields:") && error.contains("65536"), "Fixture did not reproduce the field overflow:\n" + error);
            System.out.println("PASS AIDE generated-R single DEX field overflow reproduced");

            Path rules = work.resolve("main-dex.pro"), list = work.resolve("main-dex-list.txt"), multiple = work.resolve("multiple");
            Files.createDirectory(multiple);
            Files.writeString(rules, "-keep class com.mkei.backcast.GlobalApplication { *; }\n-keep class androidx.multidex.** { *; }\n");
            List<String> generate = command(d8, "com.android.tools.r8.GenerateMainDexList");
            generate.addAll(List.of("--lib", api.toString(), "--main-dex-rules", rules.toString(), "--main-dex-list-output", list.toString(), application.toString(), runtime.toString()));
            Process generator = new ProcessBuilder(generate).redirectErrorStream(true).start(); String generated = output(generator);
            check(generator.exitValue() == 0, "Legacy boot main-dex generation failed:\n" + generated);
            List<String> split = command(d8, "com.android.tools.r8.D8");
            split.addAll(List.of("--debug", "--min-api", "16", "--lib", api.toString(), "--main-dex-list", list.toString(), "--output", multiple.toString(), application.toString(), runtime.toString()));
            Process success = new ProcessBuilder(split).redirectErrorStream(true).start(); String diagnostics = output(success);
            check(success.exitValue() == 0, "Legacy multidex compilation failed:\n" + diagnostics);
            List<Path> dex;
            try (var stream = Files.list(multiple)) { dex = stream.filter(p -> p.toString().endsWith(".dex")).sorted().toList(); }
            check(dex.size() > 1, "Field overflow was not split into multiple DEX files");
            for (Path file : dex) {
                byte[] code = Files.readAllBytes(file); java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(code).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                check(header.getInt(80) <= 65536 && header.getInt(88) <= 65536, "DEX still exceeds field or method reference limits");
                System.out.println(file.getFileName() + " fields=" + header.getInt(80) + " methods=" + header.getInt(88));
            }
            Set<String> primary = classNames(Files.readAllBytes(multiple.resolve("classes.dex")));
            check(primary.contains("Lcom/mkei/backcast/GlobalApplication;") && primary.contains("Landroidx/multidex/MultiDex;")
                    && primary.contains("Landroidx/multidex/MultiDexExtractor;"), "Android 4.x multidex startup classes were placed outside the primary DEX");
            String source = Files.readString(repo.resolve("app/src/main/java/com/mkei/backcast/GlobalApplication.java"));
            int attach = source.indexOf("protected void attachBaseContext");
            check(attach >= 0 && source.indexOf("super.attachBaseContext(base);", attach) < source.indexOf("MultiDex.install(this);", attach)
                    && source.indexOf("MultiDex.install(this);", attach) < source.indexOf("public void onCreate()", attach), "Multidex is not installed before Application.onCreate");
            System.out.println("PASS legacy multidex field limits and actual application bootstrap");
        } finally {
            try (var files = Files.walk(work)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
