package com.mkei.backcast;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.appcompat.app.AlertDialog;

/**
 * 后台长时间运行要靠系统放行：前台服务和唤醒锁挡不住电池优化和厂商的后台清理。
 * 这里检查是否已不受电池优化限制，并引导用户在系统里放行。
 */
final class BackgroundAccess {
    private static final String KEY_DECLINED = "background_prompt_declined";

    private BackgroundAccess() { }

    static boolean unrestricted(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** 发任务前调用：还受限制、且用户没选过「不再提示」时提醒一次。 */
    static void remind(Activity activity) {
        if (unrestricted(activity)) return;
        if (activity.getSharedPreferences("backcast", Context.MODE_PRIVATE).getBoolean(KEY_DECLINED, false)) return;
        show(activity, true);
    }

    /** 说明为什么要放行，按钮直接打开系统设置。 */
    static void show(final Activity activity, boolean offerDecline) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.background_title)
                .setMessage(unrestricted(activity) ? R.string.background_message_ok : R.string.background_message)
                .setPositiveButton(unrestricted(activity) ? R.string.background_open_app : R.string.background_allow,
                        (d, w) -> {
                            if (unrestricted(activity)) openAppSettings(activity);
                            else requestIgnore(activity);
                        })
                .setNeutralButton(R.string.background_open_app, (d, w) -> openAppSettings(activity));
        if (unrestricted(activity)) dialog.setNeutralButton(null, null).setNegativeButton(android.R.string.cancel, null);
        else if (offerDecline) dialog.setNegativeButton(R.string.background_never, (d, w) ->
                activity.getSharedPreferences("backcast", Context.MODE_PRIVATE).edit().putBoolean(KEY_DECLINED, true).apply());
        else dialog.setNegativeButton(android.R.string.cancel, null);
        dialog.show();
    }

    private static void requestIgnore(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (RuntimeException unsupported) {
            try { activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (RuntimeException missing) { openAppSettings(activity); }
        }
    }

    /** 厂商的「自启动」「后台运行」「省电策略」都在应用详情里或从这里进去。 */
    private static void openAppSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (RuntimeException ignored) { }
    }
}
