package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

/** This is the http endpoint for getting and setting encrypted login blobs
 *
 */
public class AccountHandler implements HttpHandler {

    private final Account account;
    private final boolean isPublicServer;

    public AccountHandler(Account account, boolean isPublicServer) {
        this.account = account;
        this.isPublicServer = isPublicServer;
    }

    public void handle(HttpExchange exchange) throws IOException {
        DataInputStream din = new DataInputStream(exchange.getRequestBody());

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);

        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/"))
            path = path.substring(1);
        String[] subComponents = path.substring(Constants.LOGIN_URL.length()).split("/");
        String method = subComponents[0];

        Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
        byte[] auth = ArrayOps.hexToBytes(params.get("auth").get(0));
        try {
            if (! HttpUtil.allowedQuery(exchange, isPublicServer)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }

            switch (method) {
                case "setLogin":
                    AggregatedMetrics.LOGIN_SET.inc();
                    byte[] payload = Serialize.readFully(din, 1024);
                    boolean isAdded = account.setLoginData(LoginData.fromCbor(CborObject.fromByteArray(payload)), auth).join();
                    dout.writeBoolean(isAdded);
                    break;
                case "getLogin":
                    AggregatedMetrics.LOGIN_GET.inc();
                    String username = params.get("username").get(0);
                    PublicSigningKey authorisedReader = PublicSigningKey.fromByteArray(ArrayOps.hexToBytes(params.get("author").get(0)));
                    byte[] res = account.getLoginData(username, authorisedReader, auth).join().serialize();
                    dout.write(res);
                    break;
                default:
                    throw new IOException("Unknown method in AccountHandler!");
            }

            byte[] b = bout.toByteArray();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
        } catch (Exception e) {
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
        }
    }
}
