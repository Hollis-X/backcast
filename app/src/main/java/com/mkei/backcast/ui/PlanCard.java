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
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 会话里的任务计划卡片（M3 色调卡片）：标题行 + 进度条，已完成绿色对勾，执行中转圈，待执行虚线圆圈。
 *
 * 每次调用 update_plan 是一个版本，各自一张卡片，旧版本保留当时的内容、不再改动。
 * 同一页里的卡片按出现顺序编号（{@link #renumber}）：最后一张是「最新」，照常展开、随计划实时更新；
 * 更早的是「历史」，收起成一行，点开可以查看当时的步骤和状态。
 */
public final class PlanCard extends LinearLayout {
    private final LinearLayout top;
    private final TextView header, version, count;
    private final View chevron;
    private final com.google.android.material.progressindicator.LinearProgressIndicator progress;
    private final LinearLayout list;
    private String rendered = null;
    private boolean renderedLive;
    private boolean history, open;

    public PlanCard(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setContentDescription("plan");
        setBackgroundResource(R.drawable.bg_plan_card);
        setPadding(px(16), px(14), px(16), px(12));
        top = new LinearLayout(context);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        header = new TextView(context);
        header.setText("任务计划");
        header.setTextSize(14);
        header.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        header.setTextColor(M3.onSecondaryContainer);
        top.addView(header);
        version = new TextView(context);
        version.setTextSize(12);
        version.setVisibility(GONE);
        LayoutParams versionLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionLp.leftMargin = px(8);
        top.addView(version, versionLp);
        top.addView(new View(context), new LayoutParams(0, 1, 1f));
        count = new TextView(context);
        count.setTextSize(12);
        count.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        count.setTextColor(M3.onSecondaryContainer);
        top.addView(count);
        View arrow = new View(context);
        arrow.setBackground(Icons.tinted(context, Icons.CHEVRON_DOWN, M3.onSurfaceVariant, px(16)));
        LayoutParams arrowLp = new LayoutParams(px(16), px(16));
        arrowLp.leftMargin = px(6);
        arrow.setVisibility(GONE);
        top.addView(arrow, arrowLp);
        chevron = arrow;
        top.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { if (history) { open = !open; paintVersion(); } }
        });
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

    /** live=false 时执行中的那一步不转圈，换成静止的半圈。历史版本永远不转。 */
    public void bind(String args, boolean live) {
        live = live && !history;
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

    /** 这一轮结束：执行中的那一步停止转圈。 */
    public void settle() { if (rendered != null && renderedLive) bind(rendered, false); }

    /** 新的一轮接着跑（目标续跑）：最新版本里执行中的那一步重新转起来。 */
    public void relive() { if (rendered != null && !history && !renderedLive) bind(rendered, true); }

    public boolean isHistory() { return history; }

    /** 编号：第 number 版，共 total 版；不是最后一张的都是历史版本（停转、收起，内容不再改）。 */
    public void setVersion(int number, int total, boolean latest) {
        boolean becameHistory = !latest && !history;
        history = !latest;
        if (becameHistory) { settle(); open = false; }
        version.setTag(new int[] { number, total });
        paintVersion();
    }

    private void paintVersion() {
        int[] v = version.getTag() instanceof int[] ? (int[]) version.getTag() : new int[] { 1, 1 };
        // 只有一版时不写版本号，免得多余；有多版时写清楚第几版、是最新还是历史。
        version.setVisibility(v[1] > 1 ? VISIBLE : GONE);
        version.setText("第 " + v[0] + " 版 · " + (history ? "历史" : "最新"));
        version.setTextColor(history ? M3.onSurfaceVariant : M3.primary);
        int ink = history ? M3.onSurfaceVariant : M3.onSecondaryContainer;
        header.setTextColor(ink);
        count.setTextColor(ink);
        if (history) setBackground(M3.fill(M3.container, px(M3.L)));
        else setBackgroundResource(R.drawable.bg_plan_card);
        boolean shown = !history || open;
        progress.setVisibility(shown ? VISIBLE : GONE);
        list.setVisibility(shown ? VISIBLE : GONE);
        chevron.setVisibility(history ? VISIBLE : GONE);
        chevron.setRotation(open ? 180f : 0f);
        setPadding(px(16), px(history && !open ? 12 : 14), px(16), px(history && !open ? 12 : 12));
    }

    /** 找出某个容器里的所有计划卡片，全部停止转圈。 */
    public static void settleAll(View root) {
        for (PlanCard card : collect(root, new ArrayList<PlanCard>())) card.settle();
    }

    /** 容器里最后（最新）的一张计划卡片；没有返回 null。 */
    public static PlanCard latest(View root) {
        List<PlanCard> cards = collect(root, new ArrayList<PlanCard>());
        return cards.isEmpty() ? null : cards.get(cards.size() - 1);
    }

    /** 按出现顺序给容器里的计划卡片编号：最后一张是最新，其余是历史。 */
    public static void renumber(View root) {
        List<PlanCard> cards = collect(root, new ArrayList<PlanCard>());
        for (int i = 0; i < cards.size(); i++) cards.get(i).setVersion(i + 1, cards.size(), i == cards.size() - 1);
    }

    private static List<PlanCard> collect(View root, List<PlanCard> out) {
        if (root instanceof PlanCard) out.add((PlanCard) root);
        else if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
        return out;
    }

    /** 挂到会话里时（实时新增、回放整页、往前补页）在下一帧给整页的卡片重新编号。 */
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        View stream = this;
        while (stream.getParent() instanceof View && stream.getId() != R.id.stream) stream = (View) stream.getParent();
        if (stream.getId() != R.id.stream) return;
        Object pending = stream.getTag(R.id.stream);
        if (pending instanceof Runnable) stream.removeCallbacks((Runnable) pending);
        final View page = stream;
        Runnable again = new Runnable() { @Override public void run() { page.setTag(R.id.stream, null); renumber(page); } };
        stream.setTag(R.id.stream, again);
        stream.post(again);
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
            icon.setImageResource("completed".equals(status) ? R.drawable.ic_step_done
                    : doing ? R.drawable.ic_step_doing : R.drawable.ic_step_pending);
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
