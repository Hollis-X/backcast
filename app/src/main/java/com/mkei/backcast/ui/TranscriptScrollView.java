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

    public TranscriptScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setOnTouchStartListener(Runnable listener) {
        touchStart = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && touchStart != null) touchStart.run();
        return super.dispatchTouchEvent(event);
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
