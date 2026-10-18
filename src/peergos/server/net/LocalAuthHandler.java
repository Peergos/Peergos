package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.util.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** redeem swaps a launch code for the local api secret, launch-url makes a new code for a caller that already has the secret */
public class LocalAuthHandler implements HttpHandler {

    private final LocalApiAuth auth;
    private final int localPort;

    public LocalAuthHandler(LocalApiAuth auth, int localPort) {
        this.auth = auth;
        this.localPort = localPort;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/"))
                path = path.substring(1);
            String action = path.substring(Constants.LOCAL_AUTH.length());
            Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
            if (action.equals("redeem")) {
                List<String> codes = params.get("code");
                Optional<String> secret = auth.redeem(codes == null || codes.size() != 1 ? null : codes.get(0));
                if (secret.isEmpty()) {
                    HttpUtil.replyErrorWithCode(exchange, 403, "This launch link has already been used");
                    return;
                }
                reply(exchange, secret.get());
            } else if (action.equals("launch-url")) {
                if (! auth.isAuthorised(exchange)) {
                    HttpUtil.replyErrorWithCode(exchange, 403, "Invalid local api token");
                    return;
                }
                reply(exchange, auth.launchUrl(localPort));
            } else
                HttpUtil.replyErrorWithCode(exchange, 404, "Unknown action");
        } catch (Exception e) {
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
        }
    }

    private static void reply(HttpExchange exchange, String body) throws IOException {
        byte[] res = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, res.length);
        exchange.getResponseBody().write(res);
    }
}
