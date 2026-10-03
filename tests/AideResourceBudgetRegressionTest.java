import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Optional exported AIDE resource-budget check; original builds and Maven cache stay read-only. */
public final class AideResourceBudgetRegressionTest {
    private static final Set<String> ANDROID_ROOTS = Set.of("androidx.appcompat:appcompat:1.0.0", "androidx.multidex:multidex:2.0.1");
    private static final String SDK_ROOT = "com.openai:openai-java:4.75.1";
    private static final Set<String> TRANSPORT_ROOTS = Set.of("com.squareup.okhttp3:okhttp:4.12.0", "com.squareup.okio:okio-jvm:3.6.0");
    private static final String APP = "com/mkei/backcast";
    private static int checks;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void passed(String name) { checks++; System.out.println("PASS " + name); }

    private static void configuration(Path root) throws Exception {
        String gradle = Files.readString(root.resolve("app/build.gradle"));
        Set<String> declared = new LinkedHashSet<>();
        Matcher dependency = Pattern.compile("\\bimplementation\\s+['\"]([^'\"]+:[^'\"]+:[^'\"]+)['\"]").matcher(gradle);
        while (dependency.find()) declared.add(dependency.group(1));
        Set<String> roots = new LinkedHashSet<>(ANDROID_ROOTS); roots.add(SDK_ROOT); roots.addAll(TRANSPORT_ROOTS);
        check(declared.equals(roots), "Application dependency roots changed; reassess Android resources and SDK DEX inputs: " + declared);
        String checker = Files.readString(root.resolve("tests/AndroidDependencyDexCheck.java"));
        Set<String> verified = new LinkedHashSet<>();
        Matcher resolve = Pattern.compile("\\bresolve\\(\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*\\)").matcher(checker);
        while (resolve.find()) verified.add(resolve.group(1) + ":" + resolve.group(2) + ":" + resolve.group(3));
        check(verified.equals(ANDROID_ROOTS), "Real AndroidX AAR dependency D8 check differs from production: " + verified);
        check(checker.contains("\"" + SDK_ROOT + "\"") && checker.contains("agent-test-classpath.txt")
                && checker.contains("BACKCAST_SDK_CLASSPATH"), "Official SDK runtime graph is missing from real dependency D8 verification");
        passed("production and real D8 verification retain AndroidX AAR roots and the official SDK JAR graph");
        check(Pattern.compile("minSdkVersion\\s+26\\b").matcher(gradle).find()
                && gradle.contains("sourceCompatibility JavaVersion.VERSION_1_8")
                && gradle.contains("targetCompatibility JavaVersion.VERSION_1_8")
                && Pattern.compile("multiDexEnabled\\s+true\\b").matcher(gradle).find(),
                "Official SDK build must retain Java8, API26 native Java8 APIs and native multidex");
        passed("official SDK build uses Java8/API26 and native multidex");
        boolean appcompat = false;
        for (String directory : List.of("app/src/main/java", "app/src/main/res")) {
            try (var walk = Files.walk(root.resolve(directory))) {
                for (Path file : walk.filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".xml")).toList()) {
                    String source = Files.readString(file);
                    check(!source.contains("com.google.android.material") && !source.contains("Theme.MaterialComponents")
                            && !source.contains("Widget.MaterialComponents") && !source.contains("@style/Material"),
                            "Removed Material library is still used in " + file);
                    appcompat |= source.contains("androidx.appcompat") || source.contains("Theme.AppCompat");
                }
            }
        }
        check(appcompat, "AppCompat usage disappeared; reassess the retained dependency graph");
        passed("removed Material library has no production Java or resource usage; AppCompat remains used");
    }

    private static DocumentBuilder parser() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder();
    }
    private static String child(Element element, String name, String fallback) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element && name.equals(node.getLocalName())) return node.getTextContent().trim();
        return fallback;
    }
    private static final class Graph {
        final Set<String> coordinates = new LinkedHashSet<>();
        final Set<String> namespaces = new LinkedHashSet<>();
        final List<Path> programs = new ArrayList<>();
        final Path cache, scratch;
        final DocumentBuilder parser = parser();
        Graph(Path cache, Path scratch) throws Exception { this.cache = cache; this.scratch = scratch; }
        void addSdkRuntime(Path root) throws Exception {
            String classpath = System.getenv("BACKCAST_SDK_CLASSPATH");
            if (classpath == null || classpath.isBlank()) {
                Path exported = root.resolve("app/build/agent-test-classpath.txt");
                check(Files.isRegularFile(exported), "Run Gradle :app:writeAgentTestClasspath first, or set BACKCAST_SDK_CLASSPATH");
                classpath = Files.readString(exported).trim();
            }
            Set<String> digests = new HashSet<>();
            for (Path artifact : programs) digests.add(digest(artifact));
            boolean sdk = false;
            for (String entry : classpath.split(Pattern.quote(java.io.File.pathSeparator))) {
                Path artifact = Paths.get(entry).toRealPath();
                check(Files.isRegularFile(artifact), "Missing official SDK runtime dependency " + artifact);
                try (ZipFile archive = new ZipFile(artifact.toFile())) {
                    sdk |= archive.getEntry("com/openai/client/OpenAIClient.class") != null;
                }
                if (digests.add(digest(artifact))) programs.add(artifact);
            }
            check(sdk, "Runtime graph has no official OpenAI SDK core");
        }
        void resolve(String coordinate) throws Exception {
            String[] parts = coordinate.split(":");
            check(parts.length == 3, "Invalid dependency coordinate " + coordinate);
            String version = parts[2];
            if (version.startsWith("[") && version.endsWith("]") && !version.contains(",")) version = version.substring(1, version.length() - 1);
            check(!version.contains("$") && !version.contains(","), "Unresolved dependency " + coordinate);
            coordinate = parts[0] + ":" + parts[1] + ":" + version;
            if (!coordinates.add(coordinate)) return;
            Path base = cache.resolve(parts[0].replace('.', '/') + "/" + parts[1] + "/" + version + "/" + parts[1] + "-" + version);
            Path pom = Paths.get(base + ".pom");
            check(Files.isRegularFile(pom), "Official dependency cache is incomplete: " + pom);
            Element project = parser.parse(pom.toFile()).getDocumentElement();
            String format = child(project, "packaging", "jar");
            Path artifact = Paths.get(base + "." + format);
            check(Files.isRegularFile(artifact), "Official dependency cache is incomplete: " + artifact);
            if ("aar".equals(format)) {
                try (ZipFile archive = new ZipFile(artifact.toFile())) {
                    ZipEntry manifest = archive.getEntry("AndroidManifest.xml");
                    check(manifest != null, "AAR has no manifest: " + artifact);
                    try (InputStream input = archive.getInputStream(manifest)) {
                        String namespace = parser.parse(input).getDocumentElement().getAttribute("package");
                        check(!namespace.isBlank(), "AAR has no resource namespace: " + artifact);
                        namespaces.add(namespace.replace('.', '/'));
                    }
                    for (ZipEntry entry : java.util.Collections.list(archive.entries())) {
                        if (!entry.getName().equals("classes.jar") && !(entry.getName().startsWith("libs/") && entry.getName().endsWith(".jar"))) continue;
                        Path extracted = scratch.resolve("dependency-" + programs.size() + ".jar");
                        try (InputStream input = archive.getInputStream(entry)) { Files.copy(input, extracted); }
                        programs.add(extracted);
                    }
                }
            } else {
                check("jar".equals(format), "Unexpected artifact packaging " + format);
                programs.add(artifact);
            }
            for (Node node = project.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element) || !"dependencies".equals(node.getLocalName())) continue;
                for (Node dependency = node.getFirstChild(); dependency != null; dependency = dependency.getNextSibling()) {
                    if (!(dependency instanceof Element)) continue;
                    Element entry = (Element) dependency;
                    String scope = child(entry, "scope", "compile");
                    if ((!scope.equals("compile") && !scope.equals("runtime")) || child(entry, "optional", "false").equals("true")) continue;
                    resolve(child(entry, "groupId", "") + ":" + child(entry, "artifactId", "") + ":" + child(entry, "version", ""));
                }
            }
        }
    }
    private static String digest(Path artifact) throws Exception {
        java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[65536]; int length;
            while ((length = input.read(buffer)) != -1) hash.update(buffer, 0, length);
        }
        return java.util.Base64.getEncoder().encodeToString(hash.digest());
    }

    private static final class ClassInfo {
        String name;
        final Set<String> references = new HashSet<>();
        int fields;
    }
    private static ClassInfo readClass(InputStream stream) throws Exception {
        try (DataInputStream input = new DataInputStream(stream)) {
            check(input.readInt() == 0xcafebabe, "Invalid actual class input");
            input.readUnsignedShort(); input.readUnsignedShort();
            Object[] pool = new Object[input.readUnsignedShort()]; Set<Integer> classIds = new HashSet<>();
            for (int i = 1; i < pool.length; i++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1: pool[i] = input.readUTF(); break;
                    case 7: pool[i] = input.readUnsignedShort(); classIds.add(i); break;
                    case 8: case 16: case 19: case 20: input.readUnsignedShort(); break;
                    case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: input.readInt(); break;
                    case 5: case 6: input.readLong(); i++; break;
                    case 15: input.readUnsignedByte(); input.readUnsignedShort(); break;
                    default: throw new AssertionError("Unknown class constant tag " + tag);
                }
            }
            ClassInfo result = new ClassInfo();
            input.readUnsignedShort(); result.name = (String) pool[(Integer) pool[input.readUnsignedShort()]];
            for (int id : classIds) result.references.add((String) pool[(Integer) pool[id]]);
            input.readUnsignedShort(); int interfaces = input.readUnsignedShort(); input.skipNBytes(interfaces * 2L);
            result.fields = input.readUnsignedShort();
            return result;
        }
    }
    private static String resourceNamespace(String name) {
        int slash = name.lastIndexOf('/');
        String local = name.substring(slash + 1);
        return local.equals("R") || local.startsWith("R$") ? name.substring(0, slash) : null;
    }
    private static final class Inputs {
        final Map<String, Path> files = new LinkedHashMap<>();
        final Map<String, ClassInfo> classes = new LinkedHashMap<>();
        final Map<String, Integer> resources = new TreeMap<>();
    }
    private static Inputs exported(Path directory) throws Exception {
        Inputs result = new Inputs();
        try (var walk = Files.walk(directory)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                ClassInfo info = readClass(Files.newInputStream(file));
                check(directory.relativize(file).toString().replace(java.io.File.separatorChar, '/').equals(info.name + ".class"), "Actual class path mismatch " + file);
                check(result.files.put(info.name, file) == null, "Duplicate exported class " + info.name);
                result.classes.put(info.name, info);
                String namespace = resourceNamespace(info.name);
                if (namespace != null) result.resources.merge(namespace, info.fields, Integer::sum);
            }
        }
        check(!result.files.isEmpty(), "No actual AIDE classes in " + directory);
        check(result.resources.containsKey(APP), "Application generated R is missing");
        for (var entry : result.resources.entrySet()) check(entry.getValue() > 3000, "Expected full actual AIDE resource union, not a small R substitute: " + entry);
        System.out.println(directory.getFileName() + " actual generated R declared fields per namespace: " + result.resources);
        return result;
    }
    private static String dexString(ByteBuffer data, int index) {
        int start = data.getInt(data.getInt(60) + index * 4);
        while ((data.get(start++) & 128) != 0) { }
        int end = start; while (data.get(end) != 0) end++;
        byte[] bytes = new byte[end - start]; data.position(start); data.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
    private static void compile(Inputs inputs, Graph graph, Set<String> removed, Path engine, Path api, String mode, Path work) throws Exception {
        List<String> command = new ArrayList<>(List.of(Paths.get(System.getProperty("java.home"), "bin", "java").toString(), "-cp", engine.toString(),
                "com.android.tools.r8.D8", "--" + mode, "--min-api", "26", "--lib", api.toString(), "--output", work.toString()));
        Set<String> expected = new HashSet<>();
        for (var entry : inputs.files.entrySet()) {
            if (removed.contains(resourceNamespace(entry.getKey()))) continue;
            command.add(entry.getValue().toString()); expected.add(entry.getKey());
        }
        for (Path program : graph.programs) {
            command.add(program.toString());
            try (ZipFile archive = new ZipFile(program.toFile())) {
                for (ZipEntry entry : java.util.Collections.list(archive.entries())) {
                    if (!entry.getName().endsWith(".class") || entry.getName().endsWith("module-info.class")
                            || entry.getName().startsWith("META-INF/versions/")) continue;
                    ClassInfo info = readClass(archive.getInputStream(entry));
                    check(expected.add(info.name), "Duplicate actual application/dependency program class " + info.name);
                    for (String reference : info.references) check(!removed.contains(resourceNamespace(reference)), "Used library references removed resource class " + reference);
                }
            }
        }
        Path log = work.resolveSibling(work.getFileName() + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(180, TimeUnit.SECONDS)) throw new AssertionError("Actual AIDE budget D8 timed out: " + engine);
            check(process.exitValue() == 0, "Actual AIDE classes plus official SDK dependencies failed native multidex compilation:\n" + Files.readString(log));
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); } }
        List<Path> dex;
        try (var list = Files.list(work)) { dex = list.filter(path -> path.toString().endsWith(".dex")).toList(); }
        check(!dex.isEmpty() && Files.isRegularFile(work.resolve("classes.dex")), "D8 produced no primary DEX");
        Set<String> actual = new HashSet<>(); long totalBytes = 0;
        for (Path file : dex) {
            byte[] bytes = Files.readAllBytes(file); ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            check(bytes.length >= 112 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("dex\n")
                    && header.getInt(32) == bytes.length && header.getInt(36) == 112, "Invalid DEX output header " + file);
            int fields = header.getInt(80), methods = header.getInt(88), definitions = header.getInt(96);
            check(fields <= 65536 && methods <= 65536, "DEX exceeds index limits " + file);
            int classOffset = header.getInt(100), typeOffset = header.getInt(68);
            for (int i = 0; i < definitions; i++) {
                String descriptor = dexString(header, header.getInt(typeOffset + header.getInt(classOffset + i * 32) * 4));
                check(actual.add(descriptor), "Duplicate DEX class " + descriptor);
            }
            totalBytes += bytes.length;
            System.out.println(file.getFileName() + " field_ids=" + fields + " method_ids=" + methods + " bytes=" + bytes.length);
        }
        for (String name : expected) check(actual.contains("L" + name + ";"), "D8 dropped a retained actual class " + name);
        for (String namespace : removed) check(actual.stream().noneMatch(name -> name.startsWith("L" + namespace + "/R$") || name.equals("L" + namespace + "/R;")), "Removed resource namespace remains in DEX " + namespace);
        long helpers = expected.stream().filter(name -> name.endsWith("$0$debug")).count();
        passed("actual AIDE " + mode + " + all " + graph.programs.size() + " official artifacts -> " + engine.getFileName()
                + " native multidex: DEX files=" + dex.size() + ", retained inputs=" + expected.size() + ", debugger=" + helpers + ", bytes=" + totalBytes);
        String diagnostics = Files.readString(log);
        if (!diagnostics.isBlank()) System.out.println("D8 warnings (exported debugger runtime may be external): " + diagnostics.substring(0, Math.min(2500, diagnostics.length())));
    }
    private static void evidence(Path root, Path export, Path cache, Path api, List<Path> engines) throws Exception {
        Path temporary = Files.createTempDirectory("backcast-aide-resource-check-");
        try {
            Graph graph = new Graph(cache, temporary);
            for (String coordinate : ANDROID_ROOTS) graph.resolve(coordinate);
            graph.addSdkRuntime(root);
            Graph original = new Graph(cache, Files.createDirectory(temporary.resolve("original-graph")));
            for (String coordinate : ANDROID_ROOTS) original.resolve(coordinate);
            original.resolve("com.google.android.material:material:1.0.0");
            Set<String> keep = new LinkedHashSet<>(graph.namespaces); keep.add(APP);
            for (String mode : List.of("debug", "release")) {
                Inputs inputs = exported(export.resolve("classes" + mode));
                Set<String> removed = new LinkedHashSet<>(inputs.resources.keySet()); removed.removeAll(keep);
                check(original.namespaces.containsAll(removed), "Refusing to remove unknown resource namespace " + removed);
                check(removed.contains("com/google/android/material"), "Actual fixture does not contain removed Material resources");
                for (var entry : inputs.classes.entrySet()) {
                    if (removed.contains(resourceNamespace(entry.getKey()))) continue;
                    for (String reference : entry.getValue().references) check(!removed.contains(resourceNamespace(reference)), "Retained actual class uses removed resource class " + reference);
                }
                System.out.println("Official graph " + original.coordinates.size() + " -> " + graph.coordinates.size() + " artifacts; actual resource namespaces "
                        + inputs.resources.size() + " -> " + (inputs.resources.size() - removed.size()) + "; removed=" + removed);
                for (int i = 0; i < engines.size(); i++) compile(inputs, graph, removed, engines.get(i), api, mode,
                        Files.createDirectory(temporary.resolve(mode + "-engine" + i)));
            }
        } finally { try (var walk = Files.walk(temporary)) { for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); } }
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 1 || args.length >= 5, "Usage: <repository> [<actual exported build> <official dependency cache> <API jar> <D8 jar> ...]");
        configuration(Paths.get(args[0]));
        if (args.length > 1) {
            List<Path> engines = new ArrayList<>(); for (int i = 4; i < args.length; i++) engines.add(Paths.get(args[i]));
            evidence(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]), engines);
        }
        System.out.println("AIDE resource budget regressions passed: " + checks);
    }
}
