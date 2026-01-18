package peergos.server.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class PinnedHost {
    final String host;
    volatile InetAddress[] addresses;
    final AtomicInteger rr = new AtomicInteger();
    long lastRefresh;

    PinnedHost(String host) throws IOException {
        this.host = host;
        this.addresses = getReachableIPs();
        if (addresses.length == 0)
            throw new IllegalStateException("Couldn't reach any addresses from " + Arrays.asList(addresses));

        this.lastRefresh = System.nanoTime();
    }

    private InetAddress[] getReachableIPs() throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        List<InetAddress> reachable = new ArrayList<>();

        for (InetAddress address : all) {
            if (isReachable(address.getHostAddress(), 443, 1_000))
                reachable.add(address);
        }
        Logging.LOG().info("Reachable IPs for " + host + ": " + reachable);
        return reachable.toArray(InetAddress[]::new);
    }

    InetAddress next() {
        InetAddress[] a = addresses;
        return a[Math.floorMod(rr.getAndIncrement(), a.length)];
    }

    void ensureRefreshed() throws IOException {
            if (System.nanoTime() - lastRefresh > 60*60*1000_000_000L)
                refresh();
    }

    void refresh() throws IOException {
        this.addresses = getReachableIPs();
        if (addresses.length == 0)
            throw new IllegalStateException("Couldn't reach any addresses for " + host);
        this.lastRefresh = System.nanoTime();
    }

    private static boolean isReachable(String addr, int openPort, int timeOutMillis) {
        try (Socket soc = new Socket()) {
            soc.connect(new InetSocketAddress(addr, openPort), timeOutMillis);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
