package com.mkei.backcast.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import com.mkei.backcast.R;
import java.util.List;

/** A stable popup container that follows the composer and stays above the keyboard. */
public final class SlashMenuPopup {
    public static final class Item {
        public final String title, detail;
        public final Runnable action;
        public Item(String title, String detail, Runnable action) {
            this.title = title; this.detail = detail; this.action = action;
        }
    }

    private final Context context;
    private final View anchor;
    private final LinearLayout rows;
    private final ScrollView scroll;
    private final PopupWindow popup;
    private int lastX, lastY, lastWidth, lastHeight;
    private boolean observing;
    private final ViewTreeObserver.OnGlobalLayoutListener layouts = new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override public void onGlobalLayout() { if (popup.isShowing()) position(); }
    };

    public SlashMenuPopup(Context context, View anchor) {
        this.context = context; this.anchor = anchor;
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll = new ScrollView(context);
        scroll.setBackgroundResource(R.drawable.bg_slash_card);
        scroll.addView(rows);
        popup = new PopupWindow(scroll, 1, 1, false);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(false);
        popup.setFocusable(false);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        popup.setAnimationStyle(R.style.SlashPopupAnimation);
        popup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override public void onDismiss() { stopObserving(); }
        });
    }

    public void show(List<Item> items) {
        rows.removeAllViews();
        for (final Item item : items) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            if (item.action != null) {
                row.setBackgroundResource(R.drawable.bg_slash_item);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) { item.action.run(); }
                });
            }
            row.addView(text(item.title, item.action == null ? 13 : 15,
                    item.action == null ? R.color.text_secondary : R.color.text_primary));
            if (item.detail != null && item.detail.length() > 0) {
                TextView detail = text(item.detail, 12, R.color.text_secondary);
                detail.setMaxLines(3);
                detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
                detail.setPadding(0, dp(2), 0, 0);
                row.addView(detail);
            }
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        scroll.scrollTo(0, 0);
        if (!observing) {
            anchor.getViewTreeObserver().addOnGlobalLayoutListener(layouts);
            observing = true;
        }
        position();
    }

    private void position() {
        Rect visible = new Rect();
        anchor.getWindowVisibleDisplayFrame(visible);
        if (visible.width() <= 0 || visible.height() <= 0) return;
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int width = Math.max(1, Math.min(anchor.getWidth() > 0 ? anchor.getWidth() : dp(280), visible.width() - dp(16)));
        int bottom = Math.min(location[1] - dp(8), visible.bottom - dp(8));
        int available = bottom - visible.top - dp(8);
        if (available <= 0) { dismiss(); return; }
        rows.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = Math.max(1, Math.min(rows.getMeasuredHeight(), available));
        int x = Math.max(visible.left + dp(8), Math.min(location[0], visible.right - width - dp(8)));
        int y = bottom - height;
        if (popup.isShowing()) {
            if (x != lastX || y != lastY || width != lastWidth || height != lastHeight)
                popup.update(x, y, width, height);
        } else {
            popup.setWidth(width); popup.setHeight(height);
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
        }
        lastX = x; lastY = y; lastWidth = width; lastHeight = height;
    }

    public boolean isShowing() { return popup.isShowing(); }
    public void dismiss() { popup.dismiss(); stopObserving(); }
    private void stopObserving() {
        if (observing && anchor.getViewTreeObserver().isAlive())
            anchor.getViewTreeObserver().removeOnGlobalLayoutListener(layouts);
        observing = false;
    }
    private TextView text(String value, int size, int color) {
        TextView result = new TextView(context);
        result.setText(value); result.setTextSize(size);
        result.setTextColor(context.getResources().getColor(color));
        return result;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
