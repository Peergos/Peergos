package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.stream.*;

public class InverseProxyHandler implements HttpHandler {
    private final String targetDomain;
    private final boolean isLocal;

    public InverseProxyHandler(String targetDomain, boolean isLocal) {
        this.targetDomain = targetDomain;
        this.isLocal = isLocal;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        try {
            HttpURLConnection conn;
            if (!isLocal) {
                System.out.println("Proxying to localhost..");
                conn = (HttpURLConnection)new URL("http://localhost:8765" + httpExchange.getRequestURI().getPath()).openConnection();
            } else {
                System.out.println("Proxying to " + targetDomain);
                conn = (HttpURLConnection)new URL("https://" + targetDomain + httpExchange.getRequestURI().getPath()).openConnection();
            }
            conn.connect();
            int respCode = conn.getResponseCode();
            if (respCode == 500) {
                httpExchange.sendResponseHeaders(500, 0);
                return;
            }
            InputStream in = conn.getInputStream();

            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            if (respCode == 200) {
                byte[] tmp = new byte[256];
                int r;
                while ((r = in.read(tmp)) >= 0)
                    bout.write(tmp, 0, r);
            }
            byte[] bytes = bout.toByteArray();
            System.out.println(new String(bytes));

            Map<String, List<String>> respHeaders = conn.getHeaderFields();
            // status is returned under a null key, remove it
            Map<String, List<String>> headers = respHeaders.entrySet().stream().filter(e -> e.getKey() != null)
                    .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
            System.out.println("headers: "+headers);
            httpExchange.getResponseHeaders().putAll(headers);
            httpExchange.sendResponseHeaders(respCode, bytes.length);
            httpExchange.getResponseBody().write(bytes);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
