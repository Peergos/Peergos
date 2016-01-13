package peergos.corenode;

import peergos.crypto.*;

import java.net.*;
import java.io.*;

import com.sun.net.httpserver.*;
import peergos.util.Args;
import peergos.util.Serialize;

public class HTTPCoreNodeServer
{
    private static final int CONNECTION_BACKLOG = 100;

    private static final int MAX_KEY_LENGTH = 1024*1024;
    public static final String CORE_URL = "/core/";
    public static final int PORT = 9999;

    public static class CoreNodeHandler implements HttpHandler
    {
        private final CoreNode coreNode;

        public CoreNodeHandler(CoreNode coreNode) {
            this.coreNode = coreNode;
        }

        public void handle(HttpExchange exchange) throws IOException 
        {
            DataInputStream din = new DataInputStream(exchange.getRequestBody());
            
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);
            
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/"))
                path = path.substring(1);
            String method = path.substring(path.lastIndexOf("/") + 1);
            //System.out.println("method "+ method +" from path "+ path);

            try {
                switch (method)
                {
                    case "addUsername":
                        addUsername(din, dout);
                        break;
                    case "getPublicKey":
                        getPublicKey(din, dout);
                        break;
                    case "getUsername":
                        getUsername(din, dout);
                        break;
                    case "getAllUsernamesGzip":
                        exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                        getAllUsernamesGzip(din, dout);
                        break;
                    case "followRequest":
                        followRequest(din, dout);
                        break;
                    case "getFollowRequests":
                        getFollowRequests(din, dout);
                        break;
                    case "removeFollowRequest":
                        removeFollowRequest(din, dout);
                        break;
                    case "addMetadataBlob":
                        addMetadataBlob(din, dout);
                        break;
                    case "getMetadataBlob":
                        getMetadataBlob(din, dout);
                        break;
                    case "removeMetadataBlob":
                        removeMetadataBlob(din, dout);
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
            } finally {
                exchange.close();
            }

        }

        void addUsername(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = deserializeString(din);
            byte[] encodedKey = deserializeByteArray(din);
            byte[] hash = deserializeByteArray(din);
            byte[] staticData = deserializeByteArray(din);
            
            boolean isAdded = coreNode.addUsername(username, encodedKey, hash, staticData);

            dout.writeBoolean(isAdded);
        }

        void getPublicKey(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = deserializeString(din);
            UserPublicKey k = coreNode.getPublicKey(username);
            if (k == null)
                return;
            byte[] b = k.getPublicKeys();
            dout.writeInt(b.length);
            dout.write(b);
        }

        void getUsername(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] publicKey = deserializeByteArray(din);
            String k = coreNode.getUsername(publicKey);
            if (k == null)
                k="";
            Serialize.serialize(k, dout);
        }

        void getAllUsernamesGzip(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] res = coreNode.getAllUsernamesGzip();
            dout.write(res);
        }

        void followRequest(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] target = deserializeByteArray(din);
            byte[] encodedSharingPublicKey = deserializeByteArray(din);

            boolean followRequested = coreNode.followRequest(new UserPublicKey(target), encodedSharingPublicKey);
            dout.writeBoolean(followRequested);
        }
        void getFollowRequests(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] ownerPublicKey = deserializeByteArray(din);
            byte[] res = coreNode.getFollowRequests(new UserPublicKey(ownerPublicKey));
            Serialize.serialize(res, dout);
        }
        void removeFollowRequest(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] owner = deserializeByteArray(din);
            byte[] signedFollowRequest = deserializeByteArray(din);

            boolean isRemoved = coreNode.removeFollowRequest(new UserPublicKey(owner), signedFollowRequest);
            dout.writeBoolean(isRemoved);
        }

        void addMetadataBlob(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] ownerPublicKey = deserializeByteArray(din);
            byte[] encodedSharingPublicKey = deserializeByteArray(din);
            byte[] signedPayload = deserializeByteArray(din);
            boolean isAdded = coreNode.setMetadataBlob(ownerPublicKey, encodedSharingPublicKey, signedPayload);
            dout.writeBoolean(isAdded);
        }

        void removeMetadataBlob(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] encodedWriterPublicKey = deserializeByteArray(din);
            byte[] signedPayload = deserializeByteArray(din);
            boolean isAdded = coreNode.removeMetadataBlob(encodedWriterPublicKey, signedPayload);
            dout.writeBoolean(isAdded);
        }

        void getMetadataBlob(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] encodedSharingKey = deserializeByteArray(din);
            byte[] b = coreNode.getMetadataBlob(encodedSharingKey);
            if (b == null)
            {
                Serialize.serialize(new byte[0], dout);
                return;
            }
            Serialize.serialize(b, dout);
        }

        public void close() throws IOException{
            coreNode.close();
        }
    }

    private final HttpServer server;
    private final InetSocketAddress address; 
    private final CoreNodeHandler ch;

    public HTTPCoreNodeServer(CoreNode coreNode, InetSocketAddress address) throws IOException
    {

        this.address = address;
        if (address.getHostName().contains("local"))
            server = HttpServer.create(address, CONNECTION_BACKLOG);
        else
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLocalHost(), address.getPort()), CONNECTION_BACKLOG);
        ch = new CoreNodeHandler(coreNode);
        server.createContext(CORE_URL, ch);
        //server.setExecutor(Executors.newFixedThreadPool(HANDLER_THREAD_COUNT));
        server.setExecutor(null);
    }

    public void start() throws IOException
    {
        server.start();
    }
    
    public InetSocketAddress getAddress(){return address;}

    public void close() throws IOException
    {   
        server.stop(5);
        ch.close();
    }
    static byte[] deserializeByteArray(DataInputStream din) throws IOException
    {
        return Serialize.deserializeByteArray(din, MAX_KEY_LENGTH);
    }

    static byte[] getByteArray(int len) throws IOException
    {
        return Serialize.getByteArray(len, MAX_KEY_LENGTH);
    }

    static String deserializeString(DataInputStream din) throws IOException
    {
        return Serialize.deserializeString(din, 1024);
    }

    public static void createAndStart(String keyfile, char[] passphrase, int port, CoreNode coreNode)
    {
        // eventually will need our own keypair to sign traffic to other core nodes our register ourselves with directory servers
        try {
            String hostname = Args.getArg("domain", "localhost");
            InetSocketAddress address = new InetSocketAddress(hostname, port);
            HTTPCoreNodeServer server = new HTTPCoreNodeServer(coreNode, address);
            server.start();
        } catch (Exception e)
        {
            e.printStackTrace();
            System.out.println("Couldn't start Corenode server!");
        }
    }
}
