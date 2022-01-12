package peergos.server.storage.auth;

import com.sun.net.httpserver.*;
import peergos.server.util.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

public class BlockAuthServer {
	private static final Logger LOG = Logging.LOG();

    public static void startListener(BlockRequestAuthoriser author,
                                     InetSocketAddress listen,
                                     int connectionBacklog,
                                     int handlerPoolSize) throws IOException {
        HttpServer localhostServer = HttpServer.create(listen, connectionBacklog);
        localhostServer.createContext("/", ex -> handle(ex, author));
        localhostServer.setExecutor(Executors.newFixedThreadPool(handlerPoolSize));
        localhostServer.start();
    }

    public static void handle(HttpExchange httpExchange, BlockRequestAuthoriser author) {
        try {
            // N.B. URI.getQuery() decodes the query string
            Map<String, List<String>> params = HttpUtil.parseQuery(httpExchange.getRequestURI().getQuery());
            Function<String, String> last = key -> params.get(key).get(params.get(key).size() - 1);

            Cid host = Cid.decodePeerId(httpExchange.getRequestHeaders().getFirst("Host"));
            Cid source = Cid.decodePeerId(last.apply("peer"));
            if (!host.equals(source))
                throw new IllegalStateException("Host doesn't match!");
            String auth = last.apply("auth");
            Cid cid = Cid.decode(last.apply("cid"));
            byte[] block = Serialize.readFully(httpExchange.getRequestBody());
            author.allowRead(cid, block, source, auth).thenAccept(res -> {
                replyBytes(httpExchange, (res ? "true" : "false").getBytes(StandardCharsets.UTF_8));
            }).exceptionally(Futures::logAndThrow).get();
        } catch (Exception e) {
            Throwable t = Exceptions.getRootCause(e);
            if (t instanceof RateLimitException) {
                HttpUtil.replyErrorWithCode(httpExchange, 429, "Too Many Requests");
            } else {
                LOG.severe("Error handling " + httpExchange.getRequestURI());
                LOG.log(Level.WARNING, t.getMessage(), t);
                HttpUtil.replyError(httpExchange, t);
            }
        } finally {
            httpExchange.close();
        }
    }

    private static void replyBytes(HttpExchange exchange, byte[] body) {
        try {
            exchange.sendResponseHeaders(200, body.length);
            DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
            dout.write(body);
            dout.flush();
            dout.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }
}
