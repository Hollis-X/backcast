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
package brut.androlib.res.decoder;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReader;
import ar.com.hjg.pngj.PngWriter;
import ar.com.hjg.pngj.chunks.PngChunkPLTE;
import ar.com.hjg.pngj.chunks.PngChunkTRNS;
import brut.androlib.exceptions.AndrolibException;
import brut.androlib.exceptions.CantFind9PatchChunkException;
import brut.androlib.res.data.ninepatch.NinePatchData;
import brut.androlib.res.data.ninepatch.OpticalInset;
import brut.util.ExtDataInput;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/** Apktool 2.9.3 nine-patch decoding without desktop or Android graphics dependencies. */
public class Res9patchStreamDecoder implements ResStreamDecoder {
    private static final int NP_CHUNK_TYPE = 0x6e705463;
    private static final int OI_CHUNK_TYPE = 0x6e704c62;
    @Override public void decode(InputStream in, OutputStream out) throws AndrolibException {
        PngReader reader = null;
        PngWriter writer = null;
        try {
            byte[] data = IOUtils.toByteArray(in);
            if (data.length == 0) return;
            NinePatchData patch = getNinePatch(data);
            OpticalInset inset = null;
            try { inset = getOpticalInset(data); }
            catch (CantFind9PatchChunkException absent) {
                // Optical bounds are optional, unlike the compiled nine-patch chunk.
            }
            reader = new PngReader(new ByteArrayInputStream(data));
            ImageInfo input = reader.imgInfo;
            int width = input.cols, height = input.rows;
            validateDivs(patch.xDivs, width);
            validateDivs(patch.yDivs, height);
            if (inset != null && (inset.layoutBoundsLeft < 0 || inset.layoutBoundsLeft > width
                    || inset.layoutBoundsRight < 0 || inset.layoutBoundsRight > width
                    || inset.layoutBoundsTop < 0 || inset.layoutBoundsTop > height
                    || inset.layoutBoundsBottom < 0 || inset.layoutBoundsBottom > height)) {
                throw new IOException("Optical bounds exceed the PNG dimensions");
            }
            int depth = input.bitDepth == 16 ? 16 : 8;
            int maximum = depth == 16 ? 65535 : 255;
            writer = new PngWriter(out, new ImageInfo(width + 2, height + 2, depth, true));
            writer.setShouldCloseStream(false);
            int[] row = new int[(width + 2) * 4];
            for (int x = 1; x <= width; x++) if (inStretch(x, patch.xDivs)) mark(row, x, maximum, false);
            writer.writeRowInt(row);

            // PNGJ decodes filtering, interlacing and packed samples; only channel expansion is local.
            ImageLineInt pixels = (ImageLineInt) reader.readRow();
            PngChunkPLTE palette = reader.getMetadata().getPLTE();
            PngChunkTRNS transparency = reader.getMetadata().getTRNS();
            for (int y = 1; y <= height; y++) {
                Arrays.fill(row, 0);
                copyPixels(pixels.getScanline(), input, palette, transparency, row, maximum);
                if (inStretch(y, patch.yDivs)) mark(row, 0, maximum, false);
                if (y >= patch.padTop + 1 && y <= height - patch.padBottom) mark(row, width + 1, maximum, false);
                if (inset != null && (y <= inset.layoutBoundsTop || y > height - inset.layoutBoundsBottom)) {
                    mark(row, width + 1, maximum, true);
                }
                writer.writeRowInt(row);
                if (y < height) pixels = (ImageLineInt) reader.readRow();
            }
            reader.end();
            Arrays.fill(row, 0);
            for (int x = patch.padLeft + 1; x <= width - patch.padRight; x++) mark(row, x, maximum, false);
            if (inset != null) {
                for (int x = 1; x <= inset.layoutBoundsLeft; x++) mark(row, x, maximum, true);
                for (int x = width; x > width - inset.layoutBoundsRight; x--) mark(row, x, maximum, true);
            }
            writer.writeRowInt(row);
            writer.end();
        } catch (IOException failure) {
            throw new AndrolibException(failure);
        } catch (RuntimeException failure) {
            throw new AndrolibException(failure);
        } finally {
            if (writer != null) writer.close();
            if (reader != null) reader.close();
        }
    }

    private void copyPixels(int[] pixels, ImageInfo info, PngChunkPLTE palette,
                            PngChunkTRNS transparency, int[] row, int maximum) throws IOException {
        int[] paletteAlpha = info.indexed && transparency != null ? transparency.getPalletteAlpha() : null;
        int[] transparentRgb = !info.indexed && !info.greyscale && !info.alpha && transparency != null
                ? transparency.getRGB() : null;
        int inputMaximum = (1 << info.bitDepth) - 1;
        for (int x = 0; x < info.cols; x++) {
            int source = x * info.channels, dest = (x + 1) * 4;
            int red, green, blue, alpha = maximum;
            if (info.indexed) {
                int index = pixels[source];
                if (palette == null || index >= palette.getNentries()) throw new IOException("Invalid PNG palette index");
                int rgb = palette.getEntry(index);
                red = rgb >> 16 & 255; green = rgb >> 8 & 255; blue = rgb & 255;
                if (paletteAlpha != null && index < paletteAlpha.length) alpha = paletteAlpha[index];
            } else if (info.greyscale) {
                red = green = blue = info.bitDepth == 16 ? pixels[source] : pixels[source] * maximum / inputMaximum;
                if (info.alpha) alpha = pixels[source + 1];
                else if (transparency != null && pixels[source] == transparency.getGray()) alpha = 0;
            } else {
                red = pixels[source]; green = pixels[source + 1]; blue = pixels[source + 2];
                if (info.alpha) alpha = pixels[source + 3];
                else if (transparentRgb != null && red == transparentRgb[0] && green == transparentRgb[1]
                        && blue == transparentRgb[2]) alpha = 0;
            }
            row[dest] = red; row[dest + 1] = green; row[dest + 2] = blue; row[dest + 3] = alpha;
        }
    }

    private void validateDivs(int[] divs, int maximum) throws IOException {
        if (divs.length % 2 != 0) throw new IOException("Invalid nine-patch stretch divisions");
        for (int value : divs) if (value < 0 || value > maximum) throw new IOException("Nine-patch stretch exceeds PNG dimensions");
    }

    private boolean inStretch(int position, int[] divs) {
        if (divs.length == 0) return true;
        for (int i = 0; i < divs.length; i += 2) if (position > divs[i] && position <= divs[i + 1]) return true;
        return false;
    }

    private void mark(int[] row, int x, int maximum, boolean optical) {
        int offset = x * 4;
        row[offset] = optical ? maximum : 0;
        row[offset + 1] = 0; row[offset + 2] = 0; row[offset + 3] = maximum;
    }

    private NinePatchData getNinePatch(byte[] data) throws AndrolibException, IOException {
        ExtDataInput input = new ExtDataInput(new ByteArrayInputStream(data));
        find9patchChunk(input, NP_CHUNK_TYPE);
        return NinePatchData.decode(input);
    }

    private OpticalInset getOpticalInset(byte[] data) throws AndrolibException, IOException {
        ExtDataInput input = new ExtDataInput(new ByteArrayInputStream(data));
        find9patchChunk(input, OI_CHUNK_TYPE);
        return OpticalInset.decode(input);
    }

    private void find9patchChunk(DataInput input, int magic) throws AndrolibException, IOException {
        input.skipBytes(8);
        while (true) {
            int size;
            try { size = input.readInt(); }
            catch (IOException failure) { throw new CantFind9PatchChunkException("Cant find nine patch chunk", failure); }
            if (input.readInt() == magic) return;
            input.skipBytes(size + 4);
        }
    }

}
