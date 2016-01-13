package peergos.server.net;

import com.sun.net.httpserver.*;

import java.io.*;

public class RedirectHandler implements HttpHandler
{
    private final String target;

    public RedirectHandler(String target) {
        this.target = target;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Location", target);
        exchange.sendResponseHeaders(301, 0);
        exchange.close();
    }
}
