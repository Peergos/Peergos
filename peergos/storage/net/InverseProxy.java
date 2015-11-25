package peergos.storage.net;

import com.sun.net.httpserver.*;
import peergos.util.*;

import java.io.*;
import java.net.*;
import java.util.*;

public class InverseProxy implements HttpHandler {
    private final String targetDomain;

    public InverseProxy(String targetDomain) {
        this.targetDomain = targetDomain;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        try {
            HttpURLConnection conn;
            if (!Args.getArg("domain", "localhost").equals("localhost")) {
                System.out.println("Signing up at localhost..");
                conn = (HttpURLConnection)new URL("http://localhost:8765" + httpExchange.getRequestURI().getPath()).openConnection();
            } else {
                System.out.println("Signing up at " + targetDomain);
                conn = (HttpURLConnection)new URL("https://" + targetDomain + httpExchange.getRequestURI().getPath()).openConnection();
            }
            conn.connect();
            InputStream in = conn.getInputStream();

            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            byte[] tmp = new byte[256];
            int r;
            while ((r = in.read(tmp)) >= 0)
                bout.write(tmp, 0, r);
            byte[] bytes = bout.toByteArray();
            System.out.println(new String(bytes));
            int respCode = conn.getResponseCode();
            Map<String, List<String>> respHeaders = conn.getHeaderFields();
            httpExchange.sendResponseHeaders(respCode, bytes.length);
            httpExchange.getResponseHeaders().putAll(respHeaders);
            httpExchange.getResponseBody().write(bytes);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
