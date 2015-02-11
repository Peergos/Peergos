package peergos.user;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import peergos.corenode.AbstractCoreNode;
import peergos.corenode.HTTPCoreNodeServer;
import peergos.storage.dht.DHTAPI;
import peergos.util.Args;
import peergos.util.Serialize;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.*;

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

    private static final int CONNECTION_BACKLOG = 100;

    public static void createAndStart(InetSocketAddress address, DHTUserAPI dhtApi, AbstractCoreNode coreNode, ExecutorService executorService) throws Exception {
        HttpServer server = HttpServer.create(address, CONNECTION_BACKLOG);
        server.createContext("/", new UserContextProxy(dhtApi, coreNode));
        server.setExecutor(executorService);
        server.start();
    }

    public static void main(String[] args) throws IOException {
        Args.parse(args);
        String listenOn = Args.getArg("address", "localhost");
        int port = Args.getInt("port", 8001);
        InetSocketAddress address = new InetSocketAddress(listenOn, port);
        AbstractCoreNode coreNode = AbstractCoreNode.getDefault();
        DHTUserAPI dhtUserAPI = new MemoryDHTUserAPI();
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            UserContextProxy.createAndStart(address, dhtUserAPI, coreNode, executorService);
            System.out.println("User-context proxt now running on "+ address);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Couldn't start User-context-proxy-server!");
        }
    }
}
