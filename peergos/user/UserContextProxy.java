package peergos.user;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.corenode.AbstractCoreNode;
import peergos.corenode.HTTPCoreNodeServer;
import peergos.util.Serialize;

import java.io.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class UserContextProxy implements HttpHandler
{
    private final HTTPCoreNodeServer.CoreNodeHandler core;
    private final DHTUserAPI dht;

    public UserContextProxy(DHTUserAPI dht, AbstractCoreNode coreNode) {
        this.dht = dht;
        this.core = new HTTPCoreNodeServer.CoreNodeHandler(coreNode);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        DataInputStream din = new DataInputStream(exchange.getRequestBody());
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);

        String path = exchange.getHttpContext().getPath();
        path = path.substring(path.indexOf("/"));
        if (path.startsWith("core"))
            core.handle(exchange);
        else if (path.startsWith("dht")) {

            if (path.startsWith("dht/put")) {
                byte[] key = Serialize.deserializeByteArray(din, 1024);
                byte[] value = Serialize.deserializeByteArray(din, 1024*1024);
                String user =Serialize.deserializeString(din, 1024);
                byte[] sharingKey = Serialize.deserializeByteArray(din, 8192);
                byte[] mapKey = Serialize.deserializeByteArray(din, 8192);
                byte[] proof = Serialize.deserializeByteArray(din, 8192);

                Future<Boolean> fut = dht.put(key, value, user, sharingKey, mapKey, proof);
                try {
                    Boolean response = fut.get(100, TimeUnit.SECONDS);
                    dout.writeBoolean(response);
                } catch (InterruptedException | TimeoutException | ExecutionException ex) {
                    ex.printStackTrace();
                    dout.writeBoolean(false);
                }

            } else if (path.startsWith("dht/get")) {
                byte[] key = Serialize.deserializeByteArray(din, 1024);
                Future<byte[]> fut = dht.get(key);
                try {
                    byte[] response = fut.get(100, TimeUnit.SECONDS);
                    dout.write(response);
                } catch (InterruptedException | TimeoutException | ExecutionException ex) {
                    dout.writeBoolean(false);
                    ex.printStackTrace();
                }
            } else if(path.startsWith("dht/contains")) {
                byte[] key = Serialize.deserializeByteArray(din, 1024);
                Future<Boolean> fut = dht.contains(key);

                try {
                    Boolean response = fut.get(100, TimeUnit.SECONDS);
                    dout.writeBoolean(response);
                } catch (InterruptedException | TimeoutException | ExecutionException ex) {
                    ex.printStackTrace();
                    dout.writeBoolean(false);
                }
            }
            dout.close();
            exchange.getResponseBody().write(bout.toByteArray());
        } else {
            dout.writeBoolean(false);
            dout.close();
            exchange.getResponseBody().write(bout.toByteArray());
            exchange.close();
        }
    }

    static String deserializeString(DataInputStream din) throws IOException
    {
        return Serialize.deserializeString(din, 1024);
    }
}
