import com.mkei.backcast.agent.ConnectionRace;
import com.mkei.backcast.agent.InternetReachability;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.NetworkRouting;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.json.JSONObject;

/** Executes the same bounded connection scheduler used by the Android network provider. */
public final class NetworkRoutingRegressionTest {
    private static int passed;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }
    private static final class Probe implements ConnectionRace.Probe {
        final String name;
        final long delay;
        final boolean fail;
        final AtomicBoolean closed = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1);
        Probe(String name, long delay, boolean fail) { this.name = name; this.delay = delay; this.fail = fail; }
        @Override public String id() { return name; }
        @Override public void connect(long deadline) throws IOException {
            entered.countDown(); long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
            while (!closed.get() && System.nanoTime() < until) {
                try { Thread.sleep(5L); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
            }
            if (closed.get()) throw new InterruptedIOException();
            if (fail) throw new java.net.ConnectException("fixture only");
        }
        @Override public void close() { closed.set(true); }
    }
    private static ConnectionRace.Feed feed(ConnectionRace.Probe... probes) {
        return new ConnectionRace.Feed() {
            @Override public List<ConnectionRace.Probe> initial() { return Arrays.asList(probes); }
            @Override public ConnectionRace.Probe poll() { return null; }
            @Override public boolean waiting() { return false; }
        };
    }
    private static void fastestConnectedNetworkWinsAndEveryProbeCloses() throws Exception {
        Probe wifi = new Probe("wifi", 800L, false), cell = new Probe("cellular", 25L, false);
        long started = System.nanoTime();
        ConnectionRace.Winner winner = ConnectionRace.connect(feed(wifi, cell), null, 1500L);
        check(winner.probe == cell && wifi.closed.get() && cell.closed.get()
                        && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500L,
                "Slow network delayed the winner or leaked an established connection");
        pass("fastestConnectedNetworkWinsAndEveryProbeCloses");
    }
    private static void lateCellularCallbackCanJoinAnUnfinishedDefaultProbe() throws Exception {
        Probe wifi = new Probe("system", 900L, false), cell = new Probe("cellular", 20L, false);
        long started = System.nanoTime(); AtomicBoolean offered = new AtomicBoolean();
        ConnectionRace.Feed incoming = new ConnectionRace.Feed() {
            @Override public List<ConnectionRace.Probe> initial() { return Arrays.asList(wifi); }
            @Override public ConnectionRace.Probe poll() {
                return System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(70L)
                        && offered.compareAndSet(false, true) ? cell : null;
            }
            @Override public boolean waiting() { return !offered.get(); }
        };
        check(ConnectionRace.connect(incoming, null, 1500L).probe == cell && wifi.closed.get(),
                "The modem callback was ignored until the default route timed out");
        pass("lateCellularCallbackCanJoinAnUnfinishedDefaultProbe");
    }
    private static void fastDefaultDoesNotWaitForAnUnavailableCellularCallback() throws Exception {
        Probe wifi = new Probe("system", 15L, false);
        ConnectionRace.Feed unavailable = new ConnectionRace.Feed() {
            @Override public List<ConnectionRace.Probe> initial() { return Arrays.asList(wifi); }
            @Override public ConnectionRace.Probe poll() { return null; }
            @Override public boolean waiting() { return true; }
        };
        long started = System.nanoTime();
        check(ConnectionRace.connect(unavailable, null, 1200L).probe == wifi
                        && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 300L,
                "A healthy default route waited for the modem activation deadline");
        pass("fastDefaultDoesNotWaitForAnUnavailableCellularCallback");
    }
    private static void cancellationClosesPendingProbesPromptly() throws Exception {
        Probe slow = new Probe("wifi", 6000L, false); AtomicBoolean current = new AtomicBoolean(true);
        Thread cancel = new Thread(() -> {
            try { slow.entered.await(500L, TimeUnit.MILLISECONDS); Thread.sleep(40L); current.set(false); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        cancel.start(); long started = System.nanoTime();
        try { ConnectionRace.connect(feed(slow), () -> current.get(), 2000L); throw new AssertionError("Cancellation selected a route"); }
        catch (InterruptedIOException expected) {
            check(slow.closed.get() && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 350L,
                    "Cancelled network selection waited for a socket deadline");
        } finally { cancel.join(1000L); }
        pass("cancellationClosesPendingProbesPromptly");
    }
    private static void everyFailedNetworkRetainsOnlyClassesAndTiming() throws Exception {
        Probe wifi = new Probe("wifi", 5L, true), cell = new Probe("cellular", 5L, true);
        try { ConnectionRace.connect(feed(wifi, cell), null, 1000L); throw new AssertionError("Failed probes selected a route"); }
        catch (ConnectionRace.Failed expected) {
            String diagnostic = expected.diagnostic.toString();
            check(expected.diagnostic.getJSONArray("connection_probes").length() == 2
                            && diagnostic.contains("ConnectException") && !diagnostic.contains("fixture only")
                            && wifi.closed.get() && cell.closed.get(),
                    "Network failure hid a candidate, leaked request text, or left a socket alive");
        }
        pass("everyFailedNetworkRetainsOnlyClassesAndTiming");
    }
    private static void totalDeadlineClosesAProbeThatNeverConnects() throws Exception {
        Probe blocked = new Probe("wifi", 6000L, false); long started = System.nanoTime();
        try { ConnectionRace.connect(feed(blocked), null, 120L); throw new AssertionError("Expired selection returned a route"); }
        catch (ConnectionRace.Failed expected) {
            check(expected.diagnostic.getString("selection").equals("deadline") && blocked.closed.get()
                            && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500L,
                    "Connection selection exceeded its shared total budget");
        }
        pass("totalDeadlineClosesAProbeThatNeverConnects");
    }
    private static void connectionRaceNeverSendsHttpBytes() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger received = new AtomicInteger(-2);
            Thread peer = new Thread(() -> {
                try (Socket accepted = server.accept()) { accepted.setSoTimeout(1200); received.set(accepted.getInputStream().read()); }
                catch (IOException error) { received.set(-3); }
            });
            peer.start();
            ConnectionRace.Probe tcp = new ConnectionRace.Probe() {
                Socket socket;
                @Override public String id() { return "fixture-tcp"; }
                @Override public synchronized void connect(long deadline) throws IOException {
                    socket = new Socket(); socket.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 500);
                }
                @Override public synchronized void close() throws IOException { if (socket != null) socket.close(); }
            };
            ConnectionRace.connect(feed(tcp), null, 1000L); peer.join(1500L);
            check(received.get() == -1 && !peer.isAlive(), "Connection probe sent HTTP/model data or left TCP open");
        }
        pass("connectionRaceNeverSendsHttpBytes");
    }
    private static void routingFacadeKeepsFailureAndLeaseIndependentFromModelRequests() throws Exception {
        AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger(), configured = new AtomicInteger();
        NetworkRouting.install((endpoint, valid) -> {
            opened.incrementAndGet();
            return new NetworkRouting.Route() {
                @Override public void configure(OkHttpClient.Builder builder) { configured.incrementAndGet(); }
                @Override public JSONObject diagnostic() { return new JSONObject(); }
                @Override public String failureMessage(LlmClient.RequestValidity valid) { return "网络可访问，但 AI 服务器连接失败"; }
                @Override public void close() { closed.incrementAndGet(); }
            };
        });
        try (NetworkRouting.Route route = NetworkRouting.open("https://fixture.invalid/v1", null)) {
            route.configure(new OkHttpClient.Builder());
            check(opened.get() == 1 && configured.get() == 1 && closed.get() == 0,
                    "Routing released the network lease before the SDK was finished");
        } finally { NetworkRouting.install(null); }
        check(closed.get() == 1 && NetworkRouting.open("https://fixture.invalid/v1", null) == null,
                "Routing lease was not released or optional host fallback changed");
        JSONObject evidence = new JSONObject().put("internet_probe", "reachable");
        NetworkRouting.Failure failure = new NetworkRouting.Failure("网络可访问，但 AI 服务器连接失败", evidence);
        check(failure.diagnostic.getString("internet_probe").equals("reachable") && !failure.getMessage().contains("HTTP"),
                "Network selection failure lost its private evidence or invented a provider response");
        pass("routingFacadeKeepsFailureAndLeaseIndependentFromModelRequests");
    }
    private static void androidProviderPreservesVpnDnsTlsAndCallbackLifetime(String root) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(root,
                "app/src/main/java/com/mkei/backcast/net/DeviceNetworks.java")), StandardCharsets.UTF_8);
        check(source.contains("TRANSPORT_VPN") && source.contains("!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)")
                        && source.contains("if (proxy) return new Selected(target, null") && !source.contains("bindProcessToNetwork("),
                "Android provider can bypass a VPN/proxy or globally change another session's route");
        check(source.contains("network.getAllByName(host)") && source.contains("network.getAllByName(hostname)")
                        && source.contains("network.getSocketFactory()") && source.contains("tlsPolicy.hostnameVerifier().verify")
                        && source.contains("new SNIHostName(host)"), "Network-specific DNS or validated TLS was omitted");
        check(source.contains("return new Selected(target, chosen.network, chosen.connectedAddress, feed, evidence)")
                        && source.contains("if (lease != null) lease.close()") && source.contains("unregisterNetworkCallback(callback)")
                        && source.contains("CACHE_MS = 60000L") && source.contains("!cellular(chosen.network)"),
                "Requested cellular could disconnect before streaming finishes or stay cached beyond its lease");
        check(source.contains("url(BAIDU).head()") && source.contains("retryOnConnectionFailure(false)")
                        && source.contains("followRedirects(false)") && !source.contains(".post(")
                        && source.contains("call.cancel(); probe.connectionPool().evictAll()")
                        && source.contains("chosen.network != null && mustKeepSystem(target)")
                        && source.contains("network != null && mustKeepSystem(target)"),
                "Connectivity diagnostics can repeat/send model HTTP or cannot cancel a DNS stall");
        pass("androidProviderPreservesVpnDnsTlsAndCallbackLifetime");
    }
    private static InternetReachability.Probe internet(String name, int status, AtomicBoolean closed, long delay) {
        return new InternetReachability.Probe() {
            @Override public String id() { return name; }
            @Override public int status() throws IOException {
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
                while (!closed.get() && System.nanoTime() < until) {
                    try { Thread.sleep(5L); } catch (InterruptedException stopped) { throw new InterruptedIOException(); }
                }
                if (status < 0) throw new IOException("fixture private error"); return status;
            }
            @Override public void close() { closed.set(true); }
        };
    }
    private static void baiduReachableOnCellularOverridesFailedDefaultWithoutRetryingEither() throws Exception {
        AtomicBoolean defaultClosed = new AtomicBoolean(), cellClosed = new AtomicBoolean();
        InternetReachability.Result result = InternetReachability.check(Arrays.asList(
                internet("system", -1, defaultClosed, 5L), internet("cellular", 503, cellClosed, 30L)), null, 1000L);
        check(result.reachable && !result.cancelled && result.diagnostic.getInt("internet_probe_status") == 503
                        && result.diagnostic.getString("internet_probe_network").equals("cellular")
                        && defaultClosed.get() && cellClosed.get() && !result.diagnostic.toString().contains("private"),
                "A failed default probe hid cellular HTTP reachability or leaked the body");
        pass("baiduReachableOnCellularOverridesFailedDefaultWithoutRetryingEither");
    }
    private static void failedInternetProbesOnlyReportUnconfirmedConnectivity() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InternetReachability.Result result = InternetReachability.check(Arrays.asList(internet("system", -1, closed, 5L)), null, 500L);
        check(!result.reachable && !result.cancelled && result.diagnostic.getString("internet_probe").equals("unconfirmed")
                        && closed.get(), "Baidu failure was mistaken for proof the whole internet is offline");
        pass("failedInternetProbesOnlyReportUnconfirmedConnectivity");
    }
    private static void internetProbeSharedDeadlineAndCancellationCloseEveryCall() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(); long started = System.nanoTime();
        InternetReachability.Result result = InternetReachability.check(Arrays.asList(internet("system", 200, closed, 5000L)), null, 120L);
        check(!result.reachable && closed.get() && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500L,
                "DNS or socket stall escaped the diagnostic deadline");
        closed.set(false);
        result = InternetReachability.check(Arrays.asList(internet("system", 200, closed, 5000L)), () -> false, 1500L);
        check(result.cancelled && closed.get(), "Cancelled diagnostic left a request running");
        pass("internetProbeSharedDeadlineAndCancellationCloseEveryCall");
    }
    public static void main(String[] args) throws Exception {
        fastestConnectedNetworkWinsAndEveryProbeCloses(); lateCellularCallbackCanJoinAnUnfinishedDefaultProbe();
        fastDefaultDoesNotWaitForAnUnavailableCellularCallback(); cancellationClosesPendingProbesPromptly();
        everyFailedNetworkRetainsOnlyClassesAndTiming(); totalDeadlineClosesAProbeThatNeverConnects();
        connectionRaceNeverSendsHttpBytes(); routingFacadeKeepsFailureAndLeaseIndependentFromModelRequests();
        baiduReachableOnCellularOverridesFailedDefaultWithoutRetryingEither(); failedInternetProbesOnlyReportUnconfirmedConnectivity();
        internetProbeSharedDeadlineAndCancellationCloseEveryCall();
        if (args.length > 0) androidProviderPreservesVpnDnsTlsAndCallbackLifetime(args[0]);
        System.out.println(passed + " network routing tests passed");
    }
}
