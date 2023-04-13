package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;
import java.util.regex.*;
import java.util.stream.*;

public class SubdomainHandler implements HttpHandler
{
    private static final Logger LOG = Logging.LOG();
    private final List<String> domains;
    private final HttpHandler handler;
    private final boolean allowSubdomains, allowAnyIp4, allowAnyIp6;
    private final Optional<Pattern> IP4Wildcard;
    private final int ipv6PortSuffixSize;

    public SubdomainHandler(List<String> domains, HttpHandler handler, boolean allowSubdomains) {
        this.handler = handler;
        this.allowSubdomains = allowSubdomains;
        Optional<String> allIp4s = domains.stream().filter(d -> d.startsWith("0.0.0.0")).findAny();
        this.allowAnyIp4 = allIp4s.isPresent();
        IP4Wildcard = allIp4s.map(d -> Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+"+d.substring(7)));
        Optional<String> allIp6s = domains.stream().filter(d -> d.startsWith("[::]")).findAny();
        this.allowAnyIp6 = allIp6s.isPresent();
        this.ipv6PortSuffixSize = allIp6s.map(d -> d.substring(4).length()).orElse(0);
        this.domains = allowAnyIp4 ?
                Stream.concat(domains.stream(), Stream.of("localhost" + allIp4s.get().substring(7))).collect(Collectors.toList()) :
                domains;
    }

    private boolean isIPv6(String ip) {
        try {
            if (! ip.substring(0, ip.length() - ipv6PortSuffixSize).contains(":")) // try to avoid DNS lookups
                return false;
            return Inet6Address.getByName(ip) instanceof Inet6Address;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        List<String> hostHeaders = exchange.getRequestHeaders().get("Host");
        if (hostHeaders.isEmpty() || (hostHeaders.size() == 1 &&
                (domains.contains(hostHeaders.get(0))
                        || (allowAnyIp4 && IP4Wildcard.get().matcher(hostHeaders.get(0)).matches())
                        || (allowAnyIp6 && isIPv6(hostHeaders.get(0)))
                ))) {
            handler.handle(exchange);
        } else if (allowSubdomains && hostHeaders.size() == 1 &&
                domains.stream().anyMatch(d -> hostHeaders.get(0).endsWith(d))) {
            handler.handle(exchange);
        } else {
            LOG.severe("Subdomain access blocked: " + hostHeaders + " not in " + String.join(",", domains));
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
        }
    }
}
