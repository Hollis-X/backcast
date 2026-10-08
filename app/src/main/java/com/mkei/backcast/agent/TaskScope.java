package com.mkei.backcast.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;

/** Only human requests can contribute paths; summaries carry a separate local snapshot. */
final class TaskScope {
    private static final Pattern PATH = Pattern.compile(
            "[`\"'](/[^`\"'\\r\\n]+)[`\"']|(?<![\\p{L}\\p{N}_:/])(/[^\\s`\"'<>|，。；：、()\\[\\]{}]+)");

    private TaskScope() { }

    static List<String> paths(List<Message> history, String workspace) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (!Message.USER.equals(message.role) || message.delegatedRequest != null
                    || message.coordinationIds != null || Goal.isSteer(message.content)
                    || Goal.isNote(message.content) || Compactor.PROMPT.equals(message.content)) continue;
            if (message.workDir != null && !message.workDir.isEmpty() && workspace != null
                    && !workspace.isEmpty() && !workspace.equals(message.workDir)) break;
            if (message.taskPaths != null) {
                List<String> saved = new ArrayList<String>();
                for (int n = 0; n < message.taskPaths.length(); n++) saved.add(message.taskPaths.optString(n));
                return saved;
            }
            if (Compactor.isSummary(message)) continue;
            String text = message.content.replaceAll("(?m)^ {0,3}>.*$", "")
                    .replaceAll("(?ms)^ {0,3}(`{3,}|~{3,})[^\\r\\n]*\\R.*?^ {0,3}\\1[^\\r\\n]*(?:\\R|$)", "");
            Matcher match = PATH.matcher(text);
            List<String> found = new ArrayList<String>();
            int previousEnd = 0;
            while (match.find() && found.size() < 32) {
                String prefix = text.substring(previousEnd, match.start());
                previousEnd = match.end();
                int boundary = Math.max(prefix.lastIndexOf('\n'), Math.max(prefix.lastIndexOf('。'),
                        Math.max(prefix.lastIndexOf('；'), prefix.lastIndexOf(';'))));
                prefix = prefix.substring(boundary + 1);
                if (Pattern.compile("不要|禁止|不得|不许|不能|别(?:读|搜|访问|碰)|不(?:读|访问|搜索)|(?i)\\b(?:except|excluding|exclude|avoid|never|do\\s+not|don't|must\\s+not)\\b")
                        .matcher(prefix).find()) continue;
                String path = match.group(1) == null ? match.group(2) : match.group(1);
                if (path.endsWith(".")) path = path.substring(0, path.length() - 1);
                if (path.length() > 1 && !path.startsWith("//") && !found.contains(path)) found.add(path);
            }
            if (!found.isEmpty()) return found;
        }
        return new ArrayList<String>();
    }

    static JSONArray snapshot(List<String> paths) {
        JSONArray array = new JSONArray();
        for (String path : paths) array.put(path);
        return array;
    }
}
