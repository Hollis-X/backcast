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

/** 多行代码块：整段画成一块圆角底色，左侧留内边距；不是一行一行的碎底色。 */
public final class CodeBlockSpan implements LineBackgroundSpan, LeadingMarginSpan {
    private final int fill;
    private final float radius, inset;

    public CodeBlockSpan(int fill) {
        this.fill = fill;
        float density = Resources.getSystem().getDisplayMetrics().density;
        radius = 8 * density; inset = 12 * density;
    }

    @Override public int getLeadingMargin(boolean first) { return Math.round(inset); }

    @Override public void drawLeadingMargin(Canvas canvas, Paint paint, int x, int dir, int top, int baseline, int bottom,
            CharSequence text, int start, int end, boolean first, Layout layout) { }

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
        float extraTop = firstLine ? inset * 0.5f : 0, extraBottom = lastLine ? inset * 0.25f : 0;
        path.addRoundRect(new RectF(left, top - extraTop, right, bottom + extraBottom), radii, Path.Direction.CW);
        canvas.drawPath(path, paint);
        paint.setColor(color);
        paint.setStyle(style);
    }
}
