package android.graphics;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import javax.imageio.ImageIO;

/** Host PNG adapter for exercising the Android decoder's pixel operations. */
public final class Bitmap {
    public enum Config { ARGB_8888 }
    public enum CompressFormat { PNG }
    public static int liveBitmaps;
    private final BufferedImage image;
    private boolean recycled;
    Bitmap(BufferedImage image) { this.image = image; liveBitmaps++; }
    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB));
    }
    public int getWidth() { return image.getWidth(); }
    public int getHeight() { return image.getHeight(); }
    public void setPremultiplied(boolean premultiplied) { }
    public void getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        image.getRGB(x, y, width, height, pixels, offset, stride);
    }
    public void setPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        image.setRGB(x, y, width, height, pixels, offset, stride);
    }
    public void setPixel(int x, int y, int color) { image.setRGB(x, y, color); }
    public boolean compress(CompressFormat format, int quality, OutputStream output) {
        try { return ImageIO.write(image, "png", output); }
        catch (IOException failure) { return false; }
    }
    public void recycle() { if (!recycled) { recycled = true; liveBitmaps--; } }
}
