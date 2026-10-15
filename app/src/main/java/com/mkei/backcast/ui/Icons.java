package com.mkei.backcast.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.TextView;

import com.mkei.backcast.R;

/**
 * 图标取图与上色。
 * 资源只按 xxxhdpi 存放，这里锁定目标像素尺寸，
 * 避免因设备密度不同把图标放大成内建尺寸。
 */
public final class Icons {

    public static final int MENU = R.drawable.ic_ds_sidebar_menu_regular_24;
    public static final int BACK = R.drawable.ic_ds_arrow_left_lg_regular_24;
    public static final int NEW_CHAT = R.drawable.ic_ds_square_and_pencil_regular_24;
    public static final int PLUS = R.drawable.ic_ds_plus_md_regular_24;
    public static final int SEND = R.drawable.ic_ds_arrow_up_lg_button_regular_24;
    public static final int STOP = R.drawable.ic_ds_stop_fill_regular_24;
    public static final int CHEVRON_DOWN = R.drawable.ic_ds_chevron_down_sm_regular_24;
    public static final int CHEVRON_RIGHT = R.drawable.ic_ds_chevron_right_sm_regular_24;
    public static final int SEARCH = R.drawable.ic_ds_magnifying_glass_md_regular_24;
    public static final int SETTINGS = R.drawable.ic_ds_gear_regular_24;
    public static final int CHAT = R.drawable.ic_ds_chat_bubble_regular_24;
    public static final int MORE = R.drawable.ic_ds_ellipsis_horizontal_regular_24;
    public static final int FOLDER = R.drawable.ic_ds_folder_regular_24;
    public static final int TERMINAL = R.drawable.ic_ds_terminal_regular_24;
    public static final int PENCIL = R.drawable.ic_ds_pencil_regular_24;
    public static final int UNDO = R.drawable.ic_ds_arrow_uturn_left_regular_24;
    public static final int PLAY = R.drawable.ic_ds_play_sm_fill_regular_24;
    public static final int PAUSE = R.drawable.ic_ds_pause_sm_fill_regular_24;

    private Icons() {
    }

    /** 取出图标，锁定像素尺寸并按颜色上色。 */
    public static Drawable tinted(Context context, int res, int color, int sizePx) {
        Drawable raw = context.getResources().getDrawable(res);
        Drawable d = raw.getConstantState() == null
                ? raw : raw.getConstantState().newDrawable();
        d = d.mutate();
        d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (d instanceof BitmapDrawable) {
            BitmapDrawable bd = (BitmapDrawable) d;
            Bitmap bmp = bd.getBitmap();
            if (bmp != null && bmp.getWidth() > 0 && bmp.getDensity() > 0) {
                // 内建尺寸 = 位图宽度 * 目标密度 / 位图密度，这里反推出目标密度。
                bd.setTargetDensity(Math.round(sizePx * (bmp.getDensity() / (float) bmp.getWidth())));
            }
        }
        d.setBounds(0, 0, sizePx, sizePx);
        return d;
    }

    /** 图标放 TextView 左侧。 */
    public static void left(TextView tv, int res, int color, int sizePx) {
        if (tv == null) {
            return;
        }
        tv.setCompoundDrawables(tinted(tv.getContext(), res, color, sizePx), null, null, null);
        tv.setCompoundDrawablePadding(Math.max(2, sizePx / 4));
    }

    /** 图标放左侧，并与文字作为一组在按钮内水平居中。 */
    public static void leftCentered(TextView tv, int res, int color, int sizePx) {
        if (tv == null) {
            return;
        }
        left(tv, res, color, sizePx);
        tv.setGravity(Gravity.CENTER);
        tv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> centerContent(tv, sizePx));
        tv.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { centerContent(tv, sizePx); }
        });
    }

    private static void centerContent(TextView tv, int sizePx) {
        int width = tv.getWidth();
        if (width <= 0) {
            return;
        }
        int text = (int) Math.ceil(tv.getPaint().measureText(String.valueOf(tv.getText())));
        int content = sizePx + tv.getCompoundDrawablePadding() + text;
        int side = Math.max(sizePx, (width - content) / 2);
        if (tv.getPaddingLeft() != side || tv.getPaddingRight() != side) {
            tv.setPadding(side, tv.getPaddingTop(), side, tv.getPaddingBottom());
        }
    }

    /** 图标放 TextView 右侧。 */
    public static void right(TextView tv, int res, int color, int sizePx) {
        if (tv == null) {
            return;
        }
        tv.setCompoundDrawables(null, null, tinted(tv.getContext(), res, color, sizePx), null);
        tv.setCompoundDrawablePadding(Math.max(2, sizePx / 4));
    }
}
