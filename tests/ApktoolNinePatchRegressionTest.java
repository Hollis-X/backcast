import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import brut.androlib.exceptions.AndrolibException;
import brut.androlib.exceptions.CantFind9PatchChunkException;
import brut.androlib.res.decoder.Res9patchStreamDecoder;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Runs the production Android replacement against real PNG chunks and upstream 2.9.3. */
public final class ApktoolNinePatchRegressionTest {
    private static String upstreamJar;
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static BufferedImage source(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int alpha = 255;
            image.setRGB(x, y, (alpha << 24) | ((32 + x * 23) << 16) | ((17 + y * 29) << 8) | (53 + x + y));
        }
        return image;
    }
    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
    }
    private static byte[] patch(int[] xDivs, int[] yDivs, int left, int right, int top, int bottom) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(0); out.writeByte(xDivs.length); out.writeByte(yDivs.length); out.writeByte(0);
        out.writeLong(0); out.writeInt(left); out.writeInt(right); out.writeInt(top); out.writeInt(bottom); out.writeInt(0);
        for (int value : xDivs) out.writeInt(value);
        for (int value : yDivs) out.writeInt(value);
        return bytes.toByteArray();
    }
    private static byte[] optical(int left, int top, int right, int bottom) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        for (int value : new int[]{left, top, right, bottom}) out.writeInt(Integer.reverseBytes(value));
        return bytes.toByteArray();
    }
    private static byte[] addChunks(byte[] png, byte[] patch, byte[] optical) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(png, 0, 33);
        if (patch != null) chunk(bytes, "npTc", patch);
        if (optical != null) chunk(bytes, "npLb", optical);
        bytes.write(png, 33, png.length - 33); return bytes.toByteArray();
    }
    private static void chunk(OutputStream output, String type, byte[] content) throws Exception {
        DataOutputStream data = new DataOutputStream(output); byte[] name = type.getBytes("US-ASCII");
        data.writeInt(content.length); data.write(name); data.write(content);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(content); data.writeInt((int) crc.getValue());
    }
    private static BufferedImage decode(byte[] compiled) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new Res9patchStreamDecoder().decode(new ByteArrayInputStream(compiled), bytes);
        check(Bitmap.liveBitmaps == 0, "Decoder leaked bitmap memory");
        check(!BitmapFactory.lastOptions.inScaled && !BitmapFactory.lastOptions.inPremultiplied
                && BitmapFactory.lastOptions.inPreferredConfig == Bitmap.Config.ARGB_8888, "Pixel decode is scaled or premultiplied");
        return ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
    }
    private static BufferedImage upstream(byte[] compiled) throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[]{new java.io.File(upstreamJar).toURI().toURL()}, null);
        try {
            Object decoder = loader.loadClass("brut.androlib.res.decoder.Res9patchStreamDecoder").getConstructor().newInstance();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            decoder.getClass().getMethod("decode", InputStream.class, OutputStream.class)
                    .invoke(decoder, new ByteArrayInputStream(compiled), bytes);
            return ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        } finally { loader.close(); }
    }
    private static void sameImage(BufferedImage left, BufferedImage right) {
        check(left.getWidth() == right.getWidth() && left.getHeight() == right.getHeight(), "Nine-patch dimensions differ");
        int width = left.getWidth(), height = left.getHeight();
        check(Arrays.equals(left.getRGB(0, 0, width, height, null, 0, width), right.getRGB(0, 0, width, height, null, 0, width)),
                "Android output differs from upstream nine-patch pixels");
    }
    private static void stretchPaddingAndPixelsMatchUpstream() throws Exception {
        BufferedImage original = source(6, 5);
        byte[] compiled = addChunks(png(original), patch(new int[]{1, 3, 4, 6}, new int[]{0, 2, 3, 5}, 1, 2, 1, 1), null);
        BufferedImage decoded = decode(compiled); sameImage(decoded, upstream(compiled));
        check(decoded.getWidth() == 8 && decoded.getHeight() == 7, "Decoder did not add the source border");
        for (int y = 0; y < 5; y++) for (int x = 0; x < 6; x++) {
            check(decoded.getRGB(x + 1, y + 1) == original.getRGB(x, y), "Interior RGB or alpha changed");
        }
        check(decoded.getRGB(0, 0) == 0 && decoded.getRGB(2, 0) == 0xff000000
                && decoded.getRGB(4, 0) == 0 && decoded.getRGB(2, 6) == 0xff000000, "Stretch or padding marks differ");
    }
    private static void semiTransparentPixelsKeepExactColorAndAlpha() throws Exception {
        BufferedImage original = source(3, 2);
        original.setRGB(0, 0, 0x7f352753); original.setRGB(1, 0, 0x40397c23); original.setRGB(2, 1, 0xff034567);
        BufferedImage decoded = decode(addChunks(png(original), patch(new int[]{0, 3}, new int[]{0, 2}, 0, 0, 0, 0), null));
        for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++) {
            check(decoded.getRGB(x + 1, y + 1) == original.getRGB(x, y), "Pixel copying changed semi-transparent color values");
        }
    }
    private static void absentStretchRangesCoverTheEntireEdge() throws Exception {
        byte[] compiled = addChunks(png(source(3, 4)), patch(new int[0], new int[0], 0, 0, 0, 0), null);
        BufferedImage decoded = decode(compiled); sameImage(decoded, upstream(compiled));
        for (int x = 1; x <= 3; x++) check(decoded.getRGB(x, 0) == 0xff000000, "Missing full horizontal stretch");
        for (int y = 1; y <= 4; y++) check(decoded.getRGB(0, y) == 0xff000000, "Missing full vertical stretch");
    }
    private static void optionalOpticalInsetsMatchUpstream() throws Exception {
        byte[] compiled = addChunks(png(source(6, 5)), patch(new int[]{0, 6}, new int[]{0, 5}, 2, 2, 2, 2), optical(1, 2, 2, 1));
        BufferedImage decoded = decode(compiled); sameImage(decoded, upstream(compiled));
        check(decoded.getRGB(1, 6) == 0xffff0000 && decoded.getRGB(6, 6) == 0xffff0000
                && decoded.getRGB(7, 1) == 0xffff0000 && decoded.getRGB(7, 5) == 0xffff0000, "Optical red bounds were lost");
    }
    private static void grayscaleAlphaRemainsTransparentAndNeutral() throws Exception {
        ComponentColorModel colors = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), new int[]{8, 8}, true, false,
                java.awt.Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 3, 2, 2, null);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++) { raster.setSample(x, y, 0, 80 + x * 40); raster.setSample(x, y, 1, 64 + y * 128); }
        BufferedImage gray = new BufferedImage(colors, raster, false, null);
        BufferedImage decoded = decode(addChunks(png(gray), patch(new int[]{0, 3}, new int[]{0, 2}, 0, 0, 0, 0), null));
        for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++) {
            int pixel = decoded.getRGB(x + 1, y + 1);
            check((pixel >>> 24) == 64 + y * 128 && (pixel >> 16 & 255) == (pixel >> 8 & 255)
                    && (pixel >> 8 & 255) == (pixel & 255), "Grayscale or alpha channels were lost");
        }
    }
    private static void missingChunkAndInvalidImageFailWithoutLeaks() throws Exception {
        boolean missing = false;
        try { decode(png(source(2, 2))); } catch (CantFind9PatchChunkException expected) { missing = true; }
        check(missing && Bitmap.liveBitmaps == 0, "Missing npTc was silently accepted or leaked memory");
        boolean invalid = false;
        try { new Res9patchStreamDecoder().decode(new ByteArrayInputStream("not png".getBytes("UTF-8")), new ByteArrayOutputStream()); }
        catch (AndrolibException expected) { invalid = true; }
        check(invalid && Bitmap.liveBitmaps == 0, "Invalid image was accepted or leaked memory");
    }
    private static void emptyInputAndOutputFailureKeepTheOriginalContract() throws Exception {
        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        new Res9patchStreamDecoder().decode(new ByteArrayInputStream(new byte[0]), empty);
        check(empty.size() == 0 && Bitmap.liveBitmaps == 0, "Empty input wrote image data");
        boolean failed = false;
        byte[] compiled = addChunks(png(source(2, 2)), patch(new int[]{0, 2}, new int[]{0, 2}, 0, 0, 0, 0), null);
        try { new Res9patchStreamDecoder().decode(new ByteArrayInputStream(compiled), new OutputStream() {
            @Override public void write(int value) throws java.io.IOException { throw new java.io.IOException("disk full"); }
        }); } catch (AndrolibException expected) { failed = true; }
        check(failed && Bitmap.liveBitmaps == 0, "Output failure did not release bitmap memory");
    }
    public static void main(String[] args) throws Exception {
        upstreamJar = args[0];
        String[] names = {"stretchPaddingAndPixelsMatchUpstream", "semiTransparentPixelsKeepExactColorAndAlpha", "absentStretchRangesCoverTheEntireEdge", "optionalOpticalInsetsMatchUpstream",
                "grayscaleAlphaRemainsTransparentAndNeutral", "missingChunkAndInvalidImageFailWithoutLeaks", "emptyInputAndOutputFailureKeepTheOriginalContract"};
        for (String name : names) {
            try { ApktoolNinePatchRegressionTest.class.getDeclaredMethod(name).invoke(null); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError(name, failure.getCause()); }
            System.out.println("PASS " + name);
        }
        System.out.println(names.length + " Android Apktool nine-patch tests passed");
    }
}
