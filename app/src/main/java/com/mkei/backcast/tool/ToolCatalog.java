package com.mkei.backcast.tool;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Sources are fixed here; model input cannot turn installation into an arbitrary download. */
public final class ToolCatalog {
    public static final class Entry {
        public final String id, name, group, source, requirements;
        public final String[] aliases;

        Entry(String id, String name, String group, String source, String requirements, String... aliases) {
            this.id = id; this.name = name; this.group = group; this.source = source;
            this.requirements = requirements; this.aliases = aliases;
        }

        public JSONObject json() throws Exception {
            JSONArray commands = new JSONArray();
            for (String alias : aliases) commands.put(alias);
            return new JSONObject().put("id", id).put("name", name).put("group", group)
                    .put("source", source).put("requirements", requirements).put("commands", commands)
                    .put("bundled", true).put("download_available", false)
                    .put("usage", usage(id)).put("argument_examples", examples(id));
        }
    }

    public static final class Artifact {
        public final String id, version, abi, url, sha256, format, prefix;
        public final long maxBytes;

        Artifact(String id, String version, String abi, String url, String sha256,
                String format, String prefix, long maxBytes) {
            this.id = id; this.version = version; this.abi = abi; this.url = url;
            this.sha256 = sha256; this.format = format; this.prefix = prefix; this.maxBytes = maxBytes;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<Entry>();
    static {
        ENTRIES.add(new Entry("apktool", "Apktool", "android", "https://github.com/iBotPeaches/Apktool",
                "APK 内置 Apktool 2.9.3 DEX JAR，使用 Android ART 运行，附带 Android aapt2。需要 Android 8.0+ ARM/ARM64。", "apktool"));
        ENTRIES.add(new Entry("radare2", "radare2", "native", "https://github.com/radareorg/radare2",
                "APK 内置官方 Android ARM/ARM64 原生包与数据库，自动释放到 App 私有路径。执行权限以设备探测为准。", "radare2", "r2"));
        ENTRIES.add(new Entry("rabin2", "rabin2", "native", "https://github.com/radareorg/radare2",
                "随 radare2 Android 包安装，用于二进制信息提取。", "rabin2"));
        ENTRIES.add(new Entry("objection", "Objection", "dynamic", "https://github.com/sensepost/objection",
                "APK 内置 Objection 1.12.5、Android Python、Frida Python 绑定及 server 和全部依赖。跨应用动态分析需要 root。", "objection"));
        String source = "https://sourceware.org/binutils/";
        String dependencies = "APK 内置 GNU binutils Android ARM/ARM64 编译版本及全部共享库，无需安装 Termux 或配置路径。";
        for (String id : new String[]{"readelf", "objdump", "nm", "strings", "addr2line", "size", "objcopy", "ar", "strip"}) {
            ENTRIES.add(new Entry(id, id, "binutils", source, dependencies, id, "g" + id, "llvm-" + id));
        }
    }

    private ToolCatalog() { }

    private static String usage(String id) {
        if ("radare2".equals(id)) return "单次非交互分析，自动退出；-c 给读取/分析命令。-e 只接受少量显式配置，不能把文件路径放在 -e 后。";
        if ("rabin2".equals(id)) return "-I 信息、-i 导入、-s 符号、-S section、-e 入口；这些是读取选项，不是 radare2 命令脚本。";
        if ("addr2line".equals(id)) return "-e 后必须是输入文件，后面的操作数是十六进制地址；符号名先用 nm 找地址。";
        if ("objcopy".equals(id)) return "选项之后先输入文件，再临时输出文件；必须显式输出，-x/--discard-all 删除非全局符号。";
        if ("strip".equals(id)) return "用 -o 指定临时输出，输入文件最后给出；不能原地修改项目输入。";
        if ("ar".equals(id)) return "rcs 创建临时归档；t 列出已有 .a 归档；单个 .so/.o 不是归档。";
        if ("apktool".equals(id)) return "decode/build 必须 -o 显式临时输出；正式交付用 export。";
        if ("objection".equals(id)) return "目标包名必须有运行进程，也可用 PID；version 只验证入口。run 单次动态命令需要 root 和设备 Frida/ART 兼容。";
        return "程序参数单独放在 arguments 数组中，输入使用工作目录或本轮私有临时目录的明确路径。";
    }

    private static JSONArray examples(String id) {
        JSONArray examples = new JSONArray();
        if ("radare2".equals(id)) examples.put(new JSONArray().put("-c").put("ii;is;afl;q").put("/工作目录/输入.so"));
        else if ("rabin2".equals(id)) examples.put(new JSONArray().put("-I").put("/工作目录/输入.so"))
                .put(new JSONArray().put("-i").put("/工作目录/输入.so"));
        else if ("addr2line".equals(id)) examples.put(new JSONArray().put("-f").put("-C").put("-e").put("/工作目录/输入.so").put("0x1234"));
        else if ("objcopy".equals(id)) examples.put(new JSONArray().put("-O").put("binary").put("/工作目录/输入.so").put("/本轮临时目录/输出.bin"));
        else if ("strip".equals(id)) examples.put(new JSONArray().put("-o").put("/本轮临时目录/输出.so").put("/工作目录/输入.so"));
        else if ("ar".equals(id)) examples.put(new JSONArray().put("rcs").put("/本轮临时目录/输出.a").put("/工作目录/输入.o"))
                .put(new JSONArray().put("t").put("/本轮临时目录/输出.a"));
        else if ("apktool".equals(id)) examples.put(new JSONArray().put("d").put("/工作目录/输入.apk").put("-o").put("/本轮临时目录/decoded"));
        else if ("objection".equals(id)) examples.put(new JSONArray().put("-n").put("运行中的包名或PID").put("run").put("memory list modules"));
        else examples.put(new JSONArray().put("--version"));
        return examples;
    }

    public static Entry get(String id) {
        for (Entry entry : ENTRIES) if (entry.id.equals(id)) return entry;
        throw new IllegalArgumentException("未知工具：" + id);
    }

    public static JSONArray list() throws Exception {
        JSONArray result = new JSONArray();
        for (Entry entry : ENTRIES) result.put(entry.json());
        return result;
    }

    public static Artifact artifact(String id, String abi) {
        if ("apktool".equals(id)) return new Artifact(id, "3.0.3", "any",
                "https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar",
                "dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423", "jar", "", 32L * 1024 * 1024);
        if ("radare2".equals(id)) {
            String suffix, digest;
            if ("arm64-v8a".equals(abi) || "aarch64".equals(abi)) {
                suffix = "aarch64"; digest = "228bf58c44fbd9f3afd37ba545ef73155415150849dbd576ad1fd89789082c9f";
            } else if ("armeabi-v7a".equals(abi) || "armeabi".equals(abi) || "arm".equals(abi)) {
                suffix = "arm"; digest = "1ecca02220f0309a7a6d5349fc914e1d930b73dca09a27567abc862dd9df6c2b";
            } else throw new IllegalArgumentException("官方固定 radare2 Android 包没有当前 ABI：" + abi + "。请绑定兼容的已有安装。");
            return new Artifact(id, "6.2.2", suffix,
                    "https://github.com/radareorg/radare2/releases/download/6.2.2/radare2-6.2.2-android-" + suffix + ".tar.gz",
                    digest, "tar.gz", "data/data/org.radare.radare2installer/radare2/", 48L * 1024 * 1024);
        }
        throw new IllegalArgumentException(get(id).requirements + " 本工具没有经验证的自动下载包。");
    }
}
