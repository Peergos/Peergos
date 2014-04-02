package peergos.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.dht.Message;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class HttpsMessageHandler implements HttpHandler
{
    private final HTTPSMessenger messenger;

    public HttpsMessageHandler(HTTPSMessenger m)
    {
        this.messenger = m;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        InputStream in = httpExchange.getRequestBody();
        Message m = Message.read(new DataInputStream(in));
        messenger.queueRequestMessage(m);
        httpExchange.sendResponseHeaders(200, 0);
        OutputStream out = httpExchange.getResponseBody();
        out.write("Nothing to see here... move along".getBytes());
        out.flush();
    }
}
