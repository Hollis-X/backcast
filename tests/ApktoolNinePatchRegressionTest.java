import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReader;
import ar.com.hjg.pngj.PngWriter;
import ar.com.hjg.pngj.chunks.PngChunkPLTE;
import ar.com.hjg.pngj.chunks.PngChunkTRNS;
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
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.IIOImage;
import javax.imageio.stream.ImageOutputStream;

/** Runs the production pure Java replacement against real PNG chunks and upstream 2.9.3. */
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
        return ImageIO.read(new ByteArrayInputStream(decodePng(compiled)));
    }
    private static byte[] decodePng(byte[] compiled) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new Res9patchStreamDecoder().decode(new ByteArrayInputStream(compiled), bytes);
        return bytes.toByteArray();
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
                "Pure Java output differs from upstream nine-patch pixels");
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
        check(missing, "Missing npTc was silently accepted");
        boolean invalid = false;
        try { new Res9patchStreamDecoder().decode(new ByteArrayInputStream("not png".getBytes("UTF-8")), new ByteArrayOutputStream()); }
        catch (AndrolibException expected) { invalid = true; }
        check(invalid, "Invalid image was accepted");
    }
    private static void emptyInputAndOutputFailureKeepTheOriginalContract() throws Exception {
        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        new Res9patchStreamDecoder().decode(new ByteArrayInputStream(new byte[0]), empty);
        check(empty.size() == 0, "Empty input wrote image data");
        boolean failed = false;
        byte[] compiled = addChunks(png(source(2, 2)), patch(new int[]{0, 2}, new int[]{0, 2}, 0, 0, 0, 0), null);
        try { new Res9patchStreamDecoder().decode(new ByteArrayInputStream(compiled), new OutputStream() {
            @Override public void write(int value) throws java.io.IOException { throw new java.io.IOException("disk full"); }
        }); } catch (AndrolibException expected) { failed = true; }
        check(failed, "Output failure was silently accepted");
    }

    private static byte[] fixture(ImageInfo info, int[][] rows, PngChunkPLTE palette, PngChunkTRNS transparency) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PngWriter writer = new PngWriter(output, info);
        if (palette != null) writer.queueChunk(palette);
        if (transparency != null && (info.indexed || info.greyscale)) writer.queueChunk(transparency);
        for (int[] row : rows) writer.writeRowInt(row);
        writer.end();
        if (transparency != null && !info.indexed && !info.greyscale) {
            // PNGJ 2.1.0's RGB tRNS writer repeats offset zero; use the format's six-byte metadata payload.
            byte[] image = output.toByteArray(); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(image, 0, 33);
            ByteArrayOutputStream values = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(values);
            for (int value : transparency.getRGB()) data.writeShort(value);
            chunk(bytes, "tRNS", values.toByteArray()); bytes.write(image, 33, image.length - 33);
            return bytes.toByteArray();
        }
        return output.toByteArray();
    }
    private static byte[] compileFixture(byte[] image) throws Exception {
        PngReader reader = new PngReader(new ByteArrayInputStream(image));
        int width = reader.imgInfo.cols, height = reader.imgInfo.rows; reader.close();
        return addChunks(image, patch(new int[]{0, width}, new int[]{0, height}, 0, 0, 0, 0), null);
    }
    private static int[][] samples(byte[] image, int depth) {
        PngReader reader = new PngReader(new ByteArrayInputStream(image));
        try {
            check(reader.imgInfo.bitDepth == depth && reader.imgInfo.alpha && !reader.imgInfo.greyscale && !reader.imgInfo.indexed,
                    "Decoded PNG did not preserve bit depth in RGBA output");
            int[][] rows = new int[reader.imgInfo.rows][];
            for (int y = 0; y < rows.length; y++) rows[y] = ((ImageLineInt) reader.readRow()).getScanline().clone();
            reader.end(); return rows;
        } finally { reader.close(); }
    }
    private static void packedPalettesAndTransparencyDecodeAtEveryBitDepth() throws Exception {
        for (int depth : new int[]{1, 2, 4}) {
            ImageInfo info = new ImageInfo(4, 2, depth, false, false, true);
            PngChunkPLTE palette = new PngChunkPLTE(info); int entries = 1 << depth;
            palette.setNentries(entries);
            for (int i = 0; i < entries; i++) palette.setEntry(i, 13 + i * 11, 230 - i * 7, 47 + i * 5);
            PngChunkTRNS alpha = new PngChunkTRNS(info); alpha.setPalletteAlpha(new int[]{0, 127});
            int[][] pixels = {{0, 1, entries - 1, 0}, {entries - 1, 0, 1, entries - 1}};
            byte[] image = fixture(info, pixels, palette, alpha);
            BufferedImage decoded = decode(compileFixture(image));
            for (int y = 0; y < 2; y++) for (int x = 0; x < 4; x++) {
                int index = pixels[y][x]; int expectedAlpha = index == 0 ? 0 : index == 1 ? 127 : 255;
                check(decoded.getRGB(x + 1, y + 1) == (expectedAlpha << 24 | palette.getEntry(index)),
                        "Packed palette or tRNS changed at depth " + depth);
            }
        }
    }
    private static void packedGrayscaleAndTransparencyDecodeAtEveryBitDepth() throws Exception {
        for (int depth : new int[]{1, 2, 4}) {
            ImageInfo info = new ImageInfo(4, 1, depth, false, true, false);
            int maximum = (1 << depth) - 1;
            PngChunkTRNS transparency = new PngChunkTRNS(info); transparency.setGray(maximum);
            int[] pixels = {0, maximum, maximum / 2, 0};
            int[][] decoded = samples(decodePng(compileFixture(fixture(info, new int[][]{pixels}, null, transparency))), 8);
            for (int x = 0; x < pixels.length; x++) {
                int offset = (x + 1) * 4, gray = pixels[x] * 255 / maximum;
                check(decoded[1][offset] == gray && decoded[1][offset + 1] == gray && decoded[1][offset + 2] == gray
                        && decoded[1][offset + 3] == (pixels[x] == maximum ? 0 : 255), "Packed grayscale/tRNS changed at depth " + depth);
            }
        }
    }
    private static void sixteenBitRgbaAndGrayscaleKeepEverySample() throws Exception {
        ImageInfo rgba = new ImageInfo(2, 2, 16, true);
        int[][] pixels = {{1, 0x1234, 65535, 0, 0xabcd, 33, 20000, 0x4321}, {65535, 32768, 40000, 65535, 1000, 29999, 0, 1}};
        int[][] decoded = samples(decodePng(compileFixture(fixture(rgba, pixels, null, null))), 16);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) for (int c = 0; c < 4; c++) {
            check(decoded[y + 1][(x + 1) * 4 + c] == pixels[y][x * 4 + c], "16-bit RGBA sample truncated");
        }
        check(decoded[0][7] == 65535 && decoded[0][0] == 0, "16-bit stretch/corner alpha incorrect");
        ImageInfo gray = new ImageInfo(3, 1, 16, true, true, false);
        int[] grayPixels = {65535, 12, 32768, 65535, 0x1234, 0x4321};
        int[][] grayDecoded = samples(decodePng(compileFixture(fixture(gray, new int[][]{grayPixels}, null, null))), 16);
        for (int x = 0; x < 3; x++) {
            int offset = (x + 1) * 4;
            check(grayDecoded[1][offset] == grayPixels[x * 2] && grayDecoded[1][offset + 1] == grayPixels[x * 2]
                    && grayDecoded[1][offset + 2] == grayPixels[x * 2] && grayDecoded[1][offset + 3] == grayPixels[x * 2 + 1],
                    "16-bit grayscale or alpha sample truncated");
        }
    }
    private static void trueColorTransparencyMatchesOriginalSamples() throws Exception {
        for (int depth : new int[]{8, 16}) {
            ImageInfo info = new ImageInfo(2, 1, depth, false);
            int[] pixels = {depth == 16 ? 0x1234 : 18, 29, 63, 18, 29, 64};
            // PNGJ 2.1.0's RGB tRNS writer uses the same offset for all channels.
            // Emit the fixture chunk directly to test the reader against valid PNG bytes.
            byte[] image = fixture(info, new int[][]{pixels}, null, null);
            ByteArrayOutputStream transparent = new ByteArrayOutputStream(), channels = new ByteArrayOutputStream();
            DataOutputStream values = new DataOutputStream(channels);
            values.writeShort(pixels[0]); values.writeShort(pixels[1]); values.writeShort(pixels[2]);
            transparent.write(image, 0, 33); chunk(transparent, "tRNS", channels.toByteArray());
            transparent.write(image, 33, image.length - 33);
            int[][] decoded = samples(decodePng(compileFixture(transparent.toByteArray())), depth);
            check(decoded[1][4] == pixels[0] && decoded[1][5] == pixels[1] && decoded[1][6] == pixels[2]
                    && decoded[1][7] == 0 && decoded[1][11] == (depth == 16 ? 65535 : 255), "RGB tRNS compared scaled or rounded values");
        }
    }
    private static void interlacedImagesKeepInteriorPixels() throws Exception {
        BufferedImage original = source(7, 5); original.setRGB(2, 3, 0x7f123456);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageOutputStream output = ImageIO.createImageOutputStream(bytes);
        try {
            writer.setOutput(output); ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new IIOImage(original, null, null), parameters); output.flush();
        } finally { writer.dispose(); output.close(); }
        byte[] image = bytes.toByteArray(); check(image[28] == 1, "Fixture is not Adam7 interlaced");
        BufferedImage decoded = decode(compileFixture(image));
        for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) {
            check(decoded.getRGB(x + 1, y + 1) == original.getRGB(x, y), "Interlaced sample differs");
        }
    }
    private static void streamsStayOwnedByCallerAndCorruptPixelsFail() throws Exception {
        byte[] compiled = compileFixture(png(source(2, 2)));
        final boolean[] closed = {false, false};
        ByteArrayInputStream in = new ByteArrayInputStream(compiled) { @Override public void close() { closed[0] = true; } };
        ByteArrayOutputStream out = new ByteArrayOutputStream() { @Override public void close() { closed[1] = true; } };
        new Res9patchStreamDecoder().decode(in, out);
        check(!closed[0] && !closed[1], "Decoder closed caller-owned streams");
        byte[] corrupt = compiled.clone();
        for (int i = 8; i < corrupt.length - 12;) {
            int size = (corrupt[i] & 255) << 24 | (corrupt[i + 1] & 255) << 16 | (corrupt[i + 2] & 255) << 8 | corrupt[i + 3] & 255;
            if (new String(corrupt, i + 4, 4, "US-ASCII").equals("IDAT")) { corrupt[i + 8] ^= 1; break; }
            i += size + 12;
        }
        boolean failed = false;
        try { decode(corrupt); } catch (AndrolibException expected) { failed = true; }
        check(failed, "Corrupt PNG compression/CRC was accepted");
    }
    public static void main(String[] args) throws Exception {
        upstreamJar = args[0];
        String[] names = {"stretchPaddingAndPixelsMatchUpstream", "semiTransparentPixelsKeepExactColorAndAlpha", "absentStretchRangesCoverTheEntireEdge", "optionalOpticalInsetsMatchUpstream",
                "grayscaleAlphaRemainsTransparentAndNeutral", "missingChunkAndInvalidImageFailWithoutLeaks", "emptyInputAndOutputFailureKeepTheOriginalContract",
                "packedPalettesAndTransparencyDecodeAtEveryBitDepth", "packedGrayscaleAndTransparencyDecodeAtEveryBitDepth",
                "sixteenBitRgbaAndGrayscaleKeepEverySample", "trueColorTransparencyMatchesOriginalSamples", "interlacedImagesKeepInteriorPixels",
                "streamsStayOwnedByCallerAndCorruptPixelsFail"};
        for (String name : names) {
            try { ApktoolNinePatchRegressionTest.class.getDeclaredMethod(name).invoke(null); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError(name, failure.getCause()); }
            System.out.println("PASS " + name);
        }
        System.out.println(names.length + " pure Java Apktool nine-patch tests passed");
    }
}
