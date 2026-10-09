package com.mkei.backcast.tool;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Exercises real download/verification logic with a controllable range server. */
public final class ToolchainDownloadRegressionTest {
    private static File root;
    private static int sequence;
    private static final ToolchainInstaller.Cancellation LIVE = new ToolchainInstaller.Cancellation() { public void check() { } };
    private static final ToolchainDownloader.Listener QUIET = new ToolchainDownloader.Listener() { public void update(String stage, long done, long total) { } };
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static File cache() { return new File(root, "cache-" + (++sequence)); }
    private static byte[] payload() {
        byte[] bytes = new byte[ToolchainDownloader.CHUNK_BYTES * 2 + 173];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) ((i * 29 + i / 19) & 255);
        bytes[0] = 0x1f; bytes[1] = (byte) 0x8b; return bytes;
    }
    private static JSONObject spec(byte[] bytes) throws Exception {
        return ToolchainFixtures.pin(new JSONObject().put("file", "common.tar.gz").put("bytes", bytes.length)
                .put("sha256", ToolchainFixtures.sha(bytes)), "fixture", bytes);
    }
    private static final class Request {
        final String url;
        final long first, last, timeout;
        final boolean probe;
        Request(String url, long first, long last, long timeout, boolean probe) { this.url = url; this.first = first; this.last = last; this.timeout = timeout; this.probe = probe; }
    }
    private static final class Server implements ToolchainDownloader.Transport {
        final byte[] bytes;
        final List<Request> requests = Collections.synchronizedList(new ArrayList<Request>());
        final AtomicInteger activeProbes = new AtomicInteger(), maxProbes = new AtomicInteger(), activeTransfers = new AtomicInteger(), maxTransfers = new AtomicInteger();
        final AtomicInteger downloads = new AtomicInteger(), cancels = new AtomicInteger();
        final CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile String probeFailure = "", transferFailure = "";
        volatile boolean failFirst, ignoreRange, blockProbes, blockDownload;
        final List<Long> downloadStarts = Collections.synchronizedList(new ArrayList<Long>());
        long prefixRead;
        Server(byte[] bytes) { this.bytes = bytes; }
        public ToolchainDownloader.Exchange open(String url, long first, long last, long timeout, boolean probe) {
            Request request = new Request(url, first, last, timeout, probe); requests.add(request);
            return new Exchange(request);
        }
        private final class Exchange implements ToolchainDownloader.Exchange {
            final Request request;
            final AtomicBoolean closed = new AtomicBoolean();
            volatile InputStream body;
            volatile boolean cancelled;
            boolean entered;
            Exchange(Request request) { this.request = request; }
            public ToolchainDownloader.Reply execute() throws Exception {
                if (cancelled) throw new IOException("cancelled");
                entered = true;
                AtomicInteger active = request.probe ? activeProbes : activeTransfers;
                int count = active.incrementAndGet(); maximum(request.probe ? maxProbes : maxTransfers, count);
                if (request.probe && blockProbes) { blocked.countDown(); release.await(5, TimeUnit.SECONDS); if (cancelled) throw new IOException("cancelled"); }
                int ordinal = request.probe ? 0 : downloads.incrementAndGet();
                if (!request.probe) downloadStarts.add(request.first);
                String fault = request.probe ? probeFailure : transferFailure;
                int from = (int) (ignoreRange && !request.probe ? 0 : request.first);
                int to = request.probe ? (int) request.last + 1 : bytes.length;
                byte[] supplied = bytes;
                if ("prefix".equals(fault) || "chunk".equals(fault)) { supplied = bytes.clone(); supplied["prefix".equals(fault) ? 0 : ToolchainDownloader.CHUNK_BYTES + 3] ^= 1; }
                if (failFirst && ordinal == 1 && !request.probe) to = ToolchainDownloader.CHUNK_BYTES + 900;
                if ("truncated".equals(fault)) to = from + Math.min(1000, to - from);
                final ByteArrayInputStream data = new ByteArrayInputStream(supplied, from, Math.max(0, to - from));
                body = new InputStream() {
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        if (cancelled) throw new IOException("cancelled");
                        if (!request.probe && blockDownload && data.available() <= bytes.length - ToolchainDownloader.CHUNK_BYTES) {
                            blocked.countDown();
                            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                            if (cancelled) throw new IOException("cancelled");
                        }
                        int read = data.read(buffer, offset, length);
                        if (request.probe && read > 0) synchronized (Server.this) { prefixRead += read; }
                        return read;
                    }
                    public int read() throws IOException {
                        byte[] one = new byte[1]; int read = read(one, 0, 1);
                        if (read < 0 && "oversized".equals(fault)) return 42;
                        return read < 0 ? -1 : one[0] & 255;
                    }
                    public void close() { finish(); }
                };
                int code = "http".equals(fault) ? 503 : "416".equals(fault) ? 416 : ignoreRange && !request.probe ? 200 : 206;
                long length = code == 200 ? bytes.length : request.last - request.first + 1;
                if ("length".equals(fault)) length--;
                String range = "bytes " + request.first + "-" + request.last + "/" + bytes.length;
                if ("range".equals(fault)) range = "bytes 1-" + request.last + "/" + bytes.length;
                if ("total".equals(fault)) range = "bytes " + request.first + "-" + request.last + "/" + (bytes.length + 1);
                return new ToolchainDownloader.Reply(code, length, range, "encoding".equals(fault) ? "gzip" : "", "html".equals(fault) ? "text/html" : "application/octet-stream", body, null);
            }
            void finish() { if (closed.compareAndSet(false, true) && entered) (request.probe ? activeProbes : activeTransfers).decrementAndGet(); }
            public void cancel() {
                cancelled = true; cancels.incrementAndGet();
                if (request.probe ? blockProbes : blockDownload) release.countDown();
                finish();
            }
            public void close() { cancel(); }
        }
        static void maximum(AtomicInteger value, int candidate) { int before; do { before = value.get(); if (before >= candidate) return; } while (!value.compareAndSet(before, candidate)); }
    }
    private static File fetch(Server server, JSONObject data, File directory) throws Exception { return new ToolchainDownloader(server).fetch(data, "fixture", directory, LIVE, QUIET); }
    private static void equal(File file, byte[] expected) throws Exception { check(java.util.Arrays.equals(Files.readAllBytes(file.toPath()), expected), "Downloaded bytes differ from pinned file"); }
    private static ToolchainDownloader.Failure refused(Server server, JSONObject data, File directory) throws Exception {
        try { fetch(server, data, directory); throw new AssertionError("Invalid server response was accepted"); }
        catch (ToolchainDownloader.Failure expected) { return expected; }
    }

    private static void onePackageHasOneWriterAndOnlySmallMirrorProbes() throws Exception {
        byte[] bytes = payload(); Server server = new Server(bytes); File output = fetch(server, spec(bytes), cache()); equal(output, bytes);
        check(server.downloads.get() == 1 && server.maxTransfers.get() == 1, "Sources raced or duplicated full downloads");
        check(server.maxProbes.get() <= 3 && server.requests.size() == 5, "Unbounded probe concurrency or repeated probes");
        check(server.prefixRead == 4L * 65536, "Availability probes read full release files");
        for (Request request : server.requests) check(request.timeout > 0 && request.timeout <= (request.probe ? 8000 : 30 * 60 * 1000), "Unbounded request deadline");
        check(server.activeProbes.get() == 0 && server.activeTransfers.get() == 0, "Sockets remained active after success");
    }
    private static void badPrefixAndHtmlCannotBecomeAvailable() throws Exception {
        byte[] bytes = payload();
        for (String kind : new String[]{"prefix", "html", "http", "range", "total", "length", "encoding", "truncated"}) {
            Server server = new Server(bytes); server.probeFailure = kind;
            ToolchainDownloader.Failure failure = refused(server, spec(bytes), cache());
            check(server.downloads.get() == 0 && server.requests.size() == 4, "Bad probe started a download or retried: " + kind);
            check(new JSONObject(failure.diagnostic()).getJSONArray("attempts").length() == 4, "Probe failure evidence was lost");
            check(server.activeProbes.get() == 0, "Failed probe leaked a response");
        }
    }
    private static void disconnectSwitchesSourceAtTheLastVerifiedBlock() throws Exception {
        byte[] bytes = payload(); Server server = new Server(bytes); server.failFirst = true;
        equal(fetch(server, spec(bytes), cache()), bytes);
        check(server.downloads.get() == 2 && server.downloadStarts.equals(java.util.Arrays.asList(0L, (long) ToolchainDownloader.CHUNK_BYTES)), "Disconnect redownloaded a verified block");
        check(server.maxTransfers.get() == 1, "Failover overlapped full transfers");
    }
    private static void everySourceIsAttemptedAtMostOnce() throws Exception {
        byte[] bytes = payload(); Server server = new Server(bytes); server.transferFailure = "truncated";
        ToolchainDownloader.Failure failure = refused(server, spec(bytes), cache());
        check(server.downloads.get() == 4 && server.requests.size() == 8, "Failed installation retried without a bound");
        java.util.Set<String> used = new java.util.HashSet<String>();
        for (Request request : server.requests) if (!request.probe) check(used.add(request.url), "A failed source was retried");
        check(new JSONObject(failure.diagnostic()).getJSONArray("attempts").length() == 4, "Download failure evidence was lost");
    }
    private static void corruptedBlockPreservesEarlierVerifiedBlocks() throws Exception {
        byte[] bytes = payload(); Server server = new Server(bytes); server.transferFailure = "chunk";
        File directory = cache(); refused(server, spec(bytes), directory);
        File part = new File(directory, ToolchainFixtures.sha(bytes) + ".part");
        check(part.length() == ToolchainDownloader.CHUNK_BYTES, "A damaged block was retained or verified earlier block lost");
        check(server.downloadStarts.get(1) == ToolchainDownloader.CHUNK_BYTES, "Corruption failed to resume at a verified boundary");
        Server repaired = new Server(bytes); equal(fetch(repaired, spec(bytes), directory), bytes);
        check(repaired.downloadStarts.get(0) == ToolchainDownloader.CHUNK_BYTES, "Restart ignored verified cached blocks");
    }
    private static void rangeIgnoringServerRestartsFromZeroWithoutAppendingOrSecondRequest() throws Exception {
        byte[] bytes = payload(); File directory = cache(); directory.mkdirs();
        File part = new File(directory, ToolchainFixtures.sha(bytes) + ".part"); Files.write(part.toPath(), java.util.Arrays.copyOf(bytes, ToolchainDownloader.CHUNK_BYTES));
        Server server = new Server(bytes); server.ignoreRange = true; equal(fetch(server, spec(bytes), directory), bytes);
        check(server.downloads.get() == 1 && server.downloadStarts.get(0) == ToolchainDownloader.CHUNK_BYTES, "Ignored Range caused extra full request");
    }
    private static void invalidDownloadRangesAndSizesNeverPublish() throws Exception {
        byte[] bytes = payload();
        for (String kind : new String[]{"range", "total", "length", "encoding", "html", "http", "416", "oversized"}) {
            Server server = new Server(bytes); server.transferFailure = kind; File directory = cache();
            refused(server, spec(bytes), directory);
            check(!new File(directory, ToolchainFixtures.sha(bytes) + ".archive").exists(), "Invalid transfer published a cache: " + kind);
            check(server.downloads.get() == 4 && server.maxTransfers.get() == 1, "Invalid transfer escaped attempt or writer bounds");
        }
    }
    private static void damagedPartialCacheIsTruncatedToItsVerifiedPrefix() throws Exception {
        byte[] bytes = payload(); File directory = cache(); directory.mkdirs();
        byte[] partial = java.util.Arrays.copyOf(bytes, ToolchainDownloader.CHUNK_BYTES * 2); partial[ToolchainDownloader.CHUNK_BYTES + 100] ^= 1;
        Files.write(new File(directory, ToolchainFixtures.sha(bytes) + ".part").toPath(), partial);
        Server server = new Server(bytes); equal(fetch(server, spec(bytes), directory), bytes);
        check(server.downloadStarts.get(0) == ToolchainDownloader.CHUNK_BYTES, "Damaged cached block was blindly resumed");
    }
    private static void unfinishedFragmentsAreNotResumedAsVerifiedBytes() throws Exception {
        byte[] bytes = payload(); File directory = cache(); directory.mkdirs();
        Files.write(new File(directory, ToolchainFixtures.sha(bytes) + ".part").toPath(), java.util.Arrays.copyOf(bytes, 100000));
        Server server = new Server(bytes); equal(fetch(server, spec(bytes), directory), bytes);
        check(server.downloadStarts.get(0) == 0, "Unverified fragment was appended to a new response");
    }
    private static void oversizedPartialIsDiscarded() throws Exception {
        byte[] bytes = payload(); File directory = cache(); directory.mkdirs();
        Files.write(new File(directory, ToolchainFixtures.sha(bytes) + ".part").toPath(), java.util.Arrays.copyOf(bytes, bytes.length + 1));
        Server server = new Server(bytes); equal(fetch(server, spec(bytes), directory), bytes); check(server.downloadStarts.get(0) == 0, "Oversized cache was resumed");
    }
    private static void verifiedCompleteCacheAndFinishedPartialNeedNoNetwork() throws Exception {
        byte[] bytes = payload(); JSONObject data = spec(bytes);
        for (String suffix : new String[]{"archive", "part"}) {
            File directory = cache(); directory.mkdirs(); Files.write(new File(directory, ToolchainFixtures.sha(bytes) + "." + suffix).toPath(), bytes);
            Server server = new Server(bytes); equal(fetch(server, data, directory), bytes); check(server.requests.isEmpty(), "A verified complete cache contacted sources");
        }
    }
    private static void corruptCompleteCacheIsVerifiedBeforeReuse() throws Exception {
        byte[] bytes = payload(); File directory = cache(); directory.mkdirs(); byte[] bad = bytes.clone(); bad[500] ^= 1;
        Files.write(new File(directory, ToolchainFixtures.sha(bytes) + ".archive").toPath(), bad);
        Server server = new Server(bytes); equal(fetch(server, spec(bytes), directory), bytes); check(server.downloads.get() == 1, "Corrupt complete cache was trusted");
    }
    private static void wholeDigestRemainsMandatoryEvenWhenAllBlocksMatch() throws Exception {
        byte[] bytes = payload(); JSONObject data = spec(bytes); data.put("sha256", String.format("%064d", 0));
        Server server = new Server(bytes); File directory = cache(); refused(server, data, directory);
        check(!new File(directory, data.getString("sha256") + ".archive").exists() && server.downloadStarts.equals(java.util.Arrays.asList(0L, 0L, 0L, 0L)), "Whole digest was bypassed or reused a rejected complete download");
    }
    private static void manifestCannotChooseAnUncontrolledSourceOrIncompleteHashes() throws Exception {
        byte[] bytes = payload();
        for (String key : new String[]{"url", "prefix_sha256", "chunk_bytes", "chunk_sha256", "bytes"}) {
            JSONObject data = spec(bytes); data.put(key, "url".equals(key) ? "https://example.com/a.tar.gz" : "bad"); Server server = new Server(bytes);
            boolean failed = false; try { fetch(server, data, cache()); } catch (Exception expected) { failed = true; }
            check(failed && server.requests.isEmpty(), "Invalid manifest issued a request: " + key);
        }
    }
    private static void cacheSymlinksAreRejectedBeforeNetworkAccess() throws Exception {
        byte[] bytes = payload(); File target = cache(); target.mkdirs(); File alias = cache(); Files.createSymbolicLink(alias.toPath(), target.toPath());
        Server server = new Server(bytes); boolean refused = false; try { fetch(server, spec(bytes), alias); } catch (IOException expected) { refused = true; }
        check(refused && server.requests.isEmpty(), "Download followed a cache-directory symlink");
        File directory = cache(); directory.mkdirs(); File outside = new File(root, "outside"); Files.write(outside.toPath(), new byte[]{1});
        Files.createSymbolicLink(new File(directory, ToolchainFixtures.sha(bytes) + ".part").toPath(), outside.toPath());
        refused = false; try { fetch(server, spec(bytes), directory); } catch (IOException expected) { refused = true; }
        check(refused && outside.length() == 1, "Download overwrote an aliased partial cache");
    }
    private static void cancellationClosesBlockedProbesWithoutStartingTransfers() throws Exception { cancel(true); }
    private static void cancellationClosesBlockedTransferAndNextInstallResumes() throws Exception { cancel(false); }
    private static ToolchainStore installationStore(final Server server) throws Exception {
        byte[] bytes = server.bytes;
        JSONObject entry = spec(bytes).put("tar_bytes", bytes.length).put("tar_sha256", ToolchainFixtures.sha(bytes));
        final JSONObject manifest = new JSONObject().put("version", "fixture").put("artifacts", new org.json.JSONArray()
                .put(new JSONObject(entry.toString()).put("abi", "any")).put(new JSONObject(entry.toString()).put("abi", "arm64-v8a")));
        return new ToolchainStore(cache(), new EmbeddedToolchain.Assets() {
            public InputStream open(String name) throws Exception {
                check("toolchain/manifest.json".equals(name), "APK archive fallback was used");
                return new ByteArrayInputStream(manifest.toString().getBytes("UTF-8"));
            }
        }, "arm64-v8a", 30, ArtRuntimeLauncher.DEVICE, server);
    }
    private static void closingAnUnrelatedToolSessionDoesNotCancelAnInstallation() throws Exception {
        final Server server = new Server(payload()); server.blockDownload = true;
        ToolchainStore store = installationStore(server);
        final ToolkitTool installer = new ToolkitTool(new ShellTool(false, root.getPath(), null), store, root.getPath(), null, "arm64-v8a", false);
        ToolkitTool unrelated = new ToolkitTool(new ShellTool(false, root.getPath(), null), store, root.getPath(), null, "arm64-v8a", false);
        Throwable[] failure = new Throwable[1]; Thread worker = new Thread(() -> {
            try { installer.installBundled(null); } catch (Throwable error) { failure[0] = error; }
        });
        worker.start(); check(server.blocked.await(3, TimeUnit.SECONDS), "Installation did not enter blocking read");
        unrelated.abort(); check(worker.isAlive() && server.activeTransfers.get() == 1 && failure[0] == null, "Unrelated session aborted the installation socket");
        installer.abort(); worker.join(2000);
        check(!worker.isAlive() && failure[0] instanceof InterruptedException && server.activeTransfers.get() == 0, "Installer abort failed to cancel its owned socket");
    }
    private static final class PausedInstallLock extends java.util.concurrent.locks.ReentrantReadWriteLock {
        volatile Thread contender;
        final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        final WriteLock writer = new WriteLock(this) {
            @Override public boolean tryLock() {
                if (Thread.currentThread() == contender) {
                    reached.countDown();
                    try { check(release.await(3, TimeUnit.SECONDS), "Contended installation lock was not released"); }
                    catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new IllegalStateException(cancelled); }
                }
                return super.tryLock();
            }
        };
        @Override public WriteLock writeLock() { return writer; }
    }
    private static void abortingAContendedInstallCannotCancelTheLockOwner() throws Exception {
        final Server server = new Server(payload()); server.blockDownload = true;
        ToolchainStore store = installationStore(server);
        PausedInstallLock lock = new PausedInstallLock();
        java.lang.reflect.Field field = ToolchainStore.class.getDeclaredField("operations");
        field.setAccessible(true); field.set(store, lock);
        final ToolkitTool owner = new ToolkitTool(new ShellTool(false, root.getPath(), null), store, root.getPath(), null, "arm64-v8a", false);
        final ToolkitTool loser = new ToolkitTool(new ShellTool(false, root.getPath(), null), store, root.getPath(), null, "arm64-v8a", false);
        Throwable[] ownerFailure = new Throwable[1], loserFailure = new Throwable[1];
        Thread worker = new Thread(() -> { try { owner.installBundled(null); } catch (Throwable error) { ownerFailure[0] = error; } });
        Thread contender = new Thread(() -> { try { loser.installBundled(null); } catch (Throwable error) { loserFailure[0] = error; } });
        lock.contender = contender;
        try {
            worker.start(); check(server.blocked.await(3, TimeUnit.SECONDS), "Owning installation did not enter blocking read");
            contender.start(); check(lock.reached.await(2, TimeUnit.SECONDS), "Contended installation did not reach the shared lock");
            int cancelledBefore = server.cancels.get();
            loser.abort();
            check(server.cancels.get() == cancelledBefore && server.activeTransfers.get() == 1 && worker.isAlive()
                    && ownerFailure[0] == null, "Aborting a failed lock contender cancelled the owning installation");
            lock.release.countDown(); contender.join(2000);
            check(!contender.isAlive() && loserFailure[0] instanceof IllegalStateException, "Contended installation bypassed the owning lock");
            owner.abort(); worker.join(2000);
            check(!worker.isAlive() && ownerFailure[0] instanceof InterruptedException && server.activeTransfers.get() == 0,
                    "Owning cancellation failed to close its download immediately");
        } finally {
            lock.release.countDown(); owner.abort(); loser.abort();
            worker.join(2000); contender.join(2000);
        }
    }
    private static void repeatedInstallOnOneSessionKeepsTheOriginalCancellationOwner() throws Exception {
        final Server server = new Server(payload()); server.blockDownload = true;
        ToolchainStore store = installationStore(server);
        final ToolkitTool owner = new ToolkitTool(new ShellTool(false, root.getPath(), null), store, root.getPath(), null, "arm64-v8a", false);
        Throwable[] failure = new Throwable[1];
        Thread worker = new Thread(() -> { try { owner.installBundled(null); } catch (Throwable error) { failure[0] = error; } });
        try {
            worker.start(); check(server.blocked.await(3, TimeUnit.SECONDS), "Installation did not enter blocking read");
            boolean refused = false;
            try { owner.installBundled(null); } catch (IllegalStateException expected) { refused = true; }
            check(refused && worker.isAlive() && server.activeTransfers.get() == 1, "Duplicate installation changed the active owner");
            owner.abort(); worker.join(2000);
            check(!worker.isAlive() && failure[0] instanceof InterruptedException && server.activeTransfers.get() == 0,
                    "Duplicate installation erased the original cancellation owner");
        } finally { owner.abort(); worker.join(2000); }
    }
    private static void cancel(boolean probing) throws Exception {
        byte[] bytes = payload(); Server server = new Server(bytes); server.blockProbes = probing; server.blockDownload = !probing;
        ToolchainDownloader downloader = new ToolchainDownloader(server); File directory = cache(); JSONObject data = spec(bytes);
        Throwable[] failure = new Throwable[1];
        Thread worker = new Thread(() -> { try { downloader.fetch(data, "fixture", directory, LIVE, QUIET); } catch (Throwable error) { failure[0] = error; } });
        worker.start(); check(server.blocked.await(3, TimeUnit.SECONDS), "Fixture never entered blocking network I/O");
        downloader.cancel(LIVE); worker.join(2000);
        check(!worker.isAlive() && failure[0] instanceof InterruptedException, "Cancellation remained blocked or failed over: " + failure[0]);
        check(server.activeProbes.get() == 0 && server.activeTransfers.get() == 0 && server.cancels.get() > 0, "Cancellation did not close network I/O");
        if (probing) check(server.downloads.get() == 0, "Cancelled probing started a package transfer");
        else {
            check(server.downloads.get() == 1 && new File(directory, data.getString("sha256") + ".part").length() == ToolchainDownloader.CHUNK_BYTES, "Cancelled transfer lost a verified block or switched sources");
            Server next = new Server(bytes); equal(fetch(next, data, directory), bytes); check(next.downloadStarts.get(0) == ToolchainDownloader.CHUNK_BYTES, "Manual next installation could not resume");
        }
    }
    private static void remove(File file) throws Exception {
        if (!Files.isSymbolicLink(file.toPath())) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); }
        Files.deleteIfExists(file.toPath());
    }
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("backcast-download-tests-").toFile(); int passed = 0;
        try {
            for (String name : new String[]{"onePackageHasOneWriterAndOnlySmallMirrorProbes", "badPrefixAndHtmlCannotBecomeAvailable", "disconnectSwitchesSourceAtTheLastVerifiedBlock", "everySourceIsAttemptedAtMostOnce", "corruptedBlockPreservesEarlierVerifiedBlocks", "rangeIgnoringServerRestartsFromZeroWithoutAppendingOrSecondRequest", "invalidDownloadRangesAndSizesNeverPublish", "damagedPartialCacheIsTruncatedToItsVerifiedPrefix", "unfinishedFragmentsAreNotResumedAsVerifiedBytes", "oversizedPartialIsDiscarded", "verifiedCompleteCacheAndFinishedPartialNeedNoNetwork", "corruptCompleteCacheIsVerifiedBeforeReuse", "wholeDigestRemainsMandatoryEvenWhenAllBlocksMatch", "manifestCannotChooseAnUncontrolledSourceOrIncompleteHashes", "cacheSymlinksAreRejectedBeforeNetworkAccess", "cancellationClosesBlockedProbesWithoutStartingTransfers", "cancellationClosesBlockedTransferAndNextInstallResumes", "closingAnUnrelatedToolSessionDoesNotCancelAnInstallation", "abortingAContendedInstallCannotCancelTheLockOwner", "repeatedInstallOnOneSessionKeepsTheOriginalCancellationOwner"}) {
                ToolchainDownloadRegressionTest.class.getDeclaredMethod(name).invoke(null); System.out.println("PASS " + name); passed++;
            }
            System.out.println(passed + " toolchain download tests passed");
        } finally { remove(root); }
    }
}
