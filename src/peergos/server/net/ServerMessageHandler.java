package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.messages.*;
import peergos.server.util.Logging;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

/** This is the http endpoint for ServerMessage calls
 *
 */
public class ServerMessageHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();

    private final ServerMessager store;
    private final CoreNode pki;
    private final ContentAddressedStorage ipfs;
    private final boolean isPublicServer;

    public ServerMessageHandler(ServerMessager store, CoreNode pki, ContentAddressedStorage ipfs, boolean isPublicServer) {
        this.store = store;
        this.pki = pki;
        this.ipfs = ipfs;
        this.isPublicServer = isPublicServer;
    }

    public void handle(HttpExchange exchange)
    {
        long t1 = System.currentTimeMillis();
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/"))
            path = path.substring(1);
        String[] subComponents = path.substring(Constants.SERVER_MESSAGE_URL.length()).split("/");
        String method = subComponents[0];

        Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
        Function<String, String> last = key -> params.get(key).get(params.get(key).size() - 1);
        String username = params.get("username").get(0);

        Cborable result;
        try {
            if (! HttpUtil.allowedQuery(exchange, isPublicServer)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }

            switch (method) {
                case "retrieve": {
                    byte[] auth = ArrayOps.hexToBytes(last.apply("auth"));
                    TimeLimited.isAllowed(path, auth, 300, ipfs, pki.getPublicKeyHash(username).join().get());
                    result = new CborObject.CborList(store.getMessages(username, auth).join()
                            .stream()
                            .map(Cborable::toCbor)
                            .collect(Collectors.toList()));
                    break;
                }
                case "send": {
                    byte[] signedReq = Serialize.readFully(exchange.getRequestBody(), 10*1024);
                    store.sendMessage(username, signedReq).join();
                    result = new CborObject.CborBoolean(true);
                    break;
                }
                default:
                    throw new IOException("Unknown method in ServerMessageHandler!");
            }

            byte[] b = result.serialize();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
        } catch (Exception e) {
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
            long t2 = System.currentTimeMillis();
            LOG.info("ServerMessage handled " + method + " request in: " + (t2 - t1) + " mS");
        }
    }
}
