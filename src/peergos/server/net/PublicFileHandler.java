package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.net.*;
import java.nio.file.*;
import java.util.logging.*;

public class PublicFileHandler implements HttpHandler {
	private static final Logger LOG = Logging.LOG();

    private static final boolean LOGGING = true;

    private final NetworkAccess network;
    private static final String PATH_PREFIX = "/public/";

    public PublicFileHandler(CoreNode core, MutablePointers mutable, ContentAddressedStorage dht) {
        this.network = NetworkAccess.buildPublicNetworkAccess(core, mutable, dht).join();
    }

    @Override
    public void handle(HttpExchange httpExchange) {
        long t1 = System.currentTimeMillis();
        String path = httpExchange.getRequestURI().getPath();
        try {
            if (! path.startsWith(PATH_PREFIX))
                throw new IllegalStateException("Public file urls must start with /public/");
            path = path.substring(PATH_PREFIX.length());
            String originalPath = path;

            AbsoluteCapability cap = UserContext.getPublicCapability(Paths.get(originalPath), network).join();

            String link = "/#{\"secretLink\":true%2c\"path\":\""
                    + URLEncoder.encode("/" + originalPath, "UTF-8")
                    + "\"%2c\"link\":\"" + cap.toLink() + "\"}";

            httpExchange.getResponseHeaders().add("Location", link);
            httpExchange.sendResponseHeaders(302, 0); // temporary redirect
            httpExchange.close();
        } catch (Exception e) {
            LOG.severe("Error handling " +httpExchange.getRequestURI());
            LOG.log(Level.WARNING, e.getMessage(), e);
            HttpUtil.replyError(httpExchange, e);
        } finally {
            httpExchange.close();
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                LOG.info("Public file Handler returned " + path + " query in: " + (t2 - t1) + " mS");
        }
    }
}
