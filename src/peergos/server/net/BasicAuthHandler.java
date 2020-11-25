package peergos.server.net;

import com.sun.net.httpserver.*;

import java.io.*;
import java.util.*;

public class BasicAuthHandler implements HttpHandler
{
    private final String auth;
    private final HttpHandler delegate;

    public BasicAuthHandler(String auth, HttpHandler delegate) {
        if (auth.split(":").length != 2)
            throw new IllegalStateException("Basic auth must be of form username:password");
        this.auth = "Basic " + Base64.getEncoder().encodeToString(auth.getBytes());
        this.delegate = delegate;
    }

    private boolean isAuthenticated(HttpExchange exchange) {
        List<String> authorization = exchange.getRequestHeaders().get("Authorization");
        if (authorization == null || authorization.isEmpty())
            return false;
        String authHeader = authorization.get(0).trim();
        return authHeader.equals(auth);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (isAuthenticated(exchange)) {
            delegate.handle(exchange);
        } else {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"Peergos server access\", charset=\"UTF-8\"");
            exchange.sendResponseHeaders(401, 0);
            exchange.close();
        }
    }
}
