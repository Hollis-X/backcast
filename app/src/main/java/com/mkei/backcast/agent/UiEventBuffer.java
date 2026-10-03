package com.mkei.backcast.agent;

import java.util.ArrayList;
import java.util.List;

/** Unpersisted UI events only; stored transcript messages remain authoritative. */
final class UiEventBuffer {
    static final int REQUEST = 0, TEXT = 1, REASONING = 2, PREVIEW = 3, START = 4,
            END = 5, ERROR = 6, CONTEXT = 7, COMPACT_START = 8, COMPACTED = 9,
            FINISH = 10, RETRY = 11, STEER = 12, PROGRESS = 13;

    static final class Event {
        final int kind, generation, token;
        int first, second;
        long sequence;
        String name, arguments;
        final StringBuilder text;
        boolean flag;

        Event(int kind, int generation, int token, long sequence, String value) {
            this.kind = kind; this.generation = generation; this.token = token;
            this.sequence = sequence; text = new StringBuilder(value == null ? "" : value);
        }

        Event copy() {
            Event copy = new Event(kind, generation, token, sequence, text.toString());
            copy.first = first; copy.second = second; copy.name = name;
            copy.arguments = arguments; copy.flag = flag;
            return copy;
        }

        void dispatch(AgentLoop.Listener listener) {
            switch (kind) {
                case REQUEST: listener.onRequestStart(generation); break;
                case TEXT: listener.onAssistantText(generation, text.toString()); break;
                case REASONING: listener.onReasoning(generation, text.toString()); break;
                case PREVIEW: listener.onToolPreview(generation, first, text.toString(), name, arguments); break;
                case START: listener.onToolStart(generation, name, arguments); break;
                case END: listener.onToolEnd(generation, name, text.toString()); break;
                case ERROR: listener.onError(generation, text.toString()); break;
                case CONTEXT: listener.onContextUsage(generation, first, second); break;
                case COMPACT_START: listener.onCompactStart(generation); break;
                case COMPACTED: listener.onCompacted(generation, flag); break;
                case FINISH: listener.onFinish(generation); break;
                case RETRY: listener.onRetry(generation); break;
                case STEER: listener.onSteer(generation); break;
                case PROGRESS:
                    if (listener instanceof AgentLoop.ProgressListener) ((AgentLoop.ProgressListener) listener)
                            .onProgress(generation, name, arguments, text.toString(), first);
                    break;
                default: throw new IllegalStateException("Unknown UI event");
            }
        }
    }

    private final ArrayList<Event> events = new ArrayList<Event>();

    void clear() { events.clear(); }

    /** Committed output has its transcript row; the last safe error remains turn metadata. */
    void clearOutputPreservingRetry() {
        Event retry = null;
        for (Event event : events) if (isRetryProgress(event)) retry = event;
        clear();
        if (retry != null) events.add(retry.copy());
    }

    private static boolean isRetryProgress(Event event) {
        return event.kind == PROGRESS && "retry".equals(event.name);
    }

    void add(Event event) {
        if (event.kind == END) return;
        if (event.kind == RETRY) {
            clear();
            events.add(new Event(REQUEST, event.generation, event.token, event.sequence, ""));
            return;
        }
        if (!events.isEmpty()) {
            Event last = events.get(events.size() - 1);
            if ((event.kind == TEXT || event.kind == REASONING) && last.kind == event.kind
                    && last.token == event.token && last.generation == event.generation) {
                last.text.append(event.text); last.sequence = event.sequence;
                return;
            }
        }
        if (event.kind == PREVIEW || event.kind == CONTEXT || event.kind == REQUEST || event.kind == FINISH || event.kind == PROGRESS) {
            for (int i = events.size() - 1; i >= 0; i--) {
                Event previous = events.get(i);
                if (previous.kind == event.kind && previous.token == event.token
                        && previous.generation == event.generation
                        && (event.kind != PROGRESS || isRetryProgress(previous) == isRetryProgress(event))
                        && (event.kind != PREVIEW || previous.first == event.first)) {
                    if (event.kind == PREVIEW) events.set(i, event.copy());
                    else { events.remove(i); events.add(event.copy()); }
                    return;
                }
            }
        }
        events.add(event.copy());
    }

    List<Event> snapshot(int generation, int token) {
        ArrayList<Event> snapshot = new ArrayList<Event>();
        for (Event event : events) {
            if (event.generation == generation && event.token == token) snapshot.add(event.copy());
        }
        return snapshot;
    }
}
