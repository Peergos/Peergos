package peergos.server.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.server.util.HttpUtil;
import peergos.server.util.Logging;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.storage.BlockCache;
import peergos.shared.util.Constants;

import java.io.OutputStream;
import java.util.*;
import java.util.function.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConfigHandler implements HttpHandler {
	private static final Logger LOG = Logging.LOG();

    private static final boolean LOGGING = true;
    private final BlockCache cache;

    public ConfigHandler(BlockCache cache) {
        this.cache = cache;
    }

    @Override
    public void handle(HttpExchange exchange) {
        long t1 = System.currentTimeMillis();
        String path = exchange.getRequestURI().getPath();
        try {
            if (! HttpUtil.allowedQuery(exchange, false)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }
            String host = exchange.getRequestHeaders().get("Host").get(0);
            if (! host.startsWith("localhost:")) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            if (path.startsWith("/"))
                path = path.substring(1);
            String action = path.substring(Constants.CONFIG.length());
            Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
            Function<String, String> last = key -> params.get(key).get(params.get(key).size() - 1);

            if (action.equals("cache/set-size-mb")) {
                long maxSizeMb = Long.parseLong(last.apply("size"));
                cache.setMaxSize(maxSizeMb * 1024*1024);
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            } else if (action.equals("cache/get-size")) {
                long cacheSizeBytes = cache.getMaxSize();
                long cacheSizeMB = cacheSizeBytes / (1024 * 1024);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] res = JSONParser.toString("{\"size\": " + cacheSizeMB + "}").getBytes();
                exchange.sendResponseHeaders(200, res.length);
                OutputStream resp = exchange.getResponseBody();
                resp.write(res);
                exchange.close();
            } else {
                LOG.info("Unknown config handler: " +exchange.getRequestURI());
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
            }
        } catch (Exception e) {
            LOG.severe("Error handling " +exchange.getRequestURI());
            LOG.log(Level.WARNING, e.getMessage(), e);
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                LOG.info("Config Handler returned in: " + (t2 - t1) + " mS");
        }
    }
}
