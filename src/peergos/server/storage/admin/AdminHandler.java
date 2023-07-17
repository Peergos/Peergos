package peergos.server.storage.admin;

import com.sun.net.httpserver.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.controller.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

public class AdminHandler implements HttpHandler {

    private final InstanceAdmin target;
    private final boolean isPublicServer;

    public AdminHandler(InstanceAdmin target, boolean isPublicServer) {
        this.target = target;
        this.isPublicServer = isPublicServer;
    }

    public void handle(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/"))
            path = path.substring(1);
        String[] subComponents = path.substring(Constants.ADMIN_URL.length()).split("/");
        String method = subComponents[0];

        Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
        Function<String, String> last = key -> params.get(key).get(params.get(key).size() - 1);

        Cborable reply;
        try {
            if (! HttpUtil.allowedQuery(exchange, isPublicServer)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }

            switch (method) {
                case HttpInstanceAdmin.VERSION:
                    InstanceAdmin.VersionInfo res = target.getVersionInfo().join();
                    reply = res.toCbor();
                    break;
                case HttpInstanceAdmin.PENDING: {
                    PublicKeyHash admin = PublicKeyHash.fromString(params.get("admin").get(0));
                    Multihash instance = Cid.decode(params.get("instance").get(0));
                    byte[] signedTime = ArrayOps.hexToBytes(last.apply("auth"));
                    List<SpaceUsage.LabelledSignedSpaceRequest> pending = target
                            .getPendingSpaceRequests(admin, instance, signedTime).join();
                    reply = new CborObject.CborList(pending);
                    break;
                }
                case HttpInstanceAdmin.APPROVE: {
                    PublicKeyHash admin = PublicKeyHash.fromString(params.get("admin").get(0));
                    Multihash instance = Cid.decode(params.get("instance").get(0));
                    byte[] signedReq = ArrayOps.hexToBytes(last.apply("req"));
                    boolean result = target.approveSpaceRequest(admin, instance, signedReq).join();
                    reply = new CborObject.CborBoolean(result);
                    break;
                }
                case HttpInstanceAdmin.WAIT_LIST: {
                    String email = params.get("email").get(0);
                    boolean result = target.addToWaitList(email).join();
                    reply = new CborObject.CborBoolean(result);
                    break;
                }
                case HttpInstanceAdmin.SIGNUPS: {
                    reply = target.acceptingSignups().join();
                    break;
                }
                default:
                    throw new IOException("Unknown method in admin handler!");
            }

            byte[] res = reply.serialize();
            exchange.sendResponseHeaders(200, res.length);
            exchange.getResponseBody().write(res);
        } catch (Exception e) {
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
        }
    }
}
