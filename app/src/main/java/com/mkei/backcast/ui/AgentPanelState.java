package com.mkei.backcast.ui;

import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.PromptGuard;
import com.mkei.backcast.agent.SubAgentManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reading and refresh state shared by the task list and its detail view. */
public final class AgentPanelState {
    public static final int TASK = 0, ACTIVITY = 1;
    public static final int HISTORY_PAGE_SIZE = 40, RESULT_PAGE_SIZE = 8000;
    public String selectedId = "";
    public int tab = TASK, historyEnd = -1, resultOffset;
    private boolean visible, loading;
    private long generation;

    public void start() { visible = true; generation++; loading = false; }
    public void stop() { visible = false; generation++; loading = false; }
    public long beginLoad() {
        if (!visible || loading) return -1;
        loading = true;
        return generation;
    }
    public boolean finishLoad(long ticket) {
        if (!visible || ticket != generation) return false;
        loading = false;
        return true;
    }
    public boolean mayPoll(boolean active) { return visible && !loading && active; }
    public void select(String id) {
        selectedId = id == null ? "" : id;
        tab = TASK; historyEnd = -1; resultOffset = 0;
    }
    public int[] historyBounds(int total) {
        int end = historyEnd < 0 ? total : Math.min(total, Math.max(0, historyEnd));
        return new int[] { Math.max(0, end - HISTORY_PAGE_SIZE), end };
    }
    public void olderHistory(int total) { historyEnd = historyBounds(total)[0]; }
    public void newerHistory(int total) {
        int end = Math.min(total, historyBounds(total)[1] + HISTORY_PAGE_SIZE);
        historyEnd = end >= total ? -1 : end;
    }
    public int[] resultBounds(int length) {
        int start = Math.min(Math.max(0, resultOffset), Math.max(0, length - 1));
        return new int[] { start, Math.min(length, start + RESULT_PAGE_SIZE) };
    }
    public int[] resultBounds(String content) {
        int[] bounds = resultBounds(content.length());
        if (bounds[0] > 0 && bounds[0] < content.length() && Character.isLowSurrogate(content.charAt(bounds[0]))
                && Character.isHighSurrogate(content.charAt(bounds[0] - 1))) bounds[0]++;
        if (bounds[1] > 0 && bounds[1] < content.length() && Character.isHighSurrogate(content.charAt(bounds[1] - 1))
                && Character.isLowSurrogate(content.charAt(bounds[1]))) bounds[1]++;
        return bounds;
    }
    public static boolean active(String status) {
        return SubAgentManager.RUNNING.equals(status) || SubAgentManager.QUEUED.equals(status)
                || SubAgentManager.WAITING.equals(status);
    }
    public static List<SubAgentManager.Record> ordered(List<SubAgentManager.Record> source) {
        List<SubAgentManager.Record> rows = new ArrayList<SubAgentManager.Record>();
        for (SubAgentManager.Record row : source) if (!SubAgentManager.ROOT.equals(row.id)) rows.add(row);
        Collections.sort(rows, new Comparator<SubAgentManager.Record>() {
            @Override public int compare(SubAgentManager.Record a, SubAgentManager.Record b) {
                if (active(a.status) != active(b.status)) return active(a.status) ? -1 : 1;
                if (a.lastActivityAt != b.lastActivityAt) return a.lastActivityAt > b.lastActivityAt ? -1 : 1;
                return a.id.compareTo(b.id);
            }
        });
        return rows;
    }
    public static final class Entry {
        public final String role, text, fullText;
        public final List<String> tools, fullTools;
        public Entry(String role, String text, List<String> tools, String fullText, List<String> fullTools) {
            this.role = role; this.text = text; this.tools = tools; this.fullText = fullText; this.fullTools = fullTools;
        }
    }
    public static List<Entry> history(SubAgentManager.Record record, int from, int to,
            String systemPrompt, String environment) throws Exception {
        List<Entry> entries = new ArrayList<Entry>();
        int start = Math.max(0, from), end = Math.min(record.history.length(), to), remaining = 32000;
        for (int i = start; i < end; i++) {
            Message message = Message.fromCheckpointJson(record.history.getJSONObject(i));
            if (Message.SYSTEM.equals(message.role)) continue;
            int limit = Math.min(8000, Math.max(100, remaining / Math.max(1, end - i)));
            String content = message.delegatedRequest == null ? message.content : message.delegatedRequest;
            if (message.coordinationIds != null) content = coordinationText(content);
            String fullText = PromptGuard.redact(content, systemPrompt, environment, "");
            content = shortText(fullText, limit);
            int toolsRemaining = Math.min(2000, Math.max(0, remaining - content.length()));
            List<String> tools = new ArrayList<String>();
            List<String> fullTools = new ArrayList<String>();
            if (message.toolCalls != null) for (int j = 0; j < message.toolCalls.length(); j++) {
                JSONObject call = message.toolCalls.optJSONObject(j);
                JSONObject fn = call == null ? null : call.optJSONObject("function");
                if (fn == null) continue;
                String tool = fn.optString("name");
                String args = fn.optString("arguments", "");
                try {
                    JSONObject values = new JSONObject(args);
                    StringBuilder text = new StringBuilder(tool);
                    Iterator<String> keys = values.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        text.append('\n').append(key).append(": ").append(String.valueOf(values.opt(key)));
                    }
                    tool = text.toString();
                } catch (Exception invalid) { tool += "\n" + args; }
                String complete = PromptGuard.redact(tool, systemPrompt, environment, "");
                fullTools.add(complete);
                if (toolsRemaining > 0) {
                    String formatted = shortText(complete, toolsRemaining);
                    tools.add(formatted); toolsRemaining -= formatted.length();
                }
            }
            remaining -= content.length();
            for (String tool : tools) remaining -= tool.length();
            entries.add(new Entry(message.coordinationIds == null ? message.role : "message", content, tools, fullText, fullTools));
        }
        return entries;
    }
    private static String coordinationText(String content) {
        try {
            int newline = content.indexOf('\n');
            // The transport prefix has no line breaks; the complete mail batch follows it.
            while (newline >= 0) {
                String candidate = content.substring(newline + 1);
                if (candidate.startsWith("[")) {
                    JSONArray messages = new JSONArray(candidate);
                    StringBuilder text = new StringBuilder();
                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject message = messages.getJSONObject(i);
                        if (text.length() > 0) text.append("\n\n");
                        text.append(message.optString("from")).append(":\n").append(message.optString("text"));
                    }
                    return text.toString();
                }
                newline = content.indexOf('\n', newline + 1);
            }
        } catch (Exception invalid) { }
        return content;
    }
    public static String shortText(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, Math.max(0, limit)) + "\u2026";
    }

    /** Result pages include reports and immutable outcomes, including older assignments. */
    public static String resultsText(SubAgentManager.Record record) {
        if (record.tasks.length() == 0 && record.results.length() == 0 && record.events.length() == 0
                && record.result.length() > 0) return record.result;
        StringBuilder out = new StringBuilder();
        JSONObject current = null;
        for (int i = record.tasks.length() - 1; i >= 0; i--) {
            JSONObject task = record.tasks.optJSONObject(i);
            if (task != null && record.currentTaskId.equals(task.optString("taskId"))) { current = task; break; }
        }
        String finalText = "", savedPartial = "";
        for (int i = record.results.length() - 1; i >= 0; i--) {
            JSONObject result = record.results.optJSONObject(i);
            if (result != null && record.currentTaskId.equals(result.optString("taskId"))) {
                finalText = result.optString("content");
                savedPartial = result.optString("partial");
                String status = result.optString("status");
                appendSection(out, "cancelled".equals(status)
                        || SubAgentManager.STOPPED.equals(status) || SubAgentManager.FAILED.equals(status)
                        ? "部分成果" : "最终结果", finalText);
                break;
            }
        }
        if (savedPartial.length() > 0 && !savedPartial.equals(finalText)) appendSection(out, "已输出的内容", savedPartial);
        if (current != null) {
            String partial = current.optString("partial");
            if (partial.length() > 0 && !partial.equals(finalText) && !partial.equals(savedPartial))
                appendSection(out, "已输出的内容", partial);
        }
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < record.events.length(); i++) {
            JSONObject event = record.events.optJSONObject(i);
            if (event == null || !record.currentTaskId.equals(event.optString("taskId"))
                    || !record.id.equals(event.optString("from")) || !"message".equals(event.optString("kind"))) continue;
            String report = event.optString("text");
            if (report.length() > 0 && !report.equals(finalText) && seen.add(report)) appendSection(out, "阶段汇报", report);
        }
        if (out.length() == 0 && record.results.length() == 0) appendSection(out, "最终结果", record.result);
        if (out.length() == 0 && record.progress.length() > 0) appendSection(out, "最近进展", record.progress);
        boolean older = false;
        for (int i = record.results.length() - 1; i >= 0; i--) {
            JSONObject result = record.results.optJSONObject(i);
            if (result == null || record.currentTaskId.equals(result.optString("taskId"))) continue;
            String content = result.optString("content");
            String partial = result.optString("partial");
            if (partial.length() > 0 && !partial.equals(content)) {
                content = content.length() == 0 ? partial : content + "\n\n部分成果\n" + partial;
            }
            if (content.length() == 0) continue;
            if (!older) { if (out.length() > 0) out.append("\n\n"); out.append("历次任务成果"); older = true; }
            String request = result.optString("taskId");
            for (int j = 0; j < record.tasks.length(); j++) {
                JSONObject task = record.tasks.optJSONObject(j);
                if (task != null && request.equals(task.optString("taskId"))) {
                    request = shortText(task.optString("request"), 220); break;
                }
            }
            appendSection(out, request, content);
        }
        return out.toString();
    }

    private static void appendSection(StringBuilder out, String label, String content) {
        if (content == null || content.length() == 0) return;
        if (out.length() > 0) out.append("\n\n");
        out.append(label).append('\n').append(content);
    }
}
