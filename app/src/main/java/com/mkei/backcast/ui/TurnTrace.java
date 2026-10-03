package com.mkei.backcast.ui;

import java.util.ArrayList;
import java.util.List;

/** 一轮里折叠进面板的思考和工具调用。不进请求报文。 */
public class TurnTrace {

    public static class Step {
        public String id = "";
        public String name = "";
        public String args = "";
        public String result = "";
        public boolean done;
        public boolean started;
        /** Streaming arguments are visible before a tool is dispatched. */
        public String phase = "preview";
    }

    public final StringBuilder reasoning = new StringBuilder();
    public final List<Step> steps = new ArrayList<Step>();
    /** 面板按发生顺序排。思考在工具后面来，就排在那条工具后面，不堆到最上面。 */
    public final List<Piece> order = new ArrayList<Piece>();
    /** 这一轮模型回复里，工具下标从 steps 的这个位置算起。 */
    private int roundOrigin;
    /** 这一轮开始时的顺序、思考长度和正文分界。请求失败重试时撤掉这一轮的预览。 */
    private int roundOrder;
    private int roundReasoning;
    private int roundBody = -1;
    private int roundTailThink = -1;
    private boolean roundOpen;
    public long elapsedMs;
    public long thinkMs;
    public boolean showReasoning = true;
    /** These describe the whole turn and survive a failed request's preview rollback. */
    public String phase = "model";
    public int retryCount;
    public String retryReason = "";
    /**
     * 正文开始时 order 的条数。
     * 这个下标之前的思考和命令留在工作时间下面，之后新来的才挂到正文下面。
     * -1 表示这一轮还没有正文。
     */
    public int bodyAt = -1;

    public static class Piece {
        public StringBuilder think;
        public Step step;
        /** 这一段思考自己的耗时。0 表示还没钉死，界面不要拿它编一个 1 秒。 */
        public long thinkMs;
        public boolean sealed;
        public String summary = "";
        public String summaryError = "";
        public boolean summaryPending;
        public int summaryChars;
        public int requestedChars;
        public boolean summaryComplete;
        public int summaryVersion;
        public String summaryPreference = "";
    }

    public static final class Range {
        public final TurnTrace trace;
        public final int start;
        public int end;

        public Range(TurnTrace trace, int start) {
            this.trace = trace; this.start = start; this.end = start;
        }

        public TurnTrace view() {
            TurnTrace part = new TurnTrace();
            for (int i = Math.max(0, start); i < Math.min(end, trace.order.size()); i++) {
                Piece piece = trace.order.get(i);
                part.order.add(piece);
                if (piece.step != null) part.steps.add(piece.step);
            }
            return part;
        }

        public String caption() {
            String text = trace.activityCaption(start, end);
            boolean pending = false;
            String stage = "进行中";
            int priority = 0;
            for (int i = Math.max(0, start); i < Math.min(end, trace.order.size()); i++) {
                Piece piece = trace.order.get(i);
                if (piece.think != null ? trace.showReasoning && !piece.sealed
                        : piece.step != null && !piece.step.done) pending = true;
                if (piece.step != null && !piece.step.done) {
                    Step step = piece.step;
                    int next = step.started || "children".equals(step.phase) ? 4
                            : "tool_approval".equals(step.phase) || "tool_review".equals(step.phase) ? 3
                            : "tool_ready".equals(step.phase) ? 2 : 1;
                    if (next >= priority) { stage = stepPhaseCaption(step); priority = next; }
                }
            }
            return pending ? text + " · " + stage : text;
        }

        public boolean hasDetail() {
            for (int i = Math.max(0, start); i < Math.min(end, trace.order.size()); i++) {
                Piece piece = trace.order.get(i);
                if (piece.step != null || trace.showReasoning && piece.think != null) return true;
            }
            return false;
        }
    }

    public int thinkCount() {
        int count = 0;
        for (Piece piece : order) if (piece.think != null) count++;
        return count;
    }

    public String activityCaption() {
        return activityCaption(0, order.size());
    }

    public String activityCaption(int from, int to) {
        int thinks = 0, tools = 0;
        for (int i = Math.max(0, from); i < Math.min(to, order.size()); i++) {
            if (showReasoning && order.get(i).think != null) thinks++;
            if (order.get(i).step != null) tools++;
        }
        if (thinks == 0 && tools == 0) return "";
        String thought = thinks > 0 ? "思考 " + thinks + " 段" : "";
        String calls = tools > 0 ? "工具 " + tools + " 次" : "";
        return thought.length() == 0 ? calls : calls.length() == 0 ? thought : thought + " · " + calls;
    }

    public boolean hasDetail() {
        return showReasoning && hasThink() || !steps.isEmpty();
    }

    /**
     * 是否真的思考过。
     *
     * 有些服务端会回一个纯空白的 reasoning_content（换行或空格），那不是思考内容，
     * 按它开折叠面板只会得到一个空壳。appendThink 已经在入口挡掉纯空白，
     * 所以这里只要非空就说明有真内容。刷新很频繁，不做字符串拷贝。
     */
    public boolean hasThink() {
        return reasoning.length() > 0;
    }

    /** 新的一次模型回复。后面的工具下标从当前条数另起，不盖住已经跑过的。 */
    public void beginRound() {
        roundOrigin = steps.size();
        roundOrder = order.size();
        roundReasoning = reasoning.length();
        roundBody = bodyAt;
        roundTailThink = -1;
        if (!order.isEmpty()) {
            Piece last = order.get(order.size() - 1);
            if (last.think != null) {
                roundTailThink = last.think.length();
            }
        }
        roundOpen = true;
    }

    /**
     * 这次回复没完成。只撤掉这一轮还没落地的预览，已经跑完的工具留着。
     * 没有记过起点时不动，避免把从库里画回来的整轮清掉。
     */
    public void dropIncompleteRound() {
        if (!roundOpen) {
            return;
        }
        for (int i = steps.size() - 1; i >= roundOrigin; i--) {
            Step step = steps.get(i);
            if (!step.started && !step.done) steps.remove(i);
        }
        for (int i = order.size() - 1; i >= roundOrder; i--) {
            Piece removed = order.get(i);
            if (removed.step != null && (removed.step.started || removed.step.done)) continue;
            order.remove(i);
            removed.summaryVersion++;
            removed.summaryPending = false;
        }
        if (reasoning.length() > roundReasoning) {
            reasoning.setLength(roundReasoning);
        }
        if (roundTailThink >= 0 && !order.isEmpty()) {
            Piece last = order.get(order.size() - 1);
            if (last.think != null && last.think.length() > roundTailThink) {
                last.think.setLength(roundTailThink);
                last.summaryVersion++; last.summaryPending = false;
                last.summary = ""; last.summaryError = ""; last.requestedChars = 0;
                last.summaryComplete = false;
            }
        }
        bodyAt = roundBody;
        roundOpen = false;
    }

    /**
     * 追加思考。上一条还是思考就续上；中间已经有工具，就新开一块排在工具后面。
     */
    public void appendThink(String text) {
        if (text == null || text.trim().length() == 0) {
            return;
        }
        reasoning.append(text);
        if (!order.isEmpty()) {
            Piece last = order.get(order.size() - 1);
            // 正文已经开始后，新来的思考另起一块，不再续在正文前那段上。
            if (last.think != null && !last.sealed && !beforeBody(last)) {
                last.think.append(text);
                return;
            }
        }
        Piece piece = new Piece();
        piece.think = new StringBuilder(text);
        order.add(piece);
    }

    /**
     * 回放时只有整轮耗时，没有每段的。钉到第一段思考上，避免每段都编一个秒数。
     * 已经有实测耗时的不再覆盖。
     */
    public void stampThinkIfMissing(long ms) {
        if (ms <= 0) {
            return;
        }
        for (int i = 0; i < order.size(); i++) {
            Piece piece = order.get(i);
            if (piece.think != null && piece.thinkMs > 0) {
                return;
            }
        }
        for (int i = 0; i < order.size(); i++) {
            Piece piece = order.get(i);
            if (piece.think != null) {
                piece.thinkMs = ms;
                return;
            }
        }
    }

    /** End the current thought without changing its place in the timeline. */
    public void sealThink() {
        if (!order.isEmpty()) {
            Piece last = order.get(order.size() - 1);
            if (last.think != null) last.sealed = true;
        }
    }

    /** 当前还没被工具截断的那段思考。没有则返回 null。 */
    public StringBuilder openThink() {
        if (order.isEmpty()) {
            return null;
        }
        Piece last = order.get(order.size() - 1);
        return last.think;
    }

    /** 流式工具调用。index 只在这一轮回复内计数。 */
    public void previewStep(int index, String id, String name, String args) {
        if (index < 0) {
            index = 0;
        }
        int at = roundOrigin + index;
        while (steps.size() <= at) {
            steps.add(new Step());
        }
        Step step = steps.get(at);
        if (step.done || step.started || !"preview".equals(step.phase)) {
            return;
        }
        if (id != null && id.length() > 0) {
            step.id = id;
        }
        if (name != null && name.length() > 0) {
            step.name = name;
        }
        if (args != null) {
            step.args = args;
        }
        if (step.name.length() > 0 || step.args.length() > 0) {
            placeStep(step);
            phase = "preview";
        }
    }

    /** 这段思考排在正文开始之前。正文之后再来的思考不能并进去。 */
    private boolean beforeBody(Piece piece) {
        if (bodyAt < 0) {
            return false;
        }
        int index = order.indexOf(piece);
        return index >= 0 && index < bodyAt;
    }

    private void placeStep(Step step) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).step == step) {
                return;
            }
        }
        sealThink();
        Piece piece = new Piece();
        piece.step = step;
        order.add(piece);
    }

    /** 这一轮里已经有同名、还没出结果的调用，就不要再插一条。 */
    public boolean hasPending(String id, String name) {
        String key = id == null ? "" : id;
        String tool = name == null ? "" : name;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step.done) {
                continue;
            }
            if (key.length() > 0 && key.equals(step.id)) {
                return true;
            }
            if (key.length() == 0 && tool.length() > 0 && tool.equals(step.name)) {
                return true;
            }
        }
        return false;
    }

    public void addStep(String id, String name, String args) {
        Step step = new Step();
        step.id = id == null ? "" : id;
        step.name = name == null ? "" : name;
        step.args = args == null ? "" : args;
        // A persisted call has complete arguments; it is not a streaming preview.
        step.phase = "tool_ready";
        steps.add(step);
        placeStep(step);
    }

    public void startStep(String name, String args) {
        Step step = pendingStep(name, args);
        if (step == null) {
            addStep("", name, args);
            step = steps.get(steps.size() - 1);
        }
        step.started = true;
        step.args = args == null ? "" : args;
        step.phase = "wait_agent".equals(name) ? "children" : "running";
        phase = step.phase;
    }

    /** Approval updates the same row as the preview, without claiming execution. */
    public void setProgress(String next, String name, String detail, int attempt) {
        String value = next == null ? "" : next;
        retryCount = Math.max(retryCount, Math.max(0, attempt));
        if ("retry".equals(value)) {
            retryReason = safeReason(detail);
        }
        if (value.length() > 0) phase = value;
        if (!"tool_ready".equals(value) && !"tool_review".equals(value)
                && !"tool_approval".equals(value) && !"children".equals(value)) return;
        Step step = pendingStep(name, "tool_ready".equals(value) ? detail : null);
        if (step == null && "children".equals(value)) {
            for (int i = steps.size() - 1; i >= 0; i--) {
                Step candidate = steps.get(i);
                if (!candidate.done && candidate.started && candidate.name.equals(name)) {
                    step = candidate; break;
                }
            }
        }
        if (step == null && "tool_ready".equals(value)) {
            addStep("", name, detail);
            step = steps.get(steps.size() - 1);
        }
        if (step != null && !step.done) {
            if ("tool_ready".equals(value) && step.started) return;
            if ("tool_ready".equals(value)) step.args = detail == null ? "" : detail;
            step.phase = value;
        }
    }

    private Step pendingStep(String name, String args) {
        String tool = name == null ? "" : name;
        for (int i = Math.min(roundOrigin, steps.size()); i < steps.size(); i++) {
            Step step = steps.get(i);
            if (!step.done && !step.started && step.name.equals(tool)) return step;
        }
        // A re-entered UI can have a persisted, complete call before beginRound().
        for (int i = 0; i < Math.min(roundOrigin, steps.size()); i++) {
            Step step = steps.get(i);
            if (!step.done && !step.started && step.name.equals(tool)
                    && (args == null || step.args.equals(args))) return step;
        }
        return null;
    }

    private static String safeReason(String text) {
        if (text == null) return "";
        String value = text.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() <= 160 ? value : value.substring(0, 160);
    }

    public static String stepPhaseCaption(Step step) {
        if ("children".equals(step.phase)) return "等待子任务";
        if (step.started) return "执行中";
        if ("tool_ready".equals(step.phase)) return "等待执行";
        if ("tool_review".equals(step.phase)) return "权限检查中";
        if ("tool_approval".equals(step.phase)) return "等待授权";
        return "正在生成参数";
    }

    /** Live phase plus cumulative retries; this is not a per-tool duration. */
    public String progressCaption(boolean live) {
        String current = "";
        if (live) {
            if ("preview".equals(phase)) current = "正在生成参数";
            else if ("tool_ready".equals(phase)) current = "等待执行";
            else if ("tool_review".equals(phase)) current = "权限检查中";
            else if ("tool_approval".equals(phase)) current = "等待授权";
            else if ("running".equals(phase)) current = "工具执行中";
            else if ("children".equals(phase)) current = "等待子任务";
            else if ("thinking".equals(phase)) current = "正在思考";
            else if ("responding".equals(phase)) current = "正在输出";
            else if ("retry".equals(phase)) current = "正在重试";
            else current = "等待模型";
        }
        if (retryCount > 0) current += (current.length() == 0 ? "" : " · ") + "已重试 " + retryCount + " 次";
        if (live && "retry".equals(phase) && retryReason.length() > 0)
            current += " · " + (retryReason.length() > 48 ? retryReason.substring(0, 48) + "…" : retryReason);
        return current;
    }

    public void fillResult(String id, String name, String result) {
        String key = id == null ? "" : id;
        String tool = name == null ? "" : name;
        if (key.length() == 0) {
            // The executing call may come from restored history before roundOrigin.
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (!step.done && step.started && (tool.length() == 0 || tool.equals(step.name))) {
                    step.result = result == null ? "" : result;
                    step.done = true;
                    return;
                }
            }
            Step pending = pendingStep(tool, null);
            if (pending != null) {
                pending.result = result == null ? "" : result;
                pending.done = true;
                return;
            }
        }
        for (int i = key.length() == 0 ? roundOrigin : 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step.done) {
                continue;
            }
            if (key.length() > 0 && key.equals(step.id)) {
                step.result = result == null ? "" : result;
                step.done = true;
                return;
            }
            if (key.length() == 0 && (tool.length() == 0 || tool.equals(step.name))) {
                step.result = result == null ? "" : result;
                step.done = true;
                return;
            }
        }
        Step step = new Step();
        step.id = key;
        step.name = tool.length() == 0 ? "tool" : tool;
        step.result = result == null ? "" : result;
        step.done = true;
        steps.add(step);
        placeStep(step);
    }

    /**
     * 没有逐段记录时，按先思考、后工具排一次。
     * 已经有发生顺序的不再重排，避免把后出现的思考又堆回最上面。
     */
    public void ensureOrder() {
        if (!order.isEmpty()) {
            return;
        }
        if (reasoning.length() > 0) {
            Piece piece = new Piece();
            piece.think = new StringBuilder(reasoning.toString());
            order.add(piece);
        }
        for (int i = 0; i < steps.size(); i++) {
            placeStep(steps.get(i));
        }
    }
}
