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

/** One chronological level. Raw reasoning never enters an Android text layout. */
public final class WorkTimeline extends LinearLayout {
    public interface Actions {
        void summarize(TurnTrace.Piece piece, boolean retry);
        void command(TurnTrace.Step step);
    }
    private final Actions actions;
    private final List<Row> rows = new ArrayList<Row>();
    private static final int INK = 0xFF252528, MUTED = 0xFF77777D;

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
            if (range.trace.showReasoning || piece.think == null) visible.add(piece);
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
            row.bind(live);
        }
    }

    private final class Row {
        final TurnTrace.Piece piece;
        final LinearLayout root, content, notes;
        final TextView marker, title, state, brief, retry;
        final ProgressBar progress;
        final View line;
        String renderedSummary = null;
        String renderedArgs = null, renderedResult = null;

        Row(final TurnTrace.Piece piece) {
            this.piece = piece;
            root = new LinearLayout(getContext());
            root.setOrientation(HORIZONTAL);
            LinearLayout rail = new LinearLayout(getContext());
            rail.setOrientation(VERTICAL);
            rail.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout dot = new FrameLayout(getContext());
            marker = label(13, MUTED);
            marker.setGravity(Gravity.CENTER);
            progress = new ProgressBar(getContext());
            dot.addView(marker, new FrameLayout.LayoutParams(px(20), px(20)));
            dot.addView(progress, new FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER));
            rail.addView(dot, new LayoutParams(px(20), px(24)));
            line = new View(getContext());
            line.setBackgroundColor(0xFFE8E8EB);
            LayoutParams railLine = new LayoutParams(px(1), 0, 1f);
            railLine.topMargin = px(5);
            rail.addView(line, railLine);
            LayoutParams railParams = new LayoutParams(px(24), ViewGroup.LayoutParams.MATCH_PARENT);
            railParams.rightMargin = px(12);
            root.addView(rail, railParams);
            content = new LinearLayout(getContext());
            content.setOrientation(VERTICAL);
            content.setPadding(0, 0, 0, px(22));
            title = label(16, INK);
            state = label(12, MUTED);
            state.setPadding(0, px(3), 0, px(6));
            brief = label(13, MUTED);
            brief.setLineSpacing(px(3), 1f);
            brief.setMaxLines(3);
            notes = new LinearLayout(getContext());
            notes.setOrientation(VERTICAL);
            retry = label(13, INK);
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

        void bind(boolean live) {
            boolean thinking = piece.think != null;
            boolean active = thinking ? live && !piece.sealed
                    : live && !piece.step.done;
            progress.setVisibility(active ? VISIBLE : GONE);
            marker.setVisibility(active ? GONE : VISIBLE);
            marker.setText(thinking ? (piece.sealed ? "\u2713" : "\u2022")
                    : piece.step.done ? (failed(piece.step) ? "!" : "\u2713") : "\u2022");
            marker.setTextColor(!thinking && failed(piece.step) ? 0xFFB44438 : MUTED);
            if (thinking) {
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
                            TextView heading = label(14, INK);
                            heading.setText(entry.optString("title"));
                            heading.setPadding(0, i == 0 ? 0 : px(12), 0, px(4));
                            notes.addView(heading, width());
                            TextView text = label(14, MUTED);
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
            title.setText(toolTitle(step));
            state.setText(toolState(step, live));
            if (renderedArgs != step.args || renderedResult != step.result) {
                renderedArgs = step.args; renderedResult = step.result;
                brief.setText(ReasoningSummary.clip(command(step), 160)
                        + (step.done && step.result.length() > 0 ? "\n" + ReasoningSummary.clip(step.result, 180) : ""));
            }
        }
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
        try {
            JSONObject args = new JSONObject(step.args);
            return "shell".equals(step.name) ? args.optString("command", step.args)
                    : args.optString("path", step.args);
        } catch (Exception empty) { return step.args == null ? "" : step.args; }
    }

    public static String toolTitle(TurnTrace.Step step) {
        if ("shell".equals(step.name)) return "执行命令";
        if ("read".equals(step.name)) return "读取文件";
        if ("edit".equals(step.name)) return "编辑文件";
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
            heading.setTextColor(INK);
            state = new TextView(context);
            state.setTextSize(12);
            state.setTextColor(MUTED);
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
                    text.setTextColor(INK);
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
