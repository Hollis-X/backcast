package com.mkei.backcast.agent;

/** Validated response preferences shared by chat and activity summaries. */
public final class ResponsePreferences {
    private ResponsePreferences() { }

    public static String normalizeVerbosity(String value) {
        return normalized(value, new String[]{"default", "low", "medium", "high"}, "default");
    }

    public static String normalizeSummary(String value) {
        return normalized(value, new String[]{"auto", "concise", "detailed", "none"}, "auto");
    }

    public static String normalizeLanguage(String value) {
        return normalized(value, new String[]{"zh-CN", "zh-TW", "en", "ja", "ko", "es", "fr", "de"}, "zh-CN");
    }

    private static String normalized(String value, String[] options, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        for (String option : options) {
            if (option.equalsIgnoreCase(trimmed)) return option;
        }
        return fallback;
    }

    public static String languageInstruction(String value) {
        String language = normalizeLanguage(value);
        String name = "Simplified Chinese";
        if ("zh-TW".equals(language)) name = "Traditional Chinese";
        else if ("en".equals(language)) name = "English";
        else if ("ja".equals(language)) name = "Japanese";
        else if ("ko".equals(language)) name = "Korean";
        else if ("es".equals(language)) name = "Spanish";
        else if ("fr".equals(language)) name = "French";
        else if ("de".equals(language)) name = "German";
        return "Mandatory application output language: " + name + " (" + language + ").\n"
                + "This setting is a hard constraint for every user-visible assistant response, including "
                + "progress updates, headings, explanations, high-level activity summaries and final answers. "
                + "Write all such prose in this language, regardless of the language of the user's request, "
                + "earlier replies, tools, documents or handoff summaries. This setting overrides any conflicting "
                + "language preference elsewhere in the conversation or editable system prompt. "
                + "Do not switch languages or add a duplicate translation. Before sending a response, check "
                + "its prose and rewrite any passage in another language into the configured language. "
                + "Preserve required protocol markers, JSON property names, tool names, paths, commands, "
                + "code identifiers and verbatim source quotations; explain them in the configured language. "
                + "Use that language for natural-language JSON values as well.";
    }

    public static String instructions(String verbosity, String language) {
        String detail = normalizeVerbosity(verbosity);
        String rule = "";
        if ("low".equals(detail)) {
            rule = "Keep answers concise: state the result and only the essential evidence or next action. ";
        } else if ("medium".equals(detail)) {
            rule = "Use a moderate level of detail: explain the result with relevant reasons and evidence. ";
        } else if ("high".equals(detail)) {
            rule = "Give detailed answers with relevant evidence, reasoning summaries, verification and practical limits. ";
        }
        if (rule.length() > 0) {
            rule = "\nApplication output verbosity: " + detail + ". " + rule
                    + "This explicit setting overrides conflicting response-length preferences in the editable "
                    + "system prompt or previous messages. This changes presentation only. Do not expand the task, repeat finished work, omit "
                    + "material failures, or expose detailed private chain-of-thought.";
        }
        return languageInstruction(language) + rule;
    }
}
