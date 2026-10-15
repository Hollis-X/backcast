package com.mkei.backcast.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.mkei.backcast.R;
import org.json.JSONArray;
import org.json.JSONObject;

/** 会话里的任务计划卡片：已完成打勾，进行中转圈，待做是虚线圆圈。 */
public final class PlanCard extends LinearLayout {
    private static final int INK = 0xFF1C1C1E, MUTED = 0xFF8E8E93;
    private final TextView header;
    private final LinearLayout list;
    private String rendered = null;
    private boolean renderedLive;

    public PlanCard(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setContentDescription("plan");
        setBackgroundResource(R.drawable.bg_plan_card);
        setElevation(px(3));
        setPadding(px(16), px(12), px(16), px(12));
        header = new TextView(context);
        header.setTextSize(13);
        header.setTextColor(MUTED);
        header.setPadding(0, 0, 0, px(4));
        addView(header, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list = new LinearLayout(context);
        list.setOrientation(VERTICAL);
        addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 卡片在会话里的外边距，留出阴影的位置。 */
    public static LinearLayout.LayoutParams layout(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int gap = Math.round(6 * density);
        lp.setMargins(Math.round(2 * density), gap, Math.round(2 * density), Math.round(10 * density));
        return lp;
    }

    /** live=false 时进行中的那一步不转圈，用虚线圆圈表示没做完。 */
    public void bind(String args, boolean live) {
        if (args != null && args.equals(rendered) && live == renderedLive) return;
        JSONArray items;
        try { items = new JSONObject(args).getJSONArray("items"); }
        catch (Exception partial) { return; }
        rendered = args; renderedLive = live;
        list.removeAllViews();
        int done = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String status = item.optString("status");
            if ("completed".equals(status)) done++;
            list.addView(row(item.optString("content"), status, live));
        }
        header.setText("任务计划 · " + done + "/" + items.length());
    }

    /** 这一轮结束：进行中的那一步停止转圈。 */
    public void settle() { if (rendered != null && renderedLive) bind(rendered, false); }

    /** 找出某个容器里的所有计划卡片，全部停止转圈。 */
    public static void settleAll(View root) {
        if (root instanceof PlanCard) ((PlanCard) root).settle();
        else if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) settleAll(group.getChildAt(i));
        }
    }

    private View row(String content, String status, boolean live) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(5), 0, px(5));
        FrameLayout mark = new FrameLayout(getContext());
        boolean doing = "in_progress".equals(status);
        if (doing && live) {
            ProgressBar spinner = new ProgressBar(getContext());
            spinner.setIndeterminate(true);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(getResources().getColor(R.color.accent)));
            mark.addView(spinner, new FrameLayout.LayoutParams(px(16), px(16), Gravity.CENTER));
        } else {
            ImageView icon = new ImageView(getContext());
            icon.setImageResource("completed".equals(status) ? R.drawable.ic_step_done : R.drawable.ic_step_pending);
            mark.addView(icon, new FrameLayout.LayoutParams(px(16), px(16), Gravity.CENTER));
        }
        LayoutParams markLp = new LayoutParams(px(20), px(20));
        markLp.rightMargin = px(10);
        row.addView(mark, markLp);
        TextView text = new TextView(getContext());
        text.setText(content);
        text.setTextSize(15);
        text.setLineSpacing(px(2), 1f);
        text.setTextColor("completed".equals(status) ? MUTED : doing ? INK : 0xFF48484A);
        row.addView(text, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private int px(float dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
}
