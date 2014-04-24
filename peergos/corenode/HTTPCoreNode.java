package peergos.corenode;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;
import peergos.net.IP;

import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import java.io.*;

import com.sun.net.httpserver.*;

public class HTTPCoreNode
{
    private static final int CONNECTION_BACKLOG = 100;
    private static final int HANDLER_THREAD_COUNT= 100;

    private static final int MAX_KEY_LENGTH = 4096;
    private static final int MAX_BLOB_LENGTH = 4*1024*1024;

    class CoreNodeHandler implements HttpHandler 
    {
        public void handle(HttpExchange httpExchange) throws IOException 
        {
            DataInputStream din = new DataInputStream(httpExchange.getRequestBody());
            DataOutputStream dout = new DataOutputStream(httpExchange.getResponseBody());

            String urlStem = httpExchange.getRequestURI().getPath();
            try
            {
                switch (urlStem)
                {
                    case "addUsername": 
                        addUsername(din, dout);
                        break;
                    case "getPublicKey":
                        getPublicKey(din, dout);
                        break;
                    default:
                        throw new IOException("Unknown method "+ urlStem);
                }
            } finally {
                dout.flush();
                din.close();
                dout.close();
            }

        }

        void addUsername(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = getString(din);
            byte[] encodedKey = getByteArray(din);
            byte[] hash = getByteArray(din);

            boolean isAdded = coreNode.addUsername(username, encodedKey, hash);
            dout.writeBoolean(isAdded);
        }

        void getPublicKey(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = getString(din);
            UserPublicKey k = coreNode.getPublicKey(username);
            if (k == null)
                return;
            byte[] b = k.getPublicKey();
            dout.writeInt(b.length);
            dout.write(b);
        }

        void followRequest(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String target = getString(din);
            byte[] encodedSharingPublicKey = getByteArray(din);

            boolean followRequested = coreNode.followRequest(target, encodedSharingPublicKey);
            dout.writeBoolean(followRequested);
        }
        void removeFollowRequest(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String target = getString(din);
            byte[] encodedSharingPublicKey = getByteArray(din);
            byte[] hash = getByteArray(din);

            boolean isRemoved = coreNode.removeFollowRequest(target, encodedSharingPublicKey, hash);
            dout.writeBoolean(isRemoved);
        }

        void allowSharingKey(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = getString(din);
            byte[] encodedSharingPublicKey = getByteArray(din);
            byte[] signedHash = getByteArray(din);
            boolean isAllowed = coreNode.allowSharingKey(username, encodedSharingPublicKey, signedHash);

            dout.writeBoolean(isAllowed);
        }

        void banSharingKey(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = getString(din);
            byte[] encodedSharingPublicKey = getByteArray(din);
            byte[] signedHash = getByteArray(din);
            boolean isBanned = coreNode.banSharingKey(username, encodedSharingPublicKey, signedHash);

            dout.writeBoolean(isBanned);
        }

        void addFragment(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = getString(din);
            byte[] encodedSharingPublicKey = getByteArray(din);
            byte[] mapKey = getByteArray(din);
            byte[] fragmentData = getByteArray(din);
            byte[] signedHash = getByteArray(din);

            boolean isAdded = coreNode.addFragment(username, encodedSharingPublicKey, mapKey, fragmentData, signedHash);
            dout.writeBoolean(isAdded);
        }

        void removeFragment(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = getString(din);
            byte[] encodedSharingKey = getByteArray(din);
            byte[] mapKey = getByteArray(din);
            byte[] sharingKeySignedHash = getByteArray(din);

            boolean isRemoved = coreNode.removeFragment(username, encodedSharingKey, mapKey, sharingKeySignedHash);
            dout.writeBoolean(isRemoved);
        }
        void getSharingKeys(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = getString(din);
            Iterator<UserPublicKey> it = coreNode.getSharingKeys(username);
            while (it.hasNext())
            {
                byte[] b = it.next().getPublicKey();
                dout.writeInt(b.length);
                dout.write(b);
            }
        }


        void getFragment(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = getString(din);
            byte[] encodedSharingKey = getByteArray(din);
            byte[] mapKey = getByteArray(din);
            ByteArrayWrapper b = coreNode.getFragment(username, encodedSharingKey, mapKey);
            byte[] bb = b.data;
            dout.writeInt(bb.length);
            dout.write(bb);
        }

        void registerFragment(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String recipient = getString(din);
            byte[] address = getByteArray(din);
            InetAddress node = InetAddress.getByAddress(address);
            int port = din.readInt();
            byte[] hash = getByteArray(din);
            
            boolean isRegistered = coreNode.registerFragment(recipient, new InetSocketAddress(node, port),hash);
            dout.writeBoolean(isRegistered);
        }
        void getQuota(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = getString(din);
            long quota = coreNode.getQuota(username);
            dout.writeLong(quota);
        }
        void getUsage(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = getString(din);
            long usage = coreNode.getUsage(username);
            dout.writeLong(usage);
        }
        void removeUsername(DataInputStream din, DataOutputStream dout) throws IOException
        {
            String username = getString(din);
            byte[] userKey = getByteArray(din);
            byte[] signedHash = getByteArray(din);
            boolean isRemoved = coreNode.removeUsername(username, userKey, signedHash);
            dout.writeBoolean(isRemoved);
        } 

    }

    private final HttpServer server;
    private final AbstractCoreNode coreNode;

    public HTTPCoreNode(AbstractCoreNode coreNode, int port) throws IOException
    {
        this.coreNode = coreNode;
        InetAddress us = IP.getMyPublicAddress();
        InetSocketAddress address = new InetSocketAddress(us, port);
        server = HttpServer.create(address, CONNECTION_BACKLOG);
        server.createContext("/", new CoreNodeHandler());
        server.setExecutor(Executors.newFixedThreadPool(HANDLER_THREAD_COUNT));
        server.start();
    }

    static byte[] getByteArray(DataInputStream din) throws IOException
    {
        int l = din.readInt();
        return getByteArray(l, MAX_KEY_LENGTH);
    }
    static byte[] getByteArray(int len) throws IOException
    {
        return getByteArray(len, MAX_KEY_LENGTH);
    }
    static byte[] getByteArray(int len, int maxLength) throws IOException
    {
        if (len > maxLength)
            throw new IOException("byte array of size "+ len +" too big.");
        return new byte[len];
    }
    static String getString(DataInputStream din) throws IOException
    {
        return getString(din, 1024);
    }
    static String getString(DataInputStream din, int len) throws IOException
    {
        int l = din.readInt();
        if (l > len)
            throw new IOException("String size "+ l + " too long.");
        byte[] b = new byte[l];
        din.readFully(b);
        return new String(b);
    }
}
