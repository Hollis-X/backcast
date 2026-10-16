package com.mkei.backcast.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * 主界面根布局，给左侧抽屉加上跟手拖拽：
 * 抽屉关着时在会话区向右横滑把它拉出来，开着时在抽屉或遮罩上向左横滑把它推回去。
 * 只有横向位移超过触摸阈值、并明显大于纵向位移时才接管；竖向滑动照常交给抽屉里的会话列表
 * 和主界面的会话流，轻点照常是点击。手下能横向滚动的内容（比如代码块）优先自己滚。
 */
public class DrawerHost extends FrameLayout {
    public interface Callback {
        boolean isDrawerOpen();
        /** 这个按下点能不能开始拉出抽屉（输入条、顶栏、弹出的面板上都不行）。 */
        boolean canOpenFrom(float x, float y);
        /** 拖拽拉出前准备好抽屉：填会话列表、定宽度、设为可见；返回抽屉宽度。 */
        int prepareOpen();
        /** 拖拽松手、动画停稳之后：open=false 时由调用方把抽屉藏掉。 */
        void settled(boolean open);
    }

    private static final float DOMINANCE = 1.5f;
    private final int slop, minFling, maxFling;
    private Callback callback;
    private View panel, scrim;
    private VelocityTracker velocity;
    private ValueAnimator settle;
    private float downX, downY, lastX, offset;
    private int width;
    private boolean candidate, dragging, opening;

    public DrawerHost(Context context) { this(context, null); }

    public DrawerHost(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration config = ViewConfiguration.get(context);
        slop = config.getScaledTouchSlop();
        minFling = config.getScaledMinimumFlingVelocity() * 4;
        maxFling = config.getScaledMaximumFlingVelocity();
    }

    public void attachDrawer(View panel, View scrim, Callback callback) {
        this.panel = panel;
        this.scrim = scrim;
        this.callback = callback;
    }

    /** 正在跟手拖拽或回弹中。 */
    public boolean dragging() { return dragging || settle != null; }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (callback == null || panel == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = lastX = event.getX();
                downY = event.getY();
                dragging = false;
                boolean open = callback.isDrawerOpen();
                opening = !open;
                candidate = settle == null && (open || callback.canOpenFrom(downX, downY));
                if (candidate) track(event);
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging) return true;
                if (!candidate) return false;
                track(event);
                float dx = event.getX() - downX, dy = event.getY() - downY;
                if (Math.abs(dy) > slop && Math.abs(dy) * DOMINANCE >= Math.abs(dx)) {
                    // 竖向为主：这一整次触摸都交给列表滚动。
                    candidate = false;
                    return false;
                }
                if (Math.abs(dx) <= slop || Math.abs(dx) < Math.abs(dy) * DOMINANCE) return false;
                if (opening ? dx < 0 : dx > 0) { candidate = false; return false; }
                if (opening && canScrollRight(this, (int) downX, (int) downY, false)) { candidate = false; return false; }
                startDrag(event.getX());
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                candidate = false;
                recycle();
                return false;
            default:
                return dragging;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!dragging) return super.onTouchEvent(event);
        track(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                moveTo(offset + event.getX() - lastX);
                lastX = event.getX();
                return true;
            case MotionEvent.ACTION_UP: {
                float vx = 0;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000, maxFling);
                    vx = velocity.getXVelocity();
                }
                boolean open = vx > minFling || vx >= -minFling && offset > -width / 2f;
                endDrag(open);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                endDrag(offset > -width / 2f);
                return true;
            default:
                return true;
        }
    }

    private void startDrag(float x) {
        dragging = true;
        candidate = false;
        panel.clearAnimation();
        if (scrim != null) scrim.clearAnimation();
        if (opening) {
            width = Math.max(1, callback.prepareOpen());
            offset = -width;
        } else {
            width = Math.max(1, panel.getWidth());
            offset = panel.getTranslationX();
        }
        lastX = x;
        // 越过阈值那一段也算进去，抽屉从手指现在的位置开始跟。
        moveTo(offset + (x - downX) - Math.signum(x - downX) * slop);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    }

    private void moveTo(float value) {
        offset = Math.max(-width, Math.min(0, value));
        panel.setTranslationX(offset);
        if (scrim != null) scrim.setAlpha(1f + offset / width);
    }

    private void endDrag(final boolean open) {
        dragging = false;
        recycle();
        float target = open ? 0 : -width;
        if (settle != null) settle.cancel();
        settle = ValueAnimator.ofFloat(offset, target);
        settle.setDuration((long) (120 + 140 * Math.abs(target - offset) / width));
        settle.setInterpolator(new DecelerateInterpolator());
        settle.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) { moveTo((Float) animation.getAnimatedValue()); }
        });
        settle.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                settle = null;
                callback.settled(open);
                panel.setTranslationX(0);
                if (scrim != null) scrim.setAlpha(1f);
            }
        });
        settle.start();
    }

    private void track(MotionEvent event) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(event);
    }

    private void recycle() {
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }

    /** 按下点下面有没有还能往左露出内容的横向滚动视图（手指右滑时它该自己滚）。 */
    private static boolean canScrollRight(View view, int x, int y, boolean self) {
        if (view.getVisibility() != VISIBLE) return false;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                int cx = x + view.getScrollX() - child.getLeft() - Math.round(child.getTranslationX());
                int cy = y + view.getScrollY() - child.getTop() - Math.round(child.getTranslationY());
                if (cx >= 0 && cx < child.getWidth() && cy >= 0 && cy < child.getHeight()
                        && canScrollRight(child, cx, cy, true)) return true;
            }
        }
        return self && view.canScrollHorizontally(-1);
    }
}
