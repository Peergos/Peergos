package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.util.*;

import java.io.*;
import java.util.*;
import java.util.logging.*;

public class SubdomainHandler implements HttpHandler
{
    private static final Logger LOG = Logging.LOG();
    private final List<String> domains;
    private final HttpHandler handler;
    private final boolean allowSubdomains;

    public SubdomainHandler(List<String> domains, HttpHandler handler, boolean allowSubdomains) {
        this.domains = domains;
        this.handler = handler;
        this.allowSubdomains = allowSubdomains;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        List<String> hostHeaders = exchange.getRequestHeaders().get("Host");
        if (hostHeaders.isEmpty() || (hostHeaders.size() == 1 &&
                domains.contains(hostHeaders.get(0)))) {
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
