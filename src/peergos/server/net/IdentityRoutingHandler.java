package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.logging.*;
import java.util.stream.*;

/** The http endpoint for IdentityRouting calls from clients hosted here. */
public class IdentityRoutingHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();
    private static final int MAX_BODY = 64 * 1024;

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
                    result = routing.status(identity(params)).join();
                    break;
                case "records":
                    result = new CborObject.CborList(routing.records(identity(params)).join().stream()
                            .map(RecordType::tag)
                            .collect(Collectors.toList()));
                    break;
                case "commit": {
                    CborObject.CborMap m = (CborObject.CborMap) body(exchange);
                    result = new CborObject.CborBoolean(routing.commit(IdentityInception.fromCbor(m.get("r")),
                            RecoveryCopy.WrappedKey.fromCbor(m.get("w")), RecoveryCopy.fromCbor(m.get("c"))).join());
                    break;
                }
                case "copy":
                    result = new CborObject.CborBoolean(routing.addRecoveryCopy(identity(params),
                            Serialize.readFully(exchange.getRequestBody(), MAX_BODY)).join());
                    break;
                case "recovery-data": {
                    CborObject.CborMap m = (CborObject.CborMap) body(exchange);
                    result = routing.recoveryData(identity(params), PublicSigningKey.fromCbor(m.get("k")), m.getByteArray("s")).join();
                    break;
                }
                case "recover": {
                    CborObject.CborMap m = (CborObject.CborMap) body(exchange);
                    result = new CborObject.CborBoolean(routing.recover(identity(params), IdentityRotation.fromCbor(m.get("r")),
                            RecoveryCopy.WrappedKey.fromCbor(m.get("w")), RecoveryCopy.fromCbor(m.get("c"))).join());
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

    private static PublicKeyHash identity(Map<String, List<String>> params) {
        return PublicKeyHash.fromString(params.get("identity").get(0));
    }

    private static Cborable body(HttpExchange exchange) throws IOException {
        return CborObject.fromByteArray(Serialize.readFully(exchange.getRequestBody(), MAX_BODY));
    }
}
