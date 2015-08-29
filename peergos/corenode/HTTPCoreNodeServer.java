package peergos.corenode;

import peergos.crypto.*;

import java.util.*;
import java.net.*;
import java.io.*;

import com.sun.net.httpserver.*;
import peergos.util.Args;
import peergos.util.ArrayOps;
import peergos.util.Serialize;

public class HTTPCoreNodeServer
{
    private static final int CONNECTION_BACKLOG = 100;
    private static final int HANDLER_THREAD_COUNT= 100;

    private static final int MAX_KEY_LENGTH = 1024*1024;
    private static final int MAX_BLOB_LENGTH = 4*1024*1024;
    public static final String CORE_URL = "/core/";

    public static class CoreNodeHandler implements HttpHandler
    {
        private final AbstractCoreNode coreNode;

        public CoreNodeHandler(AbstractCoreNode coreNode) {
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
            String method = path.substring(path.lastIndexOf("/")+1);
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
                    case "getStaticData":
                        getClearanceData(din, dout);
                        break;
                    case "updateStaticData":
                        updateClearanceData(din, dout);
                        break;
                    case "getUsername":
                        getUsername(din, dout);
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
                    case "allowSharingKey":
                        allowSharingKey(din, dout);
                        break;
                    case "banSharingKey":
                        banSharingKey(din, dout);
                        break;
                    case "addMetadataBlob":
                        addMetadataBlob(din, dout);
                        break;
                    case "removeMetadataBlob":
                        removeMetadataBlob(din, dout);
                        break;
                    case "getSharingKeys":
                        getSharingKeys(din, dout);
                        break;
                    case "getMetadataBlob":
                        getMetadataBlob(din, dout);
                        break;
                    case "addFragmentHashes":
                        addFragmentHashes(din, dout);
                        break;
                    case "getFragmentHashes":
                        getFragmentHashes(din, dout);
                        break;
                    case "isFragmentAllowed":
                        isFragmentAllowed(din, dout);
                        break;
                    case "registerFragmentStorage":
                        registerFragmentStorage(din, dout);
                        break;
                    case "getQuota":
                        getQuota(din,dout);
                        break;
                    case "getUsage":
                        getUsage(din,dout);
                        break;
                    case "removeUsername":
                        removeUsername(din,dout);
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

        void allowSharingKey(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] owner = deserializeByteArray(din);
            byte[] signedSharingPublicKey = deserializeByteArray(din);

            boolean isAllowed = coreNode.allowSharingKey(new UserPublicKey(owner), signedSharingPublicKey);
            dout.writeBoolean(isAllowed);
        }

        void banSharingKey(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] owner = deserializeByteArray(din);
            byte[] encodedSharingPublicKey = deserializeByteArray(din);
            byte[] signedHash = deserializeByteArray(din);
            boolean isBanned = coreNode.banSharingKey(new UserPublicKey(owner), encodedSharingPublicKey, signedHash);

            dout.writeBoolean(isBanned);
        }

        void addMetadataBlob(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] encodedOwnerPublicKey = deserializeByteArray(din);
            byte[] encodedSharingPublicKey = deserializeByteArray(din);
            byte[] mapKey = deserializeByteArray(din);
            byte[] metaDataBlob = deserializeByteArray(din);
            byte[] signedHash = deserializeByteArray(din);
            boolean isAdded = coreNode.addMetadataBlob(new UserPublicKey(encodedOwnerPublicKey), encodedSharingPublicKey, mapKey, metaDataBlob, signedHash);
            dout.writeBoolean(isAdded);
        }
        
        void updateClearanceData(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] owner = deserializeByteArray(din);
            byte[] signedStaticData = deserializeByteArray(din);

            boolean isUpdated = coreNode.updateStaticData(new UserPublicKey(owner), signedStaticData);
            dout.writeBoolean(isUpdated);
        }
        
        void getClearanceData(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] owner = deserializeByteArray(din);
            byte[] userClearanceData = coreNode.getStaticData(new UserPublicKey(owner));
            if (userClearanceData == null)
                dout.writeInt(0);
            else
                Serialize.serialize(userClearanceData, dout);
        }

        void removeMetadataBlob(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] ownerPublicKey = deserializeByteArray(din);
            byte[] encodedSharingKey = deserializeByteArray(din);
            byte[] mapKey = deserializeByteArray(din);
            byte[] sharingKeySignedHash = deserializeByteArray(din);

            boolean isRemoved = coreNode.removeMetadataBlob(new UserPublicKey(ownerPublicKey), encodedSharingKey, mapKey, sharingKeySignedHash);
            dout.writeBoolean(isRemoved);
        }
        void getSharingKeys(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] owner = deserializeByteArray(din);
            Iterator<UserPublicKey> it = coreNode.getSharingKeys(new UserPublicKey(owner));
            while (it.hasNext())
            {
                byte[] b = it.next().getPublicKeys();
                dout.writeInt(b.length);
                dout.write(b);
            }
            dout.writeInt(-1);

        }

        void getMetadataBlob(DataInputStream din, DataOutputStream dout) throws IOException
        {
            UserPublicKey owner = new UserPublicKey(deserializeByteArray(din));
            byte[] encodedSharingKey = deserializeByteArray(din);
            byte[] mapKey = deserializeByteArray(din);
            AbstractCoreNode.MetadataBlob b = coreNode.getMetadataBlob(owner, encodedSharingKey, mapKey);
            if (b == null)
            {
                Serialize.serialize(new byte[0], dout);
                Serialize.serialize(new byte[0], dout);
                return;
            }
            Serialize.serialize(b.metadata.data, dout);
            if (b.fragmentHashes != null)
                Serialize.serialize(b.fragmentHashes, dout);
            else
                Serialize.serialize(new byte[0], dout);
        }
        void addFragmentHashes(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] owner = deserializeByteArray(din);
            byte[] sharingKey = deserializeByteArray(din);
            byte[] mapKey = deserializeByteArray(din);
            byte[] metadataBlob = deserializeByteArray(din);
            byte[] allHashes = deserializeByteArray(din);
            byte[] sharingKeySignedHash = deserializeByteArray(din);
            boolean isAllowed = coreNode.addFragmentHashes(new UserPublicKey(owner), sharingKey, mapKey, metadataBlob, ArrayOps.split(allHashes, UserPublicKey.HASH_BYTES), sharingKeySignedHash);
            dout.writeBoolean(isAllowed);
        }
        void getFragmentHashes(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] owner = deserializeByteArray(din);
            byte[] sharingKey = deserializeByteArray(din);
            byte[] mapKey = deserializeByteArray(din);
            Serialize.serialize(coreNode.getFragmentHashes(new UserPublicKey(owner), new UserPublicKey(sharingKey), mapKey), dout);
        }
        void isFragmentAllowed(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] owner = deserializeByteArray(din);
            byte[] sharingKey =deserializeByteArray(din);
            byte[] mapKey =deserializeByteArray(din);
            byte[] hash =deserializeByteArray(din);
            boolean isAllowed = coreNode.isFragmentAllowed(new UserPublicKey(owner), sharingKey, mapKey, hash);
            dout.writeBoolean(isAllowed);
        }

        void registerFragmentStorage(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] recipientPublicKey = deserializeByteArray(din);
            byte[] address = deserializeByteArray(din);
            InetAddress node = InetAddress.getByAddress(address);
            int port = din.readInt();
            byte[] owner = deserializeByteArray(din);
            byte[] signedKeyPlusHash = deserializeByteArray(din);
            
            boolean isRegistered = coreNode.registerFragmentStorage(new UserPublicKey(recipientPublicKey), new InetSocketAddress(node, port), new UserPublicKey(owner), signedKeyPlusHash);
            dout.writeBoolean(isRegistered);
        }

        void getQuota(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] userPublicKey = deserializeByteArray(din);
            long quota = coreNode.getQuota(new UserPublicKey(userPublicKey));
            dout.writeLong(quota);
        }
        void getUsage(DataInputStream din, DataOutputStream dout) throws IOException
        {
            byte[] userPublicKey = deserializeByteArray(din);
            long usage = coreNode.getUsage(new UserPublicKey(userPublicKey));
            dout.writeLong(usage);
        }
        void removeUsername(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = deserializeString(din);
            byte[] userKey = deserializeByteArray(din);
            byte[] signedHash = deserializeByteArray(din);
            boolean isRemoved = coreNode.removeUsername(username, userKey, signedHash);
            dout.writeBoolean(isRemoved);
        }

        public void close() throws IOException{
            coreNode.close();
        }
    }

    private final HttpServer server;
    private final InetSocketAddress address; 
    private final CoreNodeHandler ch;

    public HTTPCoreNodeServer(AbstractCoreNode coreNode, InetSocketAddress address) throws IOException
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
        System.out.printf("Starting core node listening at %s:%d\n", address.getHostName(), address.getPort());
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

    public static void createAndStart(String keyfile, char[] passphrase, int port)
    {
        // eventually will need our own keypair to sign traffic to other core nodes our register ourselves with directory servers
        try {
            String hostname = Args.getArg("domain", "localhost");
            InetSocketAddress address = new InetSocketAddress(hostname, port);
            HTTPCoreNodeServer server = new HTTPCoreNodeServer(AbstractCoreNode.getDefault(), address);
            server.start();
        } catch (Exception e)
        {
            e.printStackTrace();
            System.out.println("Couldn't start Corenode server!");
        }
    }
}
