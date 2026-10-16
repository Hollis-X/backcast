package com.mkei.backcast.ui;

import android.content.Context;
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
 * 子代理会话里穿插的通信行，像 Codex 的活动行：气泡图标 + 一句话（「已向 main 发送消息」「收到 peer 的消息」），
 * 右侧是送达状态和时间，下面两行是消息原文，点一下展开全文。
 * 状态和时间来自 {@link SubAgentManager#communications(String)}，找不到对应记录时只显示原文。
 */
public final class AgentComms {
    private AgentComms() { }

    /** 一条通信行。event 可以为 null（旧记录或工具调用失败）。 */
    public static View inline(Context context, CharSequence label, JSONObject event, String text) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(context, 6), 0, dp(context, 8));
        row.setContentDescription("comm");

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(dp(context, 28));
        ImageView icon = new ImageView(context);
        icon.setImageDrawable(Icons.tinted(context, Icons.CHAT, M3.onSurfaceVariant, dp(context, 18)));
        head.addView(icon, new LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)));
        TextView title = new TextView(context);
        title.setText(label);
        title.setTextSize(14);
        title.setTextColor(M3.onSurfaceVariant);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(context, 10);
        head.addView(title, titleLp);
        CharSequence meta = meta(context, event);
        if (meta.length() > 0) {
            TextView side = new TextView(context);
            side.setText(meta);
            side.setTextSize(12);
            side.setTextColor(M3.alpha(M3.onSurfaceVariant, 0.8f));
            LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sideLp.leftMargin = dp(context, 8);
            head.addView(side, sideLp);
        }
        row.addView(head);

        String body = text == null ? "" : text.trim();
        if (body.length() > 0) {
            final TextView quote = new TextView(context);
            quote.setText(body);
            quote.setTextSize(14);
            quote.setTextColor(M3.alpha(M3.onSurfaceVariant, 0.88f));
            quote.setLineSpacing(dp(context, 2), 1f);
            quote.setMaxLines(2);
            quote.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams quoteLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            quoteLp.leftMargin = dp(context, 28);
            quoteLp.topMargin = dp(context, 2);
            row.addView(quote, quoteLp);
            row.setBackground(M3.ripple(null, M3.onSurface, dp(context, M3.M)));
            row.setOnClickListener(new View.OnClickListener() {
                private boolean open;
                @Override public void onClick(View v) {
                    open = !open;
                    quote.setMaxLines(open ? Integer.MAX_VALUE : 2);
                    quote.setEllipsize(open ? null : TextUtils.TruncateAt.END);
                }
            });
        }
        return row;
    }

    /** 右侧小字：消息写送达状态，再接时间；待读取用 tertiary、未送达用 error 色。 */
    private static CharSequence meta(Context context, JSONObject event) {
        if (event == null) return "";
        String time = time(event.optLong("createdAt"));
        String status = event.optString("status");
        int label = 0, color = 0;
        if (event.optString("mailId").length() > 0) {
            if (SubAgentManager.MAIL_READ.equals(status)) label = R.string.comm_status_read;
            else if (SubAgentManager.MAIL_DROPPED.equals(status)) { label = R.string.comm_status_dropped; color = M3.error; }
            else if (status.length() > 0) { label = R.string.comm_status_pending; color = M3.tertiary; }
        }
        if (label == 0) return time;
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder(context.getString(label));
        if (color != 0) out.setSpan(new android.text.style.ForegroundColorSpan(color), 0, out.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (time.length() > 0) out.append(" · ").append(time);
        return out;
    }

    /** 今天的只写时分，更早的带上月日。 */
    public static String time(long millis) {
        if (millis <= 0) return "";
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        boolean today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(today ? "HH:mm" : "MM-dd HH:mm", Locale.ROOT).format(new Date(millis));
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
