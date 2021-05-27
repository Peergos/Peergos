package peergos.server.net;

import com.sun.net.httpserver.*;

import java.io.*;
import java.util.*;

public class SubdomainHandler implements HttpHandler
{
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
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
        }
    }
}
