package peergos.server.util;

import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

public class HttpUtil {

    public static boolean allowedQuery(HttpExchange exchange, boolean isPublicServer) {
        // only allow http POST requests unless we are a public server (not localhost)
        if (! exchange.getRequestMethod().equals("POST") && ! isPublicServer) {
            return false;
        }
        return true;
    }

    /** Parse a url query string ignoring encoding
     *
     * @param query
     * @return
     */
    public static Map<String, List<String>> parseQuery(String query) {
        if (query == null)
            return Collections.emptyMap();
        if (query.startsWith("?"))
            query = query.substring(1);
        String[] parts = query.split("&");
        Map<String, List<String>> res = new HashMap<>();
        for (String part : parts) {
            int sep = part.indexOf("=");
            String key = part.substring(0, sep);
            String value = part.substring(sep + 1);
            res.putIfAbsent(key, new ArrayList<>());
            res.get(key).add(value);
        }
        return res;
    }

    public static void replyError(HttpExchange exchange, Throwable t) {
        try {
            Logging.LOG().log(Level.WARNING, t.getMessage(), t);
            Throwable cause = t.getCause();
            if (cause != null)
                exchange.getResponseHeaders().set("Trailer", URLEncoder.encode(cause.getMessage(), "UTF-8"));
            else
                exchange.getResponseHeaders().set("Trailer", URLEncoder.encode(t.getMessage(), "UTF-8"));

            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(400, 0);
        } catch (IOException e) {
            Logging.LOG().log(Level.WARNING, e.getMessage(), e);
        }
    }
}
