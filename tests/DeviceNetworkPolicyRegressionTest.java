import com.mkei.backcast.agent.NetworkRouting;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.net.SocketFactory;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import okhttp3.OkHttpClient;
import org.json.JSONObject;

/** Executes the production Android provider with injectable framework snapshots, not a device emulator. */
public final class DeviceNetworkPolicyRegressionTest {
    private static int passed;
    private static final class Source extends SimpleJavaFileObject {
        final String text;
        Source(String name, String text) { super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE); this.text = text; }
        @Override public CharSequence getCharContent(boolean ignored) { return text; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }
    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static Object call(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
    }
    private static URLClassLoader compile(Path root, Path output) throws Exception {
        List<JavaFileObject> sources = new ArrayList<JavaFileObject>();
        sources.add(new Source("android.content.Context", "package android.content; public class Context {public static final String CONNECTIVITY_SERVICE=\"connectivity\";"
                + "public Object service;public Context(Object v){service=v;}public Context getApplicationContext(){return this;}public Object getSystemService(String name){return service;}}"));
        sources.add(new Source("android.net.NetworkCapabilities", "package android.net;public class NetworkCapabilities {"
                + "public static final int TRANSPORT_CELLULAR=0,TRANSPORT_WIFI=1,TRANSPORT_VPN=4,NET_CAPABILITY_INTERNET=12,NET_CAPABILITY_NOT_VPN=15;"
                + "public int transport;public NetworkCapabilities(int t){transport=t;}public boolean hasTransport(int t){return transport==t;}"
                + "public boolean hasCapability(int c){return c!=NET_CAPABILITY_NOT_VPN||transport!=TRANSPORT_VPN;}}"));
        sources.add(new Source("android.net.Network", "package android.net;public class Network {public String name;public NetworkCapabilities capabilities;public boolean firstFails;public int attempts,firstTimeout;"
                + "private javax.net.SocketFactory factory=new javax.net.SocketFactory(){public java.net.Socket createSocket()throws java.io.IOException{return new java.net.Socket(){"
                + "public void connect(java.net.SocketAddress address,int timeout)throws java.io.IOException{if(firstFails&&attempts++==0){firstTimeout=timeout;try{Thread.sleep(timeout);}catch(InterruptedException x){throw new java.io.InterruptedIOException();}throw new java.net.SocketTimeoutException();}"
                + "super.connect(new java.net.InetSocketAddress(\"127.0.0.1\",((java.net.InetSocketAddress)address).getPort()),timeout);}};}"
                + "public java.net.Socket createSocket(String h,int p)throws java.io.IOException{return new java.net.Socket(h,p);}"
                + "public java.net.Socket createSocket(String h,int p,java.net.InetAddress l,int q)throws java.io.IOException{return new java.net.Socket(h,p,l,q);}"
                + "public java.net.Socket createSocket(java.net.InetAddress h,int p)throws java.io.IOException{return new java.net.Socket(h,p);}"
                + "public java.net.Socket createSocket(java.net.InetAddress h,int p,java.net.InetAddress l,int q)throws java.io.IOException{return new java.net.Socket(h,p,l,q);}};"
                + "public Network(String n,int t){name=n;capabilities=new NetworkCapabilities(t);}public javax.net.SocketFactory getSocketFactory(){return factory;}"
                + "public java.net.InetAddress[]getAllByName(String h)throws java.net.UnknownHostException{return firstFails?new java.net.InetAddress[]{java.net.InetAddress.getByName(\"::1\"),java.net.InetAddress.getByName(\"127.0.0.1\")}:java.net.InetAddress.getAllByName(h);}public String toString(){return name;}}"));
        sources.add(new Source("android.net.NetworkRequest", "package android.net;public class NetworkRequest {public static class Builder {"
                + "public Builder addTransportType(int t){return this;}public Builder addCapability(int c){return this;}public NetworkRequest build(){return new NetworkRequest();}}}"));
        sources.add(new Source("android.net.ConnectivityManager", "package android.net;public class ConnectivityManager {public Network active;"
                + "public int requests,releases,enumerations;public NetworkCallback callback;public ConnectivityManager(Network n){active=n;}"
                + "public Network getActiveNetwork(){return active;}public NetworkCapabilities getNetworkCapabilities(Network n){return n==null?null:n.capabilities;}"
                + "public Network[]getAllNetworks(){enumerations++;return new Network[0];}public void requestNetwork(NetworkRequest r,NetworkCallback c,int timeout){requests++;callback=c;}"
                + "public void unregisterNetworkCallback(NetworkCallback c){releases++;}public static class NetworkCallback{public void onAvailable(Network n){}public void onUnavailable(){}}}"));
        try (StandardJavaFileManager manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            for (JavaFileObject file : manager.getJavaFileObjects(root.resolve("app/src/main/java/com/mkei/backcast/net/DeviceNetworks.java").toFile())) sources.add(file);
            check(ToolProvider.getSystemJavaCompiler().getTask(null, manager, null,
                    Arrays.asList("-proc:none", "-source", "8", "-target", "8", "-Xlint:-options", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                    null, sources).call(), "Production Android network provider did not compile against the framework fixture");
        }
        return new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, DeviceNetworkPolicyRegressionTest.class.getClassLoader());
    }
    private static final class Fixture {
        final Class<?> network, connectivity, context, providerType;
        final Object wifi, cell, vpn, manager, provider;
        Fixture(ClassLoader loader) throws Exception {
            network=loader.loadClass("android.net.Network");connectivity=loader.loadClass("android.net.ConnectivityManager");context=loader.loadClass("android.content.Context");
            providerType=loader.loadClass("com.mkei.backcast.net.DeviceNetworks");
            wifi=network.getConstructor(String.class,int.class).newInstance("wifi-fixture",1);
            cell=network.getConstructor(String.class,int.class).newInstance("cell-fixture",0);
            vpn=network.getConstructor(String.class,int.class).newInstance("vpn-fixture",4);
            manager=connectivity.getConstructor(network).newInstance(wifi);
            provider=providerType.getConstructor(context).newInstance(context.getConstructor(Object.class).newInstance(manager));
        }
        Object feed(URI target) throws Exception {
            Class<?> type=providerType.getClassLoader().loadClass("com.mkei.backcast.net.DeviceNetworks$CandidateFeed");
            Constructor<?> constructor=type.getDeclaredConstructor(providerType,URI.class,boolean.class);constructor.setAccessible(true);
            return constructor.newInstance(provider,target,false);
        }
        NetworkRouting.Route selected(URI target,Object feed) throws Exception {
            return selected(target,feed,null);
        }
        NetworkRouting.Route selected(URI target,Object feed,InetAddress preferred) throws Exception {
            Class<?> type=providerType.getClassLoader().loadClass("com.mkei.backcast.net.DeviceNetworks$Selected");
            Constructor<?> constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
            return (NetworkRouting.Route)constructor.newInstance(provider,target,cell,preferred,feed,new JSONObject());
        }
    }
    private static void vpnStartedAfterSelectionDropsPhysicalBindingAndReleasesCellular(ClassLoader loader) throws Exception {
        Fixture fixture=new Fixture(loader);URI endpoint=URI.create("https://fixture.invalid/v1");
        Object lease=fixture.feed(endpoint);NetworkRouting.Route route=fixture.selected(endpoint,lease);
        SocketFactory physical=(SocketFactory)call(fixture.cell,"getSocketFactory");
        OkHttpClient.Builder builder=new OkHttpClient.Builder();route.configure(builder);
        check(builder.build().socketFactory()==physical && (Integer)get(fixture.manager,"releases")==0,
                "Selected network was not bound or its cellular request was released before streaming");
        set(fixture.manager,"active",fixture.vpn);builder=new OkHttpClient.Builder();route.configure(builder);
        check(builder.build().socketFactory()!=physical && (Integer)get(fixture.manager,"releases")==1
                        && route.diagnostic().getString("selection").equals("system_changed"),
                "A newly enabled VPN left the request bound to a physical network");
        Object callback=get(fixture.manager,"callback");
        var onAvailable=callback.getClass().getDeclaredMethod("onAvailable",fixture.network);onAvailable.setAccessible(true);onAvailable.invoke(callback,fixture.cell);
        check(((List<?>)call(lease,"networks")).isEmpty(),"A late modem callback revived a released cellular request");
        route.close();check((Integer)get(fixture.manager,"releases")==1,"Route close double-released a finished callback");
        pass("vpnStartedAfterSelectionDropsPhysicalBindingAndReleasesCellular");
    }
    private static void anAlreadyActiveVpnNeverEnumeratesOrRequestsPhysicalNetworks(ClassLoader loader) throws Exception {
        Fixture fixture=new Fixture(loader);set(fixture.manager,"active",fixture.vpn);
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            try(NetworkRouting.Route route=((NetworkRouting.Provider)fixture.provider).open("http://127.0.0.1:"+server.getLocalPort(),null)) {
                check((Integer)get(fixture.manager,"requests")==0 && (Integer)get(fixture.manager,"enumerations")==0
                                && route.diagnostic().getBoolean("vpn_preserved"),"VPN route enumerated or activated an underlying network");
            }
            try(Socket accepted=server.accept()) {accepted.setSoTimeout(1000);check(accepted.getInputStream().read()==-1,"VPN preflight sent HTTP bytes");}
        }
        pass("anAlreadyActiveVpnNeverEnumeratesOrRequestsPhysicalNetworks");
    }
    private static void cellularLeaseStaysUntilStreamingRouteCloses(ClassLoader loader) throws Exception {
        Fixture fixture=new Fixture(loader);URI target=URI.create("https://fixture.invalid/v1");Object lease=fixture.feed(target);
        NetworkRouting.Route route=fixture.selected(target,lease);route.configure(new OkHttpClient.Builder());
        check((Integer)get(fixture.manager,"requests")==1 && (Integer)get(fixture.manager,"releases")==0,
                "Cellular activation was not retained across the chosen streaming route");
        route.close();route.close();check((Integer)get(fixture.manager,"releases")==1,"Route close leaked or double-unregistered the cellular callback");
        pass("cellularLeaseStaysUntilStreamingRouteCloses");
    }
    private static void deadFirstAddressLeavesBudgetForTheSameNetworksIpv4(ClassLoader loader) throws Exception {
        Fixture fixture=new Fixture(loader);set(fixture.cell,"firstFails",true);
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            Class<?> probeType=loader.loadClass("com.mkei.backcast.net.DeviceNetworks$ConnectionProbe");
            Constructor<?> constructor=probeType.getDeclaredConstructor(fixture.providerType,URI.class,fixture.network);constructor.setAccessible(true);
            Object probe=constructor.newInstance(fixture.provider,URI.create("http://fixture.invalid:"+server.getLocalPort()),fixture.cell);
            var connect=probeType.getDeclaredMethod("connect",long.class);connect.setAccessible(true);
            long started=System.nanoTime();connect.invoke(probe,started+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(600L));
            call(probe,"close");
            check((Integer)get(fixture.cell,"attempts")==2 && (Integer)get(fixture.cell,"firstTimeout")<=300
                            && java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<600L,
                    "First IPv6 address consumed the entire shared budget before IPv4 was attempted");
            try(Socket accepted=server.accept()) {accepted.setSoTimeout(1000);check(accepted.getInputStream().read()==-1,"Address fallback sent HTTP bytes");}
        }
        pass("deadFirstAddressLeavesBudgetForTheSameNetworksIpv4");
    }
    private static void actualRequestDnsPrioritizesTheValidatedAddressOnlyForTheAiHost(ClassLoader loader) throws Exception {
        Fixture fixture=new Fixture(loader);set(fixture.cell,"firstFails",true);
        InetAddress ipv4=InetAddress.getByName("127.0.0.1");
        try(NetworkRouting.Route route=fixture.selected(URI.create("https://fixture.invalid/v1"),null,ipv4)) {
            OkHttpClient.Builder builder=new OkHttpClient.Builder();route.configure(builder);OkHttpClient client=builder.build();
            List<InetAddress> server=client.dns().lookup("fixture.invalid"),other=client.dns().lookup("other.invalid");
            check(server.size()==2 && server.get(0).equals(ipv4) && !server.get(1).equals(ipv4)
                            && !other.get(0).equals(ipv4) && other.get(1).equals(ipv4),
                    "Single real request retried a dead first address or changed DNS ordering for unrelated hosts");
        }
        pass("actualRequestDnsPrioritizesTheValidatedAddressOnlyForTheAiHost");
    }
    public static void main(String[] args) throws Exception {
        Path output=Files.createTempDirectory("backcast-device-network-policy-");
        try(URLClassLoader loader=compile(Paths.get(args[0]),output)) {
            vpnStartedAfterSelectionDropsPhysicalBindingAndReleasesCellular(loader);
            anAlreadyActiveVpnNeverEnumeratesOrRequestsPhysicalNetworks(loader);
            cellularLeaseStaysUntilStreamingRouteCloses(loader);
            deadFirstAddressLeavesBudgetForTheSameNetworksIpv4(loader);
            actualRequestDnsPrioritizesTheValidatedAddressOnlyForTheAiHost(loader);
            System.out.println(passed+" Android network policy fixture tests passed");
        } finally {try(var paths=Files.walk(output)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
