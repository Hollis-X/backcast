/*
 * Copyright (C) 2010 Ryszard Wisniewski <brut.alll@gmail.com>
 * Copyright (C) 2010 Connor Tumbleson <connor.tumbleson@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brut.util;

import java.util.Locale;

/** ART does not define the desktop JVM's sun.arch.data.model property. */
public class OSDetection {
    private static final String OS = property("os.name", "android").toLowerCase(Locale.US);
    private static final String BIT = property("sun.arch.data.model", "");
    private static final String ARCH = property("os.arch", "").toLowerCase(Locale.US);

    private static String property(String name, String fallback) {
        try {
            String value = System.getProperty(name);
            return value == null || value.trim().length() == 0 ? fallback : value.trim();
        } catch (SecurityException denied) { return fallback; }
    }

    public static boolean isWindows() { return OS.contains("win"); }
    public static boolean isMacOSX() { return OS.contains("mac"); }
    public static boolean isUnix() {
        return OS.contains("android") || OS.contains("nix") || OS.contains("nux")
                || OS.contains("aix") || OS.contains("sunos");
    }
    public static boolean is64Bit() {
        if (isWindows()) {
            String architecture = System.getenv("PROCESSOR_ARCHITECTURE");
            String wow64 = System.getenv("PROCESSOR_ARCHITEW6432");
            if (architecture != null && architecture.endsWith("64") || wow64 != null && wow64.endsWith("64")) return true;
        }
        if ("64".equals(BIT)) return true;
        if ("32".equals(BIT)) return false;
        if (ARCH.length() > 0) return ARCH.contains("64") || "sparcv9".equals(ARCH);
        try {
            return ((Boolean) Class.forName("android.os.Process").getMethod("is64Bit").invoke(null)).booleanValue();
        } catch (Exception unavailable) { return false; }
    }
    public static String returnOS() { return OS; }
}
