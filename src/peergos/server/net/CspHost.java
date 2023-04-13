package peergos.server.net;

import java.util.*;

public class CspHost {

    public final Optional<String> protocol;
    public final String domain;
    public final Optional<Integer> port;
    private final String portSuffix;
    private final boolean isWildcard;

    public CspHost(Optional<String> protocol, String domain, Optional<Integer> port) {
        if (protocol.isPresent() && ! protocol.get().equals("http://") && ! protocol.get().equals("https://"))
            throw new IllegalStateException("Protocol must be http:// or https://");
        this.protocol = protocol;
        this.domain = domain;
        this.isWildcard = domain.equals("0.0.0.0") || domain.equals("[::]");
        if (port.isPresent() && port.map(p -> p < 0 || p >= 65536).get())
            throw new IllegalStateException("Invalid port " + port.get());
        this.port = port;
        this.portSuffix = port.map(p -> ":" + p).orElse("");
    }

    public CspHost(String protocol, String domain) {
        this(Optional.of(protocol), domain, Optional.empty());
    }

    public CspHost(String protocol, String domain, int port) {
        this(Optional.of(protocol), domain, Optional.of(port));
    }

    public static boolean isLocal(String host) {
        return host.startsWith("localhost");
    }

    public CspHost wildcard() {
        return new CspHost(protocol, "*." + domain, port);
    }

    public boolean validSubdomain(String reqHost) {
        if (! reqHost.contains("."))
            return false;
        if (! reqHost.endsWith(portSuffix))
            return false;
        if (isWildcard)
            return true;
        String reqDomain = reqHost.substring(reqHost.indexOf(".") + 1, reqHost.length() - portSuffix.length());
        return reqDomain.equals(domain);
    }


    public String getSubdomain(String reqHost) {
        if (! reqHost.contains(".") || ! reqHost.endsWith(portSuffix))
            return "";
        if (isWildcard)
            return reqHost.substring(0, reqHost.lastIndexOf("."));
        if (reqHost.equals(domain + portSuffix))
            return "";
        return reqHost.substring(0, reqHost.length() - (1 + domain.length() + portSuffix.length()));
    }

    public String host() {
        return domain + port.map(p -> ":" + p).orElse("");
    }

    @Override
    public String toString() {
        return protocol.orElse("") + domain + portSuffix;
    }
}
