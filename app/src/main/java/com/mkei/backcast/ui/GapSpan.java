package com.mkei.backcast.ui;

import android.content.res.Resources;
import android.graphics.Paint;
import android.text.style.LineHeightSpan;

/** 段落之间的空行：固定成 4dp（再加正文行距约 9dp），不按整行字高留白。 */
public final class GapSpan implements LineHeightSpan {
    private final int height;

    public GapSpan() {
        height = Math.round(4 * Resources.getSystem().getDisplayMetrics().density);
    }

    @Override public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int lineHeight,
            Paint.FontMetricsInt fm) {
        fm.ascent = fm.top = -height;
        fm.descent = fm.bottom = 0;
    }
}
