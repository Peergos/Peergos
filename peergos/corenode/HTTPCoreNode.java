package peergos.corenode;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;

import java.util.*;
import java.net.*;
import java.io.*;

import com.sun.net.httpserver.*;
public class HTTPCoreNode
{
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

        void removeFragment(DataInputStream din, DataOutputStream dout)
        {
        }
        void getSharingKeys(DataInputStream din, DataOutputStream dout)
        {
        }

        void addStorageNodeState(DataInputStream din, DataOutputStream dout)
        {
        }

        void registerFragment(DataInputStream din, DataOutputStream dout)
        {
        }
        void getQuota(DataInputStream din, DataOutputStream dout)
        {
        }
        void getUsage(DataInputStream din, DataOutputStream dout)
        {
        }
        void removeUsername(DataInputStream din, DataOutputStream dout)
        {
        } 

    }
    
    private AbstractCoreNode coreNode;

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
