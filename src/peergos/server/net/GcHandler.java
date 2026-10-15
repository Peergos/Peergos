package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.charset.*;
import java.util.logging.*;

public class GcHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();

    private final GarbageCollector gc;

    public GcHandler(GarbageCollector gc) {
        this.gc = gc;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (gc == null) {
                // without enable-gc writes aren't tracked in transactions, so a concurrent gc could delete them
                reply(exchange, 409, "GC is not enabled on the running server. Stop it to run gc, or restart it with -enable-gc true");
                return;
            }
            boolean ran = gc.collectOrAwait(s -> Futures.of(true));
            reply(exchange, 200, ran ? "GC complete" : "Waited for the GC that was already running to complete");
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            reply(exchange, 500, "GC failed: " + e.getMessage());
        }
    }

    private static void reply(HttpExchange exchange, int code, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
