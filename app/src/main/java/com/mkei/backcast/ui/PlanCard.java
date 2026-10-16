package com.mkei.backcast.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.mkei.backcast.R;
import org.json.JSONArray;
import org.json.JSONObject;

/** 会话里的任务计划卡片（M3 色调卡片）：标题行 + 进度条，已完成实心勾，进行中转圈，待做空心圆。 */
public final class PlanCard extends LinearLayout {
    private final TextView header, count;
    private final com.google.android.material.progressindicator.LinearProgressIndicator progress;
    private final LinearLayout list;
    private String rendered = null;
    private boolean renderedLive;

    public PlanCard(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setContentDescription("plan");
        setBackgroundResource(R.drawable.bg_plan_card);
        setPadding(px(16), px(14), px(16), px(12));
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        header = new TextView(context);
        header.setText("任务计划");
        header.setTextSize(14);
        header.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        header.setTextColor(M3.onSecondaryContainer);
        top.addView(header, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        count = new TextView(context);
        count.setTextSize(12);
        count.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        count.setTextColor(M3.onSecondaryContainer);
        top.addView(count);
        addView(top, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        progress = new com.google.android.material.progressindicator.LinearProgressIndicator(context);
        progress.setTrackThickness(px(4));
        progress.setTrackCornerRadius(px(2));
        progress.setIndicatorColor(M3.primary);
        progress.setTrackColor(M3.alpha(M3.onSecondaryContainer, 0.16f));
        progress.setMax(100);
        LayoutParams barLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = px(10);
        barLp.bottomMargin = px(6);
        addView(progress, barLp);
        list = new LinearLayout(context);
        list.setOrientation(VERTICAL);
        addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 卡片在会话里的外边距，留出阴影的位置。 */
    public static LinearLayout.LayoutParams layout(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int gap = Math.round(6 * density);
        lp.setMargins(Math.round(1 * density), Math.round(2 * density), Math.round(1 * density), gap + Math.round(4 * density));
        return lp;
    }

    /** live=false 时进行中的那一步不转圈，用虚线圆圈表示没做完。 */
    public void bind(String args, boolean live) {
        if (args != null && args.equals(rendered) && live == renderedLive) return;
        JSONArray items;
        items = com.mkei.backcast.tool.PlanTool.normalize(args);
        if (items == null) return;
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
        count.setText(done + "/" + items.length());
        progress.setProgressCompat(items.length() == 0 ? 0 : Math.round(100f * done / items.length()), false);
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
            com.google.android.material.progressindicator.CircularProgressIndicator spinner =
                    new com.google.android.material.progressindicator.CircularProgressIndicator(getContext());
            spinner.setIndeterminate(true);
            spinner.setIndicatorSize(px(16));
            spinner.setTrackThickness(px(2));
            spinner.setIndicatorColor(M3.primary);
            mark.addView(spinner, new FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER));
        } else {
            ImageView icon = new ImageView(getContext());
            icon.setImageResource("completed".equals(status) ? R.drawable.ic_step_done : R.drawable.ic_step_pending);
            mark.addView(icon, new FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER));
        }
        LayoutParams markLp = new LayoutParams(px(20), px(20));
        markLp.rightMargin = px(12);
        row.addView(mark, markLp);
        TextView text = new TextView(getContext());
        text.setText(content);
        text.setTextSize(14);
        text.setLineSpacing(px(2), 1f);
        text.setTextColor(doing ? M3.onSecondaryContainer : M3.alpha(M3.onSecondaryContainer, 0.72f));
        if (doing) text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        row.addView(text, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private int px(float dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
}
