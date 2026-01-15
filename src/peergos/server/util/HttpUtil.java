package peergos.server.util;

import com.sun.net.httpserver.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;
import java.util.stream.*;

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

    public static void replyErrorWithCode(HttpExchange exchange, int httpErrorCode, String message) {
        try {
            exchange.getResponseHeaders().set("Trailer", URLEncoder.encode(message, "UTF-8"));
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(httpErrorCode, 0);
        } catch (IOException e) {
            Logging.LOG().log(Level.WARNING, e.getMessage(), e);
        }
    }

    public static byte[] get(PresignedUrl url) throws IOException {
        return getWithVersion(url).left;
    }

    public static Pair<byte[], String> getWithVersion(PresignedUrl url) throws IOException {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URI(url.base).toURL().openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(60_000);
            conn.setRequestMethod("GET");
            for (Map.Entry<String, String> e : url.fields.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }

            try {
                int respCode = conn.getResponseCode();
                if (respCode == 502 || respCode == 503)
                    throw new RateLimitException();
                if (respCode == 404)
                    throw new FileNotFoundException();
                InputStream in = conn.getInputStream();
                Map<String, String> headers = conn.getHeaderFields().entrySet()
                        .stream()
                        .filter(e -> e.getKey() != null)
                        .collect(Collectors.toMap(e -> e.getKey().toLowerCase(), e -> e.getValue().get(0)));
                String version = headers.getOrDefault("x-amz-version-id", null);
                return new Pair<>(Serialize.readFully(in), version);
            } catch (IOException e) {
                InputStream err = conn.getErrorStream();
                if (err == null)
                    throw e;
                byte[] errBody = Serialize.readFully(err);
                throw new IOException(new String(errBody), e);
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static Map<String, List<String>> head(PresignedUrl head) throws IOException {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URI(head.base).toURL().openConnection();
            conn.setRequestMethod("HEAD");
            for (Map.Entry<String, String> e : head.fields.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }

            try {
                int respCode = conn.getResponseCode();
                if (respCode == 200)
                    return conn.getHeaderFields();
                if (respCode == 502 || respCode == 503)
                    throw new RateLimitException();
                if (respCode == 404)
                    throw new FileNotFoundException();
                throw new IllegalStateException("HTTP " + respCode);
            } catch (IOException e) {
                InputStream err = conn.getErrorStream();
                if (err == null)
                    throw e;
                byte[] errBody = Serialize.readFully(err);
                throw new IOException(new String(errBody));
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static Pair<byte[], String> putWithVersion(PresignedUrl target, byte[] body) throws IOException {
        return putOrPostWithVersion("PUT", target, body);
    }

    public static byte[] post(PresignedUrl target, byte[] body) throws IOException {
        return putOrPostWithVersion("POST", target, body).left;
    }

    private static Pair<byte[], String> putOrPostWithVersion(String method, PresignedUrl target, byte[] body) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URI(target.base).toURL().openConnection();
            conn.setRequestMethod(method);
            for (Map.Entry<String, String> e : target.fields.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            conn.setDoOutput(true);
            OutputStream out = conn.getOutputStream();
            out.write(body);
            out.flush();
            out.close();

            int httpCode = conn.getResponseCode();
            if (httpCode == 502 || httpCode == 503)
                throw new RateLimitException();
            InputStream in = conn.getInputStream();
            Map<String, String> headers = conn.getHeaderFields().entrySet()
                    .stream()
                    .filter(e -> e.getKey() != null)
                    .collect(Collectors.toMap(e -> e.getKey().toLowerCase(), e -> e.getValue().get(0)));
            String version = headers.getOrDefault("x-amz-version-id", null);
            return new Pair(Serialize.readFully(in), version);
        } catch (ConnectException e) {
            throw new RateLimitException();
        } catch (IOException e) {
            if (conn != null) {
                InputStream err = conn.getErrorStream();
                if (err != null) {
                    byte[] errBody = Serialize.readFully(err);
                    throw new IOException(new String(errBody));
                }
            }
            throw new RuntimeException(e);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static void delete(PresignedUrl target) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URI(target.base).toURL().openConnection();
        conn.setRequestMethod("DELETE");
        for (Map.Entry<String, String> e : target.fields.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }

        try {
            int code = conn.getResponseCode();
            if (code == 204)
                return;
            if (code == 502 || code == 503)
                throw new RateLimitException();
            InputStream in = conn.getInputStream();
            byte[] body = Serialize.readFully(in);
            throw new IllegalStateException("HTTP " + code + "-" + body);
        } catch (IOException e) {
            InputStream err = conn.getErrorStream();
            byte[] errBody = Serialize.readFully(err);
            throw new IllegalStateException(new String(errBody), e);
        }
    }

    public static URL toURL(String url) {
        try {
            return new URI(url).toURL();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
