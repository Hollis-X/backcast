package com.mkei.backcast.tool;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;

/** 文件工具共用的路径、整篇读写，以及应用读不到时改走 root。 */
final class ToolPaths {

    static final class Probe {
        final boolean exists;
        final boolean directory;
        final long length;
        final boolean denied;

        Probe(boolean exists, boolean directory, long length, boolean denied) {
            this.exists = exists;
            this.directory = directory;
            this.length = length;
            this.denied = denied;
        }

        static Probe of(boolean directory, long length) {
            return new Probe(true, directory, length, false);
        }

        static Probe missing() {
            return new Probe(false, false, 0, false);
        }

        static Probe denied() {
            return new Probe(false, false, 0, true);
        }
    }

    static final class ProcessReader extends BufferedReader {
        private final Process process;

        ProcessReader(Process process) throws Exception {
            super(new InputStreamReader(process.getInputStream(), "UTF-8"));
            this.process = process;
        }

        @Override
        public void close() throws IOException {
            super.close();
            process.destroy();
        }
    }

    private static final HashMap<String, Object> LOCKS = new HashMap<String, Object>();

    private ToolPaths() {
    }

    /** 规范化后只能操作工作目录内的路径，不能借 .. 或符号链接绕出。 */
    static File resolve(String workDir, String path) {
        if (path == null || path.length() == 0 || path.indexOf('\0') >= 0
                || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("路径不合法。");
        }
        try {
            File root = workDir == null || workDir.length() == 0 ? null : new File(workDir).getCanonicalFile();
            File target = new File(path);
            if (!target.isAbsolute() && root != null) target = new File(root, path);
            target = target.getCanonicalFile();
            if (root != null && !inside(root, target)) {
                throw new IllegalArgumentException("路径超出工作目录：" + path
                        + "。当前目录：" + root.getPath()
                        + "。请使用该目录内的路径，不要转存到目录外的临时文件。");
            }
            return target;
        } catch (IOException error) {
            throw new IllegalArgumentException("无法确认路径：" + path, error);
        }
    }

    static File resolve(String workDir, String path, TemporaryWorkspace temporary) {
        if (temporary != null && path != null && path.indexOf('\0') < 0
                && path.indexOf('\n') < 0 && path.indexOf('\r') < 0) {
            try {
                File managed = temporary.resolveManaged(path);
                if (managed != null) return managed;
            } catch (Exception failure) {
                throw new IllegalArgumentException(failure.getMessage(), failure);
            }
        }
        File file;
        try { file = temporary == null ? resolve(workDir, path) : temporary.projectRoots(workDir).resolve(path); }
        catch (IOException failure) { throw new IllegalArgumentException("无法确认路径：" + path, failure); }
        try {
            if (temporary != null && temporary.isPrivateStorage(file)) {
                throw new IllegalArgumentException("App 私有临时存储只允许访问本轮登记目录，不能访问其他会话或登记文件。");
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("无法确认路径：" + path, failure);
        } catch (Exception failure) {
            if (failure instanceof IllegalArgumentException) throw (IllegalArgumentException) failure;
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
        return file;
    }

    private static boolean inside(File root, File target) {
        String base = root.getPath(), path = target.getPath();
        return base.equals(path) || path.startsWith(base.endsWith("/") ? base : base + "/");
    }

    static boolean organizedTest(String workDir, File file) throws Exception {
        File root = workDir == null ? null : new File(workDir).getCanonicalFile();
        return organizedTest(root, file);
    }

    static boolean organizedTest(String workDir, File file, TemporaryWorkspace temporary) throws Exception {
        File root = temporary == null ? workDir == null ? null : new File(workDir).getCanonicalFile()
                : temporary.projectRoots(workDir).rootFor(file);
        return organizedTest(root, file);
    }

    private static boolean organizedTest(File root, File file) {
        File directory = file.getParentFile();
        while (directory != null && !directory.equals(root)) {
            String name = directory.getName().toLowerCase(java.util.Locale.US);
            if ("test".equals(name) || "tests".equals(name) || "__tests__".equals(name)
                    || "spec".equals(name) || "specs".equals(name) || "androidtest".equals(name)) return true;
            // Preserve an established project's own descriptive testing directory.
            if (directory.isDirectory() && (name.contains("test") || name.contains("测试"))) return true;
            directory = directory.getParentFile();
        }
        return false;
    }

    static boolean projectRoot(String workDir, File file, TemporaryWorkspace temporary) throws Exception {
        return temporary == null ? workDir != null && file.equals(new File(workDir).getCanonicalFile())
                : temporary.projectRoots(workDir).isRoot(file);
    }

    /**
     * 命令的预检：字面路径、重定向和原地改写。
     *
     * 这是尽力而为的检查，不是系统级沙箱：脚本内部自己拼出来的路径拦不住，
     * 目的只是把「写错目录还读回来」这类常见越界挡在调用前。
     */
    static void checkCommand(String workDir, String command) {
        checkCommand(workDir, command, null);
    }

    static void checkCommand(String workDir, String command, TemporaryWorkspace temporary) {
        checkCommand(workDir, command, temporary, false);
    }

    static void checkCommand(String workDir, String command, TemporaryWorkspace temporary, boolean temporaryCommand) {
        if (command == null || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("命令不合法。");
        }
        try {
            String cwd = temporaryCommand && temporary != null ? temporary.directory().getPath() : workDir;
            scanCommand(workDir, new ShellLocation(cwd), shellWords(command), temporary, temporaryCommand);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("命令无法解析：" + command);
        }
    }

    static List<String> prepareProgramArguments(String id, List<String> arguments) {
        List<String> prepared = new ArrayList<String>(arguments);
        if (!"radare2".equals(id) || arguments.size() == 1 && ("-v".equals(arguments.get(0))
                || "--version".equals(arguments.get(0)) || "-h".equals(arguments.get(0)) || "--help".equals(arguments.get(0)))) return prepared;
        int lastCommand = -1;
        boolean attached = false;
        for (int i = 0; i < prepared.size(); i++) {
            String arg = prepared.get(i);
            if (arg == null) continue;
            if ("--".equals(arg)) break;
            if ("-c".equals(arg)) {
                if (++i < prepared.size()) {
                    lastCommand = i; attached = false;
                }
            } else if (arg.startsWith("-c") && arg.length() > 2) {
                lastCommand = i; attached = true;
            }
        }
        // -q alone still waits for input when no command/script is provided.
        // Provide an explicit read command and exit; each toolkit call is finite.
        if (lastCommand < 0) { prepared.add(0, "i;q"); prepared.add(0, "-c"); }
        else {
            String arg = prepared.get(lastCommand);
            if (arg != null) {
                String command = attached ? arg.substring(2) : arg;
                String[] parts = command.split(";", -1);
                if (!"q".equals(parts[parts.length - 1].trim())) prepared.set(lastCommand, arg + ";q");
            }
        }
        if (prepared.isEmpty() || !"-q".equals(prepared.get(0))) prepared.add(0, "-q");
        return prepared;
    }

    static void checkProgram(String workDir, String id, List<String> arguments,
            TemporaryWorkspace temporary, boolean temporaryCommand) throws Exception {
        ToolCatalog.get(id);
        if (arguments.size() > 128) throw new IllegalArgumentException("工具参数过多。");
        String cwd = temporaryCommand && temporary != null ? temporary.directory().getPath() : workDir;
        if (temporaryCommand && temporary == null) throw new IllegalArgumentException("当前没有临时材料管理器。");
        for (int i = 0; i < arguments.size(); i++) {
            String value = arguments.get(i);
            if (value == null || value.length() > 16000 || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("工具参数不合法。");
            }
            if ("binutils".equals(ToolCatalog.get(id).group) && value.startsWith("@")) {
                throw new IllegalArgumentException("当前入口不接受 GNU @响应文件，请把每个参数明确列在 arguments 中。");
            }
            if ("rabin2".equals(id) && value.startsWith("-") && !"--".equals(value)
                    && !"--version".equals(value) && !"--help".equals(value)) {
                // rabin2 -i/-I/-e are imports/info/entrypoints, unlike r2 script/config options.
                if (!value.matches("-[AcdeEghHiIjJlMqrRsStuVvwzZ]+") && !"-P".equals(value)) {
                    throw new IllegalArgumentException("rabin2 读取选项示例：['-I','文件']、['-i','文件']、['-s','文件']。"
                            + "当前入口不开放 -x/-o/-O/-C/-X 写入、下载和额外脚本操作。");
                }
            }
            if ("radare2".equals(id) && value.startsWith("-") && !"--".equals(value)) {
                if ("-e".equals(value) || value.startsWith("-e") && value.length() > 2) {
                    String setting = value.length() > 2 ? value.substring(2) : ++i < arguments.size() ? arguments.get(i) : "";
                    if (!(setting.matches("(?:scr\\.interactive|scr\\.color|bin\\.relocs\\.apply|bin\\.cache)=(?:true|false|0|1)")
                            || setting.matches("asm\\.syntax=(?:intel|att)"))) {
                        throw new IllegalArgumentException("radare2 -e 只接受 scr.interactive/scr.color/bin.relocs.apply/bin.cache 布尔配置或 asm.syntax=intel/att；"
                                + "分析命令请用 ['-c','ii;is;pdf @ sym.main;q','文件']，文件路径不能放到 -e 后。");
                    }
                    continue;
                }
                if (!value.startsWith("-c") && !"--version".equals(value) && !"--help".equals(value)
                        && !value.matches("-[qQAvhnN2]+")) {
                    throw new IllegalArgumentException("radare2 当前只开放非交互读取/分析；请用 ['-c','ii;is;afl;q','文件']。"
                            + "不能原地写入、调试进程、加载命令脚本或保存项目。");
                }
            }
            if ("radare2".equals(id) && value.startsWith("-c")) {
                String program = value.length() > 2 ? value.substring(2) : ++i < arguments.size() ? arguments.get(i) : "";
                if (program.length() == 0 || !program.matches("[A-Za-z0-9 _.,:;?@/+=*~\\[\\]()-]+")) throw new IllegalArgumentException("radare2 命令包含不允许的执行或写入语法。");
                for (String part : program.split(";")) {
                    String text = part.trim();
                    String operation = text.split("\\s+")[0].split("~", 2)[0];
                    if (text.length() > 0 && !(operation.matches("a[a-zA-Z?]*") || operation.matches("i[a-zA-Z?]*")
                            || operation.matches("p[a-zA-Z?]*") || operation.equals("s") || operation.equals("q")
                            || operation.equals("?") || operation.equals("f") || operation.equals("fj"))) {
                        throw new IllegalArgumentException("radare2 -c 只允许分析、读取、定位和退出命令。");
                    }
                }
                continue;
            }
            int equal = value.indexOf('=');
            String path = equal >= 0 ? value.substring(equal + 1) : value;
            if (path.startsWith("/") || path.startsWith("../") || path.contains("/../") || path.endsWith("/..")) {
                resolve(workDir, absolute(cwd, path).getPath(), temporary);
            }
        }
        boolean probe = arguments.size() == 1 && ("--version".equals(arguments.get(0)) || "--help".equals(arguments.get(0)));
        if ("addr2line".equals(id) && !probe) checkAddr2line(workDir, cwd, arguments, temporary);
        if (!probe && ("objcopy".equals(id) || "ar".equals(id) || "strip".equals(id))) {
            boolean readArchive = "ar".equals(id) && !arguments.isEmpty() && arguments.get(0).matches("-?[tp][a-z]*");
            if (!temporaryCommand && !readArchive) throw new IllegalArgumentException("二进制修改须在 temporary=true 的本轮临时目录中完成，再用 toolkit export 交付。");
            String output = null;
            if ("objcopy".equals(id)) {
                List<String> operands = new ArrayList<String>();
                boolean options = true;
                String flags = "|--strip-all|--strip-debug|--strip-unneeded|--only-keep-debug|--weaken|--localize-hidden|--discard-all|--discard-locals|--preserve-dates|--verbose|-S|-g|-x|-X|-p|-v|";
                String values = "|--input-target|--output-target|--target|--binary-architecture|--only-section|--remove-section|--strip-symbol|--keep-symbol|--localize-symbol|--weaken-symbol|-I|-O|-F|-B|-j|-R|-N|-K|-L|-W|";
                for (int i = 0; i < arguments.size(); i++) {
                    String arg = arguments.get(i);
                    if (options && "--".equals(arg)) { options = false; continue; }
                    if (options && arg.startsWith("-")) {
                        int equal = arg.indexOf('=');
                        String option = equal < 0 ? arg : arg.substring(0, equal);
                        if (values.contains("|" + option + "|")) {
                            if (equal < 0 && ++i >= arguments.size()) throw new IllegalArgumentException("objcopy 选项缺少参数。");
                        } else if (!(flags.contains("|" + arg + "|") || (arg.length() > 2 && !arg.startsWith("--")
                                && "IOFBjRNKLW".indexOf(arg.charAt(1)) >= 0))) {
                            throw new IllegalArgumentException("当前 objcopy 入口不支持该选项，请用标准输入、输出和 section/符号选项：" + arg);
                        }
                    } else operands.add(arg);
                }
                if (operands.size() != 2) throw new IllegalArgumentException("objcopy 必须明确指定一个输入和一个临时输出，不能原地修改。");
                output = operands.get(1);
            } else if ("strip".equals(id)) {
                for (int i = 0; i < arguments.size(); i++) {
                    String arg = arguments.get(i);
                    if ("-o".equals(arg) && ++i < arguments.size()) output = arguments.get(i);
                    else if (arg.startsWith("--output=")) output = arg.substring(9);
                }
                if (output == null) throw new IllegalArgumentException("strip 必须用 -o 指定临时输出文件，不能原地修改项目输入。");
            } else {
                if (arguments.size() < 2 || !arguments.get(0).matches("-?[drqtpmxs][a-z]*")
                        || arguments.get(0).indexOf('a') >= 0 || arguments.get(0).indexOf('b') >= 0
                        || arguments.get(0).indexOf('i') >= 0) {
                    throw new IllegalArgumentException("ar 请先给完整操作标志，再给临时归档路径；不支持会改变归档参数位置的 a/b/i 或前置选项。");
                }
                output = readArchive ? null : arguments.get(1);
                for (int i = 2; i < arguments.size(); i++) {
                    if (arguments.get(i).startsWith("-")) throw new IllegalArgumentException("ar 成员路径不能夹带额外选项，请把修饰符合并到第一项操作标志。");
                }
            }
            if (output != null) temporaryOutput(temporary.directory().getPath(), new ShellLocation(cwd), output);
        }
        if (("objdump".equals(id) || "objcopy".equals(id)) && !probe) {
            for (String arg : arguments) {
                if (arg.startsWith("--dump-section") || arg.startsWith("--add-section") || arg.startsWith("--update-section")) {
                    throw new IllegalArgumentException("当前入口不支持含隐式文件路径的 section 操作，请使用明确的临时副本工作流。");
                }
            }
        }
        if ("apktool".equals(id) && !arguments.isEmpty()
                && !(arguments.get(0).equals("--version") || arguments.get(0).equals("--help"))) {
            String mode = arguments.get(0);
            if (!"d".equals(mode) && !"decode".equals(mode) && !"b".equals(mode) && !"build".equals(mode)) {
                throw new IllegalArgumentException("Apktool 仅开放 decode/build，其他操作请使用官方入口。");
            }
            boolean output = false;
            for (int i = 1; i < arguments.size(); i++) {
                String arg = arguments.get(i), path = null;
                if (arg.startsWith("--frame-path") || "-p".equals(arg)) {
                    throw new IllegalArgumentException("Apktool 框架缓存由本轮私有临时目录管理，不接受外部 frame-path。");
                }
                if ("-o".equals(arg) || "--output".equals(arg) || "-p".equals(arg) || "--frame-path".equals(arg)) {
                    if (++i >= arguments.size()) throw new IllegalArgumentException("Apktool 输出选项缺少路径。");
                    path = arguments.get(i);
                    if ("-o".equals(arg) || "--output".equals(arg)) output = true;
                } else if (arg.startsWith("--output=")) { path = arg.substring(9); output = true; }
                else if (arg.startsWith("--frame-path=")) path = arg.substring(13);
                if (path != null) {
                    if (!temporaryCommand) throw new IllegalArgumentException("Apktool 输出须在 temporary=true 的本轮临时目录；交付时再明确写入项目。");
                    temporaryOutput(temporary.directory().getPath(), new ShellLocation(cwd), path);
                }
            }
            if (!output) throw new IllegalArgumentException("Apktool 必须用 -o 指定本轮临时目录内的输出路径。");
        }
    }

    private static void checkAddr2line(String workDir, String cwd, List<String> arguments, TemporaryWorkspace temporary) {
        String executable = null;
        boolean address = false, options = true;
        for (int i = 0; i < arguments.size(); i++) {
            String arg = arguments.get(i);
            if (options && "--".equals(arg)) { options = false; continue; }
            if (options && ("-e".equals(arg) || "--exe".equals(arg))) {
                if (++i >= arguments.size()) throw new IllegalArgumentException("addr2line -e 缺少输入文件。示例：['-f','-C','-e','文件.so','0x1234']。");
                executable = arguments.get(i);
            } else if (options && (arg.startsWith("--exe=") || arg.startsWith("-e") && arg.length() > 2)) {
                executable = arg.startsWith("--exe=") ? arg.substring(6) : arg.substring(2);
            } else if (options && ("-j".equals(arg) || "--section".equals(arg))) {
                if (++i >= arguments.size()) throw new IllegalArgumentException("addr2line section 选项缺少名称。");
            } else if (!options || !arg.startsWith("-")) {
                if (!arg.matches("(?:0[xX])?[0-9A-Fa-f]+")) throw new IllegalArgumentException("addr2line 地址应是十六进制数；"
                        + "输入文件放在 -e 后。示例：['-f','-C','-e','文件.so','0x1234']。符号名先用 nm 查询地址。");
                address = true;
            }
        }
        if (executable == null || executable.length() == 0 || !address) {
            throw new IllegalArgumentException("addr2line 必须明确给出 -e 输入文件和至少一个地址，不能等待标准输入。"
                    + "示例：['-f','-C','-e','文件.so','0x1234']。");
        }
        File input = absolute(cwd, executable);
        resolve(workDir, input.getPath(), temporary);
        if (executable.matches("(?:0[xX])?[0-9A-Fa-f]+") || executable.indexOf('/') < 0
                && executable.indexOf('.') < 0 && !input.isFile()) {
            throw new IllegalArgumentException("addr2line 的 -e 后必须是输入文件，不能是符号名或地址；"
                    + "示例：['-f','-C','-e','文件.so','0x1234']。");
        }
    }

    /** Check literal output arguments while allowing project paths as command inputs. */
    static void checkTemporaryCommand(String temporaryDir, String command) {
        checkTemporaryCommand(temporaryDir, new ShellLocation(temporaryDir), command);
    }

    private static void checkTemporaryCommand(String temporaryDir, ShellLocation initial, String command) {
        List<ShellWord> words = shellWords(command);
        List<String> arguments = new ArrayList<String>();
        List<ShellLocation> parents = new ArrayList<ShellLocation>();
        ShellLocation location = new ShellLocation(initial);
        for (int i = 0; i < words.size(); i++) {
            ShellWord word = words.get(i);
            for (String sub : word.substitutions) checkTemporaryCommand(temporaryDir, location, sub);
            if (word.syntax && redirect(word.text)) {
                if (++i >= words.size() || words.get(i).syntax) throw new IllegalArgumentException("重定向缺少目标。");
                for (String sub : words.get(i).substitutions) checkTemporaryCommand(temporaryDir, location, sub);
                String target = words.get(i).text;
                if (!descriptorRedirect(word.text) && word.text.indexOf('>') >= 0 && !"/dev/null".equals(target)) {
                    temporaryOutput(temporaryDir, location, target);
                }
            } else if (word.syntax) {
                checkTemporaryArguments(temporaryDir, location, arguments);
                arguments.clear();
                if ("(".equals(word.text)) parents.add(new ShellLocation(location));
                if (")".equals(word.text) && !parents.isEmpty()) location = parents.remove(parents.size() - 1);
            } else if (!allDigits(word.text) || i + 1 >= words.size() || !redirect(words.get(i + 1).text)) {
                arguments.add(word.text);
            }
        }
        checkTemporaryArguments(temporaryDir, location, arguments);
    }

    private static void checkTemporaryArguments(String temporaryDir, ShellLocation location, List<String> arguments) {
        if (arguments.isEmpty()) return;
        int start = 0;
        while (start < arguments.size() && arguments.get(start).matches("[A-Za-z_][A-Za-z0-9_]*=.*")) start++;
        if (start == arguments.size()) return;
        String tool = new File(arguments.get(start++)).getName();
        if ("cd".equals(tool)) {
            if (arguments.size() - start != 1 || arguments.get(start).indexOf('$') >= 0) {
                throw new IllegalArgumentException("cd 必须给出明确路径。");
            }
            location.changeTo(arguments.get(start));
            return;
        }
        boolean each = "touch".equals(tool) || "mkdir".equals(tool) || "mkfifo".equals(tool)
                || "truncate".equals(tool) || "rm".equals(tool) || "rmdir".equals(tool);
        boolean destination = "cp".equals(tool) || "mv".equals(tool) || "ln".equals(tool) || "install".equals(tool);
        boolean compiler = "cc".equals(tool) || "gcc".equals(tool) || "g++".equals(tool)
                || "clang".equals(tool) || "clang++".equals(tool) || "c++".equals(tool);
        String last = null;
        boolean targetDirectory = false;
        boolean outputSpecified = false, compileReadOnly = false;
        List<String> javaSources = new ArrayList<String>();
        boolean positionalOnly = false;
        for (int i = start; i < arguments.size(); i++) {
            String value = arguments.get(i);
            if ("--".equals(value) && !positionalOnly) { positionalOnly = true; continue; }
            if (!positionalOnly && value.startsWith("-")) {
                boolean output = (destination && ("-t".equals(value) || "--target-directory".equals(value)))
                        || (compiler && "-o".equals(value))
                        || (("javac".equals(tool) || "unzip".equals(tool)) && "-d".equals(value))
                        || ("tar".equals(tool) && "-C".equals(value));
                if (output) {
                    if (++i >= arguments.size()) throw new IllegalArgumentException("临时输出选项缺少路径。");
                    temporaryOutput(temporaryDir, location, arguments.get(i));
                    outputSpecified = true;
                    if (destination) targetDirectory = true;
                } else if (destination && value.startsWith("--target-directory=")) {
                    temporaryOutput(temporaryDir, location, value.substring(value.indexOf('=') + 1));
                    targetDirectory = true;
                } else if (compiler && value.startsWith("-o") && value.length() > 2) {
                    temporaryOutput(temporaryDir, location, value.substring(2));
                    outputSpecified = true;
                } else if (compiler && ("-E".equals(value) || "-fsyntax-only".equals(value))) {
                    compileReadOnly = true;
                } else if (("mkdir".equals(tool) || "mkfifo".equals(tool) || "install".equals(tool))
                        && ("-m".equals(value) || "--mode".equals(value))) {
                    i++;
                } else if (("touch".equals(tool) && ("-t".equals(value) || "-d".equals(value)
                        || "--date".equals(value) || "-r".equals(value) || "--reference".equals(value)))
                        || ("truncate".equals(tool) && ("-s".equals(value) || "--size".equals(value)
                        || "-r".equals(value) || "--reference".equals(value)))) {
                    i++;
                }
                continue;
            }
            last = value;
            if ("javac".equals(tool) && value.endsWith(".java")) javaSources.add(value);
            if (each) temporaryOutput(temporaryDir, location, value);
        }
        if (destination && !targetDirectory && last != null) temporaryOutput(temporaryDir, location, last);
        if (compiler && !outputSpecified && !compileReadOnly) temporaryOutput(temporaryDir, location, "a.out");
        if ("javac".equals(tool) && !outputSpecified) {
            for (String source : javaSources) temporaryOutput(temporaryDir, location, source.substring(0, source.length() - 5) + ".class");
        }
    }

    private static void temporaryOutput(String temporaryDir, ShellLocation location, String path) {
        String expanded = path.replace("${TMPDIR}", temporaryDir).replace("$TMPDIR", temporaryDir);
        if (expanded.indexOf('$') >= 0 || expanded.indexOf('`') >= 0) {
            throw new IllegalArgumentException("临时输出请使用 temporary 返回的明确路径，不能使用未知变量。");
        }
        try {
            for (String cwd : location.directories) {
                File output = resolve(temporaryDir, absolute(cwd, expanded).getPath());
                if (".backcast-owner".equals(output.getName())) throw new IllegalArgumentException("不能修改临时目录所有权标记。");
            }
        }
        catch (IllegalArgumentException outside) {
            throw new IllegalArgumentException("临时命令只能写入专用临时目录：" + path
                    + "。请把输出放在 " + temporaryDir + "，正式测试或交付物请明确分类。");
        }
    }

    private static void scanCommand(String workDir, ShellLocation initial, List<ShellWord> words,
            TemporaryWorkspace temporary, boolean temporaryCommand) {
        List<String> arguments = new ArrayList<String>();
        List<ShellLocation> parents = new ArrayList<ShellLocation>();
        ShellLocation location = new ShellLocation(initial);
        for (int i = 0; i < words.size(); i++) {
            ShellWord word = words.get(i);
            for (String sub : word.substitutions) scanCommand(workDir, location, shellWords(sub), temporary, temporaryCommand);
            if (word.syntax && redirect(word.text)) {
                if (++i >= words.size() || words.get(i).syntax) throw new IllegalArgumentException("重定向缺少目标。");
                for (String sub : words.get(i).substitutions) scanCommand(workDir, location, shellWords(sub), temporary, temporaryCommand);
                String target = words.get(i).text;
                if (descriptorRedirect(word.text)) {
                    if (!allDigits(target) && !"-".equals(target)) throw new IllegalArgumentException("文件描述符复制必须指定数字或 -。");
                } else if (!"/dev/null".equals(target)) {
                    boolean writing = word.text.indexOf('>') >= 0;
                    if (writing && !temporaryCommand) throw new IllegalArgumentException("不要用 shell 重定向写项目文件，请使用 write 或 edit；临时输出请用 temporary=true。");
                    if (target.indexOf('$') < 0) {
                        for (String cwd : location.directories) resolve(workDir, absolute(cwd, target).getPath(), temporary);
                    }
                }
            } else if (word.syntax) {
                checkArguments(workDir, location, arguments, temporary);
                arguments.clear();
                if ("(".equals(word.text)) parents.add(new ShellLocation(location));
                if (")".equals(word.text) && !parents.isEmpty()) location = parents.remove(parents.size() - 1);
            } else if (!allDigits(word.text) || i + 1 >= words.size() || !redirect(words.get(i + 1).text)) {
                arguments.add(word.text);
            }
        }
        checkArguments(workDir, location, arguments, temporary);
    }

    private static void checkArguments(String workDir, ShellLocation location, List<String> arguments, TemporaryWorkspace temporary) {
        if (arguments.isEmpty()) return;
        int start = 0;
        while (start < arguments.size() && arguments.get(start).matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
            checkPath(workDir, location, arguments.get(start++), temporary);
        }
        if (start == arguments.size()) return;
        String executable = arguments.get(start++);
        checkPath(workDir, location, executable, temporary);
        String tool = new File(executable).getName();
        if ("tee".equals(tool)) throw new IllegalArgumentException("写文件请使用 write 或 edit，不要使用 tee。");
        if ("cd".equals(tool)) {
            if (arguments.size() - start != 1 || arguments.get(start).indexOf('$') >= 0) {
                throw new IllegalArgumentException("cd 必须给出工作目录内的明确路径。");
            }
            for (String cwd : location.directories) resolve(workDir, absolute(cwd, arguments.get(start)).getPath(), temporary);
            location.changeTo(arguments.get(start));
            return;
        }
        boolean grep = "grep".equals(tool) || "egrep".equals(tool) || "fgrep".equals(tool);
        boolean sed = "sed".equals(tool);
        boolean awk = "awk".equals(tool) || "gawk".equals(tool) || "mawk".equals(tool) || "nawk".equals(tool);
        boolean expression = false, literalNext = false, pathNext = false, options = true;
        for (int i = start; i < arguments.size(); i++) {
            String value = arguments.get(i);
            if (literalNext) { literalNext = false; continue; }
            if (pathNext) { checkPath(workDir, location, value, temporary); pathNext = false; continue; }
            if (options && "--".equals(value)) { options = false; continue; }
            if (options && value.startsWith("-")) {
                int operand = shortArgumentIndex(value, grep ? "efABCm" : sed ? "ef" : awk ? "efFv" : "");
                if (sed && (value.startsWith("--in-place") || (!value.startsWith("--")
                        && value.substring(1, operand < 0 ? value.length() : operand).indexOf('i') >= 0))) {
                    throw new IllegalArgumentException("改文件请使用 edit，不要用 sed 原地改写。");
                }
                if (operand > 0) {
                    char flag = value.charAt(operand);
                    String attached = value.substring(operand + 1);
                    if (flag == 'e' || flag == 'f') expression = true;
                    if (flag == 'f') {
                        if (attached.length() == 0) pathNext = true;
                        else checkPath(workDir, location, attached, temporary);
                    } else if (attached.length() == 0) {
                        literalNext = true;
                    }
                } else if (((grep || sed) && ("--regexp".equals(value) || "--expression".equals(value)))
                        || (awk && "--source".equals(value))) {
                    expression = true; literalNext = true;
                } else if ((grep || sed || awk) && "--file".equals(value)) {
                    expression = true; pathNext = true;
                } else if (grep && ("--after-context".equals(value)
                            || "--before-context".equals(value) || "--context".equals(value)
                            || "--max-count".equals(value))) {
                    literalNext = true;
                } else if (((grep || sed) && (value.startsWith("--regexp=") || value.startsWith("--expression=")))
                        || (awk && value.startsWith("--source="))) {
                    expression = true;
                } else if ((grep || sed || awk) && value.startsWith("--file=")) {
                    expression = true; checkPath(workDir, location, value, temporary);
                } else {
                    checkPath(workDir, location, value, temporary);
                }
                continue;
            }
            if ((grep || sed || awk) && !expression) { expression = true; continue; }
            if (!"echo".equals(tool) && !"printf".equals(tool)) checkPath(workDir, location, value, temporary);
        }
    }

    private static int shortArgumentIndex(String option, String argumentFlags) {
        if (option.startsWith("--")) return -1;
        for (int i = 1; i < option.length(); i++) {
            if (argumentFlags.indexOf(option.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    private static void checkPath(String workDir, ShellLocation location, String value, TemporaryWorkspace temporary) {
        int equal = value.indexOf('=');
        String path = equal >= 0 ? value.substring(equal + 1) : value;
        if ("/dev/null".equals(path)) return;
        if (path.startsWith("/") || path.equals("..") || path.startsWith("../")
                || path.contains("/../") || path.endsWith("/..")) {
            for (String cwd : location.directories) resolve(workDir, absolute(cwd, path).getPath(), temporary);
        }
    }

    // cd may fail, so later relative paths must be valid from every possible directory.
    private static final class ShellLocation {
        final List<String> directories = new ArrayList<String>();
        ShellLocation(String cwd) { directories.add(cwd); }
        ShellLocation(ShellLocation other) { directories.addAll(other.directories); }
        void changeTo(String path) {
            List<String> before = new ArrayList<String>(directories);
            for (String cwd : before) {
                String target = absolute(cwd, path).getPath();
                if (!directories.contains(target)) directories.add(target);
                if (directories.size() > 128) {
                    throw new IllegalArgumentException("多次相对 cd 的执行目录无法确认，请拆分命令并使用明确的绝对路径。");
                }
            }
        }
    }

    private static File absolute(String cwd, String path) {
        File target = new File(path);
        if (!target.isAbsolute() && cwd != null) target = new File(cwd, path);
        try { return target.getCanonicalFile(); }
        catch (IOException error) { throw new IllegalArgumentException("无法确认路径：" + path, error); }
    }

    private static boolean redirect(String value) { return value.startsWith("<") || value.startsWith(">"); }
    private static boolean descriptorRedirect(String value) { return "<&".equals(value) || ">&".equals(value); }

    private static final class ShellWord {
        final String text;
        final boolean syntax;
        final List<String> substitutions;
        ShellWord(String text, boolean syntax) { this(text, syntax, new ArrayList<String>()); }
        ShellWord(String text, boolean syntax, List<String> substitutions) {
            this.text = text; this.syntax = syntax; this.substitutions = new ArrayList<String>(substitutions);
        }
    }

    /** Preserve complete quoted arguments, including newlines and adjacent quote segments. */
    private static List<ShellWord> shellWords(String command) {
        List<ShellWord> words = new ArrayList<ShellWord>();
        StringBuilder word = new StringBuilder();
        List<String> substitutions = new ArrayList<String>();
        boolean started = false;
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\\' && quote != '\'' && i + 1 < command.length()) {
                char next = command.charAt(++i);
                if (next != '\n') {
                    if (quote == '"' && "\\\"$`".indexOf(next) < 0) word.append('\\');
                    word.append(next); started = true;
                }
            } else if (quote != '\'' && c == '$' && i + 1 < command.length() && command.charAt(i + 1) == '(') {
                int end = substitutionEnd(command, i + 1);
                if (i + 2 < command.length() && command.charAt(i + 2) != '(') substitutions.add(command.substring(i + 2, end));
                word.append(command.substring(i, end + 1)); started = true; i = end;
            } else if (quote != '\'' && c == '`') {
                throw new IllegalArgumentException("无法确认反引号命令替换里的路径，请使用明确路径或临时脚本。");
            } else if (quote != 0) {
                if (c == quote) quote = 0; else word.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c; started = true;
            } else if (Character.isWhitespace(c) || "|;&()<>".indexOf(c) >= 0) {
                if (started) { words.add(new ShellWord(word.toString(), false, substitutions)); word.setLength(0); substitutions.clear(); started = false; }
                if (Character.isWhitespace(c) && c != '\n') continue;
                String op = String.valueOf(c);
                if (i + 1 < command.length()) {
                    char next = command.charAt(i + 1);
                    if ((c == '<' || c == '>') && next == '(') throw new IllegalArgumentException("设备使用 sh，不支持 <(...) 或 >(...)；请用 App 私有临时文件或管道。");
                    if (next == c || ((c == '<' || c == '>') && (next == '&' || next == '|'))
                            || (c == '<' && next == '>')) { op += next; i++; }
                }
                if ("<<".equals(op)) throw new IllegalArgumentException("多行临时脚本请用 write(purpose=temporary)，再用 shell 执行。");
                words.add(new ShellWord(op, true));
            } else if (c == '#' && !started) {
                while (i < command.length() && command.charAt(i) != '\n') i++;
                words.add(new ShellWord("\n", true));
            } else {
                word.append(c); started = true;
            }
        }
        if (quote != 0) throw new IllegalArgumentException("命令引号没有闭合。");
        if (started) words.add(new ShellWord(word.toString(), false, substitutions));
        return words;
    }

    private static int substitutionEnd(String command, int begin) {
        int depth = 0;
        char quote = 0;
        for (int i = begin; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\\' && quote != '\'') { i++; continue; }
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) return i;
        }
        throw new IllegalArgumentException("命令替换括号没有闭合。");
    }

    private static boolean allDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        }
        return value.length() > 0;
    }

    /** 同一文件的 edit / write 串行，避免两处同时写。 */
    static Object lock(File file) {
        String key;
        try {
            key = file.getCanonicalPath();
        } catch (Exception e) {
            key = file.getAbsolutePath();
        }
        synchronized (LOCKS) {
            Object lock = LOCKS.get(key);
            if (lock == null) {
                lock = new Object();
                LOCKS.put(key, lock);
            }
            return lock;
        }
    }

    /**
     * 应用看不见 sdcard 上的文件时，exists 会说不存在。
     * 开了 root 就再问一次，避免把没权限说成文件不在。
     */
    static Probe probe(File file, boolean useRoot) {
        boolean direct = false;
        try {
            direct = file.exists();
        } catch (SecurityException ignored) {
            direct = false;
        }
        if (direct) {
            try {
                if (file.isDirectory()) {
                    return Probe.of(true, 0);
                }
                return Probe.of(false, file.length());
            } catch (SecurityException ignored) {
                // 看得到名字却读不了，下面改走 root。
            }
        }
        if (useRoot && RootShell.available()) {
            return rootProbe(file);
        }
        if (!direct && storageHidden(file)) {
            return Probe.denied();
        }
        return Probe.missing();
    }

    static byte[] readBytes(File file, int max) throws Exception {
        return readBytes(file, max, false);
    }

    static byte[] readBytes(File file, int max, boolean useRoot) throws Exception {
        if (file.canRead()) {
            try {
                return readDirect(file, max);
            } catch (Exception e) {
                if (!useRoot || !RootShell.available()) {
                    throw e;
                }
            }
        } else if (!useRoot || !RootShell.available()) {
            throw new IllegalArgumentException("没有权限读取：" + file.getAbsolutePath());
        }
        return RootShell.readAll("cat " + RootShell.quote(file.getAbsolutePath()), max);
    }

    static String readString(File file, int max) throws Exception {
        return new String(readBytes(file, max), "UTF-8");
    }

    static void writeString(File file, String content) throws Exception {
        writeBytes(file, content.getBytes("UTF-8"), false);
    }

    static void writeBytes(File file, byte[] data, boolean useRoot) throws Exception {
        Exception directError = null;
        try {
            writeDirect(file, data);
            return;
        } catch (Exception e) {
            directError = e;
        }
        if (useRoot && RootShell.available()) {
            writeRoot(file, data);
            return;
        }
        if (directError instanceof IllegalArgumentException) {
            throw (IllegalArgumentException) directError;
        }
        throw new IllegalArgumentException("没有权限写入：" + file.getAbsolutePath());
    }

    static boolean sniffNul(File file, boolean useRoot) {
        try {
            byte[] head = readHead(file, 4096, useRoot);
            for (int i = 0; i < head.length; i++) {
                if (head[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 只取开头。文件更长也不算失败。 */
    private static byte[] readHead(File file, int max, boolean useRoot) throws Exception {
        if (file.canRead()) {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] buf = new byte[max];
                int n = in.read(buf);
                if (n <= 0) {
                    return new byte[0];
                }
                if (n == buf.length) {
                    return buf;
                }
                byte[] cut = new byte[n];
                System.arraycopy(buf, 0, cut, 0, n);
                return cut;
            } finally {
                in.close();
            }
        }
        if (!useRoot || !RootShell.available()) {
            throw new IllegalArgumentException("没有权限读取：" + file.getAbsolutePath());
        }
        return RootShell.readAll(
                "head -c " + max + " " + RootShell.quote(file.getAbsolutePath()), max);
    }

    static boolean endsWithNewline(File file, boolean useRoot) {
        if (file.canRead()) {
            return endsDirect(file);
        }
        if (!useRoot || !RootShell.available()) {
            return false;
        }
        try {
            byte[] tail = RootShell.readAll(
                    "tail -c 1 " + RootShell.quote(file.getAbsolutePath()), 8);
            return tail.length > 0 && tail[tail.length - 1] == '\n';
        } catch (Exception e) {
            return false;
        }
    }

    static BufferedReader openText(File file, boolean useRoot) throws Exception {
        if (file.canRead()) {
            return new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        }
        if (!useRoot || !RootShell.available()) {
            throw new IllegalArgumentException("没有权限读取：" + file.getAbsolutePath());
        }
        return new ProcessReader(RootShell.start("cat " + RootShell.quote(file.getAbsolutePath())));
    }

    private static byte[] readDirect(File file, int max) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) {
                    continue;
                }
                if (total > max - n) {
                    throw new IllegalArgumentException("文件超过 " + max + " 字节，不能整篇载入。");
                }
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeDirect(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalArgumentException("无法创建目录：" + parent.getAbsolutePath());
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            if (data != null && data.length > 0) {
                out.write(data);
            }
            out.flush();
        } finally {
            out.close();
        }
    }

    private static void writeRoot(File file, byte[] data) throws Exception {
        String path = file.getAbsolutePath();
        File parent = file.getParentFile();
        String tmp = parent == null
                ? path + ".backcast-tmp"
                : new File(parent, ".backcast-" + System.nanoTime() + ".tmp").getAbsolutePath();
        String cmd = "cat > " + RootShell.quote(tmp) + " && mv " + RootShell.quote(tmp) + " "
                + RootShell.quote(path);
        if (parent != null) {
            cmd = "mkdir -p " + RootShell.quote(parent.getAbsolutePath()) + " && " + cmd;
        }
        boolean moved = false;
        try {
            RootShell.Out out = RootShell.exec(cmd, data == null ? new byte[0] : data, 2048, 60000);
            if (out.exit != 0) {
                throw new IllegalArgumentException(trimErr(out.stderr, "没有权限写入：" + path));
            }
            moved = true;
        } finally {
            if (!moved) {
                try { RootShell.exec("rm -f " + RootShell.quote(tmp), null, 1024, 15000); }
                catch (Exception ignored) { }
            }
        }
    }

    private static Probe rootProbe(File file) {
        String q = RootShell.quote(file.getAbsolutePath());
        String cmd = "if [ -d " + q + " ]; then echo DIR; elif [ -f " + q
                + " ]; then echo FILE; wc -c < " + q + "; elif [ -e " + q
                + " ]; then echo OTHER; else echo MISSING; fi";
        try {
            RootShell.Out out = RootShell.exec(cmd, null, 200, 15000);
            String text = new String(out.stdout, "UTF-8").trim();
            if (text.startsWith("DIR")) {
                return Probe.of(true, 0);
            }
            if (text.startsWith("FILE")) {
                long len = 0;
                int nl = text.indexOf('\n');
                String num = nl >= 0 ? text.substring(nl + 1).trim() : "";
                if (num.length() > 0) {
                    int cut = 0;
                    while (cut < num.length() && num.charAt(cut) != ' ' && num.charAt(cut) != '\t') {
                        cut++;
                    }
                    try {
                        len = Long.parseLong(num.substring(0, cut));
                    } catch (NumberFormatException ignored) {
                        len = 0;
                    }
                }
                return Probe.of(false, len);
            }
            if (text.startsWith("OTHER")) {
                return Probe.of(false, 0);
            }
            if (out.exit != 0 && text.length() == 0) {
                return Probe.denied();
            }
            return Probe.missing();
        } catch (Exception e) {
            return Probe.denied();
        }
    }

    private static boolean storageHidden(File file) {
        String path = file.getAbsolutePath();
        if (!path.startsWith("/storage/") && !path.startsWith("/sdcard/")
                && !"/sdcard".equals(path) && !"/storage".equals(path)) {
            return false;
        }
        File parent = file.getParentFile();
        if (parent == null) {
            return true;
        }
        try {
            return !parent.exists() || !parent.canRead();
        } catch (SecurityException e) {
            return true;
        }
    }

    private static boolean endsDirect(File file) {
        long len = file.length();
        if (len <= 0) {
            return false;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            long skip = len - 1;
            while (skip > 0) {
                long n = in.skip(skip);
                if (n <= 0) {
                    break;
                }
                skip -= n;
            }
            return in.read() == '\n';
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String trimErr(String stderr, String fallback) {
        if (stderr == null) {
            return fallback;
        }
        String t = stderr.trim();
        return t.length() == 0 ? fallback : fallback + "：" + t;
    }
}
