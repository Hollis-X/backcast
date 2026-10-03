package com.mkei.backcast.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import com.mkei.backcast.agent.ConnectionRace;
import com.mkei.backcast.agent.InternetReachability;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.NetworkRouting;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SNIHostName;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** API26 multi-network connection selection. All model HTTP remains owned by the official SDK. */
public final class DeviceNetworks implements NetworkRouting.Provider {
    private static final long CONNECT_MS = 3500L, CACHE_MS = 60000L;
    private static final String BAIDU = "https://www.baidu.com/";
    private final ConnectivityManager connectivity;
    private final OkHttpClient tlsPolicy = new OkHttpClient.Builder()
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    private Cache cache;

    private static final class Cache {
        final String endpoint, baseline;
        final Network network;
        final InetAddress address;
        final long until;
        Cache(String endpoint, String baseline, Network network, InetAddress address) {
            this.endpoint = endpoint; this.baseline = baseline; this.network = network; this.address = address;
            until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CACHE_MS);
        }
    }
    public DeviceNetworks(Context context) {
        connectivity = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override public NetworkRouting.Route open(String endpoint, LlmClient.RequestValidity valid) throws IOException {
        if (!NetworkRouting.current(valid)) throw new InterruptedIOException("Cancelled network selection");
        URI target;
        try { target = new URI(endpoint); }
        catch (Exception malformed) { throw new IOException("Invalid AI server address", malformed); }
        String host = target.getHost();
        if (host == null || !("https".equalsIgnoreCase(target.getScheme()) || "http".equalsIgnoreCase(target.getScheme())))
            throw new IOException("Invalid AI server address");
        Network active = null;
        try { active = connectivity == null ? null : connectivity.getActiveNetwork(); }
        catch (SecurityException denied) { /* Keep system routing if the network snapshot cannot be read. */ }
        NetworkCapabilities caps = capabilities(active);
        String baseline = active == null ? "none" : active.toString();
        boolean proxy = hasProxy(target);
        boolean protectedRoute = caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) || proxy;
        // A configured proxy must remain owned by OkHttp, including any CONNECT authentication.
        if (proxy) return new Selected(target, null, null, null, metadata("system_proxy", "system"));
        String cacheKey = target.getScheme() + "://" + host + ":" + port(target);
        synchronized (this) {
            if (!protectedRoute && cache != null && cache.until > System.nanoTime()
                    && cache.endpoint.equals(cacheKey) && cache.baseline.equals(baseline)
                    && (cache.network == null || capabilities(cache.network) != null))
                return new Selected(target, cache.network, cache.address, null, metadata("cached", label(cache.network)));
        }
        CandidateFeed feed = new CandidateFeed(target, protectedRoute);
        try {
            ConnectionRace.Winner winner = ConnectionRace.connect(feed, valid, CONNECT_MS);
            ConnectionProbe chosen = (ConnectionProbe) winner.probe;
            if (chosen.network != null && mustKeepSystem(target)) {
                feed.close(); return new Selected(target, null, null, null, metadata("system_changed", "system"));
            }
            JSONObject evidence = winner.diagnostic;
            put(evidence, "selected_network", chosen.id());
            put(evidence, "vpn_preserved", protectedRoute && caps != null
                    && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
            if (!protectedRoute && !cellular(chosen.network)) synchronized (this) {
                cache = new Cache(cacheKey, baseline, chosen.network, chosen.connectedAddress);
            }
            return new Selected(target, chosen.network, chosen.connectedAddress, feed, evidence);
        } catch (ConnectionRace.Failed failure) {
            JSONObject evidence = failure.diagnostic;
            String message;
            try { message = internetFailure(target, null, feed, evidence, valid); }
            finally { feed.close(); }
            throw new NetworkRouting.Failure(message, evidence);
        } catch (IOException | RuntimeException failure) {
            feed.close(); throw failure;
        }
    }

    private final class CandidateFeed implements ConnectionRace.Feed, java.io.Closeable {
        final List<ConnectionRace.Probe> initial = new ArrayList<ConnectionRace.Probe>();
        final ArrayBlockingQueue<ConnectionRace.Probe> incoming = new ArrayBlockingQueue<ConnectionRace.Probe>(4);
        final Set<String> seen = new HashSet<String>();
        final List<Network> networks = new ArrayList<Network>();
        final URI target;
        volatile boolean requesting, closed;
        ConnectivityManager.NetworkCallback callback;
        CandidateFeed(URI target, boolean systemOnly) {
            this.target = target;
            initial.add(new ConnectionProbe(target, null)); seen.add("system");
            if (systemOnly || connectivity == null) return;
            try {
                for (Network network : connectivity.getAllNetworks()) {
                    NetworkCapabilities value = capabilities(network);
                    if (value != null && value.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && value.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                            && (value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)))
                        addInitial(network);
                }
                requesting = true;
                callback = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) { offer(network); requesting = false; }
                    @Override public void onUnavailable() { requesting = false; }
                };
                // Requesting VALIDATED is unsupported: probe the server rather than trusting a capability.
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
                connectivity.requestNetwork(request, callback, (int) CONNECT_MS);
            } catch (SecurityException | IllegalArgumentException unavailable) { requesting = false; }
        }
        private synchronized void addInitial(Network network) {
            if (seen.add(network.toString())) { networks.add(network); initial.add(new ConnectionProbe(target, network)); }
        }
        private synchronized void offer(Network network) {
            if (!closed && seen.add(network.toString())) { networks.add(network); incoming.offer(new ConnectionProbe(target, network)); }
        }
        synchronized List<Network> networks() { return new ArrayList<Network>(networks); }
        @Override public List<ConnectionRace.Probe> initial() { return initial; }
        @Override public ConnectionRace.Probe poll() { return incoming.poll(); }
        @Override public boolean waiting() { return requesting && !closed; }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true; requesting = false;
            if (callback != null && connectivity != null) try { connectivity.unregisterNetworkCallback(callback); }
            catch (IllegalArgumentException alreadyReleased) { }
            ConnectionRace.Probe pending;
            while ((pending = incoming.poll()) != null) try { pending.close(); } catch (IOException ignored) { }
        }
    }

    private final class ConnectionProbe implements ConnectionRace.Probe {
        final URI target;
        final Network network;
        volatile Socket running;
        volatile InetAddress connectedAddress;
        volatile boolean closed;
        ConnectionProbe(URI target, Network network) { this.target = target; this.network = network; }
        @Override public String id() { return label(network); }
        @Override public void connect(long deadline) throws IOException {
            String host = target.getHost();
            InetAddress[] addresses = network == null ? InetAddress.getAllByName(host) : network.getAllByName(host);
            IOException last = new UnknownHostException("No server address");
            for (int index = 0; index < addresses.length; index++) {
                InetAddress address = addresses[index];
                if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled connection probe");
                // A dead first IPv6 address must leave time for the same network's IPv4 address.
                int budget = Math.max(1, remaining(deadline) / (addresses.length - index));
                long addressDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget));
                Socket socket = (network == null ? SocketFactory.getDefault() : network.getSocketFactory()).createSocket();
                synchronized (this) {
                    if (closed) { socket.close(); throw new InterruptedIOException("Cancelled connection probe"); }
                    running = socket;
                }
                try {
                    socket.connect(new InetSocketAddress(address, port(target)), remaining(addressDeadline));
                    if ("https".equalsIgnoreCase(target.getScheme())) {
                        SSLSocket secure = (SSLSocket) tlsPolicy.sslSocketFactory().createSocket(socket, host, port(target), true);
                        synchronized (this) {
                            if (closed) { secure.close(); throw new InterruptedIOException("Cancelled connection probe"); }
                            running = secure;
                        }
                        SSLParameters parameters = secure.getSSLParameters();
                        if (host.indexOf(':') < 0 && !host.matches("[0-9]+(?:\\.[0-9]+){3}"))
                            parameters.setServerNames(Arrays.asList(new SNIHostName(host)));
                        secure.setSSLParameters(parameters); secure.setSoTimeout(remaining(addressDeadline)); secure.startHandshake();
                        if (!tlsPolicy.hostnameVerifier().verify(host, secure.getSession()))
                            throw new javax.net.ssl.SSLPeerUnverifiedException("Server hostname verification failed");
                    }
                    connectedAddress = address; return;
                } catch (IOException failed) { last = failed; }
                finally { Socket latest = running; if (latest != null) try { latest.close(); } catch (IOException ignored) { } }
            }
            throw last;
        }
        @Override public synchronized void close() throws IOException {
            closed = true; if (running != null) running.close();
        }
    }

    private final class Selected implements NetworkRouting.Route {
        final URI target;
        final Network network;
        final InetAddress preferred;
        final CandidateFeed lease;
        final JSONObject evidence;
        boolean closed;
        Selected(URI target, Network network, InetAddress preferred, CandidateFeed lease, JSONObject evidence) {
            this.target = target; this.network = network; this.preferred = preferred; this.lease = lease; this.evidence = evidence;
        }
        @Override public void configure(OkHttpClient.Builder builder) {
            if ((network != null || preferred != null) && mustKeepSystem(target)) {
                put(evidence, "selected_network", "system"); put(evidence, "selection", "system_changed");
                if (lease != null) lease.close(); return;
            }
            if (network != null) builder.socketFactory(network.getSocketFactory());
            if (network != null || preferred != null) builder.dns(new Dns() {
                @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
                    if (network != null && mustKeepSystem(target)) throw new UnknownHostException("System network routing changed");
                    List<InetAddress> addresses = network == null ? Dns.SYSTEM.lookup(hostname)
                            : Arrays.asList(network.getAllByName(hostname));
                    if (preferred == null || !hostname.equalsIgnoreCase(target.getHost()) || mustKeepSystem(target)) return addresses;
                    List<InetAddress> ordered = new ArrayList<InetAddress>(); ordered.add(preferred);
                    for (InetAddress address : addresses) if (!ordered.contains(address)) ordered.add(address);
                    return ordered;
                }
            });
        }
        @Override public JSONObject diagnostic() {
            try { return new JSONObject(evidence.toString()); } catch (Exception impossible) { return new JSONObject(); }
        }
        @Override public String failureMessage(LlmClient.RequestValidity valid) {
            synchronized (DeviceNetworks.this) { cache = null; }
            return internetFailure(target, network, lease, evidence, valid);
        }
        @Override public synchronized void close() {
            if (closed) return; closed = true; if (lease != null) lease.close();
        }
    }

    private String internetFailure(URI target, Network selected, CandidateFeed lease, JSONObject evidence, LlmClient.RequestValidity valid) {
        if (!NetworkRouting.current(valid)) return "请求已停止";
        List<Network> networks = new ArrayList<Network>(); networks.add(null);
        if (!mustKeepSystem(target) && !hasProxy(URI.create(BAIDU))) {
            if (selected != null) networks.add(selected);
            if (lease != null) for (Network network : lease.networks()) if (!networks.contains(network)) networks.add(network);
        }
        List<InternetReachability.Probe> probes = new ArrayList<InternetReachability.Probe>();
        // At most one public HEAD per allowed network; never credentials, input or model POST.
        for (final Network network : networks) {
            if (probes.size() >= 3) break;
            OkHttpClient.Builder builder = tlsPolicy.newBuilder().connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS).writeTimeout(2, TimeUnit.SECONDS).callTimeout(2500, TimeUnit.MILLISECONDS)
                    .connectionPool(new okhttp3.ConnectionPool()).dispatcher(new okhttp3.Dispatcher())
                    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false);
            if (network != null && !mustKeepSystem(target)) builder.socketFactory(network.getSocketFactory()).dns(
                    hostname -> Arrays.asList(network.getAllByName(hostname)));
            final OkHttpClient probe = builder.build();
            final okhttp3.Call call = probe.newCall(new Request.Builder().url(BAIDU).head().build());
            probes.add(new InternetReachability.Probe() {
                @Override public String id() { return label(network); }
                @Override public int status() throws IOException {
                    try (Response response = call.execute()) { return response.code(); }
                }
                @Override public void close() {
                    call.cancel(); probe.connectionPool().evictAll(); probe.dispatcher().executorService().shutdown();
                }
            });
        }
        InternetReachability.Result result = InternetReachability.check(probes, valid, 2500L);
        java.util.Iterator<String> keys = result.diagnostic.keys();
        while (keys.hasNext()) { String key = keys.next(); put(evidence, key, result.diagnostic.opt(key)); }
        return result.cancelled ? "请求已停止" : result.reachable ? "网络可访问，但 AI 服务器连接失败"
                : "无法确认网络连通，请检查 WiFi 或流量";
    }

    private NetworkCapabilities capabilities(Network network) {
        if (network == null || connectivity == null) return null;
        try { return connectivity.getNetworkCapabilities(network); } catch (SecurityException denied) { return null; }
    }
    private boolean mustKeepSystem(URI endpoint) {
        if (connectivity == null || hasProxy(endpoint)) return true;
        try {
            NetworkCapabilities value = capabilities(connectivity.getActiveNetwork());
            return value == null || value.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !value.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        } catch (SecurityException denied) { return true; }
    }
    private boolean cellular(Network network) {
        NetworkCapabilities value = capabilities(network);
        return value != null && value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }
    private String label(Network network) {
        if (network == null) return "system";
        NetworkCapabilities value = capabilities(network);
        return value != null && value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular"
                : value != null && value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi" : "network";
    }
    private static boolean hasProxy(URI endpoint) {
        ProxySelector selector = ProxySelector.getDefault();
        if (selector == null) return false;
        try { for (Proxy proxy : selector.select(endpoint)) if (proxy != null && proxy.type() != Proxy.Type.DIRECT) return true; }
        catch (RuntimeException unknown) { return true; }
        return false;
    }
    private static int port(URI uri) { return uri.getPort() > 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }
    private static int remaining(long deadline) throws java.net.SocketTimeoutException {
        long milliseconds = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (milliseconds <= 0) throw new java.net.SocketTimeoutException("Connection selection deadline");
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, milliseconds));
    }
    private static JSONObject metadata(String mode, String network) {
        JSONObject value = new JSONObject(); put(value, "selection", mode); put(value, "selected_network", network); return value;
    }
    private static void put(JSONObject value, String key, Object text) {
        try { value.put(key, text); } catch (org.json.JSONException ignored) { }
    }
}
