package com.mkei.backcast.ui;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Parses away from the UI, retains only the newest text, and applies one result at a time. */
public final class MarkdownRenderQueue {
    public interface Renderer {
        CharSequence render(String source, int codeBackground);
    }
    public interface Callback {
        void apply(CharSequence result);
    }

    private static final class Request {
        final Object key;
        final String source;
        final int background;
        final boolean priority;
        final Callback callback;
        Request(Object key, String source, int background, boolean priority, Callback callback) {
            this.key = key; this.source = source; this.background = background;
            this.priority = priority; this.callback = callback;
        }
    }

    private final Executor worker, ui;
    private final Renderer renderer;
    private final Map<Request, Request> pending = new LinkedHashMap<Request, Request>();
    private final Map<Object, Request> latest = new IdentityHashMap<Object, Request>();
    private boolean processing, closed;

    public MarkdownRenderQueue(Executor worker, Executor ui, Renderer renderer) {
        this.worker = worker; this.ui = ui; this.renderer = renderer;
    }

    public synchronized void submit(Object key, String source, int background,
            boolean priority, Callback callback) {
        if (closed) return;
        Request request = new Request(key, source == null ? "" : source, background, priority, callback);
        Request previous = latest.put(key, request);
        pending.remove(previous);
        pending.put(request, request);
        schedule();
    }

    /** A session change invalidates pending work and results already posted to the UI. */
    public synchronized void cancelAll() {
        pending.clear(); latest.clear();
    }

    public synchronized void close() {
        closed = true;
        cancelAll();
    }

    private synchronized void schedule() {
        if (closed || processing || pending.isEmpty()) return;
        processing = true;
        try {
            worker.execute(new Runnable() {
                @Override public void run() { renderNext(); }
            });
        } catch (RuntimeException rejected) {
            processing = false;
            pending.clear(); latest.clear();
        }
    }

    private void renderNext() {
        final Request request;
        synchronized (this) {
            if (closed || pending.isEmpty()) {
                processing = false;
                return;
            }
            Request selected = null;
            for (Request candidate : pending.values()) {
                if (selected == null) selected = candidate;
                if (candidate.priority) { selected = candidate; break; }
            }
            request = selected;
            pending.remove(request);
        }
        CharSequence parsed;
        try {
            parsed = renderer.render(request.source, request.background);
        } catch (RuntimeException invalidMarkup) {
            parsed = request.source;
        }
        final CharSequence result = parsed;
        try {
            ui.execute(new Runnable() {
                @Override public void run() {
                    try {
                        synchronized (MarkdownRenderQueue.this) {
                            if (closed || latest.get(request.key) != request) return;
                            latest.remove(request.key);
                        }
                        request.callback.apply(result);
                    } finally {
                        synchronized (MarkdownRenderQueue.this) {
                            processing = false;
                            schedule();
                        }
                    }
                }
            });
        } catch (RuntimeException rejected) {
            synchronized (this) {
                if (latest.get(request.key) == request) latest.remove(request.key);
                processing = false;
                schedule();
            }
        }
    }
}
