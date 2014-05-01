package peergos.directory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public class CoreListHandler implements HttpHandler {
    private final DirectoryServer dir;

    public CoreListHandler(DirectoryServer dir)
    {
        this.dir = dir;
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
        exchange.getResponseBody().write(dir.getCoreServers());
        exchange.close();
    }
}
