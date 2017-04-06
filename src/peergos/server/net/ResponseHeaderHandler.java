package peergos.server.net;

import com.sun.net.httpserver.*;

import java.io.*;
import java.util.*;

public class ResponseHeaderHandler implements HttpHandler {

    private final Map<String, String> responseHeaders;
    private final HttpHandler handler;

    public ResponseHeaderHandler(Map<String, String> responseHeaders, HttpHandler handler) {
        this.responseHeaders = responseHeaders;
        this.handler = handler;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        for (String key: responseHeaders.keySet())
            httpExchange.getResponseHeaders().set(key, responseHeaders.get(key));
        handler.handle(httpExchange);
    }
}
