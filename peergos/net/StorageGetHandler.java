package peergos.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.storage.Storage;
import peergos.util.Arrays;
import peergos.util.ByteArrayWrapper;

import java.io.IOException;

public class StorageGetHandler implements HttpHandler
{
    protected final Storage storage;
    protected final String uri;

    public StorageGetHandler(Storage storage, String url)
    {
        this.storage = storage;
        this.uri = url;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        if (httpExchange.getRequestMethod().equals("GET"))
        {
            handleGet(httpExchange);
        }
    }

    protected void handleGet(HttpExchange exchange) throws IOException
    {
        String filename = exchange.getRequestURI().toString().substring(uri.length());
        ByteArrayWrapper key = new ByteArrayWrapper(Arrays.hexToBytes(filename));
        if (storage.contains(key))
        {
            byte[] res = storage.get(key);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(res);
            exchange.close();
        }
    }
}
