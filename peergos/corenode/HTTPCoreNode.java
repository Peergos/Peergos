
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

    @Override public UserPublicKey getPublicKey(String username) 
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize("getPublicKey", dout);
            serialize(username, dout);
            dout.flush();
            
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


    @Override public boolean addUsername(String username, byte[] encodedUserKey, byte[] signedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            serialize("addUsername", dout);
            serialize(username, dout);
            serialize(encodedUserKey, dout);
            serialize(signedHash, dout);
            dout.flush();
            
            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

   @Override public boolean followRequest(String target, byte[] encodedSharingPublicKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);

            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
        
            serialize("followRequest", dout);    
            serialize(target, dout);
            serialize(encodedSharingPublicKey, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
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
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            serialize("removeFollowRequest", dout);
            serialize(target, dout);
            serialize(data, dout);
            serialize(signedHash, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
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
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize("allowSharingKey", dout);
            serialize(username, dout);
            serialize(encodedSharingPublicKey, dout);
            serialize(signedHash, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
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
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            serialize("banSharingKey", dout);
            serialize(username, dout);
            serialize(encodedSharingPublicKey, dout);
            serialize(signedHash, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
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
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize("addFragment", dout);
            serialize(username, dout);
            serialize(encodedSharingPublicKey, dout);
            serialize(mapKey, dout);
            serialize(fragmentData, dout);
            serialize(sharingKeySignedHash, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
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
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            serialize("removeFragment", dout);
            serialize(username, dout);
            serialize(encodedSharingKey, dout);
            serialize(mapKey, dout);
            serialize(sharingKeySignedMapKey, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
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
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            serialize("removeUsername", dout);
            serialize(username, dout);
            serialize(userKey, dout);
            serialize(signedHash, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public Iterator<UserPublicKey> getSharingKeys(String username)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            serialize("getSharingKeys", dout); 
            serialize(username, dout); 
            dout.flush();

            ArrayList<UserPublicKey> sharingKeys = new ArrayList<UserPublicKey>();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            int l = 0;
            while((l = din.readInt()) >=0)
            {
                byte[] b = getByteArray(l);
                din.readFully(b); 
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
   @Override public ByteArrayWrapper getFragment(String username, byte[] encodedSharingKey, byte[] mapKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize("getFragment", dout);
            serialize(username, dout);
            serialize(encodedSharingKey, dout);
            serialize(mapKey, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
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
   @Override public boolean registerFragment(String recipient, InetSocketAddress node, byte[] hash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize("registerFragment", dout);
            serialize(recipient, dout);
            serialize(node.getAddress().getAddress(), dout);
            dout.writeInt(node.getPort());
            serialize(hash, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public long getQuota(String user) 
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize("getQuota", dout);
            serialize(user, dout);
            dout.flush();
            
            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readLong();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return -1l;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public long getUsage(String username)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            serialize("getUsage", dout);
            serialize(username, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
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
