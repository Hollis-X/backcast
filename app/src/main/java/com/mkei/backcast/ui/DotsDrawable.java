package com.mkei.backcast.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/** 三个依次起伏的小圆点，表示还在生成。自己按帧刷新，停掉就不再占用。 */
public final class DotsDrawable extends Drawable implements Animatable, Runnable {
    private static final long PERIOD = 900;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    private boolean running;
    private long startedAt;

    public DotsDrawable(int color, int sizePx) {
        paint.setColor(color);
        size = sizePx;
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float r = b.width() / 11f, gap = r * 3.2f, cx = b.exactCenterX(), cy = b.exactCenterY();
        float t = running ? ((SystemClock.uptimeMillis() - startedAt) % PERIOD) / (float) PERIOD : 0f;
        for (int i = 0; i < 3; i++) {
            float phase = (t - i * 0.18f + 1f) % 1f;
            float wave = phase < 0.5f ? (float) Math.sin(phase * 2 * Math.PI) : 0f;
            paint.setAlpha(Math.round(110 + 145 * Math.max(0f, wave)));
            canvas.drawCircle(cx + (i - 1) * gap, cy - wave * r * 1.4f, r, paint);
        }
        if (running) scheduleSelf(this, SystemClock.uptimeMillis() + 16);
    }

    @Override public void run() { invalidateSelf(); }

    @Override public void start() {
        if (running) return;
        running = true;
        startedAt = SystemClock.uptimeMillis();
        invalidateSelf();
    }

    @Override public void stop() {
        running = false;
        unscheduleSelf(this);
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (!visible) stop();
        return changed;
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
