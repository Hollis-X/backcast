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
            for (int i = Math.max(0, start); i < Math.min(end, trace.order.size()); i++) {
                Piece piece = trace.order.get(i);
                if (piece.think != null ? !piece.sealed : piece.step != null && !piece.step.done) pending = true;
            }
            return pending ? text + " · 进行中" : text;
        }

        public boolean hasDetail() {
            return Math.min(end, trace.order.size()) > Math.max(0, start);
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
            if (order.get(i).think != null) thinks++;
            if (order.get(i).step != null) tools++;
        }
        if (thinks == 0 && tools == 0) return "";
        String thought = thinks > 0 ? "思考 " + thinks + " 段" : "";
        String calls = tools > 0 ? "工具 " + tools + " 次" : "";
        return thought.length() == 0 ? calls : calls.length() == 0 ? thought : thought + " · " + calls;
    }

    public boolean hasDetail() {
        return hasThink() || !steps.isEmpty();
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
        while (steps.size() > roundOrigin) {
            steps.remove(steps.size() - 1);
        }
        while (order.size() > roundOrder) {
            Piece removed = order.remove(order.size() - 1);
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
        if (step.done) {
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
        steps.add(step);
        placeStep(step);
    }

    public void startStep(String name, String args) {
        for (int i = roundOrigin; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (!step.done && !step.started && step.name.equals(name)) {
                step.started = true;
                step.args = args == null ? "" : args;
                return;
            }
        }
        addStep("", name, args);
        steps.get(steps.size() - 1).started = true;
    }

    public void fillResult(String id, String name, String result) {
        String key = id == null ? "" : id;
        String tool = name == null ? "" : name;
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