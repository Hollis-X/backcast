package com.mkei.backcast.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** 上下文窗口的分段条：每段一种颜色，按占比铺开，其余是浅灰底。 */
public final class UsageBar extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private float[] parts = new float[0];
    private int[] colors = new int[0];
    private int track = 0xFFE9E9ED;

    public UsageBar(Context context) { super(context); }

    /** parts 是各段占整个窗口的比例（0–1），按顺序从左往右画。 */
    public void setParts(float[] parts, int[] colors, int track) {
        this.parts = parts; this.colors = colors; this.track = track;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), r = h / 2f;
        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        paint.setColor(track);
        canvas.drawRect(rect, paint);
        float x = 0;
        for (int i = 0; i < parts.length && i < colors.length; i++) {
            float seg = Math.max(0f, Math.min(1f, parts[i])) * w;
            if (seg <= 0) continue;
            paint.setColor(colors[i]);
            canvas.drawRect(x, 0, Math.min(w, x + seg), h, paint);
            x += seg;
        }
        canvas.restore();
    }
}
