package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.logging.*;

/** Runs a gc for a caller that can read the token file, which a reverse proxy on this machine can't forward for */
public class GcHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();
    public static final String TOKEN_HEADER = "X-Peergos-Gc-Token";

    private final GarbageCollector gc;
    private final PartitionStatus partitionStatus;
    private final byte[] token;

    public GcHandler(GarbageCollector gc, PartitionStatus partitionStatus, Path tokenFile) throws IOException {
        this.gc = gc;
        this.partitionStatus = partitionStatus;
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String encoded = ArrayOps.bytesToHex(raw);
        this.token = encoded.getBytes(StandardCharsets.UTF_8);
        LocalApiAuth.writeOwnerOnly(tokenFile, encoded);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            List<String> supplied = exchange.getRequestHeaders().get(TOKEN_HEADER);
            if (supplied == null || supplied.size() != 1 ||
                    ! MessageDigest.isEqual(token, supplied.get(0).getBytes(StandardCharsets.UTF_8))) {
                reply(exchange, 403, "Invalid gc token");
                return;
            }
            if (gc == null) {
                reply(exchange, 409, "This server's S3 blockstore is garbage collected separately");
                return;
            }
            if (! partitionStatus.isDone()) {
                reply(exchange, 409, "The blockstore is still being partitioned, try again when it has finished");
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
