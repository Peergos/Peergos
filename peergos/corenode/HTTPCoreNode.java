package peergos.corenode;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;

import java.util.*;
import java.net.*;
import java.io.*;

import com.sun.net.httpserver.*;
public class HTTPCoreNode
{
    class CoreNodeHandler implements HttpHandler 
    {
        private HttpExchange exchange;
            private DataInputStream din;
            private DataOutputStream dout;
        public void handle(HttpExchange httpExchange) throws IOException 
        {
            exchange = httpExchange;
            din = new DataInputStream(exchange.getRequestBody());
            dout = new DataOutputStream(exchange.getResponseBody());

            String urlStem = httpExchange.getRequestURI().getPath();
                try
                {
            switch (urlStem)
            {
                case "addUsername": 
                    addUsername();
                    break;
                case "getPublicKey":
                    getPublicKey();
                    break;
                default:
                    throw new IOException("Unknown method "+ urlStem);
            }
                } finally {
                    din.close();
                    dout.close();
                }

        }

        void addUsername() throws IOException
        {
            String username = din.readUTF();
            int encodedLength = din.readInt();
            byte[] encodedKey = new byte[encodedLength];
            din.readFully(encodedKey);
            int hashLength = din.readInt();
            byte[] hash = new byte[hashLength];
            din.readFully(hash);

            boolean isAdded = coreNode.addUsername(username, encodedKey, hash);
            dout.writeBoolean(isAdded);
        }

        void getPublicKey() throws IOException
        {
            String username = din.readUTF();
            UserPublicKey k = coreNode.getPublicKey(username);
            if (k == null)
                return;
            byte[] b = k.getPublicKey();
            dout.writeInt(b.length);
            dout.write(b);
        }
        
        void followRequest() throws IOException
        {
            String target = din.readUTF();
            int length = din.readInt();
            byte[] encodedSharingPublicKey = new byte[length];
            din.readFully(encodedSharingPublicKey);

            boolean followRequested = coreNode.followRequest(target, encodedSharingPublicKey);
            dout.writeBoolean(followRequested);
        }
        void removeFollowRequest() throws IOException
        {
            String target = din.readUTF();
            int length = din.readInt();
            byte[] encodedSharingPublicKey = new byte[length];
            din.readFully(encodedSharingPublicKey);
            int hashLength = din.readInt();
            byte[] hash = new byte[hashLength];
            din.readFully(hash);

            boolean isRemoved = coreNode.removeFollowRequest(target, encodedSharingPublicKey, hash);
            dout.writeBoolean(isRemoved);
        }
        void allowSharingKey()
        {
        }
        void banSharingKey()
        {
        }
        void addFragment()
        {
        }
        void removeFragment()
        {
        }
        void getSharingKeys()
        {
        }
        
        void addStorageNodeState()
        {
        }

        void registerFragment()
        {
        }
        void getQuota()
        {
        }
        void getUsage()
        {
        }
        void removeUsername()
        {
        } 
    }
    private AbstractCoreNode coreNode;


}
