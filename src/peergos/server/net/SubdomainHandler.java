package peergos.server.net;

import com.sun.net.httpserver.*;

import java.io.*;
import java.util.*;

public class SubdomainHandler implements HttpHandler
{
    private final String domain;
    private final HttpHandler handler;
    private final boolean allowSubdomains;

    public SubdomainHandler(String domain, HttpHandler handler, boolean allowSubdomains) {
        this.domain = domain;
        this.handler = handler;
        this.allowSubdomains = allowSubdomains;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        List<String> hostHeaders = exchange.getRequestHeaders().get("Host");
        if (hostHeaders.isEmpty() || (hostHeaders.size() == 1 && hostHeaders.get(0).equals(domain))) {
            handler.handle(exchange);
        } else if (allowSubdomains && hostHeaders.size() == 1 && hostHeaders.get(0).endsWith(domain)) {
            handler.handle(exchange);
        } else {
            System.out.println("Subdomain err: " + hostHeaders + " doesn't match " + domain);
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
        }
    }
}
