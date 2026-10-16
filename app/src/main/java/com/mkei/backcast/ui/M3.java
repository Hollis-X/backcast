package com.mkei.backcast.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

import com.mkei.backcast.R;

/**
 * Material 3 色彩角色和形状，给代码里动态画的视图用。
 * 初值是浅色色板；每个页面 onCreate 调一次 load()，按当前深浅色从资源重新读取。
 */
public final class M3 {
    public static int primary = 0xFF415F91, onPrimary = 0xFFFFFFFF;
    public static int primaryContainer = 0xFFD6E3FF, onPrimaryContainer = 0xFF284777;
    public static int secondary = 0xFF565F71;
    public static int secondaryContainer = 0xFFDAE2F9, onSecondaryContainer = 0xFF3E4759;
    public static int tertiary = 0xFF705575;
    public static int tertiaryContainer = 0xFFFAD8FD, onTertiaryContainer = 0xFF573E5C;
    public static int error = 0xFFBA1A1A, errorContainer = 0xFFFFDAD6, onErrorContainer = 0xFF93000A;
    public static int surface = 0xFFF9F9FF, onSurface = 0xFF191C20;
    public static int surfaceVariant = 0xFFE0E2EC, onSurfaceVariant = 0xFF44474E;
    public static int outline = 0xFF74777F, outlineVariant = 0xFFC4C6D0;
    public static int containerLowest = 0xFFFFFFFF, containerLow = 0xFFF3F3FA, container = 0xFFEDEDF4;
    public static int containerHigh = 0xFFE7E8EE, containerHighest = 0xFFE2E2E9;
    public static int inverseSurface = 0xFF2E3036, inverseOnSurface = 0xFFF0F0F7;
    public static int success = 0xFF3B6939, successContainer = 0xFFBCF0B4, onSuccessContainer = 0xFF23501F;
    public static int warning = 0xFF7C5800, warningContainer = 0xFFFFDEA6, onWarningContainer = 0xFF5E4200;
    public static boolean dark;

    /** 形状刻度（dp）：超小 4、小 8、中 12、大 16、超大 28。 */
    public static final int XS = 4, S = 8, M = 12, L = 16, XL = 28;

    private M3() {}

    public static void load(Context context) {
        primary = c(context, R.color.md_primary);
        onPrimary = c(context, R.color.md_on_primary);
        primaryContainer = c(context, R.color.md_primary_container);
        onPrimaryContainer = c(context, R.color.md_on_primary_container);
        secondary = c(context, R.color.md_secondary);
        secondaryContainer = c(context, R.color.md_secondary_container);
        onSecondaryContainer = c(context, R.color.md_on_secondary_container);
        tertiary = c(context, R.color.md_tertiary);
        tertiaryContainer = c(context, R.color.md_tertiary_container);
        onTertiaryContainer = c(context, R.color.md_on_tertiary_container);
        error = c(context, R.color.md_error);
        errorContainer = c(context, R.color.md_error_container);
        onErrorContainer = c(context, R.color.md_on_error_container);
        surface = c(context, R.color.md_surface);
        onSurface = c(context, R.color.md_on_surface);
        surfaceVariant = c(context, R.color.md_surface_variant);
        onSurfaceVariant = c(context, R.color.md_on_surface_variant);
        outline = c(context, R.color.md_outline);
        outlineVariant = c(context, R.color.md_outline_variant);
        containerLowest = c(context, R.color.md_surface_container_lowest);
        containerLow = c(context, R.color.md_surface_container_low);
        container = c(context, R.color.md_surface_container);
        containerHigh = c(context, R.color.md_surface_container_high);
        containerHighest = c(context, R.color.md_surface_container_highest);
        inverseSurface = c(context, R.color.md_inverse_surface);
        inverseOnSurface = c(context, R.color.md_inverse_on_surface);
        success = c(context, R.color.md_success);
        successContainer = c(context, R.color.md_success_container);
        onSuccessContainer = c(context, R.color.md_on_success_container);
        warning = c(context, R.color.md_warning);
        warningContainer = c(context, R.color.md_warning_container);
        onWarningContainer = c(context, R.color.md_on_warning_container);
        dark = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** 颜色加透明度（M3 的状态层：悬停 8%、按下 10%、禁用 12% / 38%）。 */
    public static int alpha(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0x00FFFFFF);
    }

    /** 实心圆角块。radiusPx 小于 0 时画成全圆角胶囊。 */
    public static GradientDrawable fill(int color, float radiusPx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radiusPx < 0 ? 999f * 4 : radiusPx);
        return bg;
    }

    /** 描边圆角块（描边按钮、描边卡片、未选中的筛选芯片）。 */
    public static GradientDrawable stroke(int fill, int strokeColor, int strokePx, float radiusPx) {
        GradientDrawable bg = fill(fill, radiusPx);
        bg.setStroke(strokePx, strokeColor);
        return bg;
    }

    /** 外面套一层 M3 涟漪，按下时出现 onSurface 10% 的状态层。 */
    public static android.graphics.drawable.Drawable ripple(android.graphics.drawable.Drawable content, int ink, float radiusPx) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(alpha(ink, 0.12f)), content, fill(0xFFFFFFFF, radiusPx));
    }

    public static final int FILLED = 0, TONAL = 1, OUTLINED = 2, TEXT = 3;

    /** 代码里建 M3 按钮：填充 / 色调 / 描边 / 文字四种，样式取自主题。 */
    public static com.google.android.material.button.MaterialButton button(Context context, int kind, CharSequence text) {
        int[] attrs = { R.attr.appButtonFilled, R.attr.appButtonTonal, R.attr.appButtonOutlined, R.attr.appButtonText };
        com.google.android.material.button.MaterialButton button =
                new com.google.android.material.button.MaterialButton(context, null, attrs[kind]);
        button.setText(text);
        return button;
    }

    /** 代码里建 M3 描边文本框；返回外层，输入框用 getEditText() 取。 */
    public static com.google.android.material.textfield.TextInputLayout field(Context context, CharSequence label) {
        com.google.android.material.textfield.TextInputLayout layout =
                new com.google.android.material.textfield.TextInputLayout(context, null, R.attr.appFieldStyle);
        layout.setHint(label);
        com.google.android.material.textfield.TextInputEditText edit =
                new com.google.android.material.textfield.TextInputEditText(layout.getContext());
        layout.addView(edit, new android.widget.LinearLayout.LayoutParams(-1, -2));
        return layout;
    }

    private static int c(Context context, int res) {
        return context.getResources().getColor(res, context.getTheme());
    }
}
