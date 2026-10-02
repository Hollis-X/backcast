package com.mkei.backcast.tool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Resolve the ART command actually present on this device, including APEX installs. */
public final class ArtRuntimeLauncher {
    public interface Probe {
        /** Return the executable ELF's bitness, or zero when it cannot be used. */
        int executableBits(String path);
    }

    static final Probe DEVICE = new Probe() {
        public int executableBits(String path) { return inspect(new File(path)); }
    };

    static final class Selection {
        final String path;
        final int bits;
        Selection(String path, int bits) { this.path = path; this.bits = bits; }
    }

    private static final String[] DIRECTORIES = {
        "/system/bin/", "/apex/com.android.art/bin/", "/apex/com.android.runtime/bin/"
    };

    private ArtRuntimeLauncher() { }

    static Selection select(String abi, Probe probe) throws IOException {
        int preferred = "arm64-v8a".equals(abi) ? 64 : 32;
        for (int bits : new int[]{preferred, preferred == 64 ? 32 : 64}) {
            for (String directory : DIRECTORIES) {
                String path = directory + "dalvikvm" + bits;
                if (probe.executableBits(path) == bits) return new Selection(path, bits);
            }
            for (String directory : DIRECTORIES) {
                String path = directory + "dalvikvm";
                if (probe.executableBits(path) == bits) return new Selection(path, bits);
            }
        }
        throw new IOException("Apktool 无法启动：设备没有可执行的 ART 入口。已检查 /system/bin、"
                + "/apex/com.android.art/bin 和 /apex/com.android.runtime/bin 下的 dalvikvm64、dalvikvm32、dalvikvm。"
                + "其他内置工具仍可独立使用。");
    }

    static int inspect(File file) {
        try {
            if (!file.isFile() || !file.canExecute()) return 0;
            FileInputStream input = new FileInputStream(file);
            try {
                byte[] header = new byte[20]; int count = 0;
                while (count < header.length) {
                    int read = input.read(header, count, header.length - count);
                    if (read < 0) return 0;
                    count += read;
                }
                if (header[0] != 127 || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                        || header[5] != 1) return 0;
                int machine = (header[18] & 255) | ((header[19] & 255) << 8);
                if (header[4] == 2 && machine == 183) return 64;
                if (header[4] == 1 && machine == 40) return 32;
                return 0;
            } finally { input.close(); }
        } catch (IOException unavailable) { return 0; }
        catch (SecurityException inaccessible) { return 0; }
    }
}
