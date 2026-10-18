package com.mkei.backcast.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/**
 * 骨架屏：数据还没读出来时，先按读完后的版式画几块灰色占位，上面斜扫过一道高光。
 *
 * 每块占位自己驱动扫光，按窗口坐标算位置，同一屏的几块看起来是同一道光扫过去。
 * 视图离开窗口或不再绘制时扫光自然停下；系统关掉动画时只画静止的灰块。
 * 读完后由页面把整块骨架移走，换成真内容。
 */
public final class Skeleton {
    /** 一轮扫光的时长（毫秒），其中最后一段是停顿。 */
    private static final long CYCLE = 1500L, SWEEP = 1100L;

    private Skeleton() {}

    /** 一块圆角占位。radiusPx 小于 0 时画成胶囊。 */
    public static View block(Context context, float radiusPx) {
        View view = new View(context);
        view.setBackground(new Shimmer(radiusPx));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    /** 几行文字占位，fractions 是每行占整行宽度的比例。 */
    public static LinearLayout lines(Context context, float... fractions) {
        LinearLayout box = container(context);
        for (int i = 0; i < fractions.length; i++) {
            box.addView(line(context, fractions[i], dp(context, 12)), spaced(context, i == 0 ? 0 : 10));
        }
        return box;
    }

    /**
     * 列表占位：每行一个可选的前置方块（头像或复选框）、一行标题和一行可选的说明。
     * segmented 时按 M3 分段列表画容器底色，首尾大圆角，行间 2dp 缝。
     */
    public static LinearLayout list(Context context, int rows, int leadDp, boolean subtitle, boolean segmented) {
        LinearLayout box = container(context);
        float[] title = { 0.42f, 0.58f, 0.36f, 0.5f, 0.46f, 0.62f };
        float[] sub = { 0.78f, 0.64f, 0.84f, 0.7f, 0.58f, 0.74f };
        for (int i = 0; i < rows; i++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int vertical = dp(context, subtitle ? 12 : 10);
            row.setPadding(dp(context, segmented ? 16 : 8), vertical, dp(context, segmented ? 16 : 8), vertical);
            row.setMinimumHeight(dp(context, subtitle ? 64 : 48));
            if (segmented) {
                float top = dp(context, i == 0 ? M3.L : M3.XS), bottom = dp(context, i == rows - 1 ? M3.L : M3.XS);
                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setColor(M3.container);
                shape.setCornerRadii(new float[] { top, top, top, top, bottom, bottom, bottom, bottom });
                row.setBackground(shape);
            }
            if (leadDp > 0) {
                LinearLayout.LayoutParams lead = new LinearLayout.LayoutParams(dp(context, leadDp), dp(context, leadDp));
                lead.rightMargin = dp(context, 16);
                row.addView(block(context, dp(context, leadDp >= 32 ? M3.M : M3.XS)), lead);
            }
            LinearLayout text = new LinearLayout(context);
            text.setOrientation(LinearLayout.VERTICAL);
            text.addView(line(context, title[i % title.length], dp(context, 14)), spaced(context, 0));
            if (subtitle) text.addView(line(context, sub[i % sub.length], dp(context, 10)), spaced(context, 10));
            row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(context, segmented ? 2 : 0);
            box.addView(row, lp);
        }
        return box;
    }

    /** 对话占位：右边一个用户气泡，左边几行回复，画两轮。 */
    public static LinearLayout chat(Context context) {
        LinearLayout box = container(context);
        box.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
        float[][] replies = { { 0.92f, 0.86f, 0.95f, 0.48f }, { 0.88f, 0.94f, 0.62f } };
        float[] asks = { 0.56f, 0.42f };
        for (int turn = 0; turn < asks.length; turn++) {
            LinearLayout ask = new LinearLayout(context);
            ask.setOrientation(LinearLayout.HORIZONTAL);
            ask.setGravity(Gravity.END);
            ask.setWeightSum(1f);
            ask.addView(block(context, dp(context, 20)), new LinearLayout.LayoutParams(0, dp(context, 44), asks[turn]));
            box.addView(ask, spaced(context, turn == 0 ? 0 : 28));
            for (int i = 0; i < replies[turn].length; i++) {
                box.addView(line(context, replies[turn][i], dp(context, 12)), spaced(context, i == 0 ? 24 : 12));
            }
        }
        return box;
    }

    /** 把骨架从父视图上摘下来；已经摘过或没有时什么都不做。 */
    public static void remove(View skeleton) {
        if (skeleton != null && skeleton.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) skeleton.getParent()).removeView(skeleton);
        }
    }

    private static LinearLayout container(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        // 读屏只念一次「加载中」，不去逐块念占位。
        box.setContentDescription("加载中");
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        box.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return box;
    }

    /** 一行文字占位：横向按比例占宽，胶囊形。 */
    private static View line(Context context, float fraction, int height) {
        LinearLayout holder = new LinearLayout(context);
        holder.setOrientation(LinearLayout.HORIZONTAL);
        holder.setWeightSum(1f);
        holder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        holder.addView(block(context, -1f), new LinearLayout.LayoutParams(0, height, Math.max(0.05f, Math.min(1f, fraction))));
        return holder;
    }

    private static LinearLayout.LayoutParams spaced(Context context, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(context, topDp);
        return lp;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** 底色是 surfaceContainerHighest；高光是一道半透明的斜带，浅色里提亮，深色里也只轻轻提亮。 */
    static final class Shimmer extends Drawable implements Runnable {
        private final float radius;
        private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int[] window = new int[2];
        private final android.graphics.Matrix shift = new android.graphics.Matrix();
        private final int baseColor = M3.containerHighest;
        private final int glowColor = M3.alpha(0xFFFFFFFF, M3.dark ? 0.07f : 0.6f);
        private LinearGradient gradient;
        private float band;

        Shimmer(float radiusPx) {
            radius = radiusPx;
            base.setColor(baseColor);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            rect.set(bounds);
            float r = radius < 0 ? rect.height() / 2f : radius;
            canvas.drawRoundRect(rect, r, r, base);
            if (!ValueAnimator.areAnimatorsEnabled()) return;
            Callback callback = getCallback();
            View host = callback instanceof View ? (View) callback : null;
            if (host == null) return;
            host.getLocationInWindow(window);
            int travel = host.getRootView() == null ? host.getWidth() : host.getRootView().getWidth();
            float width = Math.max(1f, travel * 0.45f);
            if (gradient == null || band != width) {
                band = width;
                gradient = new LinearGradient(0, 0, width, 0, new int[] { 0, glowColor, 0 }, null, Shader.TileMode.CLAMP);
                glow.setShader(gradient);
            }
            long t = SystemClock.uptimeMillis() % CYCLE;
            if (t < SWEEP) {
                // 扫光从窗口左外侧进、右外侧出，换算到这块占位自己的坐标。
                float x = -width + (travel + width) * (t / (float) SWEEP) - window[0];
                shift.setTranslate(x, 0);
                gradient.setLocalMatrix(shift);
                canvas.drawRoundRect(rect, r, r, glow);
            }
            unscheduleSelf(this);
            scheduleSelf(this, SystemClock.uptimeMillis() + 16);
        }

        @Override public void run() { invalidateSelf(); }

        @Override public boolean setVisible(boolean visible, boolean restart) {
            boolean changed = super.setVisible(visible, restart);
            if (!visible) unscheduleSelf(this);
            else if (changed) invalidateSelf();
            return changed;
        }

        @Override public void setAlpha(int alpha) { base.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { base.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
