package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.*;
import peergos.server.social.*;
import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.social.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;

/** This is the http endpoint for SocialNetwork
 *
 * This receives calls to send, retrieve and remove follow requests.
 *
 */
public class SocialHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();

    private final SocialNetwork social;
    private final boolean isPublicServer;

    public SocialHandler(SocialNetwork social, boolean isPublicServer) {
        this.social = social;
        this.isPublicServer = isPublicServer;
    }

    public void handle(HttpExchange exchange)
    {
        long t1 = System.currentTimeMillis();
        DataInputStream din = new DataInputStream(exchange.getRequestBody());

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);

        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/"))
            path = path.substring(1);
        String[] subComponents = path.substring(Constants.SOCIAL_URL.length()).split("/");
        String method = subComponents[0];
        Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
        Function<String, String> last = key -> params.get(key).get(params.get(key).size() - 1);
//            LOG.info("social method "+ method +" from path "+ path);

        PublicKeyHash owner = PublicKeyHash.fromString(last.apply("owner"));
        try {
            if (! HttpUtil.allowedQuery(exchange, isPublicServer)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }
            switch (method) {
                case "followRequest":
                    AggregatedMetrics.FOLLOW_REQUEST_COUNTER.inc();
                    byte[] encryptedCap = Serialize.readFully(din, 1024);
                    boolean followRequested = social.sendFollowRequest(owner, encryptedCap).get();
                    dout.writeBoolean(followRequested);
                    break;
                case "getFollowRequests":
                    AggregatedMetrics.GET_FOLLOW_REQUEST_COUNTER.inc();
                    byte[] signedTime = ArrayOps.hexToBytes(last.apply("auth"));
                    byte[] res = social.getFollowRequests(owner, signedTime).get();
                    Serialize.serialize(res, dout);
                    break;
                case "removeFollowRequest":
                    AggregatedMetrics.REMOVE_FOLLOW_REQUEST_COUNTER.inc();
                    byte[] signedFollowRequest = Serialize.readFully(din, 4096);
                    boolean isRemoved = social.removeFollowRequest(owner, signedFollowRequest).get();
                    dout.writeBoolean(isRemoved);
                    break;
                default:
                    throw new IOException("Unknown method "+ method);
            }

            dout.flush();
            dout.close();
            byte[] b = bout.toByteArray();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
        } catch (Exception e) {
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
            long t2 = System.currentTimeMillis();
            LOG.info("Social Network server handled " + method + " request in: " + (t2 - t1) + " mS");
        }
    }
}
