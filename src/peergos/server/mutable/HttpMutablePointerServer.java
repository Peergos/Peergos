package peergos.server.mutable;

import com.sun.net.httpserver.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class HttpMutablePointerServer
{
    public static final int PORT = 9998;

    private static final boolean LOGGING = true;
    private static final int CONNECTION_BACKLOG = 100;
    private static final int HANDLER_THREAD_COUNT = 100;

    public static final String MUTABLE_POINTERS_URL = "mutable/";

    public static class MutationHandler implements HttpHandler
    {
        private final MutablePointers mutable;

        public MutationHandler(MutablePointers mutable) {
            this.mutable = mutable;
        }

        public void handle(HttpExchange exchange) throws IOException
        {
            long t1 = System.currentTimeMillis();
            DataInputStream din = new DataInputStream(exchange.getRequestBody());

            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/"))
                path = path.substring(1);
            String[] subComponents = path.substring(MUTABLE_POINTERS_URL.length()).split("/");
            String method = subComponents[0];
//            System.out.println("core method "+ method +" from path "+ path);

            try {
                switch (method)
                {
                    case "setPointer":
                        setPointer(din, dout);
                        break;
                    case "getPointer":
                        getPointer(din, dout);
                        break;
                    default:
                        throw new IOException("Unknown method "+ method);
                }

                dout.flush();
                dout.close();
                byte[] b = bout.toByteArray();
                exchange.sendResponseHeaders(200, b.length);
                exchange.getResponseBody().write(b);
            } catch (Exception e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(400, 0);
                OutputStream body = exchange.getResponseBody();
                body.write(e.getMessage().getBytes());
            } finally {
                exchange.close();
                long t2 = System.currentTimeMillis();
                if (LOGGING)
                    System.out.println("Mutable pointers server handled " + method + " request in: " + (t2 - t1) + " mS");
            }

        }

        void setPointer(DataInputStream din, DataOutputStream dout) throws Exception
        {
            byte[] ownerPublicKey = CoreNodeUtils.deserializeByteArray(din);
            byte[] encodedSharingPublicKey = CoreNodeUtils.deserializeByteArray(din);
            byte[] signedPayload = CoreNodeUtils.deserializeByteArray(din);
            boolean isAdded = mutable.setPointer(
                    PublicKeyHash.fromCbor(CborObject.fromByteArray(ownerPublicKey)),
                    PublicKeyHash.fromCbor(CborObject.fromByteArray(encodedSharingPublicKey)),
                    signedPayload).get();
            dout.writeBoolean(isAdded);
        }

        void getPointer(DataInputStream din, DataOutputStream dout) throws Exception
        {
            PublicKeyHash encodedSharingKey = PublicKeyHash.fromCbor(CborObject.deserialize(new CborDecoder(din)));
            MaybeMultihash metadataBlob = mutable.getPointer(encodedSharingKey).get();

            dout.write(metadataBlob.serialize());
        }
    }

    private final HttpServer server;
    private final InetSocketAddress address;
    private final MutationHandler ch;

    public HttpMutablePointerServer(MutablePointers mutable, InetSocketAddress address) throws IOException
    {

        this.address = address;
        if (address.getHostName().contains("local"))
            server = HttpServer.create(address, CONNECTION_BACKLOG);
        else
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLocalHost(), address.getPort()), CONNECTION_BACKLOG);
        ch = new MutationHandler(mutable);
        server.createContext("/" + MUTABLE_POINTERS_URL, ch);
        server.setExecutor(Executors.newFixedThreadPool(HANDLER_THREAD_COUNT));
    }

    public void start() throws IOException
    {
        server.start();
    }

    public void close() throws IOException
    {   
        server.stop(5);
    }
}
