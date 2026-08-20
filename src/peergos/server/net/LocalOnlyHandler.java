package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

/** Everything that keeps a local only api local, in one place.
 *
 *  These apis add a sync pair for any local folder, unmount the drive, repoint the app at
 *  another server, or stop the process, and none of them authenticate their caller. A page
 *  in the user's browser can POST to them cross site without a preflight, so each of the
 *  checks below has to hold before the request reaches the handler itself.
 */
public class LocalOnlyHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();

    private final HttpHandler handler;
    private final String allowedHost;
    private final String allowedOrigin;
    private final boolean allowGet;

    public LocalOnlyHandler(HttpHandler handler, int localPort) {
        this(handler, localPort, false);
    }

    /** @param allowGet for an api a browser reaches by navigation rather than from script */
    public LocalOnlyHandler(HttpHandler handler, int localPort, boolean allowGet) {
        this.handler = handler;
        this.allowedHost = "localhost:" + localPort;
        this.allowedOrigin = "http://" + allowedHost;
        this.allowGet = allowGet;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // The Host header is chosen by the caller, the peer address by the kernel. Without
        // this, anything on the lan reaches all of these apis just by sending
        // Host: localhost:<port>, whenever the server is bound to more than loopback.
        InetSocketAddress peer = exchange.getRemoteAddress();
        if (peer == null || peer.getAddress() == null || ! peer.getAddress().isLoopbackAddress()) {
            refuse(exchange, 404, "remote address " + peer);
            return;
        }

        String method = exchange.getRequestMethod();
        if (! method.equals("POST") && ! (allowGet && method.equals("GET"))) {
            refuse(exchange, 405, "method " + method);
            return;
        }

        List<String> hosts = exchange.getRequestHeaders().get("Host");
        if (hosts == null || hosts.size() != 1 || ! hosts.get(0).equals(allowedHost)) {
            refuse(exchange, 404, "host " + hosts);
            return;
        }

        // Anything that admits to another origin is refused: otherwise any site the user
        // visits could pause their sync or remove a folder. No Access-Control-Allow-Origin
        // is ever sent, so such a caller cannot read a reply either.
        List<String> origins = exchange.getRequestHeaders().get("Origin");
        if (origins != null && ! origins.isEmpty() && ! origins.get(0).equals(allowedOrigin)) {
            refuse(exchange, 403, "origin " + origins.get(0));
            return;
        }

        // A navigation or a media load carries no Origin at all, so for a GET api this is
        // the check that separates our own page from any other. It also keeps out the app
        // subdomains, which are same-site but not us. Browsers too old to send it are left
        // with the origin check above.
        List<String> site = exchange.getRequestHeaders().get("Sec-Fetch-Site");
        if (site != null && ! site.isEmpty()
                && ! site.get(0).equals("same-origin") && ! site.get(0).equals("none")) {
            refuse(exchange, 403, "sec-fetch-site " + site.get(0));
            return;
        }

        handler.handle(exchange);
    }

    private static void refuse(HttpExchange exchange, int code, String reason) throws IOException {
        LOG.info("Refusing local only request to " + exchange.getRequestURI().getPath() + ": " + reason);
        exchange.sendResponseHeaders(code, 0);
        exchange.close();
    }
}
