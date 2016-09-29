package peergos.server.corenode;

import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.CoreNodeUtils;
import peergos.shared.corenode.UserPublicKeyLink;
import peergos.shared.crypto.*;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

import com.sun.net.httpserver.*;
import peergos.shared.ipfs.api.*;
import peergos.shared.merklebtree.MaybeMultihash;
import peergos.shared.util.Args;
import peergos.shared.util.Serialize;

public class HTTPCoreNodeServer
{
    private static final int CONNECTION_BACKLOG = 100;
    
    public static final String CORE_URL = "core/";
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
            String[] subComponents = path.substring(CORE_URL.length()).split("/");
            String method = subComponents[0];
//            System.out.println("core method "+ method +" from path "+ path);

            try {
                switch (method)
                {
                    case "getChain":
                        getChain(din, dout);
                        break;
                    case "updateChain":
                        updateChain(din, dout);
                        break;
                    case "getPublicKey":
                        getPublicKey(din, dout);
                        break;
                    case "getUsername":
                        getUsername(din, dout);
                        break;
                    case "getUsernamesGzip":
                        exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        getAllUsernamesGzip(subComponents.length > 1 ? subComponents[1] : "", din, dout);
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

        void getChain(DataInputStream din, DataOutputStream dout) throws Exception
        {
            String username = CoreNodeUtils.deserializeString(din);

            List<UserPublicKeyLink> chain = coreNode.getChain(username).get();
            dout.writeInt(chain.size());
            for (UserPublicKeyLink link : chain) {
                link.owner.serialize(dout);
                Serialize.serialize(link.toByteArray(), dout);
            }
        }

        void updateChain(DataInputStream din, DataOutputStream dout) throws Exception
        {
            String username = CoreNodeUtils.deserializeString(din);
            int count = din.readInt();
            List<UserPublicKeyLink> res = new ArrayList<>();
            for (int i=0; i < count; i++) {
                UserPublicKey owner = UserPublicKey.deserialize(din);
                res.add(UserPublicKeyLink.fromByteArray(owner, Serialize.deserializeByteArray(din, UserPublicKeyLink.MAX_SIZE)));
            }
            boolean isAdded = coreNode.updateChain(username, res).get();

            dout.writeBoolean(isAdded);
        }

        void getPublicKey(DataInputStream din, DataOutputStream dout) throws Exception
        {
            String username = CoreNodeUtils.deserializeString(din);
            Optional<UserPublicKey> k = coreNode.getPublicKey(username).get();
            dout.writeBoolean(k.isPresent());
            if (!k.isPresent())
                return;
            byte[] b = k.get().serialize();
            dout.writeInt(b.length);
            dout.write(b);
        }

        void getUsername(DataInputStream din, DataOutputStream dout) throws Exception
        {
            byte[] publicKey = CoreNodeUtils.deserializeByteArray(din);
            String k = coreNode.getUsername(UserPublicKey.fromByteArray(publicKey)).get();
            if (k == null)
                k="";
            Serialize.serialize(k, dout);
        }

        void getAllUsernamesGzip(String prefix, DataInputStream din, DataOutputStream dout) throws Exception
        {
            List<String> res = coreNode.getUsernames(prefix).get();
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            GZIPOutputStream gout = new GZIPOutputStream(bout);
            gout.write(JSONParser.toString(res).getBytes());
            gout.flush();
            gout.close();
            dout.write(bout.toByteArray());
        }

        void followRequest(DataInputStream din, DataOutputStream dout) throws Exception
        {
            byte[] encodedKey = Serialize.deserializeByteArray(din, UserPublicKey.MAX_SIZE);
            UserPublicKey target = UserPublicKey.deserialize(new DataInputStream(new ByteArrayInputStream(encodedKey)));
            byte[] encodedSharingPublicKey = CoreNodeUtils.deserializeByteArray(din);

            boolean followRequested = coreNode.followRequest(target, encodedSharingPublicKey).get();
            dout.writeBoolean(followRequested);
        }
        void getFollowRequests(DataInputStream din, DataOutputStream dout) throws Exception
        {
            byte[] encodedKey = Serialize.deserializeByteArray(din, UserPublicKey.MAX_SIZE);
            UserPublicKey ownerPublicKey = UserPublicKey.deserialize(new DataInputStream(new ByteArrayInputStream(encodedKey)));
            byte[] res = coreNode.getFollowRequests(ownerPublicKey).get();
            Serialize.serialize(res, dout);
        }
        void removeFollowRequest(DataInputStream din, DataOutputStream dout) throws Exception
        {
            byte[] encodedKey = Serialize.deserializeByteArray(din, UserPublicKey.MAX_SIZE);
            UserPublicKey owner = UserPublicKey.deserialize(new DataInputStream(new ByteArrayInputStream(encodedKey)));
            byte[] signedFollowRequest = CoreNodeUtils.deserializeByteArray(din);

            boolean isRemoved = coreNode.removeFollowRequest(owner, signedFollowRequest).get();
            dout.writeBoolean(isRemoved);
        }

        void addMetadataBlob(DataInputStream din, DataOutputStream dout) throws Exception
        {
            byte[] ownerPublicKey = CoreNodeUtils.deserializeByteArray(din);
            byte[] encodedSharingPublicKey = CoreNodeUtils.deserializeByteArray(din);
            byte[] signedPayload = CoreNodeUtils.deserializeByteArray(din);
            boolean isAdded = coreNode.setMetadataBlob(
                    UserPublicKey.fromByteArray(ownerPublicKey),
                    UserPublicKey.fromByteArray(encodedSharingPublicKey),
                    signedPayload).get();
            dout.writeBoolean(isAdded);
        }

        void removeMetadataBlob(DataInputStream din, DataOutputStream dout) throws Exception
        {
            byte[] encodedWriterPublicKey = CoreNodeUtils.deserializeByteArray(din);
            byte[] signedPayload = CoreNodeUtils.deserializeByteArray(din);
            boolean isAdded = coreNode.removeMetadataBlob(
                    UserPublicKey.fromByteArray(encodedWriterPublicKey),
                    signedPayload).get();
            dout.writeBoolean(isAdded);
        }

        void getMetadataBlob(DataInputStream din, DataOutputStream dout) throws Exception
        {
            byte[] encodedSharingKey = CoreNodeUtils.deserializeByteArray(din);
            MaybeMultihash metadataBlob = coreNode.getMetadataBlob(
                    UserPublicKey.fromByteArray(encodedSharingKey)).get();

            metadataBlob.serialize(dout);
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
        server.createContext("/" + CORE_URL, ch);
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


    public static void createAndStart(String keyfile, char[] passphrase, int port, CoreNode coreNode, Args args)
    {
        // eventually will need our own keypair to sign traffic to other core nodes
        try {
            String hostname = args.getArg("domain", "localhost");
            System.out.println("Starting core node server listening on: " + hostname+":"+port +" proxying to "+coreNode);
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
