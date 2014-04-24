
package peergos.corenode;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;
import peergos.net.IP;

import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import java.io.*;
import static peergos.corenode.HTTPCoreNodeServer.*;


public class HTTPCoreNode extends AbstractCoreNode
{
    private final URL coreNodeURL;

    public HTTPCoreNode(URL coreNodeURL)
    {
        this.coreNodeURL = coreNodeURL;
    }

    public URL getCoreNodeURL(){return coreNodeURL;}

    @Override public synchronized UserPublicKey getPublicKey(String username) 
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize(username, dout);
            byte[] publicKey = deserializeByteArray(din); 
            return new UserPublicKey(publicKey);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }


    public boolean addUsername(String username, byte[] encodedUserKey, byte[] signedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            serialize(username, dout);
            serialize(encodedUserKey, dout);
            serialize(signedHash, dout);
            
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

   @Override public synchronized boolean followRequest(String target, byte[] encodedSharingPublicKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            serialize(target, dout);
            serialize(encodedSharingPublicKey, dout);

            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public boolean removeFollowRequest(String target, byte[] data, byte[] signedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            serialize(target, dout);
            serialize(data, dout);
            serialize(signedHash, dout);

            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public boolean allowSharingKey(String username, byte[] encodedSharingPublicKey, byte[] signedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize(username, dout);
            serialize(encodedSharingPublicKey, dout);
            serialize(signedHash, dout);

            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public boolean banSharingKey(String username, byte[] encodedSharingPublicKey, byte[] signedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            serialize(username, dout);
            serialize(encodedSharingPublicKey, dout);
            serialize(signedHash, dout);

            return din.readBoolean();

        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public boolean addFragment(String username, byte[] encodedSharingPublicKey, byte[] mapKey, byte[] fragmentData, byte[] sharingKeySignedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize(username, dout);
            serialize(encodedSharingPublicKey, dout);
            serialize(mapKey, dout);
            serialize(fragmentData, dout);
            serialize(sharingKeySignedHash, dout);

            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public boolean removeFragment(String username, byte[] encodedSharingKey, byte[] mapKey, byte[] sharingKeySignedMapKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            serialize(username, dout);
            serialize(encodedSharingKey, dout);
            serialize(mapKey, dout);
            serialize(sharingKeySignedMapKey, dout);

            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public boolean removeUsername(String username, byte[] userKey, byte[] signedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            serialize(username, dout);
            serialize(userKey, dout);
            serialize(signedHash, dout);

            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public synchronized Iterator<UserPublicKey> getSharingKeys(String username)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            ArrayList<UserPublicKey> sharingKeys = new ArrayList<UserPublicKey>();
            while(din.readInt() >=0)
            {
                byte[] b = deserializeByteArray(din);
                sharingKeys.add(new UserPublicKey(b));
            }
            return sharingKeys.iterator();

        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public synchronized ByteArrayWrapper getFragment(String username, byte[] encodedSharingKey, byte[] mapKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize(username, dout);
            serialize(encodedSharingKey, dout);
            serialize(mapKey, dout);

            byte[] b = deserializeByteArray(din);
            return new ByteArrayWrapper(b);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public synchronized boolean registerFragment(String recipient, InetSocketAddress node, byte[] hash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize(recipient, dout);
            serialize(node.getAddress().getAddress(), dout);
            dout.writeInt(node.getPort());
            serialize(hash, dout);

            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public synchronized long getQuota(String user) 
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize(user, dout);
            
            return din.readLong();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return -1l;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public synchronized long getUsage(String username)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize(username, dout);
            return din.readLong();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return -1l;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public void close()     
    {}
}
