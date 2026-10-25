package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.util.Logging;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.messaging.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;

/** This is the http endpoint for EphemeralSignals calls
 *
 */
public class EphemeralSignalHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();

    private final EphemeralSignals signals;
    private final boolean isPublicServer;

    public EphemeralSignalHandler(EphemeralSignals signals, boolean isPublicServer) {
        this.signals = signals;
        this.isPublicServer = isPublicServer;
    }

    public void handle(HttpExchange exchange) {
        long t1 = System.currentTimeMillis();
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/"))
            path = path.substring(1);
        String method = path.substring(Constants.EPHEMERAL_URL.length());

        Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
        Function<String, String> last = key -> params.get(key).get(params.get(key).size() - 1);

        try {
            if (! HttpUtil.allowedQuery(exchange, isPublicServer)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }
            String username = last.apply("username");
            Cborable result;
            switch (method) {
                case "post": {
                    byte[] signedPost = Serialize.readFully(exchange.getRequestBody(), 4 * 1024);
                    result = new CborObject.CborBoolean(signals.post(username, signedPost).join());
                    break;
                }
                case "poll": {
                    byte[] channel = ArrayOps.hexToBytes(last.apply("channel"));
                    long since = Long.parseLong(last.apply("since"));
                    int wait = Integer.parseInt(last.apply("wait"));
                    byte[] auth = ArrayOps.hexToBytes(last.apply("auth"));
                    result = signals.poll(username, channel, since, wait, auth).join();
                    break;
                }
                default:
                    throw new IOException("Unknown method in EphemeralSignalHandler!");
            }

            byte[] b = result.serialize();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
        } catch (Exception e) {
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
            long t2 = System.currentTimeMillis();
            if (! method.equals("poll"))
                LOG.info("Ephemeral signal handled " + method + " request in: " + (t2 - t1) + " mS");
        }
    }
}
