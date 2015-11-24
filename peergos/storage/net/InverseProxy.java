package peergos.storage.net;

import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;

public class InverseProxy implements HttpHandler {
    private final String targetDomain;

    public InverseProxy(String targetDomain) {
        this.targetDomain = targetDomain;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        URLConnection conn;
        if (httpExchange.getRequestURI().getHost().equals(targetDomain)) {
            conn = new URL("http://localhost:8765/"+httpExchange.getRequestURI().getPath()).openConnection();
        } else {
            conn = new URL("https://"+targetDomain+"/"+httpExchange.getRequestURI().getPath()).openConnection();
        }
        conn.connect();
        InputStream in = conn.getInputStream();
        int r;
        while ((r= in.read()) >= 0);
    }
}
