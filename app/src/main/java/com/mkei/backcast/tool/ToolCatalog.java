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
                    .put("download_available", "apktool".equals(id) || "radare2".equals(id));
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
                "需要设备可执行的 Java 8+ JVM；Android ART 不能直接运行普通 JAR。重打包还需要兼容 Android 的 aapt/aapt2。", "apktool"));
        ENTRIES.add(new Entry("radare2", "radare2", "native", "https://github.com/radareorg/radare2",
                "官方 Android arm/arm64 包可私有安装；ABI、动态链接器和 SELinux 是否允许执行须以探测结果为准。", "radare2", "r2"));
        ENTRIES.add(new Entry("rabin2", "rabin2", "native", "https://github.com/radareorg/radare2",
                "随 radare2 Android 包安装，用于二进制信息提取。", "rabin2"));
        ENTRIES.add(new Entry("objection", "Objection", "dynamic", "https://github.com/sensepost/objection",
                "需要可执行的 Python、已安装的 objection/Frida 依赖和匹配的 Frida server 或 Gadget；Android 支持指目标设备，不能直接运行桌面 wheel。", "objection"));
        String source = "https://sourceware.org/binutils/";
        String dependencies = "需要为 Android ABI 编译的工具及其共享库；可绑定已有安装。Termux GNU 版本可能使用 g 前缀。";
        for (String id : new String[]{"readelf", "objdump", "nm", "strings", "addr2line", "size", "objcopy", "ar", "strip"}) {
            ENTRIES.add(new Entry(id, id, "binutils", source, dependencies, id, "g" + id, "llvm-" + id));
        }
    }

    private ToolCatalog() { }

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
