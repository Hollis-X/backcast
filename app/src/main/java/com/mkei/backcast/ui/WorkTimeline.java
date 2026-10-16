package com.mkei.backcast.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.mkei.backcast.agent.ReasoningSummary;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** One chronological level. Reasoning shows its summary, or the original text when summaries are off. */
public final class WorkTimeline extends LinearLayout {
    public interface Actions {
        void summarize(TurnTrace.Piece piece, boolean retry);
        void command(TurnTrace.Step step);
    }
    private final Actions actions;
    private final List<Row> rows = new ArrayList<Row>();

    public WorkTimeline(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        setOrientation(VERTICAL);
        setPadding(0, px(8), 0, px(12));
    }

    public void bind(TurnTrace.Range range, boolean live) {
        int start = Math.max(0, range.start);
        int end = Math.min(range.end, range.trace.order.size());
        List<TurnTrace.Piece> visible = new ArrayList<TurnTrace.Piece>();
        for (int i = start; i < end; i++) {
            TurnTrace.Piece piece = range.trace.order.get(i);
            visible.add(piece);
        }
        int count = visible.size();
        boolean reset = rows.size() > count;
        for (int i = 0; !reset && i < rows.size(); i++)
            reset = rows.get(i).piece != visible.get(i);
        if (reset) { rows.clear(); removeAllViews(); }
        while (rows.size() < count) {
            Row row = new Row(visible.get(rows.size()));
            rows.add(row);
            addView(row.root, width());
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            row.line.setVisibility(i == rows.size() - 1 ? INVISIBLE : VISIBLE);
            row.bind(live, range.trace.rawReasoning);
        }
    }

    private final class Row {
        final TurnTrace.Piece piece;
        final LinearLayout root, content, notes;
        final TextView marker, title, state, brief, retry;
        final com.google.android.material.progressindicator.CircularProgressIndicator progress;
        final View line;
        String renderedSummary = null;
        String renderedArgs = null, renderedResult = null;
        int renderedThink = -1;

        Row(final TurnTrace.Piece piece) {
            this.piece = piece;
            root = new LinearLayout(getContext());
            root.setOrientation(HORIZONTAL);
            LinearLayout rail = new LinearLayout(getContext());
            rail.setOrientation(VERTICAL);
            rail.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout dot = new FrameLayout(getContext());
            marker = label(11, M3.onSecondaryContainer);
            marker.setGravity(Gravity.CENTER);
            // M3：每一步一个 secondaryContainer 小圆点；运行中换成圆形进度指示器。
            android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
            dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dotBg.setColor(M3.secondaryContainer);
            marker.setBackground(dotBg);
            progress = new com.google.android.material.progressindicator.CircularProgressIndicator(getContext());
            progress.setIndeterminate(true);
            progress.setIndicatorSize(px(16));
            progress.setTrackThickness(px(2));
            progress.setIndicatorColor(M3.primary);
            dot.addView(marker, new FrameLayout.LayoutParams(px(20), px(20)));
            dot.addView(progress, new FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER));
            rail.addView(dot, new LayoutParams(px(20), px(24)));
            line = new View(getContext());
            line.setBackgroundColor(M3.outlineVariant);
            LayoutParams railLine = new LayoutParams(px(1), 0, 1f);
            railLine.topMargin = px(5);
            rail.addView(line, railLine);
            LayoutParams railParams = new LayoutParams(px(24), ViewGroup.LayoutParams.MATCH_PARENT);
            railParams.rightMargin = px(12);
            root.addView(rail, railParams);
            content = new LinearLayout(getContext());
            content.setOrientation(VERTICAL);
            content.setPadding(0, 0, 0, px(22));
            title = label(16, M3.onSurface);
            state = label(12, M3.onSurfaceVariant);
            state.setPadding(0, px(3), 0, px(6));
            brief = label(13, M3.onSurfaceVariant);
            brief.setLineSpacing(px(3), 1f);
            brief.setMaxLines(3);
            notes = new LinearLayout(getContext());
            notes.setOrientation(VERTICAL);
            retry = label(14, M3.primary);
            retry.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            retry.setText("重试摘要");
            retry.setPadding(0, px(6), 0, px(6));
            retry.setOnClickListener(new OnClickListener() {
                public void onClick(View v) { actions.summarize(piece, true); }
            });
            content.addView(title, width());
            content.addView(state, width());
            content.addView(brief, width());
            content.addView(notes, width());
            content.addView(retry, width());
            root.addView(content, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (piece.step != null) {
                content.setOnClickListener(new OnClickListener() {
                    public void onClick(View v) { actions.command(piece.step); }
                });
            }
        }

        void bind(boolean live, boolean raw) {
            boolean thinking = piece.think != null;
            boolean active = thinking ? live && !piece.sealed
                    : live && !piece.step.done;
            progress.setVisibility(active ? VISIBLE : GONE);
            marker.setVisibility(active ? GONE : VISIBLE);
            marker.setText(thinking ? (piece.sealed ? "\u2713" : "\u2022")
                    : piece.step.done ? (failed(piece.step) ? "!" : "\u2713") : "\u2022");
            boolean bad = !thinking && failed(piece.step);
            marker.setTextColor(bad ? M3.onErrorContainer : M3.onSecondaryContainer);
            ((android.graphics.drawable.GradientDrawable) marker.getBackground()).setColor(bad ? M3.errorContainer : M3.secondaryContainer);
            if (thinking && raw) {
                bindRaw(active);
                return;
            }
            if (thinking) {
                renderedThink = -1;
                brief.setMaxLines(3);
                if (piece.sealed) actions.summarize(piece, false);
                title.setText(piece.sealed ? "推理摘要" : "正在思考");
                String caption = active ? "思考中" : piece.sealed ? "思考结束" : "思考未完成";
                if (piece.thinkMs > 0) caption += " · " + ((piece.thinkMs + 999) / 1000) + "s";
                if (piece.summaryPending) caption += " · 正在总结推理";
                state.setText(caption);
                brief.setVisibility(piece.sealed && piece.summary.length() == 0 ? VISIBLE : GONE);
                brief.setText(!piece.sealed ? "" : piece.summaryError.length() > 0 ? piece.summaryError
                        : piece.summaryPending ? "正在总结推理" : "等待推理摘要");
                retry.setVisibility(piece.summaryError.length() > 0
                        && !piece.summaryError.equals(com.mkei.backcast.agent.PromptGuard.REFUSAL) ? VISIBLE : GONE);
                notes.setVisibility(piece.sealed ? VISIBLE : GONE);
                if (!piece.summary.equals(renderedSummary)) {
                    renderedSummary = piece.summary;
                    notes.removeAllViews();
                    try {
                        JSONArray entries = new JSONArray(piece.summary);
                        for (int i = 0; i < entries.length(); i++) {
                            JSONObject entry = entries.getJSONObject(i);
                            TextView heading = label(14, M3.onSurface);
                            heading.setText(entry.optString("title"));
                            heading.setPadding(0, i == 0 ? 0 : px(12), 0, px(4));
                            notes.addView(heading, width());
                            TextView text = label(14, M3.onSurfaceVariant);
                            text.setText(entry.optString("text"));
                            text.setLineSpacing(px(4), 1f);
                            notes.addView(text, width());
                        }
                    } catch (Exception empty) { }
                }
                return;
            }
            retry.setVisibility(GONE);
            TurnTrace.Step step = piece.step;
            brief.setMaxLines(3);
            title.setText(toolTitle(step));
            state.setText(withDelta(toolState(step, live), step.done ? com.mkei.backcast.agent.LineDelta.parse(step.result) : null));
            if (renderedArgs != step.args || renderedResult != step.result) {
                renderedArgs = step.args; renderedResult = step.result;
                brief.setText(ReasoningSummary.clip(command(step), 160)
                        + (step.done && step.result.length() > 0 ? "\n" + ReasoningSummary.clip(com.mkei.backcast.agent.LineDelta.strip(step.result), 180) : ""));
            }
        }

        private void bindRaw(boolean active) {
            title.setText(piece.sealed ? "思考" : "正在思考");
            String caption = active ? "思考中" : piece.sealed ? "思考结束" : "思考未完成";
            if (piece.thinkMs > 0) caption += " · " + ((piece.thinkMs + 999) / 1000) + "s";
            state.setText(caption);
            retry.setVisibility(GONE);
            notes.setVisibility(GONE);
            int size = piece.think.length();
            brief.setMaxLines(Integer.MAX_VALUE);
            brief.setVisibility(size == 0 ? GONE : VISIBLE);
            if (renderedThink != size) {
                renderedThink = size;
                String text = piece.think.toString().trim();
                if (text.length() > RAW_LIMIT) {
                    int from = text.length() - RAW_LIMIT;
                    if (Character.isLowSurrogate(text.charAt(from))) from++;
                    text = "…" + text.substring(from);
                }
                brief.setText(text);
            }
        }
    }

    /** 原始思考只取末尾一段，避免超长文本拖慢排版。 */
    private static final int RAW_LIMIT = 4000;

    /** 在文字后面接上绿色 +增 红色 -删，带浅色底。delta 为 null 时原样返回。 */
    public static CharSequence withDelta(CharSequence text, int[] delta) {
        if (delta == null) return text;
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder(text);
        if (out.length() > 0) out.append(" ");
        appendChip(out, " +" + delta[0] + " ", M3.onSuccessContainer, M3.successContainer);
        appendChip(out, " -" + delta[1] + " ", M3.onErrorContainer, M3.errorContainer);
        return out;
    }

    private static void appendChip(android.text.SpannableStringBuilder out, String chip, int ink, int fill) {
        int start = out.length();
        out.append(chip.trim());
        out.setSpan(new ChipSpan(ink, fill), start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    public static String toolState(TurnTrace.Step step, boolean live) {
        return step.done ? (failed(step) ? "失败" : "完成")
                : live ? TurnTrace.stepPhaseCaption(step) : "未完成";
    }

    public static String resultTitle(TurnTrace.Step step) {
        return step.done ? (failed(step) ? "失败" : "返回结果") : toolState(step, true);
    }

    public static boolean failed(TurnTrace.Step step) {
        return step.done && com.mkei.backcast.agent.ToolOutcome.failed(step.name, step.result);
    }

    public static String command(TurnTrace.Step step) {
        // 计划的完整清单画在会话卡片上，这里只写一句进度，不摊开原始 JSON。
        if (TurnTrace.PLAN.equals(step.name)) return TurnTrace.planView(step.args)[0];
        try {
            JSONObject args = new JSONObject(step.args);
            return "shell".equals(step.name) ? args.optString("command", step.args)
                    : args.optString("path", step.args);
        } catch (Exception empty) { return step.args == null ? "" : step.args; }
    }

    public static String toolTitle(TurnTrace.Step step) {
        if (TurnTrace.PLAN.equals(step.name)) return "更新任务计划";
        if (TurnTrace.ASK.equals(step.name)) return "询问用户";
        if ("shell".equals(step.name)) return "执行命令";
        if ("read".equals(step.name)) return "读取文件";
        if ("edit".equals(step.name)) return "修改文件";
        if ("write".equals(step.name)) return "写入文件";
        if ("update_goal".equals(step.name)) return "更新目标";
        return step.name;
    }

    public static final class CommandView extends LinearLayout {
        private final TurnTrace.Step step;
        private final TextView heading, state;
        private final List<String> chunks = new ArrayList<String>();
        private final BaseAdapter adapter;
        private String renderedArgs, renderedResult;
        private String renderedPhase;
        private boolean renderedDone, renderedStarted;

        public CommandView(final Context context, TurnTrace.Step step) {
            super(context);
            this.step = step;
            setOrientation(VERTICAL);
            final int pad = (int) (8 * context.getResources().getDisplayMetrics().density);
            heading = new TextView(context);
            heading.setTextSize(17);
            heading.setTextColor(M3.onSurface);
            state = new TextView(context);
            state.setTextSize(12);
            state.setTextColor(M3.onSurfaceVariant);
            state.setPadding(0, pad / 2, 0, pad);
            addView(heading);
            addView(state);
            adapter = new BaseAdapter() {
                public int getCount() { return chunks.size(); }
                public Object getItem(int at) { return chunks.get(at); }
                public long getItemId(int at) { return at; }
                public View getView(int at, View recycled, ViewGroup parent) {
                    TextView text = recycled instanceof TextView ? (TextView) recycled : new TextView(context);
                    text.setText(chunks.get(at));
                    text.setTextSize(13);
                    text.setTextColor(M3.onSurface);
                    text.setTypeface(Typeface.MONOSPACE);
                    text.setTextIsSelectable(true);
                    text.setLineSpacing(pad / 2, 1f);
                    text.setPadding(0, pad, 0, pad);
                    return text;
                }
            };
            ListView list = new ListView(context);
            list.setDivider(null);
            list.setAdapter(adapter);
            addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            bind();
        }

        public void bind() {
            heading.setText(toolTitle(step));
            state.setText(toolState(step, true));
            if (renderedArgs == step.args && renderedResult == step.result
                    && renderedDone == step.done && renderedStarted == step.started
                    && step.phase.equals(renderedPhase)) return;
            renderedArgs = step.args; renderedResult = step.result;
            renderedPhase = step.phase;
            renderedDone = step.done; renderedStarted = step.started;
            chunks.clear();
            String arguments = step.args == null ? "" : step.args;
            if (!"preview".equals(step.phase) || step.started || step.done) {
                try { arguments = new JSONObject(arguments).toString(2); } catch (Exception ignored) { }
            }
            chunks.add("调用参数");
            split(chunks, arguments);
            chunks.add(resultTitle(step));
            String result = step.result == null ? "" : step.result;
            split(chunks, result.length() == 0 ? "尚无返回结果" : result);
            adapter.notifyDataSetChanged();
        }
    }

    private static void split(List<String> chunks, String text) {
        for (int at = 0; at < text.length();) {
            int end = Math.min(text.length(), at + 2048);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            chunks.add(text.substring(at, end));
            at = end;
        }
    }

    private TextView label(int size, int color) {
        TextView text = new TextView(getContext());
        text.setTextSize(size);
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        return text;
    }
    private LayoutParams width() { return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private int px(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
}
