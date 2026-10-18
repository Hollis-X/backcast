package com.mkei.backcast.ui;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.LineHeightSpan;

/**
 * 多行代码块：整段画成一块圆角底色，左侧和上下留内边距；不是一行一行的碎底色。
 * 上下内边距算进首末行的行高里，底色不会盖到前后的正文。
 */
public final class CodeBlockSpan implements LineBackgroundSpan, LeadingMarginSpan, LineHeightSpan {
    private final int fill;
    private final float radius, inset;
    private final int pad;

    public CodeBlockSpan(int fill) {
        this.fill = fill;
        float density = Resources.getSystem().getDisplayMetrics().density;
        radius = 8 * density; inset = 12 * density; pad = Math.round(6 * density);
    }

    @Override public int getLeadingMargin(boolean first) { return Math.round(inset); }

    @Override public void drawLeadingMargin(Canvas canvas, Paint paint, int x, int dir, int top, int baseline, int bottom,
            CharSequence text, int start, int end, boolean first, Layout layout) { }

    @Override public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int lineHeight,
            Paint.FontMetricsInt fm) {
        if (!(text instanceof Spanned)) return;
        Spanned spanned = (Spanned) text;
        if (start <= spanned.getSpanStart(this)) { fm.ascent -= pad; fm.top -= pad; }
        if (end >= spanned.getSpanEnd(this)) { fm.descent += pad; fm.bottom += pad; }
    }

    @Override public void drawBackground(Canvas canvas, Paint paint, int left, int right, int top, int baseline, int bottom,
            CharSequence text, int start, int end, int lineNumber) {
        if (!(text instanceof Spanned)) return;
        Spanned spanned = (Spanned) text;
        int blockStart = spanned.getSpanStart(this), blockEnd = spanned.getSpanEnd(this);
        boolean firstLine = start <= blockStart, lastLine = end >= blockEnd;
        int color = paint.getColor();
        Paint.Style style = paint.getStyle();
        paint.setColor(fill);
        paint.setStyle(Paint.Style.FILL);
        float r = radius;
        float[] radii = { firstLine ? r : 0, firstLine ? r : 0, firstLine ? r : 0, firstLine ? r : 0,
                lastLine ? r : 0, lastLine ? r : 0, lastLine ? r : 0, lastLine ? r : 0 };
        Path path = new Path();
        path.addRoundRect(new RectF(left, top, right, bottom), radii, Path.Direction.CW);
        canvas.drawPath(path, paint);
        paint.setColor(color);
        paint.setStyle(style);
    }
}
