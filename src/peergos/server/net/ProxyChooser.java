package peergos.server.net;

import peergos.server.util.Args;

import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.Optional;

public class ProxyChooser extends ProxySelector {
    private final List<Proxy> proxies;

    public ProxyChooser(Optional<SocketAddress> https, Proxy.Type type) {
        proxies = https.stream().map(a -> new Proxy(type, a)).toList();
    }

    @Override
    public List<Proxy> select(URI uri) {
        if (uri.getHost().equalsIgnoreCase("localhost"))
            return List.of(Proxy.NO_PROXY);
        if (proxies.isEmpty()) {
            return proxies;
        }
        return List.of(Proxy.NO_PROXY);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {}

    public static InetSocketAddress parseAddress(String addr) {
        String host = addr.substring(0, addr.indexOf(":"));
        int port = Integer.parseInt(addr.substring(addr.indexOf(":") + 1));
        return new InetSocketAddress(host, port);
    }

    public static Optional<ProxySelector> build(Args a) {
        boolean useHttpProxy = a.hasArg("http_proxy");
        Optional<ProxySelector> httpProxy = ! useHttpProxy ?
                Optional.empty() :
                Optional.of(new ProxyChooser(
                        useHttpProxy ?
                                Optional.of(parseAddress(a.getArg("http_proxy"))) :
                                Optional.empty(),
                        Proxy.Type.HTTP
                ));
        boolean useSocksProxy = a.hasArg("socks_proxy");
        if (useSocksProxy && useHttpProxy)
            throw new IllegalStateException("Cannot use socks proxy at same time as http proxy!");
        Optional<ProxySelector> socksProxy = ! useSocksProxy ?
                Optional.empty() :
                Optional.of(new ProxyChooser(
                        Optional.of(parseAddress(a.getArg("socks_proxy"))),
                        Proxy.Type.SOCKS
                ));
        return httpProxy.isPresent() ? httpProxy : socksProxy;
    }
}
