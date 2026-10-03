package com.mkei.backcast.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.Locale;

/** Bounded local error evidence, independent of the transcript and model context. */
public final class Diagnostics {
    public static final int MAX_CHARS = 8192;
    private static final String REDACTED = "[redacted]";
    private Diagnostics() { }

    public static String scrub(String value, String... secrets) {
        String result = value == null ? "" : value;
        if (secrets != null) for (String secret : secrets) {
            if (secret == null || secret.length() == 0) continue;
            result = result.replace(secret, REDACTED);
            String quoted = JSONObject.quote(secret);
            if (quoted.length() > 2) result = result.replace(quoted.substring(1, quoted.length() - 1), REDACTED);
        }
        result = result.replaceAll("(?i)\\bBearer\\s+[^\\s\\\"'<>;,]+", "Bearer " + REDACTED);
        result = result.replaceAll("(?i)(api[_-]?key|access[_-]?token|authorization|password)[\\\"']?\\s*[=:]\\s*[\\\"']?[^\\s\\\"'<>;,}]+", "$1=" + REDACTED);
        result = result.replaceAll("\\b(?:sk|xai)-[A-Za-z0-9_-]{8,}", REDACTED);
        result = result.replaceAll("(https?://)[^/\\s\\\"'<>]+@", "$1");
        result = result.replaceAll("(https?://[^\\s?\\\"'<>]+)\\?[^\\s\\\"'<>]*", "$1");
        return result;
    }

    public static String boundedJson(JSONObject value, String... secrets) {
        try {
            Object safe = clean(value, secrets, new int[]{6000}, 0);
            String text = safe.toString();
            if (text.length() <= MAX_CHARS) return text;
            return new JSONObject().put("truncated", true).put("summary", limit(scrub(text, secrets), 1800)).toString();
        } catch (Exception ignored) { return "{\"diagnostic_unavailable\":true}"; }
    }

    public static String detail(String value) {
        if (value == null || value.length() == 0) return "";
        try { return boundedJson(new JSONObject(value)); }
        catch (Exception ignored) {
            try { return boundedJson(new JSONObject().put("detail", value)); }
            catch (Exception impossible) { return ""; }
        }
    }

    public static JSONObject failure(Throwable error) {
        JSONObject value = new JSONObject();
        JSONArray causes = new JSONArray();
        try {
            Throwable cursor = error;
            for (int c = 0; cursor != null && c < 8; c++) {
                JSONObject cause = new JSONObject().put("class", cursor.getClass().getName());
                JSONArray frames = new JSONArray();
                StackTraceElement[] stack = cursor.getStackTrace();
                for (int i = 0; i < Math.min(15, stack.length); i++) frames.put(stack[i].toString());
                cause.put("stack", frames); causes.put(cause);
                if (cursor.getCause() == cursor) break;
                cursor = cursor.getCause();
            }
            value.put("exceptions", causes);
        } catch (Exception ignored) { }
        return value;
    }

    private static Object clean(Object value, String[] secrets, int[] budget, int depth) throws Exception {
        if (depth > 8 || budget[0] <= 0) return "[truncated]";
        if (value instanceof JSONObject) {
            JSONObject out = new JSONObject();
            Iterator<String> keys = ((JSONObject) value).keys();
            int count = 0;
            while (keys.hasNext() && count++ < 40 && budget[0] > 0) {
                String key = keys.next();
                budget[0] -= key.length() + 8;
                out.put(limit(scrub(key, secrets), 80), sensitive(key) ? REDACTED
                        : clean(((JSONObject) value).opt(key), secrets, budget, depth + 1));
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray out = new JSONArray();
            JSONArray source = (JSONArray) value;
            for (int i = 0; i < Math.min(24, source.length()) && budget[0] > 0; i++)
                out.put(clean(source.opt(i), secrets, budget, depth + 1));
            return out;
        }
        if (value == null || value == JSONObject.NULL || value instanceof Number || value instanceof Boolean) return value;
        String raw = value.toString();
        // A provider may encode its JSON error as a string inside the diagnostic object.
        if (raw.trim().startsWith("{")) {
            try { return clean(new JSONObject(raw), secrets, budget, depth + 1); }
            catch (Exception notJson) { }
        }
        if (raw.trim().startsWith("[")) {
            try { return clean(new JSONArray(raw), secrets, budget, depth + 1); }
            catch (Exception notJson) { }
        }
        String text = scrub(raw, secrets);
        text = limit(text, Math.max(0, Math.min(2048, budget[0])));
        budget[0] -= text.length() + 8;
        return text;
    }

    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.US).replace("-", "_");
        return normalized.contains("authorization") || normalized.contains("api_key")
                || normalized.contains("password") || normalized.contains("secret")
                || normalized.equals("token") || normalized.equals("access_token")
                || normalized.equals("messages") || normalized.equals("request_body")
                || normalized.equals("prompt") || normalized.equals("input")
                || normalized.equals("content") || normalized.equals("reasoning_content")
                || normalized.equals("arguments") || normalized.equals("tool_calls");
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
