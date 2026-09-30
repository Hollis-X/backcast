package com.mkei.backcast.tool;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StreamTokenizer;
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

    private static boolean inside(File root, File target) {
        String base = root.getPath(), path = target.getPath();
        return base.equals(path) || path.startsWith(base.endsWith("/") ? base : base + "/");
    }

    static boolean organizedTest(String workDir, File file) throws Exception {
        File root = workDir == null ? null : new File(workDir).getCanonicalFile();
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

    /**
     * 命令的预检：字面路径、重定向和原地改写。
     *
     * 这是尽力而为的检查，不是系统级沙箱：脚本内部自己拼出来的路径拦不住，
     * 目的只是把「写错目录还读回来」这类常见越界挡在调用前。
     */
    static void checkCommand(String workDir, String command) {
        if (command == null || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("命令不合法。");
        }
        try {
            scanCommand(workDir, command);
        } catch (IOException error) {
            throw new IllegalArgumentException("命令无法解析：" + command);
        }
    }

    /** Check literal output arguments while allowing project paths as command inputs. */
    static void checkTemporaryCommand(String temporaryDir, String command) {
        try {
            StreamTokenizer words = new StreamTokenizer(new StringReader(command));
            words.resetSyntax();
            words.wordChars(33, 65535);
            words.whitespaceChars(0, 32);
            words.quoteChar('\''); words.quoteChar('"');
            for (char separator : "|;&()<>`".toCharArray()) words.ordinaryChar(separator);
            words.eolIsSignificant(true);
            List<String> arguments = new ArrayList<String>();
            int type;
            while ((type = words.nextToken()) != StreamTokenizer.TT_EOF) {
                if (type == '|' || type == ';' || type == '&' || type == '(' || type == ')'
                        || type == StreamTokenizer.TT_EOL) {
                    checkTemporaryArguments(temporaryDir, arguments);
                    arguments.clear();
                } else if (words.sval != null) arguments.add(words.sval);
            }
            checkTemporaryArguments(temporaryDir, arguments);
        } catch (IOException error) {
            throw new IllegalArgumentException("无法确认临时命令的输出路径。");
        }
    }

    private static void checkTemporaryArguments(String temporaryDir, List<String> arguments) {
        if (arguments.isEmpty()) return;
        String tool = new File(arguments.get(0)).getName();
        boolean each = "touch".equals(tool) || "mkdir".equals(tool) || "mkfifo".equals(tool)
                || "truncate".equals(tool) || "rm".equals(tool) || "rmdir".equals(tool) || "cd".equals(tool);
        boolean destination = "cp".equals(tool) || "mv".equals(tool) || "ln".equals(tool) || "install".equals(tool);
        boolean compiler = "cc".equals(tool) || "gcc".equals(tool) || "g++".equals(tool)
                || "clang".equals(tool) || "clang++".equals(tool) || "c++".equals(tool);
        String last = null;
        boolean targetDirectory = false;
        boolean positionalOnly = false;
        for (int i = 1; i < arguments.size(); i++) {
            String value = arguments.get(i);
            if ("--".equals(value) && !positionalOnly) { positionalOnly = true; continue; }
            if (!positionalOnly && value.startsWith("-")) {
                boolean output = (destination && ("-t".equals(value) || "--target-directory".equals(value)))
                        || (compiler && "-o".equals(value))
                        || (("javac".equals(tool) || "unzip".equals(tool)) && "-d".equals(value))
                        || ("tar".equals(tool) && "-C".equals(value));
                if (output) {
                    if (++i >= arguments.size()) throw new IllegalArgumentException("临时输出选项缺少路径。");
                    temporaryOutput(temporaryDir, arguments.get(i));
                    if (destination) targetDirectory = true;
                } else if (destination && value.startsWith("--target-directory=")) {
                    temporaryOutput(temporaryDir, value.substring(value.indexOf('=') + 1));
                    targetDirectory = true;
                } else if (compiler && value.startsWith("-o") && value.length() > 2) {
                    temporaryOutput(temporaryDir, value.substring(2));
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
            if (each) temporaryOutput(temporaryDir, value);
        }
        if (destination && !targetDirectory && last != null) temporaryOutput(temporaryDir, last);
    }

    private static void temporaryOutput(String temporaryDir, String path) {
        String expanded = path.replace("${TMPDIR}", temporaryDir).replace("$TMPDIR", temporaryDir);
        if (expanded.indexOf('$') >= 0 || expanded.indexOf('`') >= 0) {
            throw new IllegalArgumentException("临时输出请使用 temporary 返回的明确路径，不能使用未知变量。");
        }
        try { resolve(temporaryDir, expanded); }
        catch (IllegalArgumentException outside) {
            throw new IllegalArgumentException("临时命令只能写入专用临时目录：" + path
                    + "。请把输出放在 " + temporaryDir + "，正式测试或交付物请明确分类。");
        }
    }

    private static void scanCommand(String workDir, String command) throws IOException {
        StreamTokenizer words = new StreamTokenizer(new StringReader(command));
        words.resetSyntax();
        words.wordChars(33, 65535);
        words.whitespaceChars(0, 32);
        words.quoteChar('\''); words.quoteChar('"');
        for (char separator : "|;&()<>`".toCharArray()) words.ordinaryChar(separator);
        words.eolIsSignificant(true);
        boolean executable = true, changeDir = false, writeRedirect = false, readRedirect = false;
        String tool = "";
        int type;
        while ((type = words.nextToken()) != StreamTokenizer.TT_EOF) {
            if (type == '`') {
                throw new IllegalArgumentException("无法确认命令替换里的路径，请直接给出工作目录内的路径。");
            }
            if (type == '<') { readRedirect = true; continue; }
            if (type == '>') { writeRedirect = true; continue; }
            if (type == '|' || type == ';' || type == '&' || type == '(' || type == ')'
                    || type == StreamTokenizer.TT_EOL) {
                executable = true; changeDir = false; writeRedirect = false; readRedirect = false;
                continue;
            }
            if (words.sval == null) continue;
            String value = words.sval;
            if (readRedirect || writeRedirect) {
                if (writeRedirect && !value.startsWith("/dev/")) {
                    throw new IllegalArgumentException("不要用 shell 重定向写文件，"
                            + "请使用 write 或 edit，并把文件留在工作目录内。");
                }
                if (value.startsWith("/")) resolve(workDir, value);
                readRedirect = false; writeRedirect = false;
                continue;
            }
            if (executable) {
                // 2>/dev/null 这类文件描述符前缀不是命令名。
                if (allDigits(value)) continue;
                tool = new File(value).getName();
                if ("tee".equals(tool)) {
                    throw new IllegalArgumentException("写文件请使用 write 或 edit，不要使用 tee。");
                }
                changeDir = "cd".equals(tool);
                executable = false;
                continue;
            }
            if ("sed".equals(tool) && (value.startsWith("-i") || value.startsWith("--in-place"))) {
                throw new IllegalArgumentException("改文件请使用 edit，不要用 sed 原地改写。");
            }
            int equal = value.indexOf('=');
            String path = equal >= 0 ? value.substring(equal + 1) : value;
            if (path.startsWith("/") || path.equals("..") || path.startsWith("../")) {
                resolve(workDir, path);
            }
        }
        if (changeDir) {
            throw new IllegalArgumentException("cd 必须给出工作目录内的明确路径。");
        }
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
