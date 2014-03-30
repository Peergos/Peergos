package defiance.directory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public class ReadableStorageListHandler implements HttpHandler {
    private final DirectoryServer dir;

    public ReadableStorageListHandler(DirectoryServer directoryServer) {
        this.dir = directoryServer;
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
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(dir.getReadableStorageServers());
        exchange.close();
    }
}
