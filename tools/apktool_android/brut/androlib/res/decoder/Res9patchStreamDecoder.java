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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

/** Apktool 2.9.3 nine-patch decoding using Android graphics instead of desktop AWT. */
public class Res9patchStreamDecoder implements ResStreamDecoder {
    private static final int NP_CHUNK_TYPE = 0x6e705463;
    private static final int OI_CHUNK_TYPE = 0x6e704c62;
    private static final int NP_COLOR = 0xff000000;
    private static final int OI_COLOR = 0xffff0000;

    @Override public void decode(InputStream in, OutputStream out) throws AndrolibException {
        Bitmap image = null, bordered = null;
        try {
            byte[] data = IOUtils.toByteArray(in);
            if (data.length == 0) return;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inPremultiplied = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            image = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (image == null) throw new IOException("Invalid PNG image");
            int width = image.getWidth(), height = image.getHeight();
            bordered = Bitmap.createBitmap(width + 2, height + 2, Bitmap.Config.ARGB_8888);
            bordered.setPremultiplied(false);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                image.getPixels(row, 0, width, 0, y, width, 1);
                bordered.setPixels(row, 0, width, 1, y + 1, width, 1);
            }

            NinePatchData patch = getNinePatch(data);
            drawHLine(bordered, height + 1, patch.padLeft + 1, width - patch.padRight);
            drawVLine(bordered, width + 1, patch.padTop + 1, height - patch.padBottom);
            if (patch.xDivs.length == 0) drawHLine(bordered, 0, 1, width);
            else for (int i = 0; i < patch.xDivs.length; i += 2) {
                drawHLine(bordered, 0, patch.xDivs[i] + 1, patch.xDivs[i + 1]);
            }
            if (patch.yDivs.length == 0) drawVLine(bordered, 0, 1, height);
            else for (int i = 0; i < patch.yDivs.length; i += 2) {
                drawVLine(bordered, 0, patch.yDivs[i] + 1, patch.yDivs[i + 1]);
            }

            try {
                OpticalInset inset = getOpticalInset(data);
                for (int i = 0; i < inset.layoutBoundsLeft; i++) bordered.setPixel(1 + i, height + 1, OI_COLOR);
                for (int i = 0; i < inset.layoutBoundsRight; i++) bordered.setPixel(width - i, height + 1, OI_COLOR);
                for (int i = 0; i < inset.layoutBoundsTop; i++) bordered.setPixel(width + 1, 1 + i, OI_COLOR);
                for (int i = 0; i < inset.layoutBoundsBottom; i++) bordered.setPixel(width + 1, height - i, OI_COLOR);
            } catch (CantFind9PatchChunkException absent) {
                // Optical bounds are optional, unlike the compiled nine-patch chunk.
            }
            if (!bordered.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Cannot encode PNG image");
        } catch (IOException failure) {
            throw new AndrolibException(failure);
        } catch (RuntimeException failure) {
            throw new AndrolibException(failure);
        } finally {
            if (bordered != null) bordered.recycle();
            if (image != null) image.recycle();
        }
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

    private void drawHLine(Bitmap image, int y, int first, int last) {
        for (int x = first; x <= last; x++) image.setPixel(x, y, NP_COLOR);
    }

    private void drawVLine(Bitmap image, int x, int first, int last) {
        for (int y = first; y <= last; y++) image.setPixel(x, y, NP_COLOR);
    }
}
