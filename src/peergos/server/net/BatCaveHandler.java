package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

/** This is the http endpoint for storing Block Access Tokens (BATs)
 *
 */
public class BatCaveHandler implements HttpHandler {

    private final BatCave bats;
    private final CoreNode pki;
    private final ContentAddressedStorage ipfs;
    private final boolean isPublicServer;

    public BatCaveHandler(BatCave bats, CoreNode pki, ContentAddressedStorage ipfs, boolean isPublicServer) {
        this.bats = bats;
        this.pki = pki;
        this.ipfs = ipfs;
        this.isPublicServer = isPublicServer;
    }

    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/"))
            path = path.substring(1);
        String[] subComponents = path.substring(Constants.BATS_URL.length()).split("/");
        String method = subComponents[0];

        Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
        Function<String, String> last = key -> params.get(key).get(params.get(key).size() - 1);
        byte[] auth = ArrayOps.hexToBytes(last.apply("auth"));
        String username = last.apply("username");
        try {
            if (! HttpUtil.allowedQuery(exchange, isPublicServer)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }

            Cborable result;
            switch (method) {
                case "addBat":
                    AggregatedMetrics.BAT_ADD.inc();
                    TimeLimited.isAllowed(path, auth, 300, ipfs, pki.getPublicKeyHash(username).join().get());
                    BatId batid = new BatId(Cid.decode(last.apply("batid")));
                    Bat bat = Bat.fromString(last.apply("bat"));
                    bats.addBat(username, batid, bat, auth);
                    result = new CborObject.CborBoolean(true);
                    break;
                case "getUserBats":
                    AggregatedMetrics.BATS_GET.inc();
                    TimeLimited.isAllowed(path, auth, 300, ipfs, pki.getPublicKeyHash(username).join().get());
                    List<BatWithId> userBats = bats.getUserBats(username, auth).join();
                    result = new CborObject.CborList(userBats);
                    break;
                default:
                    throw new IOException("Unknown method in BatsHandler!");
            }

            byte[] b = result.serialize();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
        } catch (Exception e) {
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
        }
    }
}
