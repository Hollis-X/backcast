package com.mkei.backcast.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.mkei.backcast.R;
import com.mkei.backcast.agent.SubAgentManager;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 子代理的状态标签，和详情页「通信记录」里的每一条：
 * 发送方 → 接收方、收到还是发出、类型、送达或执行状态、时间、正文。
 * 数据来自 {@link SubAgentManager#communications(String)}。
 */
public final class AgentComms {
    private AgentComms() { }

    /** phase=cancelled 的 failed 是用户手动停掉的，不算异常。 */
    public static int stateLabel(String status, String phase) {
        if (SubAgentManager.RUNNING.equals(status)) return R.string.agent_state_running;
        if (SubAgentManager.QUEUED.equals(status)) return R.string.agent_state_queued;
        if (SubAgentManager.WAITING.equals(status)) return R.string.agent_state_waiting;
        if (SubAgentManager.FAILED.equals(status))
            return "cancelled".equals(phase) ? R.string.agent_state_stopped : R.string.agent_state_failed;
        if (SubAgentManager.STOPPED.equals(status)) return R.string.agent_state_stopped;
        if (SubAgentManager.CLOSED.equals(status)) return R.string.agent_state_closed;
        return R.string.agent_state_idle;
    }

    public static int stateColor(String status, String phase) {
        if (SubAgentManager.RUNNING.equals(status)) return M3.primary;
        if (SubAgentManager.QUEUED.equals(status) || SubAgentManager.WAITING.equals(status)) return M3.tertiary;
        if (SubAgentManager.FAILED.equals(status)) return "cancelled".equals(phase) ? M3.outline : M3.error;
        if (SubAgentManager.STOPPED.equals(status) || SubAgentManager.CLOSED.equals(status)) return M3.outline;
        return M3.success;
    }

    /** 状态小标签：圆点（进行中换成小转圈）+ 文字，底色是同色 12%。 */
    public static View chip(Context context, int color, CharSequence text, boolean spinning) {
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(context, 8), dp(context, 3), dp(context, 10), dp(context, 3));
        chip.setBackground(M3.fill(M3.alpha(color, 0.12f), dp(context, M3.S)));
        if (spinning) {
            com.google.android.material.progressindicator.CircularProgressIndicator spinner =
                    new com.google.android.material.progressindicator.CircularProgressIndicator(context);
            spinner.setIndeterminate(true);
            spinner.setIndicatorSize(dp(context, 10));
            spinner.setTrackThickness(dp(context, 2));
            spinner.setIndicatorColor(color);
            spinner.setTrackColor(0);
            chip.addView(spinner, new LinearLayout.LayoutParams(dp(context, 10), dp(context, 10)));
        } else {
            View dot = new View(context);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(color);
            dot.setBackground(circle);
            chip.addView(dot, new LinearLayout.LayoutParams(dp(context, 7), dp(context, 7)));
        }
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(color);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(context, 6);
        chip.addView(label, lp);
        return chip;
    }

    /** 最终结果：完成时的 final，或任务失败时由管理器记下的 error（没有 mailId）。 */
    public static boolean isResult(JSONObject entry) {
        String kind = entry.optString("kind");
        return "final".equals(kind) || "error".equals(kind) && entry.optString("mailId").length() == 0;
    }

    public static int kindLabel(JSONObject entry) {
        String kind = entry.optString("kind");
        if ("task".equals(kind)) return R.string.comm_kind_task;
        if ("progress".equals(kind)) return R.string.comm_kind_progress;
        if ("partial".equals(kind)) return R.string.comm_kind_partial;
        if (isResult(entry)) return "final".equals(kind) ? R.string.comm_kind_final : R.string.comm_kind_failed;
        if ("error".equals(kind)) return R.string.comm_kind_error;
        return R.string.comm_kind_message;
    }

    /** 返回 0 表示没有可显示的状态（升级前留下的旧记录）。 */
    public static int statusLabel(JSONObject entry) {
        String status = entry.optString("status");
        if (status.length() == 0) return 0;
        if ("task".equals(entry.optString("kind"))) {
            if (SubAgentManager.QUEUED.equals(status)) return R.string.comm_status_queued;
            if (SubAgentManager.RUNNING.equals(status)) return R.string.comm_status_running;
            if (SubAgentManager.WAITING.equals(status)) return R.string.comm_status_waiting;
            if ("completed".equals(status)) return R.string.comm_status_completed;
            if (SubAgentManager.FAILED.equals(status)) return R.string.comm_status_failed;
            if (SubAgentManager.STOPPED.equals(status)) return R.string.comm_status_stopped;
            return 0;
        }
        if (isResult(entry)) return SubAgentManager.MAIL_READ.equals(status)
                ? R.string.comm_status_result_read : R.string.comm_status_result_pending;
        if (SubAgentManager.MAIL_READ.equals(status)) return R.string.comm_status_read;
        if (SubAgentManager.MAIL_DROPPED.equals(status)) return R.string.comm_status_dropped;
        return R.string.comm_status_pending;
    }

    public static int statusColor(JSONObject entry) {
        String status = entry.optString("status");
        if (SubAgentManager.RUNNING.equals(status)) return M3.primary;
        if (SubAgentManager.MAIL_READ.equals(status) || "completed".equals(status)) return M3.success;
        if (SubAgentManager.FAILED.equals(status) || SubAgentManager.MAIL_DROPPED.equals(status)) return M3.error;
        if (SubAgentManager.STOPPED.equals(status)) return M3.outline;
        return M3.tertiary;
    }

    /** 今天的只写时分秒，更早的带上月日。 */
    public static String time(long millis) {
        if (millis <= 0) return "";
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        boolean today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(today ? "HH:mm:ss" : "MM-dd HH:mm", Locale.ROOT).format(new Date(millis));
    }

    /** 最近活动：一分钟内写「刚刚」，一小时内写分钟，一天内写小时，更早写日期。 */
    public static String ago(Context context, long millis) {
        long gap = Math.max(0, System.currentTimeMillis() - millis);
        if (gap < 60000L) return context.getString(R.string.agent_meta_now);
        if (gap < 3600000L) return context.getString(R.string.agent_meta_minutes, (int) (gap / 60000L));
        if (gap < 86400000L) return context.getString(R.string.agent_meta_hours, (int) (gap / 3600000L));
        return time(millis);
    }

    /** 某一方在这一页上的称呼：本代理写「我」，用户从这个页面发的写「你」，其余写名字。 */
    private static String party(Context context, JSONObject entry, String id, String name, String self, boolean sender) {
        if (sender && entry.optBoolean("fromUser")) return context.getString(R.string.comm_user);
        if (self.equals(id)) return context.getString(R.string.comm_me);
        return name.length() == 0 ? id : name;
    }

    /**
     * 一条通信卡片。发出的铺 secondaryContainer，收到的铺 surfaceContainerHigh，一眼分得清方向。
     * 正文超过 6 行时折叠，点「展开全文」切换。
     */
    public static View row(final Context context, JSONObject entry, String self, boolean expanded,
                           View.OnClickListener toggle) {
        boolean outgoing = entry.optBoolean("outgoing");
        int ink = outgoing ? M3.onSecondaryContainer : M3.onSurface;
        int soft = outgoing ? M3.alpha(M3.onSecondaryContainer, 0.72f) : M3.onSurfaceVariant;
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        card.setBackground(M3.fill(outgoing ? M3.secondaryContainer : M3.containerHigh, dp(context, M3.L)));
        card.setContentDescription("comm:" + entry.optString("kind") + ":" + entry.optString("from") + "->" + entry.optString("to"));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(name(context, party(context, entry, entry.optString("from"), entry.optString("fromName"), self, true), ink),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView arrow = new ImageView(context);
        arrow.setImageDrawable(Icons.tinted(context, R.drawable.ic_arrow_forward, soft, dp(context, 14)));
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(context, 14), dp(context, 14));
        arrowLp.leftMargin = dp(context, 6);
        arrowLp.rightMargin = dp(context, 6);
        head.addView(arrow, arrowLp);
        TextView to = name(context, party(context, entry, entry.optString("to"), entry.optString("toName"), self, false), ink);
        head.addView(to, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView when = new TextView(context);
        when.setText(time(entry.optLong("createdAt")));
        when.setTextSize(12);
        when.setTextColor(soft);
        head.addView(when);
        card.addView(head);

        LinearLayout tags = new LinearLayout(context);
        tags.setOrientation(LinearLayout.HORIZONTAL);
        tags.setGravity(Gravity.CENTER_VERTICAL);
        TextView kind = new TextView(context);
        kind.setText(context.getString(outgoing ? R.string.comm_out : R.string.comm_in) + " · "
                + context.getString(kindLabel(entry)));
        kind.setTextSize(12);
        kind.setTextColor(soft);
        kind.setIncludeFontPadding(false);
        kind.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4));
        kind.setBackground(M3.stroke(0, M3.alpha(ink, 0.24f), dp(context, 1), dp(context, M3.S)));
        tags.addView(kind);
        int status = statusLabel(entry);
        if (status != 0) {
            View chip = chip(context, statusColor(entry), context.getString(status),
                    SubAgentManager.RUNNING.equals(entry.optString("status")));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(context, 8);
            tags.addView(chip, lp);
        }
        LinearLayout.LayoutParams tagsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagsLp.topMargin = dp(context, 8);
        card.addView(tags, tagsLp);

        String body = entry.optString("text").trim();
        TextView text = new TextView(context);
        text.setText(body);
        text.setTextSize(14);
        text.setTextColor(ink);
        text.setLineSpacing(dp(context, 2), 1f);
        text.setTextIsSelectable(false);
        boolean longText = body.length() > 240 || lines(body) > 6;
        if (longText && !expanded) {
            text.setMaxLines(6);
            text.setEllipsize(TextUtils.TruncateAt.END);
        }
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = dp(context, 10);
        card.addView(text, textLp);
        if (longText) {
            TextView more = new TextView(context);
            more.setText(expanded ? R.string.comm_less : R.string.comm_more);
            more.setTextSize(13);
            more.setTextColor(M3.primary);
            more.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            more.setPadding(0, dp(context, 8), dp(context, 8), 0);
            more.setOnClickListener(toggle);
            card.addView(more);
        }
        return card;
    }

    private static TextView name(Context context, String value, int color) {
        TextView name = new TextView(context);
        name.setText(value);
        name.setTextSize(14);
        name.setTextColor(color);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        return name;
    }

    private static int lines(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        return count;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
