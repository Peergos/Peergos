
package peergos.corenode;

import peergos.crypto.*;
import peergos.user.UserContext;
import peergos.util.*;

import java.sql.*;
import java.util.*;
import java.net.*;
import java.io.*;
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
            return new UserPublicKey(publicKey);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    @Override public boolean setStaticData(UserPublicKey owner, byte[] signedStaticData)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/setStaticData").openConnection();
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
   @Override public boolean removeFollowRequest(UserPublicKey owner, byte[] signedRequest)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/removeFollowRequest").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            

            Serialize.serialize(owner.getPublicKeys(), dout);
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
   
   @Override public boolean setMetadataBlob(byte[] ownerPublicKey, byte[] sharingPublicKey, byte[] sharingKeySignedPayload)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/addMetadataBlob").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize(ownerPublicKey, dout);
            Serialize.serialize(sharingPublicKey, dout);
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

    @Override public boolean removeMetadataBlob(byte[] encodedSharingPublicKey, byte[] sharingKeySignedPayload)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/removeMetadataBlob").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);

            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize(encodedSharingPublicKey, dout);
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

    @Override public byte[] getMetadataBlob(byte[] encodedSharingKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL("core/getMetadataBlob").openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());


            Serialize.serialize(encodedSharingKey, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            byte[] meta = deserializeByteArray(din);
            return meta;
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
