
package peergos.corenode;

import peergos.crypto.*;
import peergos.user.UserContext;
import peergos.util.ArrayOps;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.util.*;
import java.net.*;
import java.io.*;
import static peergos.corenode.HTTPCoreNodeServer.*;


public class HTTPCoreNode extends AbstractCoreNode
{
    private final URL coreNodeURL;

    public HTTPCoreNode(URL coreNodeURL)
    {
        System.out.println("Creating HTTP Corenode API at "+coreNodeURL);
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
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("getPublicKey", dout);
            Serialize.serialize(username, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
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

    @Override public boolean updateStaticData(String username, byte[] signedHash, byte[] staticData)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("updateStaticData", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(signedHash, dout);
            Serialize.serialize(staticData, dout);
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
    
    @Override public synchronized byte[] getStaticData(String username)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("getStaticData", dout);
            Serialize.serialize(username, dout);
            dout.flush();
        
            return deserializeByteArray(din); 
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    @Override public String getUsername(byte[] publicKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("getUsername", dout);
            Serialize.serialize(publicKey, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return Serialize.deserializeString(din, UserContext.MAX_USERNAME_SIZE);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    @Override public boolean addUsername(String username, byte[] encodedUserKey, byte[] signedHash, byte[] staticData)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            Serialize.serialize("addUsername", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(encodedUserKey, dout);
            Serialize.serialize(signedHash, dout);
            Serialize.serialize(staticData, dout);
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

   @Override public boolean followRequest(String target, byte[] encryptedPermission)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);

            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
        
            Serialize.serialize("followRequest", dout);
            Serialize.serialize(target, dout);
            Serialize.serialize(encryptedPermission, dout);
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
    @Override public byte[] getFollowRequests(String username)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);

            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("getFollowRequests", dout);
            Serialize.serialize(username, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return deserializeByteArray(din);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
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
            
            Serialize.serialize("removeFollowRequest", dout);
            Serialize.serialize(target, dout);
            Serialize.serialize(data, dout);
            Serialize.serialize(signedHash, dout);
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

            Serialize.serialize("allowSharingKey", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(encodedSharingPublicKey, dout);
            Serialize.serialize(signedHash, dout);
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
            
            Serialize.serialize("banSharingKey", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(encodedSharingPublicKey, dout);
            Serialize.serialize(signedHash, dout);
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
   @Override public boolean addMetadataBlob(String username, byte[] encodedSharingPublicKey, byte[] mapKey, byte[] metadataBlob, byte[] sharingKeySignedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("addMetadataBlob", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(encodedSharingPublicKey, dout);
            Serialize.serialize(mapKey, dout);
            Serialize.serialize(metadataBlob, dout);
            Serialize.serialize(sharingKeySignedHash, dout);
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
   @Override public boolean removeMetadataBlob(String username, byte[] encodedSharingKey, byte[] mapKey, byte[] sharingKeySignedMapKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            Serialize.serialize("removeMetadataBlob", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(encodedSharingKey, dout);
            Serialize.serialize(mapKey, dout);
            Serialize.serialize(sharingKeySignedMapKey, dout);
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
            
            Serialize.serialize("removeUsername", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(userKey, dout);
            Serialize.serialize(signedHash, dout);
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
            
            Serialize.serialize("getSharingKeys", dout);
            Serialize.serialize(username, dout);
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
   @Override public MetadataBlob getMetadataBlob(String username, byte[] encodedSharingKey, byte[] mapKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("getMetadataBlob", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(encodedSharingKey, dout);
            Serialize.serialize(mapKey, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            byte[] meta = deserializeByteArray(din);
            byte[] hashes = deserializeByteArray(din);
            return new MetadataBlob(new ByteArrayWrapper(meta), hashes);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    @Override public boolean isFragmentAllowed(String owner, byte[] encodedSharingKey, byte[] mapkey, byte[] hash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);

            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("isFragmentAllowed", dout);
            Serialize.serialize(owner, dout);
            Serialize.serialize(encodedSharingKey, dout);
            Serialize.serialize(mapkey, dout);
            Serialize.serialize(hash, dout);
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

    @Override public boolean registerFragmentStorage(String spaceDonor, InetSocketAddress node, String owner, byte[] encodedSharingKey, byte[] hash, byte[] signedKeyPlusHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("registerFragmentStorage", dout);
            Serialize.serialize(spaceDonor, dout);
            Serialize.serialize(node.getAddress().getAddress(), dout);
            dout.writeInt(node.getPort());
            Serialize.serialize(owner, dout);
            Serialize.serialize(encodedSharingKey, dout);
            Serialize.serialize(hash, dout);
            Serialize.serialize(signedKeyPlusHash, dout);
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

            Serialize.serialize("getQuota", dout);
            Serialize.serialize(user, dout);
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

            Serialize.serialize("getUsage", dout);
            Serialize.serialize(username, dout);
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

   @Override public boolean addFragmentHashes(String username, byte[] encodedSharingPublicKey, byte[] mapKey, byte[] metadataBlob, List<ByteArrayWrapper> allHashes, byte[] sharingKeySignedHash)
   {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("addFragmentHashes", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(encodedSharingPublicKey, dout);
            Serialize.serialize(mapKey, dout);
            Serialize.serialize(metadataBlob, dout);
            Serialize.serialize(ArrayOps.concat(allHashes), dout);
            Serialize.serialize(sharingKeySignedHash, dout);
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
    @Override public byte[] getFragmentHashes(String username, UserPublicKey sharingKey, byte[] mapKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);

            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("getFragmentHashes", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(sharingKey.getPublicKey(), dout);
            Serialize.serialize(mapKey, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return Serialize.deserializeByteArray(din, Integer.MAX_VALUE);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public void close()     
    {}
}
