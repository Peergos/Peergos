
package peergos.corenode;

import org.ipfs.api.Multihash;
import peergos.crypto.*;
import peergos.util.*;

import java.net.*;
import java.io.*;
import java.util.*;

import static peergos.corenode.HTTPCoreNodeServer.*;


public class HTTPCoreNode implements CoreNode
{
    private final URL coreNodeURL;

    public static CoreNode getInstance() throws IOException {
        return new HTTPCoreNode(new URL("http://"+ SSL.getCommonName(SSL.getCoreServerCertificates()[0])+":"+HTTPCoreNodeServer.PORT+"/"));
    }

    public HTTPCoreNode(URL coreNodeURL)
    {
        System.out.println("Creating HTTP Corenode API at " + coreNodeURL);
        this.coreNodeURL = coreNodeURL;
    }

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
            return UserPublicKey.deserialize(new DataInputStream(new ByteArrayInputStream(publicKey)));
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    @Override public String getUsername(UserPublicKey publicKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getUsername").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(publicKey.serialize(), dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return Serialize.deserializeString(din, CoreNode.MAX_USERNAME_SIZE);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    @Override public List<UserPublicKeyLink> getChain(String username) {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getChain").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize(username, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            int count = din.readInt();
            List<UserPublicKeyLink> res = new ArrayList<>();
            for (int i=0; i < count; i++) {
                UserPublicKey owner = UserPublicKey.deserialize(din);
                res.add(UserPublicKeyLink.fromByteArray(owner, Serialize.deserializeByteArray(din, UserPublicKeyLink.MAX_SIZE)));
            }
            return res;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            throw new IllegalStateException(ioe);
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    @Override public boolean updateChain(String username, List<UserPublicKeyLink> chain) {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/updateChain").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize(username, dout);
            dout.writeInt(chain.size());
            for (UserPublicKeyLink link : chain) {
                link.owner.serialize(dout);
                Serialize.serialize(link.toByteArray(), dout);
            }
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

            Serialize.serialize(target.serialize(), dout);
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

    @Override public byte[] getAllUsernamesGzip() throws IOException
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getAllUsernamesGzip").openConnection();
            conn.setDoInput(true);

            DataInputStream din = new DataInputStream(conn.getInputStream());
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int r;
            while ((r = din.read(tmp)) >= 0) {
                bout.write(tmp, 0, r);
            }
            return bout.toByteArray();
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


            Serialize.serialize(owner.serialize(), dout);
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
   @Override public boolean removeFollowRequest(UserPublicKey owner, byte[] signedRequest)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/removeFollowRequest").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            

            Serialize.serialize(owner.serialize(), dout);
            Serialize.serialize(signedRequest, dout);
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
   
   @Override public boolean setMetadataBlob(UserPublicKey ownerPublicKey, UserPublicKey sharingPublicKey, byte[] sharingKeySignedPayload)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/addMetadataBlob").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize(ownerPublicKey.serialize(), dout);
            Serialize.serialize(sharingPublicKey.serialize(), dout);
            Serialize.serialize(sharingKeySignedPayload, dout);
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

    @Override public boolean removeMetadataBlob(UserPublicKey encodedSharingPublicKey, byte[] sharingKeySignedPayload)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/removeMetadataBlob").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);

            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize(encodedSharingPublicKey.serialize(), dout);
            Serialize.serialize(sharingKeySignedPayload, dout);
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

    @Override public Multihash getMetadataBlob(UserPublicKey encodedSharingKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getMetadataBlob").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(encodedSharingKey.serialize(), dout);
            dout.flush();


            DataInputStream din = new DataInputStream(conn.getInputStream());
            byte[] meta = deserializeByteArray(din);
            return new Multihash(meta);
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
