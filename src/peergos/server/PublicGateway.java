package peergos.server;

import com.sun.net.httpserver.*;
import peergos.server.net.*;
import peergos.server.util.*;
import peergos.shared.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.logging.*;

/** This class acts as a public gateway for serving websites from Peergos
 *
 *  A user alice can publish a webroot in Peergos.
 *  This is then served at alice.public.localhost:9000 and alice.peergos.me
 */
public class PublicGateway {
    private static final Logger LOG = Logging.LOG();

    private final String domainSuffix;
    private final NetworkAccess network;
    private final Crypto crypto;
    private volatile HttpServer localhostServer;

    public PublicGateway(String domainSuffix, Crypto crypto, NetworkAccess network) {
        this.domainSuffix = domainSuffix;
        this.crypto = crypto;
        this.network = network;
    }

    public void shutdown() {
        localhostServer.stop(0);
    }

    public void initAndStart(InetSocketAddress local,
                             boolean isPublicServer,
                             int connectionBacklog,
                             int handlerPoolSize) throws IOException {
        LOG.info("Starting local Peergos gateway at: localhost:" + local.getPort());
        localhostServer = HttpServer.create(local, connectionBacklog);

        GatewayHandler publicGateway = new GatewayHandler(domainSuffix, crypto, network);
        localhostServer.createContext("/", isPublicServer ? new HSTSHandler(publicGateway) : publicGateway);

        localhostServer.setExecutor(Threads.newPool(handlerPoolSize, "peergos-gateway-handler-"));
        localhostServer.start();
    }
}
