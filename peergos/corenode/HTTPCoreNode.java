
package peergos.corenode;

import peergos.crypto.*;
import peergos.user.UserContext;
import peergos.util.*;

import java.sql.*;
import java.util.*;
import java.net.*;
import java.io.*;
import static peergos.corenode.HTTPCoreNodeServer.*;


public class HTTPCoreNode extends AbstractCoreNode
{
    private final URL coreNodeURL;

    public static AbstractCoreNode getInstance() throws IOException {
        return new HTTPCoreNode(new URL("http://"+ SSL.getCommonName(SSL.getCoreServerCertificates()[0])+":"+AbstractCoreNode.PORT+"/"));
    }

    public HTTPCoreNode(URL coreNodeURL)
    {
        System.out.println("Creating HTTP Corenode API at " + coreNodeURL);
        this.coreNodeURL = coreNodeURL;
    }

    public URL getCoreNodeURL(){return coreNodeURL;}

    public URL buildURL(String method) throws IOException {
        try {
            return new URL(coreNodeURL, method);
        } catch (MalformedURLException mexican) {
            throw new IOException(mexican);
        }
    }

    @Override public UserPublicKey getPublicKey(String username) 
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getPublicKey").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


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

    @Override public boolean updateStaticData(UserPublicKey owner, byte[] signedStaticData)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/updateStaticData").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(owner.getPublicKeys(), dout);
            Serialize.serialize(signedStaticData, dout);
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
    
    @Override public synchronized byte[] getStaticData(UserPublicKey owner)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getStaticData").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);

            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            Serialize.serialize(owner.getPublicKeys(), dout);
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

    @Override public String getUsername(byte[] publicKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getUsername").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


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

    @Override public boolean addUsername(String username, byte[] encodedUserKey, byte[] signed, byte[] staticData)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/addUsername").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            

            Serialize.serialize(username, dout);
            Serialize.serialize(encodedUserKey, dout);
            Serialize.serialize(signed, dout);
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

   @Override public boolean followRequest(UserPublicKey target, byte[] encryptedPermission)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/followRequest").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);

            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize(target.getPublicKeys(), dout);
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
    @Override public byte[] getFollowRequests(UserPublicKey owner)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getFollowRequests").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);

            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(owner.getPublicKeys(), dout);
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
   @Override public boolean removeFollowRequest(UserPublicKey owner, byte[] data, byte[] signedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/removeFollowRequest").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            

            Serialize.serialize(owner.getPublicKeys(), dout);
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
   
   @Override public boolean allowSharingKey(UserPublicKey owner, byte[] signedSharingPublicKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/allowSharingKey").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(owner.getPublicKeys(), dout);
            Serialize.serialize(signedSharingPublicKey, dout);
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
   @Override public boolean banSharingKey(UserPublicKey owner, byte[] encodedSharingPublicKey, byte[] signedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/banSharingKey").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            

            Serialize.serialize(owner.getPublicKeys(), dout);
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
   @Override public boolean addMetadataBlob(UserPublicKey owner, byte[] encodedSharingPublicKey, byte[] mapKey, byte[] metadataBlob, byte[] sharingKeySignedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/addMetadataBlob").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(owner.getPublicKeys(), dout);
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
   @Override public boolean removeMetadataBlob(UserPublicKey owner, byte[] encodedSharingKey, byte[] mapKey, byte[] sharingKeySignedMapKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/removeMetadataBlob").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            

            Serialize.serialize(owner.getPublicKeys(), dout);
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
            conn = (HttpURLConnection) buildURL("core/removeUsername").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            

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
   @Override public Iterator<UserPublicKey> getSharingKeys(UserPublicKey owner)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getSharingKeys").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            

            Serialize.serialize(owner.getPublicKeys(), dout);
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
   @Override public MetadataBlob getMetadataBlob(UserPublicKey owner, byte[] encodedSharingKey, byte[] mapKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getMetadataBlob").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(owner.getPublicKeys(), dout);
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

    @Override public boolean isFragmentAllowed(UserPublicKey owner, byte[] encodedSharingKey, byte[] mapkey, byte[] hash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/isFragmentAllowed").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);

            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(owner.getPublicKeys(), dout);
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

    @Override public boolean registerFragmentStorage(UserPublicKey spaceDonor, InetSocketAddress node, UserPublicKey owner, byte[] signedKeyPlusHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/registerFragmentStorage").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(spaceDonor.getPublicKeys(), dout);
            Serialize.serialize(node.getAddress().getAddress(), dout);
            dout.writeInt(node.getPort());
            Serialize.serialize(owner.getPublicKeys(), dout);
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

   @Override public long getQuota(UserPublicKey user)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getQuota").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(user.getPublicKeys(), dout);
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
   @Override public long getUsage(UserPublicKey owner)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getUsage").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(owner.getPublicKeys(), dout);
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

   @Override public boolean addFragmentHashes(UserPublicKey owner, byte[] encodedSharingPublicKey, byte[] mapKey, byte[] metadataBlob, List<ByteArrayWrapper> allHashes, byte[] sharingKeySignedHash)
   {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/addFragmentHashes").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(owner.getPublicKeys(), dout);
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
    @Override public byte[] getFragmentHashes(UserPublicKey owner, UserPublicKey sharingKey, byte[] mapKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getFragmentHashes").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);

            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(owner.getPublicKeys(), dout);
            Serialize.serialize(sharingKey.getPublicKeys(), dout);
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
