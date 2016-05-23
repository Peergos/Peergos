
package peergos.corenode;

import org.ipfs.api.Multihash;
import peergos.crypto.*;
import peergos.merklebtree.*;
import peergos.user.*;
import peergos.util.*;

import java.net.*;
import java.io.*;
import java.util.*;

import static peergos.corenode.HTTPCoreNodeServer.*;


public class HTTPCoreNode implements CoreNode
{
    private final HttpPoster poster;

    public static CoreNode getInstance(URL coreURL) throws IOException {
        return new HTTPCoreNode(new HttpPoster.Java(coreURL));
    }

    public HTTPCoreNode(HttpPoster poster)
    {
        System.out.println("Creating HTTP Corenode API at " + poster);
        this.poster = poster;
    }

    @Override public Optional<UserPublicKey> getPublicKey(String username)
    {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(username, dout);
            dout.flush();

            byte[] res = poster.post("core/getPublicKey", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            
            if (!din.readBoolean())
                return Optional.empty();
            byte[] publicKey = deserializeByteArray(din);
            return Optional.of(UserPublicKey.deserialize(new DataInputStream(new ByteArrayInputStream(publicKey))));
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return Optional.empty();
        }
    }

    @Override public String getUsername(UserPublicKey publicKey)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(publicKey.toUserPublicKey().serialize(), dout);
            dout.flush();

            byte[] res = poster.post("core/getUsername", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            return Serialize.deserializeString(din, CoreNode.MAX_USERNAME_SIZE);
        } catch (IOException ioe) {
            System.err.println("Couldn't connect to " + poster);
            ioe.printStackTrace();
            return null;
        }
    }

    @Override public List<UserPublicKeyLink> getChain(String username) {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(username, dout);
            dout.flush();

            byte[] res = poster.post("core/getChain", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            int count = din.readInt();
            List<UserPublicKeyLink> result = new ArrayList<>();
            for (int i=0; i < count; i++) {
                UserPublicKey owner = UserPublicKey.deserialize(din);
                result.add(UserPublicKeyLink.fromByteArray(owner, Serialize.deserializeByteArray(din, UserPublicKeyLink.MAX_SIZE)));
            }
            return result;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            throw new IllegalStateException(ioe);
        }
    }

    @Override public boolean updateChain(String username, List<UserPublicKeyLink> chain) {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(username, dout);
            dout.writeInt(chain.size());
            for (UserPublicKeyLink link : chain) {
                link.owner.serialize(dout);
                Serialize.serialize(link.toByteArray(), dout);
            }
            dout.flush();

            byte[] res = poster.post("core/updateChain", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        }
    }

    @Override public boolean followRequest(UserPublicKey target, byte[] encryptedPermission)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(target.toUserPublicKey().serialize(), dout);
            Serialize.serialize(encryptedPermission, dout);
            dout.flush();

            byte[] res = poster.post("core/followRequest", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        }
    }

    @Override public byte[] getAllUsernamesGzip() throws IOException
    {
        byte[] res = poster.post("core/getAllUsernamesGzip", new byte[0]);
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int r;
        while ((r = din.read(tmp)) >= 0) {
            bout.write(tmp, 0, r);
        }
        return bout.toByteArray();
    }

    @Override public byte[] getFollowRequests(UserPublicKey owner)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);


            Serialize.serialize(owner.toUserPublicKey().serialize(), dout);
            dout.flush();

            byte[] res = poster.post("core/getFollowRequests", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            return deserializeByteArray(din);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        }
    }
    
    @Override public boolean removeFollowRequest(UserPublicKey owner, byte[] signedRequest)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(owner.toUserPublicKey().serialize(), dout);
            Serialize.serialize(signedRequest, dout);
            dout.flush();

            byte[] res = poster.post("core/removeFollowRequest", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        }
    }
   
   @Override public boolean setMetadataBlob(UserPublicKey ownerPublicKey, UserPublicKey sharingPublicKey, byte[] sharingKeySignedPayload)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(ownerPublicKey.toUserPublicKey().serialize(), dout);
            Serialize.serialize(sharingPublicKey.toUserPublicKey().serialize(), dout);
            Serialize.serialize(sharingKeySignedPayload, dout);
            dout.flush();

            byte[] res = poster.post("core/addMetadataBlob", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        }
    }

    @Override public boolean removeMetadataBlob(UserPublicKey encodedSharingPublicKey, byte[] sharingKeySignedPayload)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(encodedSharingPublicKey.toUserPublicKey().serialize(), dout);
            Serialize.serialize(sharingKeySignedPayload, dout);
            dout.flush();

            byte[] res = poster.post("core/removeMetadataBlob", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        }
    }

    @Override public MaybeMultihash getMetadataBlob(UserPublicKey encodedSharingKey)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);


            Serialize.serialize(encodedSharingKey.toUserPublicKey().serialize(), dout);
            dout.flush();

            byte[] res = poster.post("core/getMetadataBlob", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            byte[] meta = deserializeByteArray(din);
            if (meta.length == 0)
                return MaybeMultihash.EMPTY();
            return MaybeMultihash.of(new Multihash(meta));
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return MaybeMultihash.EMPTY();
        }
    }

   @Override public void close()
    {}
}
