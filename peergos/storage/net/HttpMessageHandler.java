package peergos.storage.net;

import akka.actor.ActorRef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.storage.dht.Message;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class HttpMessageHandler implements HttpHandler
{
    private final ActorRef router;

    public HttpMessageHandler(ActorRef r)
    {
        this.router = r;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        InputStream in = httpExchange.getRequestBody();
        Message m = Message.read(new DataInputStream(in));
        router.tell(m, ActorRef.noSender());
        httpExchange.sendResponseHeaders(200, 0);
        OutputStream out = httpExchange.getResponseBody();
        out.write("Nothing to see here... move along".getBytes());
        out.flush();
    }
}
