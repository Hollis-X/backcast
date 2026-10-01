import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Exercises the actual Apktool CLI on its bundled compiled Android framework. */
public final class ApktoolDecodeRegressionTest {
    private static final String ASSET = "backcast actual APK decode fixture\n";
    private static File workspace;
    private static File output;
    private static String log;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void copy(InputStream input, OutputStream target) throws Exception {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count != 0) target.write(buffer, 0, count);
        }
    }

    private static File fixture(String apktoolJar) throws Exception {
        File file = new File(workspace, "compiled-framework.apk");
        ZipFile upstream = new ZipFile(apktoolJar);
        ZipEntry framework = upstream.getEntry("brut/androlib/android-framework.jar");
        check(framework != null, "Upstream Apktool does not contain the compiled framework fixture");
        ZipInputStream source = new ZipInputStream(upstream.getInputStream(framework));
        ZipOutputStream target = new ZipOutputStream(new FileOutputStream(file));
        try {
            boolean manifest = false;
            boolean resources = false;
            ZipEntry entry;
            while ((entry = source.getNextEntry()) != null) {
                String name = entry.getName();
                if (!"AndroidManifest.xml".equals(name) && !"resources.arsc".equals(name)) continue;
                target.putNextEntry(new ZipEntry(name));
                copy(source, target);
                target.closeEntry();
                manifest |= "AndroidManifest.xml".equals(name);
                resources |= "resources.arsc".equals(name);
            }
            check(manifest && resources, "Fixture lacks actual compiled Android manifest or resources");
            target.putNextEntry(new ZipEntry("assets/decode-fixture.txt"));
            target.write(ASSET.getBytes("UTF-8"));
            target.closeEntry();
        } finally {
            target.close();
            source.close();
            upstream.close();
        }
        return file;
    }

    private static void decode(File fixture) throws Exception {
        File frameworks = new File(workspace, "framework-cache");
        output = new File(workspace, "decoded");
        File home = new File(workspace, "home");
        check(home.mkdir(), "Cannot create isolated fixture home");
        List<String> command = new ArrayList<String>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        command.add("-Djava.awt.headless=true");
        command.add("-Duser.home=" + home.getPath());
        command.add("-Djava.io.tmpdir=" + workspace.getPath());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("brut.apktool.Main");
        command.add("d");
        command.add("-f");
        command.add("-p");
        command.add(frameworks.getPath());
        command.add("-o");
        command.add(output.getPath());
        command.add(fixture.getPath());
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final Throwable[] readingError = new Throwable[1];
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                InputStream input = process.getInputStream();
                try { copy(input, bytes); }
                catch (Throwable failure) { readingError[0] = failure; }
                finally { try { input.close(); } catch (Exception ignored) { } }
            }
        }, "apktool-decode-output");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.currentTimeMillis() + 90000;
        Integer exit = null;
        try {
            while (exit == null && System.currentTimeMillis() < deadline) {
                try { exit = Integer.valueOf(process.exitValue()); }
                catch (IllegalThreadStateException running) { Thread.sleep(25); }
            }
            check(exit != null, "Actual Apktool decode timed out");
            reader.join(5000);
            check(!reader.isAlive() && readingError[0] == null, "Cannot collect actual Apktool decode output");
            log = new String(bytes.toByteArray(), "UTF-8");
            check(exit.intValue() == 0, "Actual Apktool decode failed: " + tail(log));
            check(log.contains("Decoding AndroidManifest.xml") && log.contains("Decoding values"),
                    "Actual resource decoders did not execute: " + tail(log));
        } finally {
            if (exit == null) process.destroy();
        }
    }

    private static String tail(String text) {
        return text.length() > 5000 ? text.substring(text.length() - 5000) : text;
    }

    private static Document xml(File file) throws Exception {
        check(file.isFile(), "Expected decoded XML missing: " + file.getName());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(file);
    }

    private static String text(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try { copy(input, bytes); }
        finally { input.close(); }
        return new String(bytes.toByteArray(), "UTF-8");
    }

    private static void compiledManifestBecomesReadableNamespacedXml() throws Exception {
        Document manifest = xml(new File(output, "AndroidManifest.xml"));
        Element root = manifest.getDocumentElement();
        check("manifest".equals(root.getTagName()) && "android".equals(root.getAttribute("package")),
                "Compiled framework manifest did not decode to the actual Android package");
        NodeList permissions = root.getElementsByTagName("permission");
        check(permissions.getLength() > 100, "Binary manifest permissions were not decoded");
        Element permission = (Element)permissions.item(0);
        check(permission.getAttributeNS("http://schemas.android.com/apk/res/android", "name").startsWith("android.permission."),
                "Framework attribute IDs were not resolved into namespaced Android attributes");
    }

    private static void compiledResourceTableBecomesXmlValuesAndPublicIds() throws Exception {
        Document publicXml = xml(new File(output, "res/values/public.xml"));
        NodeList ids = publicXml.getDocumentElement().getElementsByTagName("public");
        check(ids.getLength() > 1000, "Actual compiled framework resource table was not decoded");
        boolean string = false;
        boolean attribute = false;
        for (int i = 0; i < ids.getLength(); i++) {
            Element symbol = (Element)ids.item(i);
            check(symbol.getAttribute("id").startsWith("0x01"), "Framework resource package ID changed");
            string |= "string".equals(symbol.getAttribute("type"));
            attribute |= "attr".equals(symbol.getAttribute("type"));
        }
        check(string && attribute, "Resource symbols lost their actual Android types");
        Document strings = xml(new File(output, "res/values/strings.xml"));
        check(strings.getElementsByTagName("string").getLength() > 100, "Actual Android string values are missing");
        Document attrs = xml(new File(output, "res/values/attrs.xml"));
        check(attrs.getElementsByTagName("attr").getLength() > 100, "Actual Android attribute values are missing");
    }

    private static void decodeCopiesAssetsAndWritesUsableProjectMetadata() throws Exception {
        check(ASSET.equals(text(new File(output, "assets/decode-fixture.txt"))), "APK asset copy changed fixture bytes");
        String metadata = text(new File(output, "apktool.yml"));
        check(metadata.contains("version: 2.9.3") && metadata.contains("apkFileName: compiled-framework.apk"),
                "Actual decode did not write the expected Apktool project metadata");
        check(new File(output, "original/AndroidManifest.xml").isFile(), "Original compiled manifest was not preserved");
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 2, "Pass upstream Apktool JAR and the runner-owned build directory");
        workspace = new File(args[1], "apktool-decode-fixture");
        check(workspace.mkdir(), "Fixture workspace must be fresh and owned by the runner");
        decode(fixture(args[0]));
        String[] names = {"compiledManifestBecomesReadableNamespacedXml", "compiledResourceTableBecomesXmlValuesAndPublicIds",
                "decodeCopiesAssetsAndWritesUsableProjectMetadata"};
        for (String name : names) {
            try { ApktoolDecodeRegressionTest.class.getDeclaredMethod(name).invoke(null); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError(name, failure.getCause()); }
            System.out.println("PASS " + name);
        }
        System.out.println(names.length + " actual Apktool APK decode tests passed (host JVM; framework fixture has no DEX or nine-patch files)");
    }
}
