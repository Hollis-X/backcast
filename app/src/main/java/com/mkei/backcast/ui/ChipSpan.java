package com.mkei.backcast.ui;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.style.ReplacementSpan;

/** 行内小标签：圆角浅色底，深色粗字，用来显示 +新增 / -删除 行数。 */
public final class ChipSpan extends ReplacementSpan {
    private final int ink, fill;
    private final float pad, radius, gap;

    public ChipSpan(int ink, int fill) {
        this.ink = ink; this.fill = fill;
        float density = Resources.getSystem().getDisplayMetrics().density;
        pad = 5 * density; radius = 5 * density; gap = 3 * density;
    }

    @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        Paint bold = bold(paint);
        if (fm != null) bold.getFontMetricsInt(fm);
        return Math.round(bold.measureText(text, start, end) + pad * 2 + gap);
    }

    @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        Paint bold = bold(paint);
        float width = bold.measureText(text, start, end) + pad * 2;
        Paint.FontMetrics metrics = bold.getFontMetrics();
        float boxTop = y + metrics.ascent - pad * 0.35f, boxBottom = y + metrics.descent + pad * 0.35f;
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(fill);
        canvas.drawRoundRect(new RectF(x, boxTop, x + width, boxBottom), radius, radius, background);
        bold.setColor(ink);
        canvas.drawText(text, start, end, x + pad, y, bold);
    }

    private static Paint bold(Paint paint) {
        Paint bold = new Paint(paint);
        bold.setTypeface(Typeface.create(paint.getTypeface(), Typeface.BOLD));
        bold.setTextSize(paint.getTextSize() * 0.92f);
        return bold;
    }
}
