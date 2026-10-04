package com.mkei.backcast.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.text.TextPaint;
import android.widget.TextView;

/**
 * 短标签上的跑马灯。
 *
 * 系统跑马灯要文字比控件宽才会滚，这几行字都很短。
 * 文字着色在部分机型上也不会进字形，所以扫光画到自己的图上再贴回去。
 * phase 小于 0 时就是普通文字。
 */
public class SweepText extends TextView {

    private float phase = -1f;
    private Bitmap buffer;
    private final TextPaint sweepPaint = new TextPaint();
    private final Matrix shift = new Matrix();

    public SweepText(Context context) {
        super(context);
    }

    /** 0 到 1 循环。小于 0 关掉扫光。 */
    public void setPhase(float phase) {
        if (phase < 0f) {
            if (this.phase >= 0f) {
                this.phase = -1f;
                invalidate();
            }
            return;
        }
        this.phase = phase;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (phase < 0f) {
            super.onDraw(canvas);
            return;
        }
        int w = getWidth();
        int h = getHeight();
        CharSequence text = getText();
        if (w <= 0 || h <= 0 || text == null || text.length() == 0) {
            super.onDraw(canvas);
            return;
        }
        if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h) {
            if (buffer != null) {
                buffer.recycle();
            }
            buffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        buffer.eraseColor(0);
        Canvas soft = new Canvas(buffer);
        String s = text.toString();
        sweepPaint.set(getPaint());
        float span = sweepPaint.measureText(s);
        if (span < 1f) {
            span = w;
        }
        float x0 = getTotalPaddingLeft();
        // 底是浅灰，扫过的一段是纯黑，短标题上才看得清。
        LinearGradient band = new LinearGradient(
                x0, 0f, x0 + span, 0f,
                new int[]{0xFFC8C8CE, 0xFF000000, 0xFF000000, 0xFFC8C8CE},
                new float[]{0f, 0.28f, 0.72f, 1f},
                Shader.TileMode.REPEAT);
        shift.setTranslate(phase * span, 0f);
        band.setLocalMatrix(shift);
        sweepPaint.setShader(band);
        int baseline = getBaseline();
        if (baseline <= 0) {
            baseline = getPaddingTop() + (int) (-sweepPaint.ascent());
        }
        soft.drawText(s, x0, baseline, sweepPaint);
        sweepPaint.setShader(null);
        canvas.drawBitmap(buffer, 0f, 0f, null);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (buffer != null) {
            buffer.recycle();
            buffer = null;
        }
    }
}
