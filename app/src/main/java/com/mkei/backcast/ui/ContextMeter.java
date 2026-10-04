package com.mkei.backcast.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * 上下文用量圆环。
 *
 * 环从 12 点方向顺时针填充，中间写字。
 * 分为两段颜色：正常量与接近上限的量，让用户一眼看出要不要压缩。
 */
public class ContextMeter extends View {

    /** 环内文字左右留白比例，避免字贴到环上。 */
    private static final float TEXT_MARGIN = 0.86f;

    /** 换色比例，跟随自动压缩阈值：越过这段就是压缩区间。 */
    private float warnRatio = 0.9f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint normal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();

    private float ratio;
    private String text = "";

    public ContextMeter(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(0xFFE4E4E7);
        normal.setStyle(Paint.Style.STROKE);
        normal.setStrokeCap(Paint.Cap.ROUND);
        normal.setColor(0xFF111111);
        warn.setStyle(Paint.Style.STROKE);
        warn.setStrokeCap(Paint.Cap.ROUND);
        warn.setColor(0xFFD97706);
        label.setColor(0xFF6E6E76);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    /** 设置换色比例，让圆环和实际压缩阈值保持一致。 */
    public void setWarnRatio(float ratio) {
        float r = ratio;
        if (r <= 0f || r > 1f) {
            return;
        }
        this.warnRatio = r;
        invalidate();
    }

    /** ratio 取 0..1，text 是环中间的字。 */
    public void setUsage(float ratio, String text) {
        float r = ratio;
        if (r < 0f) {
            r = 0f;
        }
        if (r > 1f) {
            r = 1f;
        }
        this.ratio = r;
        this.text = text == null ? "" : text;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float stroke = w * 0.10f;
        if (stroke < 2f) {
            stroke = 2f;
        }
        track.setStrokeWidth(stroke);
        normal.setStrokeWidth(stroke);
        warn.setStrokeWidth(stroke);

        float inset = stroke / 2f + 1f;
        arc.set(inset, inset, w - inset, h - inset);

        canvas.drawArc(arc, -90f, 360f, false, track);
        if (ratio > 0f) {
            float sweep = 360f * ratio;
            // 前半段用正常色，越过警戒线后换色，一眼能看出进入压缩区间。
            if (ratio <= warnRatio) {
                canvas.drawArc(arc, -90f, sweep, false, normal);
            } else {
                canvas.drawArc(arc, -90f, 360f * warnRatio, false, normal);
                canvas.drawArc(arc, -90f + 360f * warnRatio,
                        sweep - 360f * warnRatio, false, warn);
            }
        }

        float radius = (w - 2f * inset) / 2f;
        float size = w * 0.30f;
        label.setTextSize(size);
        // 字太长就缩到环内能放下为止，避免压到环上。
        // 可用宽度按环内弦长算：字上下缘那一圈环内最窄，按矩形宽度会压到环。
        for (int i = 0; i < 3; i++) {
            float room = roomFor(label, radius) * TEXT_MARGIN;
            float width = label.measureText(text);
            if (width <= room || width <= 0f) {
                break;
            }
            size = size * (room / width);
            if (size < w * 0.16f) {
                size = w * 0.16f;
                label.setTextSize(size);
                break;
            }
            label.setTextSize(size);
        }
        float cy = h / 2f - (label.descent() + label.ascent()) / 2f;
        canvas.drawText(text, w / 2f, cy, label);
    }

    /** 当前字号下，环内能横放多宽的文字。 */
    private static float roomFor(Paint p, float radius) {
        float half = (p.descent() - p.ascent()) / 2f;
        if (half >= radius) {
            return 0f;
        }
        return 2f * (float) Math.sqrt(radius * radius - half * half);
    }
}
