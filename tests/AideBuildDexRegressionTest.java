import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Optional diagnostic against exported AIDE class files; never mutates the exported build. */
public final class AideBuildDexRegressionTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class Classes {
        final List<Path> files = new ArrayList<>();
        final Map<String, Path> identities = new LinkedHashMap<>();
        final Map<Integer, Integer> versions = new HashMap<>();
    }
    private static Classes inspect(Path directory) throws Exception {
        Classes result = new Classes();
        try (var walk = Files.walk(directory)) {
            result.files.addAll(walk.filter(path -> path.toString().endsWith(".class")).sorted().toList());
        }
        check(!result.files.isEmpty(), "No class files in " + directory);
        for (Path file : result.files) {
            try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
                check(input.readInt() == 0xcafebabe, "Invalid class file " + file);
                input.readUnsignedShort(); int version = input.readUnsignedShort(); result.versions.merge(version, 1, Integer::sum);
                Object[] constants = new Object[input.readUnsignedShort()];
                for (int i = 1; i < constants.length; i++) {
                    int tag = input.readUnsignedByte();
                    switch (tag) {
                        case 1: constants[i] = input.readUTF(); break;
                        case 7: constants[i] = input.readUnsignedShort(); break;
                        case 8: case 16: case 19: case 20: input.readUnsignedShort(); break;
                        case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: input.readInt(); break;
                        case 5: case 6: input.readLong(); i++; break;
                        case 15: input.readUnsignedByte(); input.readUnsignedShort(); break;
                        default: throw new AssertionError("Invalid constant tag " + tag + " in " + file);
                    }
                }
                input.readUnsignedShort();
                String identity = (String) constants[(Integer) constants[input.readUnsignedShort()]];
                String relative = directory.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                check(relative.equals(identity + ".class"), "Class descriptor/path mismatch " + identity + " in " + file);
                Path existing = result.identities.put(identity, file);
                check(existing == null, "Duplicate class " + identity + " in " + existing + " and " + file);
            }
        }
        for (String page : List.of("MainActivity", "SettingsActivity", "AiConfigActivity", "UserPreferencesActivity", "ToolConfigActivity")) {
            check(result.identities.containsKey("com/mkei/backcast/" + page), "Missing current settings page " + page);
        }
        check(result.identities.keySet().stream().noneMatch(name -> name.startsWith("com/mkei/backcast/MainActivity$Toolkit")
                || name.startsWith("com/mkei/backcast/SettingsActivity$Choice")), "Old moved tool/choice classes remain in " + directory);
        long debug = result.identities.keySet().stream().filter(name -> name.endsWith("$0$debug")).count();
        System.out.println("PASS AIDE " + directory.getFileName() + ": " + result.files.size() + " unique classes; versions="
                + result.versions + "; debugger classes=" + debug + "; current pages present and legacy moved classes absent");
        return result;
    }
    private static String dexString(ByteBuffer data, int index) throws Exception {
        int offset = data.getInt(data.getInt(60) + index * 4);
        while ((data.get(offset++) & 128) != 0) { }
        int end = offset; while (data.get(end) != 0) end++;
        // A class descriptor is ASCII; arbitrary DEX string contents need modified UTF-8 instead.
        byte[] name = new byte[end - offset]; data.position(offset); data.get(name);
        return new String(name, java.nio.charset.StandardCharsets.US_ASCII);
    }
    private static Set<String> dexClasses(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        check(bytes.length >= 112 && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("dex\n"), "Invalid D8 output " + file);
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        check(data.getInt(32) == bytes.length && data.getInt(36) == 112, "Invalid DEX size/header " + file);
        int count = data.getInt(96), definitions = data.getInt(100), types = data.getInt(68);
        Set<String> descriptors = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int type = data.getInt(definitions + i * 32);
            String descriptor = dexString(data, data.getInt(types + type * 4));
            check(descriptors.add(descriptor), "Duplicate definition in DEX " + descriptor);
        }
        return descriptors;
    }
    private static void dex(Classes inputs, Path api, Path engine, String mode, Path scratch) throws Exception {
        Path output = Files.createDirectory(scratch.resolve("dex")), log = scratch.resolve("d8.log");
        List<String> command = new ArrayList<>(List.of(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", engine.toString(), "com.android.tools.r8.D8", "--" + mode, "--min-api", "16", "--lib", api.toString(), "--output", output.toString()));
        inputs.files.forEach(file -> command.add(file.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean overflow;
        try {
            if (!process.waitFor(180, TimeUnit.SECONDS)) throw new AssertionError("AIDE D8 timed out: " + engine + "\n" + Files.readString(log));
            String diagnostics = Files.readString(log);
            overflow = process.exitValue() != 0 && diagnostics.contains("Cannot fit requested classes in a single dex file")
                    && diagnostics.contains("# fields:");
            if (overflow) {
                java.util.regex.Matcher fields = java.util.regex.Pattern.compile("# fields: (\\d+) > 65536").matcher(diagnostics);
                check(fields.find(), "Single-DEX failure did not identify field overflow\n" + diagnostics);
                System.out.println("REPRODUCED " + engine.getFileName() + "/" + mode + ": single-DEX field count=" + fields.group(1));
            } else check(process.exitValue() == 0, "AIDE bytecode failed " + engine.getFileName() + "/" + mode + "\n" + diagnostics);
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); } }
        if (overflow) {
            check(inputs.identities.containsKey("com/mkei/backcast/GlobalApplication"), "Legacy multidex needs the application startup class");
            Path mainDexList = scratch.resolve("main-dex-list.txt");
            Files.writeString(mainDexList, "com/mkei/backcast/GlobalApplication.class\n");
            command.add("--main-dex-list"); command.add(mainDexList.toString());
            log = scratch.resolve("d8-multidex.log");
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                if (!process.waitFor(180, TimeUnit.SECONDS)) throw new AssertionError("AIDE multidex D8 timed out\n" + Files.readString(log));
                check(process.exitValue() == 0, "AIDE legacy multidex still failed " + engine.getFileName() + "/" + mode + "\n" + Files.readString(log));
            } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); } }
        }
        Set<String> actual = new HashSet<>(); long bytes = 0;
        try (var walk = Files.list(output)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".dex")).toList()) {
                Set<String> definitions = dexClasses(file);
                for (String name : definitions) check(actual.add(name), "Duplicate class across DEX outputs " + name);
                bytes += Files.size(file);
                ByteBuffer header = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
                int fields = header.getInt(80), methods = header.getInt(88);
                check(fields <= 65536 && methods <= 65536, "DEX field/method index overflow in " + file);
                System.out.println("DEX " + file.getFileName() + ": fields=" + fields + ", methods=" + methods + ", classes=" + definitions.size());
            }
        }
        for (String name : inputs.identities.keySet()) check(actual.contains("L" + name + ";"), "D8 dropped AIDE class " + name);
        if (overflow) check(dexClasses(output.resolve("classes.dex")).contains("Lcom/mkei/backcast/GlobalApplication;"),
                "The application startup class was moved to a secondary DEX");
        System.out.println("PASS actual AIDE bytecode -> " + engine.getFileName() + "/" + mode + "/min-api16: " + actual.size()
                + " defined classes, all " + inputs.files.size() + " input identities retained; " + bytes + " bytes");
        String diagnostics = Files.readString(log);
        if (!diagnostics.isBlank()) System.out.println("D8 diagnostics: " + diagnostics.substring(0, Math.min(6000, diagnostics.length())));
    }
    public static void main(String[] args) throws Exception {
        Path build = Paths.get(args[0]), api = Paths.get(args[1]);
        Classes debug = inspect(build.resolve("classesdebug")), release = inspect(build.resolve("classesrelease"));
        Path workspace = Files.createTempDirectory("backcast-aide-class-dex-");
        try {
            for (int i = 2; i < args.length; i++) for (String mode : List.of("debug", "release")) {
                Path scratch = Files.createDirectory(workspace.resolve("engine" + i + "-" + mode));
                dex(mode.equals("debug") ? debug : release, api, Paths.get(args[i]), mode, scratch);
            }
        } finally { try (var walk = Files.walk(workspace)) {
            for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } }
    }
}
