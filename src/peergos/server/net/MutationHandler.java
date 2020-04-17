package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.logging.*;

/** This is the http endpoint for MutablePointer calls
 *
 */
public class MutationHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();

    private final MutablePointers mutable;
    private final boolean isPublicServer;

    public MutationHandler(MutablePointers mutable, boolean isPublicServer) {
        this.mutable = mutable;
        this.isPublicServer = isPublicServer;
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        long t1 = System.currentTimeMillis();
        DataInputStream din = new DataInputStream(exchange.getRequestBody());

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);

        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/"))
            path = path.substring(1);
        String[] subComponents = path.substring(Constants.MUTABLE_POINTERS_URL.length()).split("/");
        String method = subComponents[0];
//            LOG.info("core method "+ method +" from path "+ path);

        Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
        PublicKeyHash owner = PublicKeyHash.fromString(params.get("owner").get(0));
        PublicKeyHash writer = PublicKeyHash.fromString(params.get("writer").get(0));
        try {
            if (! HttpUtil.allowedQuery(exchange, isPublicServer)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }

            switch (method) {
                case "setPointer":
                    AggregatedMetrics.MUTABLE_POINTERS_SET.inc();
                    byte[] signedPayload = Serialize.readFully(din, 1024);
                    boolean isAdded = mutable.setPointer(owner, writer, signedPayload).get();
                    dout.writeBoolean(isAdded);
                    break;
                case "getPointer":
                    AggregatedMetrics.MUTABLE_POINTERS_GET.inc();
                    byte[] metadataBlob = mutable.getPointer(owner, writer).get().orElse(new byte[0]);
                    dout.write(metadataBlob);
                    break;
                default:
                    throw new IOException("Unknown method in mutable pointers!");
            }

            byte[] b = bout.toByteArray();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
        } catch (Exception e) {
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
            long t2 = System.currentTimeMillis();
            LOG.info("Mutable pointers server handled " + method + " request in: " + (t2 - t1) + " mS");
        }
    }
}
