package com.mkei.backcast.tool;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fetches pinned release files. Only an explicit package installation calls this class. */
public final class ToolchainDownloader {
    static final int PREFIX_BYTES = 65536, CHUNK_BYTES = 4 * 1024 * 1024;
    static final long PROBE_MS = 8000L, PROBE_TOTAL_MS = 15000L, TRANSFER_MS = 30L * 60 * 1000;
    private static final String RELEASE = "https://github.com/Hollis-X/backcast/releases/download/toolchain-";
    private static final String[] MIRRORS = {"https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/"};

    interface Transport { Exchange open(String url, long first, long last, long timeoutMs, boolean probe) throws Exception; }
    interface Exchange extends Closeable { Reply execute() throws Exception; void cancel(); }
    static final class Reply implements Closeable {
        final int code;
        final long length;
        final String range, encoding, type;
        final InputStream body;
        final Closeable owner;
        Reply(int code, long length, String range, String encoding, String type, InputStream body, Closeable owner) {
            this.code = code; this.length = length; this.range = range == null ? "" : range;
            this.encoding = encoding == null ? "" : encoding; this.type = type == null ? "" : type;
            this.body = body; this.owner = owner;
        }
        public void close() throws IOException { try { if (body != null) body.close(); } finally { if (owner != null) owner.close(); } }
    }
    interface Listener { void update(String stage, long verifiedBytes, long totalBytes); }

    public static final class Failure extends IOException {
        private final String detail;
        Failure(JSONArray attempts) {
            super("工具包下载失败，请检查网络后重新安装。");
            JSONObject data = new JSONObject();
            try { data.put("attempts", attempts); } catch (Exception ignored) { }
            detail = data.toString();
        }
        public String diagnostic() { return detail; }
    }

    static final class Artifact {
        final String url, digest, prefixDigest;
        final long bytes;
        final int prefixBytes;
        final String[] chunks;
        Artifact(JSONObject data, String version) throws Exception {
            url = data.getString("url"); digest = data.getString("sha256"); bytes = data.getLong("bytes");
            prefixBytes = data.getInt("prefix_bytes"); prefixDigest = data.getString("prefix_sha256");
            String expected = RELEASE + version + "/" + data.getString("file");
            if (!version.matches("[A-Za-z0-9._-]+") || !data.getString("file").matches("[A-Za-z0-9._-]+\\.tar\\.gz")
                    || !expected.equals(url) || !digest.matches("[0-9a-f]{64}") || !prefixDigest.matches("[0-9a-f]{64}")
                    || bytes <= 0 || bytes > 512L * 1024 * 1024 || prefixBytes != Math.min((long) PREFIX_BYTES, bytes)
                    || data.getInt("chunk_bytes") != CHUNK_BYTES) throw new IOException("工具包下载清单不完整。");
            JSONArray hashes = data.getJSONArray("chunk_sha256");
            if (hashes.length() != (bytes + CHUNK_BYTES - 1) / CHUNK_BYTES) throw new IOException("工具包块校验清单不完整。");
            chunks = new String[hashes.length()];
            for (int i = 0; i < chunks.length; i++) {
                chunks[i] = hashes.getString(i);
                if (!chunks[i].matches("[0-9a-f]{64}")) throw new IOException("工具包块校验清单无效。");
            }
        }
        List<String> sources() {
            List<String> result = new ArrayList<String>(); result.add(url);
            for (String mirror : MIRRORS) result.add(mirror + url);
            return result;
        }
    }

    private final Transport transport;
    private final Set<Session> sessions = new HashSet<Session>();
    ToolchainDownloader() { this(new HttpTransport()); }
    ToolchainDownloader(Transport transport) { this.transport = transport; }

    /** Cancels only the request owner's sockets; stores may serve several tool sessions. */
    void cancel(ToolchainInstaller.Cancellation owner) {
        if (owner == null) throw new IllegalArgumentException("工具包下载取消必须指定所属操作。");
        List<Session> current;
        synchronized (sessions) { current = new ArrayList<Session>(sessions); }
        for (Session session : current) if (session.cancellation == owner) session.close();
    }

    File fetch(JSONObject data, String version, File directory, ToolchainInstaller.Cancellation cancellation, Listener listener) throws Exception {
        Artifact artifact = new Artifact(data, version);
        cancellation.check();
        directory = safe(directory, directory);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建工具包下载缓存。");
        File part = safe(new File(directory, artifact.digest + ".part"), directory);
        File complete = safe(new File(directory, artifact.digest + ".archive"), directory);
        if (complete.isFile()) {
            if (complete.length() == artifact.bytes && artifact.digest.equals(hash(complete, cancellation))) {
                listener.update("verifying", artifact.bytes, artifact.bytes); return complete;
            }
            if (!complete.delete()) throw new IOException("无法清理损坏的工具包下载缓存。");
        }
        final Session session = new Session(cancellation);
        synchronized (sessions) { sessions.add(session); }
        JSONArray failures = new JSONArray();
        try {
            session.check();
            long verified = recover(part, artifact, session);
            listener.update(verified > 0 ? "resuming" : "probing", verified, artifact.bytes);
            // A completed transfer interrupted before publication does not require any HTTP request.
            if (verified == artifact.bytes && artifact.digest.equals(hash(part, session))) return publish(part, complete);
            if (verified == artifact.bytes) { truncate(part, 0); verified = 0; }
            List<String> available = probeSources(artifact, session, failures);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TRANSFER_MS);
            for (int i = 0; i < available.size(); i++) {
                session.check();
                if (System.nanoTime() >= deadline) { failed(failures, available.get(i), "download", "deadline"); break; }
                String source = available.get(i);
                listener.update(i == 0 ? verified > 0 ? "resuming" : "downloading" : "switching", verified, artifact.bytes);
                try {
                    transfer(source, artifact, part, verified, session, listener, deadline);
                    session.check(); listener.update("verifying", artifact.bytes, artifact.bytes);
                    if (!artifact.digest.equals(hash(part, session))) {
                        truncate(part, 0); throw new IOException("whole_sha256_mismatch");
                    }
                    return publish(part, complete);
                } catch (Exception failure) {
                    session.check();
                    if (!(failure instanceof IOException)) throw failure;
                    failed(failures, source, "download", failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage()));
                    verified = recover(part, artifact, session);
                }
            }
            throw new Failure(failures);
        } finally {
            session.close(); synchronized (sessions) { sessions.remove(session); }
        }
    }

    private List<String> probeSources(final Artifact artifact, final Session session, JSONArray failures) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROBE_TOTAL_MS);
        ExecutorService pool = Executors.newFixedThreadPool(3, new ThreadFactory() {
            public Thread newThread(Runnable runnable) { Thread worker = new Thread(runnable, "backcast-tool-download-probe"); worker.setDaemon(true); return worker; }
        });
        CompletionService<Probe> completions = new ExecutorCompletionService<Probe>(pool);
        List<Future<Probe>> workers = new ArrayList<Future<Probe>>();
        List<String> result = new ArrayList<String>();
        try {
            for (final String source : artifact.sources()) workers.add(completions.submit(new Callable<Probe>() {
                public Probe call() {
                    try {
                        session.check(); long remaining = remaining(deadline);
                        Exchange exchange = session.open(source, 0, artifact.prefixBytes - 1, Math.min(PROBE_MS, remaining), true);
                        try {
                            Reply reply = exchange.execute();
                            try {
                                validate(reply, artifact, 0, artifact.prefixBytes - 1);
                                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                                byte[] buffer = new byte[8192]; int left = artifact.prefixBytes;
                                while (left > 0) {
                                    session.check(); remaining(deadline);
                                    int read = reply.body.read(buffer, 0, Math.min(buffer.length, left));
                                    if (read < 0) throw new IOException("probe_truncated");
                                    if (read == 0) continue;
                                    sha.update(buffer, 0, read); left -= read;
                                }
                                if (!artifact.prefixDigest.equals(ToolchainInstaller.hex(sha.digest()))) throw new IOException("probe_sha256_mismatch");
                                session.check(); return new Probe(source, null);
                            } finally { reply.close(); }
                        } finally { session.release(exchange); }
                    } catch (Exception failure) { return new Probe(source, failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage())); }
                }
            }));
            for (int received = 0; received < workers.size();) {
                session.check();
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) break;
                Future<Probe> completed = completions.poll(Math.min(50L, left), TimeUnit.MILLISECONDS);
                if (completed == null) continue;
                Probe probe = completed.get(); received++;
                if (probe.error == null) result.add(probe.url); else failed(failures, probe.url, "probe", probe.error);
            }
            for (int i = 0; i < workers.size(); i++) if (!workers.get(i).isDone()) failed(failures, artifact.sources().get(i), "probe", "deadline");
            return result;
        } finally {
            for (Future<Probe> worker : workers) worker.cancel(true);
            // Cancel any probe still blocked in DNS / TLS / read before the full transfer begins.
            session.cancelActive(); pool.shutdownNow();
        }
    }
    private static final class Probe {
        final String url, error;
        Probe(String url, String error) { this.url = url; this.error = error; }
    }

    private void transfer(String source, Artifact artifact, File part, long start, Session session, Listener listener, long deadline) throws Exception {
        Exchange exchange = session.open(source, start, artifact.bytes - 1, remaining(deadline), false);
        try {
            Reply reply = exchange.execute();
            try {
                validate(reply, artifact, start, artifact.bytes - 1);
                // Some mirrors ignore Range. Reuse this response from byte zero, never append its body.
                if (reply.code == 200) start = 0;
                long verified = start;
                RandomAccessFile output = new RandomAccessFile(part, "rw");
                try {
                    output.setLength(start); output.seek(start);
                    byte[] buffer = new byte[32768];
                    while (verified < artifact.bytes) {
                        session.check(); remaining(deadline);
                        int index = (int) (verified / CHUNK_BYTES);
                        long wanted = Math.min((long) CHUNK_BYTES, artifact.bytes - verified), received = 0;
                        MessageDigest sha = MessageDigest.getInstance("SHA-256");
                        try {
                            while (received < wanted) {
                                session.check(); remaining(deadline);
                                int read = reply.body.read(buffer, 0, (int) Math.min((long) buffer.length, wanted - received));
                                if (read < 0) throw new IOException("download_truncated");
                                if (read == 0) continue;
                                output.write(buffer, 0, read); sha.update(buffer, 0, read); received += read;
                            }
                            if (!artifact.chunks[index].equals(ToolchainInstaller.hex(sha.digest()))) throw new IOException("chunk_sha256_mismatch:" + index);
                        } catch (Exception failure) { output.setLength(verified); throw failure; }
                        output.getFD().sync(); verified += wanted;
                        listener.update("downloading", verified, artifact.bytes);
                    }
                    session.check(); remaining(deadline);
                    if (reply.body.read() != -1) { output.setLength(0); throw new IOException("download_oversized"); }
                } finally { output.close(); }
            } finally { reply.close(); }
        } finally { session.release(exchange); }
    }

    private static void validate(Reply reply, Artifact artifact, long first, long last) throws Exception {
        if (reply.body == null || reply.type.toLowerCase(java.util.Locale.US).contains("text/html")) throw new IOException("not_an_archive");
        if (reply.encoding.length() > 0 && !"identity".equalsIgnoreCase(reply.encoding)) throw new IOException("unexpected_content_encoding");
        if (reply.code == 206) {
            String expected = "bytes " + first + "-" + last + "/" + artifact.bytes;
            if (!expected.equals(reply.range) || reply.length >= 0 && reply.length != last - first + 1) throw new IOException("invalid_content_range");
        } else if (reply.code == 200) {
            if (reply.length >= 0 && reply.length != artifact.bytes) throw new IOException("invalid_content_length");
        } else throw new IOException("http_" + reply.code);
    }

    private static long recover(File part, Artifact artifact, ToolchainInstaller.Cancellation cancellation) throws Exception {
        if (!part.exists()) return 0;
        long verified = 0;
        RandomAccessFile input = new RandomAccessFile(part, "rw");
        try {
            if (input.length() > artifact.bytes) { input.setLength(0); return 0; }
            byte[] buffer = new byte[32768];
            for (int i = 0; i < artifact.chunks.length; i++) {
                cancellation.check();
                long length = Math.min((long) CHUNK_BYTES, artifact.bytes - verified);
                if (input.length() - verified < length) break;
                MessageDigest sha = MessageDigest.getInstance("SHA-256"); long consumed = 0;
                while (consumed < length) {
                    cancellation.check(); int read = input.read(buffer, 0, (int) Math.min((long) buffer.length, length - consumed));
                    if (read < 0) break; sha.update(buffer, 0, read); consumed += read;
                }
                if (consumed != length || !artifact.chunks[i].equals(ToolchainInstaller.hex(sha.digest()))) break;
                verified += length;
            }
            input.setLength(verified); return verified;
        } finally { input.close(); }
    }
    private static File publish(File part, File complete) throws IOException {
        if (!part.renameTo(complete)) throw new IOException("无法保存校验通过的工具包。"); return complete;
    }
    private static void truncate(File file, long length) throws IOException {
        RandomAccessFile output = new RandomAccessFile(file, "rw"); try { output.setLength(length); } finally { output.close(); }
    }
    private static String hash(File file, ToolchainInstaller.Cancellation cancellation) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256"); InputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[32768]; int read;
            while ((read = input.read(buffer)) >= 0) { cancellation.check(); if (read > 0) sha.update(buffer, 0, read); }
        } finally { input.close(); }
        cancellation.check(); return ToolchainInstaller.hex(sha.digest());
    }
    private static File safe(File file, File directory) throws IOException {
        File absolute = file.getAbsoluteFile(), canonical = file.getCanonicalFile();
        if (!absolute.equals(canonical) || !canonical.equals(directory.getCanonicalFile())
                && !canonical.getPath().startsWith(directory.getCanonicalPath() + File.separator)) throw new IOException("工具包下载缓存路径越界。");
        return canonical;
    }
    private static long remaining(long deadline) throws IOException {
        long value = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (value <= 0) throw new IOException("deadline"); return value;
    }
    private static void failed(JSONArray failures, String source, String stage, String error) {
        try { failures.put(new JSONObject().put("source", source).put("stage", stage).put("error", error)); }
        catch (Exception ignored) { }
    }

    private final class Session implements ToolchainInstaller.Cancellation {
        final ToolchainInstaller.Cancellation cancellation;
        final Set<Exchange> active = new HashSet<Exchange>();
        volatile boolean closed;
        Session(ToolchainInstaller.Cancellation cancellation) { this.cancellation = cancellation; }
        public void check() throws Exception {
            cancellation.check(); if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException("工具包下载已取消。");
        }
        Exchange open(String url, long first, long last, long timeout, boolean probe) throws Exception {
            check(); Exchange request = transport.open(url, first, last, timeout, probe);
            synchronized (active) {
                if (closed) { request.cancel(); request.close(); throw new InterruptedException("工具包下载已取消。"); }
                active.add(request);
            }
            try { check(); return request; } catch (Exception failure) { release(request); throw failure; }
        }
        void release(Exchange request) {
            synchronized (active) { active.remove(request); }
            request.cancel(); try { request.close(); } catch (IOException ignored) { }
        }
        void cancelActive() {
            List<Exchange> current; synchronized (active) { current = new ArrayList<Exchange>(active); }
            for (Exchange request : current) release(request);
        }
        void close() { closed = true; cancelActive(); }
    }

    private static final class HttpTransport implements Transport {
        // System routing preserves the user's VPN and proxy; no model credentials enter these requests.
        final OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).build();
        public Exchange open(String url, long first, long last, long timeoutMs, boolean probe) {
            return new HttpExchange(client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(probe ? 8 : 20, TimeUnit.SECONDS).build(), url, first, last, timeoutMs);
        }
    }
    private static final class HttpExchange implements Exchange {
        final OkHttpClient client;
        final String original;
        final long first, last;
        final long deadline;
        volatile Call active;
        volatile Response response;
        volatile boolean cancelled;
        HttpExchange(OkHttpClient client, String original, long first, long last, long timeout) {
            this.client = client; this.original = original; this.first = first; this.last = last;
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        }
        public Reply execute() throws Exception {
            String url = original;
            for (int redirects = 0; redirects <= 5; redirects++) {
                if (cancelled) throw new IOException("cancelled");
                Request request = new Request.Builder().url(url).header("Range", "bytes=" + first + "-" + last)
                        .header("Accept-Encoding", "identity").header("Accept", "application/octet-stream")
                        .header("User-Agent", "Backcast-Toolchain").get().build();
                Call call = client.newBuilder().callTimeout(remaining(deadline), TimeUnit.MILLISECONDS).build().newCall(request); active = call;
                if (cancelled) { call.cancel(); throw new IOException("cancelled"); }
                Response reply = call.execute(); response = reply;
                if (cancelled) { reply.close(); throw new IOException("cancelled"); }
                int code = reply.code();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = reply.header("Location"); reply.close(); response = null;
                    if (location == null || redirects == 5) throw new IOException("invalid_redirect");
                    URI target = new URI(url).resolve(location);
                    String host = target.getHost();
                    if (!"https".equalsIgnoreCase(target.getScheme()) || target.getUserInfo() != null || host == null
                            || !("github.com".equalsIgnoreCase(host) || host.toLowerCase(java.util.Locale.US).endsWith(".githubusercontent.com")
                            || "ghfast.top".equalsIgnoreCase(host) || "gh-proxy.com".equalsIgnoreCase(host) || "ghproxy.net".equalsIgnoreCase(host)))
                        throw new IOException("untrusted_redirect");
                    url = target.toString(); continue;
                }
                final Response owned = reply;
                return new Reply(code, reply.body() == null ? -1 : reply.body().contentLength(), reply.header("Content-Range"),
                        reply.header("Content-Encoding"), reply.header("Content-Type"), reply.body() == null ? null : reply.body().byteStream(),
                        new Closeable() { public void close() { owned.close(); } });
            }
            throw new IOException("redirect_limit");
        }
        public void cancel() { cancelled = true; Call call = active; if (call != null) call.cancel(); Response reply = response; if (reply != null) reply.close(); }
        public void close() { cancel(); }
    }
}
