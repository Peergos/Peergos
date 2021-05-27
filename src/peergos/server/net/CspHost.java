package peergos.server.net;

import java.util.*;

public class CspHost {

    public final Optional<String> protocol;
    public final String domain;
    public final Optional<Integer> port;

    public CspHost(Optional<String> protocol, String domain, Optional<Integer> port) {
        if (protocol.isPresent() && ! protocol.get().equals("http://") && ! protocol.get().equals("https://"))
            throw new IllegalStateException("Protocol must be http:// or https://");
        this.protocol = protocol;
        this.domain = domain;
        if (port.isPresent() && port.map(p -> p < 0 || p >= 65536).get())
            throw new IllegalStateException("Invalid port " + port.get());
        this.port = port;
    }

    public CspHost(String protocol, String domain) {
        this(Optional.of(protocol), domain, Optional.empty());
    }

    public CspHost(String protocol, String domain, int port) {
        this(Optional.of(protocol), domain, Optional.of(port));
    }

    public CspHost wildcard() {
        return new CspHost(protocol, "*." + domain, port);
    }

    public String host() {
        return domain + port.map(p -> ":" + p).orElse("");
    }

    @Override
    public String toString() {
        return protocol.orElse("") + domain + port.map(p -> ":" + p).orElse("");
    }
}
