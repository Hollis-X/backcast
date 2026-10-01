import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Locale;

/** Loads the actual replacement in isolated class loaders with ART-like properties. */
public final class ApktoolOsDetectionRegressionTest {
    private static URL replacement;
    private static String apktoolJar;
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void property(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }
    private static Object invoke(Class<?> type, String method) throws Exception { return type.getMethod(method).invoke(null); }
    private static void verify(String os, String dataModel, String architecture, boolean unix, boolean bits64, String expectedOs) throws Exception {
        String oldOs = System.getProperty("os.name"), oldBits = System.getProperty("sun.arch.data.model"), oldArch = System.getProperty("os.arch");
        URLClassLoader loader = new URLClassLoader(new URL[]{replacement}, null);
        try {
            property("os.name", os); property("sun.arch.data.model", dataModel); property("os.arch", architecture);
            Class<?> detector = loader.loadClass("brut.util.OSDetection");
            check(unix == ((Boolean)invoke(detector, "isUnix")).booleanValue(), "Unix detection differs: " + os);
            check(bits64 == ((Boolean)invoke(detector, "is64Bit")).booleanValue(), "Process width detection differs: " + architecture);
            check(expectedOs.equals(invoke(detector, "returnOS")), "OS normalization differs");
        } finally {
            property("os.name", oldOs); property("sun.arch.data.model", oldBits); property("os.arch", oldArch); loader.close();
        }
    }
    private static void missingSunPropertyWorksOnArm64AndArm32() throws Exception {
        verify("Linux", null, "aarch64", true, true, "linux");
        verify("Android", null, "arm64", true, true, "android");
        verify("Linux", null, "armv7l", true, false, "linux");
    }
    private static void missingOsNameAndAllPropertiesDoNotCrash() throws Exception {
        verify(null, null, "aarch64", true, true, "android");
        verify(null, null, null, true, false, "android");
        verify(" ", " ", " ", true, false, "android");
    }
    private static void validProcessDataModelOverridesDeviceArchitecture() throws Exception {
        verify("Linux", "32", "aarch64", true, false, "linux");
        verify("Linux", "64", "armv7l", true, true, "linux");
        verify("Linux", "invalid", "x86_64", true, true, "linux");
    }
    private static void localeDoesNotChangeLinuxClassification() throws Exception {
        Locale prior = Locale.getDefault();
        try { Locale.setDefault(new Locale("tr", "TR")); verify("LINUX", null, "AARCH64", true, true, "linux"); }
        finally { Locale.setDefault(prior); }
        verify("Mac OS X", "64", "x86_64", false, true, "mac os x");
    }
    private static void actualApktoolVersionRunsWithMissingSunAndOsProperties() throws Exception {
        String javaExecutable = new java.io.File(System.getProperty("java.home"), "bin/java").getPath();
        String classpath = new java.io.File(replacement.toURI()).getPath() + java.io.File.pathSeparator + apktoolJar;
        Process process = new ProcessBuilder(javaExecutable, "-cp", classpath, "ApktoolOsDetectionRegressionTest", "probe").redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); InputStream input = process.getInputStream();
        try { byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count); }
        finally { input.close(); }
        int exit = process.waitFor(); String output = new String(bytes.toByteArray(), "UTF-8");
        check(exit == 0 && output.contains("2.9.3") && !output.contains("Exception"), "Actual Apktool startup failed: " + output);
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "probe".equals(args[0])) {
            System.clearProperty("sun.arch.data.model"); System.clearProperty("os.name"); System.setProperty("os.arch", "aarch64");
            brut.apktool.Main.main(new String[]{"--version"}); return;
        }
        replacement = new java.io.File(args[0]).toURI().toURL(); apktoolJar = args[1];
        String[] names = {"missingSunPropertyWorksOnArm64AndArm32", "missingOsNameAndAllPropertiesDoNotCrash",
                "validProcessDataModelOverridesDeviceArchitecture", "localeDoesNotChangeLinuxClassification",
                "actualApktoolVersionRunsWithMissingSunAndOsProperties"};
        for (String name : names) {
            try { ApktoolOsDetectionRegressionTest.class.getDeclaredMethod(name).invoke(null); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError(name, failure.getCause()); }
            System.out.println("PASS " + name);
        }
        System.out.println(names.length + " Android Apktool OS detection tests passed");
    }
}
