package android.graphics;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

public final class BitmapFactory {
    public static final class Options {
        public boolean inScaled, inPremultiplied;
        public Bitmap.Config inPreferredConfig;
    }
    public static Options lastOptions;
    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options options) {
        lastOptions = options;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data, offset, length));
            return image == null ? null : new Bitmap(image);
        } catch (Exception invalid) { return null; }
    }
}
