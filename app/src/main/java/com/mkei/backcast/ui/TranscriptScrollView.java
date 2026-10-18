package com.mkei.backcast.ui;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.ScrollView;

/** ScrollView with an explicit stop for a user's jump to the latest message. */
public class TranscriptScrollView extends ScrollView {
    private Runnable touchStart;
    private boolean touching;
    private long releasedAt;

    public TranscriptScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setOnTouchStartListener(Runnable listener) {
        touchStart = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touching = true;
            if (touchStart != null) touchStart.run();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touching = false;
            releasedAt = SystemClock.uptimeMillis();
        }
        return super.dispatchTouchEvent(event);
    }

    /** 这次滚动是不是用户手指带起来的（按着，或刚松手还在惯性滑）。内容变长、键盘弹起引起的滚动不算。 */
    public boolean isUserScroll() {
        return touching || SystemClock.uptimeMillis() - releasedAt < 1500;
    }

    public void stopScroll() {
        // Clamp overscroll first so CANCEL cannot start a spring-back animation.
        super.scrollTo(getScrollX(), getScrollY());
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 0f, 0f, 0);
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        try {
            // Native DOWN aborts both fling and smooth scrolling without private API access.
            super.onTouchEvent(down);
            super.onTouchEvent(cancel);
            // Intercept CANCEL also clears a non-dragging pointer/tracker.
            super.onInterceptTouchEvent(cancel);
        } finally {
            down.recycle();
            cancel.recycle();
        }
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
    }
}
