package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.logging.*;

/** The http endpoint for IdentityRouting calls from clients hosted here. */
public class IdentityRoutingHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();
    private static final int MAX_INCEPTION_SIZE = 16 * 1024;

    private final IdentityRouting routing;
    private final boolean isPublicServer;

    public IdentityRoutingHandler(IdentityRouting routing, boolean isPublicServer) {
        this.routing = routing;
        this.isPublicServer = isPublicServer;
    }

    public void handle(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/"))
            path = path.substring(1);
        String method = path.substring(Constants.IDENTITY_ROUTING_URL.length());
        Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
        try {
            if (! HttpUtil.allowedQuery(exchange, isPublicServer)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }
            Cborable result;
            switch (method) {
                case "status":
                    result = routing.status(PublicKeyHash.fromString(params.get("identity").get(0))).join();
                    break;
                case "commit": {
                    byte[] raw = Serialize.readFully(exchange.getRequestBody(), MAX_INCEPTION_SIZE);
                    IdentityInception inception = IdentityInception.fromCbor(CborObject.fromByteArray(raw));
                    result = new CborObject.CborBoolean(routing.commit(inception).join());
                    break;
                }
                default:
                    throw new IOException("Unknown method in IdentityRoutingHandler!");
            }
            byte[] b = result.serialize();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Identity routing " + method + " failed: " + e.getMessage(), e);
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
        }
    }
}
