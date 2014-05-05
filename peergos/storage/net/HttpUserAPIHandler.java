package peergos.storage.net;

import akka.actor.ActorRef;
import static akka.pattern.Patterns.ask;

import akka.actor.ActorSystem;
import akka.dispatch.OnFailure;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.storage.dht.*;
import peergos.user.fs.Fragment;
import peergos.util.ArrayOps;
import peergos.util.Serialize;
import scala.concurrent.Future;

import java.io.*;

public class HttpUserAPIHandler implements HttpHandler
{
    private final ActorRef router;
    private final ActorSystem system;

    public HttpUserAPIHandler(ActorRef r, ActorSystem system)
    {
        this.router = r;
        this.system = system;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        InputStream in = httpExchange.getRequestBody();
        DataInputStream din = new DataInputStream(in);
        Message m = Message.read(din);
        if (m instanceof Message.PUT) {
            byte[] value = Serialize.deserializeByteArray(din, Fragment.SIZE);
            Future<Object> fut = ask(router, new MessageMailbox(m), 30000);
            fut.onSuccess(new DHTAPI.PutHandler(((Message.PUT) m).getKey(), value, new PutSuccess(httpExchange)), system.dispatcher());
            fut.onFailure(new Failure(httpExchange), system.dispatcher());
        } else if (m instanceof Message.GET){
            int type = din.readInt();
            if (type == 1) // GET
            {
                Future<Object> fut = ask(router, new MessageMailbox(m), 30000);
                fut.onSuccess(new DHTAPI.GetHandler(((Message.GET) m).getKey(), new GetSuccess(((Message.GET) m).getKey(), httpExchange)), system.dispatcher());
                fut.onFailure(new Failure(httpExchange), system.dispatcher());
            }
            else if (type == 2) // CONTAINS
            {
                Future<Object> fut = ask(router, new MessageMailbox(m), 30000);
                fut.onSuccess(new DHTAPI.GetHandler(((Message.GET) m).getKey(), new ContainsSuccess(httpExchange)), system.dispatcher());
                fut.onFailure(new Failure(httpExchange), system.dispatcher());
            }
        }
    }

    private static class PutSuccess implements PutHandlerCallback
    {
        private final HttpExchange exchange;

        private PutSuccess(HttpExchange exchange)
        {
            this.exchange = exchange;
        }

        @Override
        public void callback(PutOffer offer) {
            try {
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(1); // success

                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private static class GetSuccess implements GetHandlerCallback
    {
        private final HttpExchange exchange;
        private final byte[] key;

        private GetSuccess(byte[] key, HttpExchange exchange)
        {
            this.key = key;
            this.exchange = exchange;
        }

        @Override
        public void callback(GetOffer offer) {
            try {
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(1); // success
                byte[] frag = HttpMessenger.getFragment(offer.getTarget().addr, offer.getTarget().port+1, "/" + ArrayOps.bytesToHex(key));
                Serialize.serialize(frag, dout);
                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private static class ContainsSuccess implements GetHandlerCallback
    {
        private final HttpExchange exchange;

        private ContainsSuccess(HttpExchange exchange)
        {
            this.exchange = exchange;
        }

        @Override
        public void callback(GetOffer offer) {
            try {
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(1); // success
                dout.writeInt(offer.getSize());
                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private static class Failure extends OnFailure
    {
        private final HttpExchange exchange;

        private Failure(HttpExchange exchange)
        {
            this.exchange = exchange;
        }

        public void onFailure(java.lang.Throwable throwable) throws java.lang.Throwable {
            try {
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(-1);
                Serialize.serialize(throwable.getMessage(), dout);
                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }
}
