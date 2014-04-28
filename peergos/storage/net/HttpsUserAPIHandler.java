package peergos.storage.net;

import akka.actor.ActorRef;
import static akka.pattern.Patterns.ask;

import akka.actor.ActorSystem;
import akka.dispatch.OnFailure;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.storage.dht.*;
import peergos.user.fs.Fragment;
import peergos.util.Arrays;
import peergos.util.Serialize;
import scala.concurrent.Future;

import java.io.*;

public class HttpsUserAPIHandler implements HttpHandler
{
    private final ActorRef router;
    private final ActorSystem system;

    public HttpsUserAPIHandler(ActorRef r, ActorSystem system)
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
            DataOutputStream dout = new DataOutputStream(httpExchange.getResponseBody());
            fut.onSuccess(new DHTAPI.PutHandler(((Message.PUT) m).getKey(), value, new PutSuccess(dout)), system.dispatcher());
            fut.onFailure(new Failure(dout), system.dispatcher());
        } else if (m instanceof Message.GET){
            int type = din.readInt();
            if (type == 1) // GET
            {
                Future<Object> fut = ask(router, new MessageMailbox(m), 30000);
                DataOutputStream dout = new DataOutputStream(httpExchange.getResponseBody());
                fut.onSuccess(new DHTAPI.GetHandler(((Message.GET) m).getKey(), new GetSuccess(((Message.GET) m).getKey(), dout)), system.dispatcher());
                fut.onFailure(new Failure(dout), system.dispatcher());
            }
            else if (type == 2) // CONTAINS
            {
                Future<Object> fut = ask(router, new MessageMailbox(m), 30000);
                DataOutputStream dout = new DataOutputStream(httpExchange.getResponseBody());
                fut.onSuccess(new DHTAPI.GetHandler(((Message.GET) m).getKey(), new ContainsSuccess(dout)), system.dispatcher());
                fut.onFailure(new Failure(dout), system.dispatcher());
            }
        }
        httpExchange.sendResponseHeaders(200, 0);
    }

    private static class PutSuccess implements PutHandlerCallback
    {
        private final DataOutputStream dout;

        private PutSuccess(DataOutputStream dout)
        {
            this.dout = dout;
        }

        @Override
        public void callback(PutOffer offer) {
            try {

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
        private final DataOutputStream dout;
        private final byte[] key;

        private GetSuccess(byte[] key, DataOutputStream dout)
        {
            this.key = key;
            this.dout = dout;
        }

        @Override
        public void callback(GetOffer offer) {
            try {
                dout.writeInt(1); // success
                byte[] frag = HTTPSMessenger.getFragment(offer.getTarget().addr, offer.getTarget().port, "/" + Arrays.bytesToHex(key));
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
        private final DataOutputStream dout;

        private ContainsSuccess(DataOutputStream dout)
        {
            this.dout = dout;
        }

        @Override
        public void callback(GetOffer offer) {
            try {
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
        private final DataOutputStream dout;

        private Failure(DataOutputStream dout)
        {
            this.dout = dout;
        }

        public void onFailure(java.lang.Throwable throwable) throws java.lang.Throwable {
            try {
                dout.writeInt(-1);
                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }
}
