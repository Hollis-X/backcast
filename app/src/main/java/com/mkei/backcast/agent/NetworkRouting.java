package com.mkei.backcast.agent;

import java.io.Closeable;
import java.io.IOException;
import okhttp3.OkHttpClient;
import org.json.JSONObject;

/** Optional device routing, installed by the Android application without changing SDK requests. */
public final class NetworkRouting {
    public interface Provider {
        Route open(String endpoint, LlmClient.RequestValidity valid) throws IOException;
    }

    public interface Route extends Closeable {
        void configure(OkHttpClient.Builder builder);
        JSONObject diagnostic();
        /** Called only after the single real request fails; never resends the model request. */
        String failureMessage(LlmClient.RequestValidity valid);
    }

    public static final class Failure extends IOException {
        public final JSONObject diagnostic;
        public Failure(String message, JSONObject diagnostic) {
            super(message);
            this.diagnostic = diagnostic == null ? new JSONObject() : diagnostic;
        }
    }

    private static volatile Provider provider;
    private NetworkRouting() { }
    public static void install(Provider replacement) { provider = replacement; }
    public static Route open(String endpoint, LlmClient.RequestValidity valid) throws IOException {
        Provider current = provider;
        return current == null ? null : current.open(endpoint, valid);
    }
    public static boolean current(LlmClient.RequestValidity valid) {
        return !Thread.currentThread().isInterrupted() && (valid == null || valid.isCurrent());
    }
}
