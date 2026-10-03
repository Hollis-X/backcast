import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.tools.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Optional official AndroidX AAR + Gradle SDK runtime/D8 check, without AndroidX type substitutes. */
public final class AndroidDependencyDexCheck {
    private static final String MAVEN = "https://dl.google.com/dl/android/maven2/";
    private static final String SDK_ROOT = "com.openai:openai-java:4.75.1";
    private static final Map<String, Path> programs = new LinkedHashMap<>();
    private static final Set<String> visited = new HashSet<>();
    private static final Map<String, String> resourceNamespaces = new TreeMap<>();
    private static Path cache;
    private static DocumentBuilder parser;
    private static Path fetch(String relative) throws Exception {
        Path file = cache.resolve(relative);
        if (!Files.isRegularFile(file)) {
            Files.createDirectories(file.getParent());
            Path draft = Files.createTempFile(file.getParent(), "download-", ".tmp");
            try {
                Process download = new ProcessBuilder("curl", "--fail", "--silent", "--show-error", "--location", "--retry", "1",
                        "--connect-timeout", "10", "--max-time", "30", "--output", draft.toString(), MAVEN + relative).inheritIO().start();
                if (download.waitFor() != 0) throw new IOException("Official Google Maven download failed: " + relative);
                Files.move(draft, file, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(draft); }
        }
        return file;
    }
    private static String child(Element element, String name, String fallback) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element && name.equals(node.getLocalName())) return node.getTextContent().trim();
        return fallback;
    }
    private static String exactVersion(String version) {
        if (version.startsWith("[") && version.endsWith("]") && !version.contains(",")) return version.substring(1, version.length() - 1);
        if (version.contains("$") || version.contains(",")) throw new IllegalArgumentException("Unresolved dependency version: " + version);
        return version;
    }
    private static void resolve(String group, String name, String version) throws Exception {
        version = exactVersion(version);
        String coordinate = group + ":" + name + ":" + version;
        if (!visited.add(coordinate)) return;
        System.out.println("Resolving " + coordinate);
        String base = group.replace('.', '/') + "/" + name + "/" + version + "/" + name + "-" + version;
        Element project = parser.parse(fetch(base + ".pom").toFile()).getDocumentElement();
        String format = child(project, "packaging", "jar");
        Path artifact = fetch(base + "." + format);
        if ("aar".equals(format)) {
            Path classes = artifact.resolveSibling(name + "-" + version + "-classes.jar");
            try (ZipFile archive = new ZipFile(artifact.toFile())) {
                ZipEntry symbols = archive.getEntry("R.txt"), manifest = archive.getEntry("AndroidManifest.xml");
                if (symbols != null && symbols.getSize() > 0 && manifest != null) {
                    try (InputStream input = archive.getInputStream(manifest)) {
                        String namespace = parser.parse(input).getDocumentElement().getAttribute("package");
                        if (namespace.isEmpty()) throw new IOException("Missing AAR resource namespace: " + coordinate);
                        resourceNamespaces.put(namespace, coordinate);
                    }
                }
                ZipEntry entry = archive.getEntry("classes.jar");
                if (entry != null) {
                    try (InputStream input = archive.getInputStream(entry)) { Files.copy(input, classes, StandardCopyOption.REPLACE_EXISTING); }
                    programs.put(coordinate, classes);
                }
                for (ZipEntry embedded : Collections.list(archive.entries())) if (embedded.getName().startsWith("libs/") && embedded.getName().endsWith(".jar")) {
                    Path library = artifact.resolveSibling(name + "-" + version + "-" + embedded.getName().substring(5));
                    try (InputStream input = archive.getInputStream(embedded)) { Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING); }
                    programs.put(coordinate + "/" + embedded.getName(), library);
                }
            }
        } else if ("jar".equals(format)) programs.put(coordinate, artifact);
        else throw new IllegalArgumentException("Unexpected artifact packaging: " + format);
        for (Node node = project.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element) || !"dependencies".equals(node.getLocalName())) continue;
            for (Node dependency = node.getFirstChild(); dependency != null; dependency = dependency.getNextSibling()) {
                if (!(dependency instanceof Element)) continue;
                Element entry = (Element) dependency;
                String scope = child(entry, "scope", "compile");
                if (!"compile".equals(scope) && !"runtime".equals(scope) || "true".equals(child(entry, "optional", "false"))) continue;
                resolve(child(entry, "groupId", ""), child(entry, "artifactId", ""), child(entry, "version", ""));
            }
        }
    }
    private static void addSdkRuntime(Path root) throws Exception {
        String classpath = System.getenv("BACKCAST_SDK_CLASSPATH");
        if (classpath == null || classpath.isBlank()) {
            Path exported = root.resolve("app/build/agent-test-classpath.txt");
            if (!Files.isRegularFile(exported)) throw new AssertionError("Run Gradle :app:writeAgentTestClasspath first, or set BACKCAST_SDK_CLASSPATH");
            classpath = Files.readString(exported).trim();
        }
        Set<String> digests = new HashSet<>();
        for (Path artifact : programs.values()) digests.add(digest(artifact));
        boolean sdk = false;
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            Path artifact = Paths.get(entry).toRealPath();
            if (!Files.isRegularFile(artifact)) throw new AssertionError("Missing official runtime dependency " + artifact);
            try (ZipFile archive = new ZipFile(artifact.toFile())) {
                sdk |= archive.getEntry("com/openai/client/OpenAIClient.class") != null;
            }
            // Gradle's resolved JAR list also includes AndroidX annotations. Keep one copy
            // of identical artifacts; a differing duplicate class remains a D8 failure.
            if (digests.add(digest(artifact))) programs.put("Gradle runtime/" + artifact.getFileName(), artifact);
        }
        if (!sdk) throw new AssertionError("Runtime classpath has no official SDK core for " + SDK_ROOT);
    }
    private static String digest(Path artifact) throws Exception {
        java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[65536]; int length;
            while ((length = input.read(buffer)) != -1) hash.update(buffer, 0, length);
        }
        return Base64.getEncoder().encodeToString(hash.digest());
    }
    private static final class Source extends SimpleJavaFileObject {
        final String body;
        Source(String name, String body) { super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE); this.body = body; }
        @Override public CharSequence getCharContent(boolean ignore) { return body; }
    }
    private static Path compileApplication(Path root, Path api, Path work) throws Exception {
        Map<String, Set<String>> resources = new TreeMap<>();
        List<Path> files;
        try (var stream = Files.walk(root.resolve("app/src/main/java"))) { files = stream.filter(p -> p.toString().endsWith(".java")).toList(); }
        Pattern references = Pattern.compile("(?<![A-Za-z0-9_.])R\\.([a-z]+)\\.(\\w+)");
        for (Path file : files) {
            Matcher matcher = references.matcher(Files.readString(file));
            while (matcher.find()) resources.computeIfAbsent(matcher.group(1), ignored -> new TreeSet<>()).add(matcher.group(2));
        }
        StringBuilder resource = new StringBuilder("package com.mkei.backcast; public final class R {"); int value = 1;
        for (var entry : resources.entrySet()) {
            resource.append("public static final class ").append(entry.getKey()).append(" {");
            for (String name : entry.getValue()) resource.append("public static final int ").append(name).append('=').append(value++).append(';');
            resource.append('}');
        }
        resource.append('}');
        Path output = work.resolve("application-classes"); Files.createDirectory(output);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
            List<JavaFileObject> sources = new ArrayList<>();
            manager.getJavaFileObjectsFromPaths(files).forEach(sources::add);
            sources.add(new Source("com.mkei.backcast.R", resource.toString()));
            String classpath = api + File.pathSeparator + String.join(File.pathSeparator, programs.values().stream().map(Path::toString).toList());
            if (!compiler.getTask(null, manager, null, List.of("-proc:none", "-source", "8", "-target", "8", "-Xlint:-options",
                    "-encoding", "UTF-8", "-classpath", classpath, "-d", output.toString()), null, sources).call()) throw new AssertionError("Real dependency application compilation failed");
        }
        Path jar = work.resolve("application.jar");
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(jar)); var walk = Files.walk(output)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                archive.putNextEntry(new ZipEntry(output.relativize(file).toString().replace(File.separatorChar, '/')));
                Files.copy(file, archive); archive.closeEntry();
            }
        }
        System.out.println("Compiled " + files.size() + " Java8 production files against real AndroidX and official SDK runtime; only referenced application R is generated.");
        return jar;
    }
    private static int dex(Path d8, Path api, Path work, String mode, Path application, boolean release) throws Exception {
        Path output = work.resolve(mode); Files.createDirectory(output);
        List<String> command = new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java", "-cp", d8.toString(),
                "com.android.tools.r8.D8", "--min-api", "26", release ? "--release" : "--debug", "--lib", api.toString(), "--output", output.toString()));
        if (application != null) command.add(application.toString());
        for (Path program : programs.values()) command.add(program.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String diagnostics = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exit = process.waitFor();
        System.out.println("D8 " + mode + " exit=" + exit);
        System.out.println(diagnostics);
        if (exit != 0) return exit;
        try (var files = Files.list(output)) {
            List<Path> dexFiles = files.filter(p -> p.toString().endsWith(".dex")).sorted().toList();
            if (dexFiles.isEmpty()) throw new AssertionError("D8 produced no DEX files");
            for (Path file : dexFiles) {
                byte[] header = Files.readAllBytes(file);
                if (header.length < 112 || !new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("dex\n"))
                    throw new AssertionError("Invalid DEX header " + file);
                int fields = (header[80] & 255) | (header[81] & 255) << 8 | (header[82] & 255) << 16 | (header[83] & 255) << 24;
                int methods = (header[88] & 255) | (header[89] & 255) << 8 | (header[90] & 255) << 16 | (header[91] & 255) << 24;
                if (fields > 65536 || methods > 65536) throw new AssertionError("DEX reference limit exceeded: " + file);
                System.out.println(file.getFileName() + " field_ids=" + fields + " method_ids=" + methods + " bytes=" + header.length);
            }
        }
        return exit;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: AndroidDependencyDexCheck.java <repository> <official-dependency-cache> <D8-JAR> <Android-API-JAR>");
        Path root = Paths.get(args[0]), d8 = Paths.get(args[2]), api = Paths.get(args[3]); cache = Paths.get(args[1]);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); parser = factory.newDocumentBuilder();
        // This reproduces the currently pinned app/build.gradle dependencies.
        // Update these roots when production dependency versions change.
        resolve("androidx.appcompat", "appcompat", "1.0.0");
        resolve("androidx.multidex", "multidex", "2.0.1");
        addSdkRuntime(root);
        System.out.println("Resolved " + programs.size() + " real compile/runtime artifacts from official Google Maven and Gradle's SDK graph:");
        for (var entry : programs.entrySet()) System.out.println(entry.getKey() + " " + Files.size(entry.getValue()) + " bytes");
        System.out.println("Resolved " + resourceNamespaces.size() + " dependency resource namespaces (plus application namespace):");
        for (var entry : resourceNamespaces.entrySet()) System.out.println(entry.getKey() + " " + entry.getValue());
        Path work = Files.createTempDirectory("backcast-real-d8-");
        try {
            if (dex(d8, api, work, "dependencies-debug", null, false) != 0) throw new AssertionError("Real dependency native multidex compilation failed; diagnostics printed above");
            Path application = compileApplication(root, api, work);
            if (dex(d8, api, work, "application-debug", application, false) != 0) throw new AssertionError("Real dependency/application native multidex compilation failed; diagnostics printed above");
            if (dex(d8, api, work, "application-release", application, true) != 0) throw new AssertionError("Real dependency/application release native multidex compilation failed; diagnostics printed above");
        } finally {
            try (var files = Files.walk(work)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
