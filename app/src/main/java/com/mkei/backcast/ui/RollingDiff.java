package com.mkei.backcast.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 目标栏的代码改动行数「+新增 -删除」。数字变化时像翻页时钟一样，只有变了的那几位滚动：
 * 旧数字向上滑出、新数字从下面滑入，超出这一栏的部分被裁掉。
 * 数字用等宽字形（tnum）、位数只增不减地预留宽度，滚动时整栏不会左右抖动。
 */
public final class RollingDiff extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int fromAdded, fromRemoved, toAdded, toRemoved;
    private float t = 1f;
    private ValueAnimator roll;
    private int addedColor = 0xFF3B6939, removedColor = 0xFFBA1A1A;

    public RollingDiff(Context context) { this(context, null); }

    public RollingDiff(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setFontFeatureSettings("tnum");
    }

    public void setColors(int added, int removed) {
        addedColor = added; removedColor = removed;
        invalidate();
    }

    public int added() { return toAdded; }
    public int removed() { return toRemoved; }

    /** animate=false 直接显示（首次绑定、换会话）；否则变了的数位滚动过去。 */
    public void set(int added, int removed, boolean animate) {
        if (added == toAdded && removed == toRemoved) return;
        if (roll != null) roll.cancel();
        fromAdded = toAdded; fromRemoved = toRemoved;
        toAdded = added; toRemoved = removed;
        setContentDescription("+" + added + " -" + removed);
        requestLayout();
        if (!animate || !isAttachedToWindow()) { t = 1f; invalidate(); return; }
        t = 0f;
        roll = ValueAnimator.ofFloat(0f, 1f);
        roll.setDuration(360);
        roll.setInterpolator(new DecelerateInterpolator(1.6f));
        roll.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                t = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        roll.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (roll != null) roll.cancel();
        t = 1f;
        super.onDetachedFromWindow();
    }

    private float digit() { return paint.measureText("0"); }

    /** 一段的宽度：符号 + 新旧两个数里较长那个的位数。 */
    private float segment(int from, int to) {
        int digits = Math.max(String.valueOf(from).length(), String.valueOf(to).length());
        return paint.measureText("+") + digits * digit();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        int width = (int) Math.ceil(segment(fromAdded, toAdded) + paint.measureText(" ") + segment(fromRemoved, toRemoved))
                + getPaddingLeft() + getPaddingRight();
        int height = (int) Math.ceil(metrics.descent - metrics.ascent) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float line = metrics.descent - metrics.ascent;
        float base = (getHeight() - line) / 2f - metrics.ascent;
        float x = getPaddingLeft();
        x = drawSegment(canvas, "+", fromAdded, toAdded, addedColor, x, base, line);
        x += paint.measureText(" ");
        drawSegment(canvas, "-", fromRemoved, toRemoved, removedColor, x, base, line);
        canvas.restore();
    }

    /** 符号不动；数字右对齐，从个位往左逐位比较，不同的那一位上下滚动。 */
    private float drawSegment(Canvas canvas, String sign, int from, int to, int color, float x, float base, float line) {
        paint.setColor(color);
        paint.setAlpha(255);
        canvas.drawText(sign, x, base, paint);
        float digit = digit();
        String old = String.valueOf(from), now = String.valueOf(to);
        int slots = Math.max(old.length(), now.length());
        float left = x + paint.measureText(sign);
        for (int i = 0; i < slots; i++) {
            float at = left + (slots - 1 - i) * digit;
            char a = i < old.length() ? old.charAt(old.length() - 1 - i) : 0;
            char b = i < now.length() ? now.charAt(now.length() - 1 - i) : 0;
            if (t >= 1f || a == b) {
                if (b != 0) { paint.setAlpha(255); canvas.drawText(String.valueOf(b), at, base, paint); }
                continue;
            }
            // 数字变大时往上翻，变小时往下翻，和时钟走字的方向一致。
            float dir = to >= from ? -1f : 1f;
            if (a != 0) {
                paint.setAlpha(Math.round(255 * (1f - t)));
                canvas.drawText(String.valueOf(a), at, base + dir * t * line, paint);
            }
            if (b != 0) {
                paint.setAlpha(Math.round(255 * t));
                canvas.drawText(String.valueOf(b), at, base - dir * (1f - t) * line, paint);
            }
        }
        paint.setAlpha(255);
        return left + slots * digit;
    }
}
