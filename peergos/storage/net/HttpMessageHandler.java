package peergos.storage.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.storage.dht.Message;
import peergos.storage.dht.Router;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class HttpMessageHandler implements HttpHandler
{
    private final Router router;

    public HttpMessageHandler(Router r)
    {
        this.router = r;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        InputStream in = httpExchange.getRequestBody();
        Message m = Message.read(new DataInputStream(in));
        router.enqueueMessage(m);
        httpExchange.sendResponseHeaders(200, 0);
        OutputStream out = httpExchange.getResponseBody();
        out.write("Nothing to see here... move along".getBytes());
        out.flush();
    }
}
