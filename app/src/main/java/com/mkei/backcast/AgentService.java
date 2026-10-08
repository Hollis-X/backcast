package com.mkei.backcast;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import com.mkei.backcast.agent.Diagnostics;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Foreground priority and a wake lock protect active work; neither guarantees process survival. */
public class AgentService extends Service {

    private static final int NOTE_ID = 7;
    private static final String CHANNEL = "run";
    private static final ExecutorService DIAGNOSTICS = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "backcast-service-diagnostic");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService recoveryWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "backcast-service-recovery");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private int latestStartId;
    private boolean recovering, foreground, stopping, exitChecked, wakeFailureReported;
    private volatile boolean destroyed;

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, AgentService.class);
            context.startForegroundService(intent);
        } catch (RuntimeException error) {
            recordAsync(context, "foreground_start_rejected", "后台运行保护启动失败", error);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        stopping = false;
        try {
            if (!foreground) {
                startForeground(NOTE_ID, notification("正在执行任务"));
                foreground = true;
            }
        } catch (RuntimeException error) {
            recordAsync(this, "foreground_setup_failed", "后台运行保护建立失败", error);
            stopping = true;
            releaseWake();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        holdWake();
        if (intent == null || flags != 0) {
            JSONObject evidence = evidence("service_recreated", null);
            try { evidence.put("null_intent", intent == null).put("start_flags", flags); }
            catch (Exception ignored) { }
            recordAsync(this, "后台任务服务收到恢复启动", evidence);
        }
        if (!recovering) recoverInBackground();
        return START_STICKY;
    }

    /** Service callbacks remain responsive while persisted sessions and files are restored. */
    private void recoverInBackground() {
        recovering = true;
        final int owner = latestStartId;
        final Context app = getApplicationContext();
        recoveryWorker.execute(() -> {
            if (destroyed) return;
            if (!exitChecked) { recordPreviousExit(app); exitChecked = true; }
            if (destroyed) return;
            boolean work = true;
            String text = "正在执行任务";
            try {
                RunHub hub = RunHub.get(app);
                if (destroyed) return;
                try { hub.recover(); }
                catch (RuntimeException error) {
                    record(app, "后台任务恢复失败", evidence("recovery_failed", error));
                }
                work = hub.hasWork();
                if (work) text = hub.noteText();
            } catch (RuntimeException error) {
                record(app, "后台任务状态检查失败", evidence("recovery_state_failed", error));
            }
            final boolean active = work;
            final String note = text;
            main.post(() -> {
                recovering = false;
                if (destroyed) return;
                if (!active) {
                    if (owner != latestStartId) { recoverInBackground(); return; }
                    if (stopSelfResult(owner)) {
                        stopping = true;
                        releaseWake();
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        foreground = false;
                    }
                } else {
                    try {
                        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        if (nm != null) nm.notify(NOTE_ID, notification(note));
                    } catch (RuntimeException error) {
                        recordAsync(app, "notification_update_failed", "后台任务通知更新失败", error);
                    }
                }
            });
        });
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        recordActiveLifecycle("task_removed", "任务界面已移除，后台服务仍在运行");
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (!stopping) recordActiveLifecycle("service_destroyed_with_work", "任务仍活跃时后台服务被销毁");
        // Recovery never owns an agent's running thread; destroying the service does not cancel it.
        recoveryWorker.shutdown();
        releaseWake();
        super.onDestroy();
    }

    private void recordActiveLifecycle(String event, String summary) {
        final Context app = getApplicationContext();
        DIAGNOSTICS.execute(() -> {
            try {
                if (RunHub.get(app).hasWork()) record(app, summary, evidence(event, null));
            } catch (RuntimeException error) {
                record(app, summary, evidence(event, error));
            }
        });
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "任务", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text).setOngoing(true).setContentIntent(pending).getNotification();
    }

    private void holdWake() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) throw new IllegalStateException("PowerManager unavailable");
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "backcast:run");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (RuntimeException error) {
            if (!wakeFailureReported) recordAsync(this, "wake_lock_failed", "后台运行唤醒锁获取失败", error);
            wakeFailureReported = true;
        }
    }

    private void releaseWake() {
        PowerManager.WakeLock previous = wakeLock;
        wakeLock = null;
        try { if (previous != null && previous.isHeld()) previous.release(); }
        catch (RuntimeException error) { recordAsync(this, "wake_lock_release_failed", "后台运行唤醒锁释放失败", error); }
    }

    private static JSONObject evidence(String event, Throwable error) {
        JSONObject detail = new JSONObject();
        try {
            detail.put("event", event).put("pid", android.os.Process.myPid())
                    .put("observed_at", System.currentTimeMillis());
            if (error != null) detail.put("failure", Diagnostics.failure(error));
        } catch (Exception ignored) { }
        return detail;
    }

    private static void recordAsync(Context context, String event, String summary, Throwable error) {
        recordAsync(context, summary, evidence(event, error));
    }

    private static void recordAsync(Context context, String summary, JSONObject detail) {
        final Context app = context.getApplicationContext();
        DIAGNOSTICS.execute(() -> record(app, summary, detail));
    }

    private static boolean record(Context context, String summary, JSONObject detail) {
        try (ChatStore store = new ChatStore(context)) {
            List<Long> pending = store.runningIds();
            detail.put("pending_sessions", new JSONArray(pending));
            String safe = Diagnostics.boundedJson(detail);
            store.recordDiagnostic(-1L, "agent_service", summary, safe);
            for (Long sid : pending) store.recordDiagnostic(sid.longValue(), "agent_service", summary, safe);
            return true;
        } catch (Exception unavailable) {
            Log.w("AgentService", summary + " (diagnostic unavailable: " + unavailable.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /** Fatal reporting must finish before the application's existing handler terminates the process. */
    public static void recordUncaughtException(Context context, Thread thread, Throwable error) {
        JSONObject detail = evidence("uncaught_exception", error);
        try { detail.put("thread_id", thread.getId()).put("thread_name", thread.getName()); }
        catch (Exception ignored) { }
        record(context.getApplicationContext(), "进程出现未捕获异常", detail);
    }

    /** Call off the main thread; an OS exit record is evidence, not attribution to a particular task. */
    public static synchronized void recordPreviousExit(Context context) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
            if (manager == null) return;
            SharedPreferences saved = context.getSharedPreferences("agent_service_diagnostics", MODE_PRIVATE);
            long recorded = saved.getLong("exit_timestamp", 0L);
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 16);
            ApplicationExitInfo previous = null;
            for (ApplicationExitInfo exit : exits) {
                if (exit.getPid() == android.os.Process.myPid() || !context.getPackageName().equals(exit.getProcessName())) continue;
                if (previous == null || exit.getTimestamp() > previous.getTimestamp()) previous = exit;
            }
            if (previous == null || previous.getTimestamp() <= recorded) return;
            JSONObject detail = evidence("previous_process_exit", null);
            detail.put("exit_pid", previous.getPid()).put("exit_reason", previous.getReason())
                    .put("exit_status", previous.getStatus()).put("exit_importance", previous.getImportance())
                    .put("exit_timestamp", previous.getTimestamp());
            if (record(context, "检测到上次进程退出记录", detail))
                saved.edit().putLong("exit_timestamp", previous.getTimestamp()).commit();
        } catch (Exception unavailable) {
            Log.w("AgentService", "Previous process exit evidence unavailable: " + unavailable.getClass().getSimpleName());
        }
    }
}
