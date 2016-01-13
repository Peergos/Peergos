package peergos.corenode;

import peergos.crypto.*;
import peergos.util.ArrayOps;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.util.*;
import java.io.*;
import java.util.zip.*;

public abstract class AbstractCoreNode implements CoreNode
{

    static class UserData
    {
        public static final int MAX_PENDING_FOLLOWERS = 100;

        private final Set<ByteArrayWrapper> followRequests;
        private final Map<UserPublicKey, Map<ByteArrayWrapper, byte[]>> metadata;

        private ByteArrayWrapper staticData;

        UserData(ByteArrayWrapper clearanceData)
        {
            this.staticData = clearanceData;
            this.followRequests = new HashSet<>();
            this.metadata = new HashMap<>();
        }
    }

    private final Map<UserPublicKey, UserData> userMap;
    private final Map<String, UserPublicKey> userNameToPublicKeyMap;
    private final Map<UserPublicKey, String> userPublicKeyToNameMap;

    public AbstractCoreNode()
    {
        this.userMap = new HashMap<>();
        this.userNameToPublicKeyMap = new HashMap<>();
        this.userPublicKeyToNameMap = new HashMap<>();
    }

    public synchronized UserPublicKey getPublicKey(String username)
    {
        return userNameToPublicKeyMap.get(username);
    }

    public synchronized String getUsername(byte[] encodedUserKey)
    {
        UserPublicKey key = new UserPublicKey(encodedUserKey);
        String name = userPublicKeyToNameMap.get(key);
        if (name == null)
            name = "";
        return name;
    }

    public boolean addUsername(String username, byte[] encodedUserKey, byte[] signed, byte[] staticData)
    {
        UserPublicKey key = new UserPublicKey(encodedUserKey);

        if (! key.isValidSignature(signed, ArrayOps.concat(username.getBytes(), encodedUserKey, staticData)))
            return false;

        return addUsername(username, key, new ByteArrayWrapper(staticData));
    }

    protected synchronized boolean addUsername(String username, UserPublicKey key, ByteArrayWrapper clearanceData)
    {
        if (userNameToPublicKeyMap.containsKey(username))
            return false;
        if (userPublicKeyToNameMap.containsKey(key))
            return false;

        userNameToPublicKeyMap.put(username, key); 
        userPublicKeyToNameMap.put(key, username); 
        userMap.put(key, new UserData(clearanceData));
        return true;
    }

    public byte[] getAllUsernamesGzip() throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutput dout = new DataOutputStream(new GZIPOutputStream(bout));
        for (String uname: userNameToPublicKeyMap.keySet())
            Serialize.serialize(uname, dout);

        return bout.toByteArray();
    }

    public boolean setStaticData(UserPublicKey owner, byte[] signedStaticData)
    {
        return updateStaticData(owner, new ByteArrayWrapper(owner.unsignMessage(signedStaticData)));
    }

    protected synchronized boolean updateStaticData(UserPublicKey owner, ByteArrayWrapper clearanceData)
    {
        UserData userData = userMap.get(owner);
        if (userData == null)
            return false;
        userData.staticData = clearanceData;
        return true;
    }

    public synchronized byte[] getStaticData(UserPublicKey owner)
    {
        UserData userData = userMap.get(owner);
        return userData != null ? Arrays.copyOf(userData.staticData.data, userData.staticData.data.length) : new byte[0];
    }

    public synchronized boolean followRequest(UserPublicKey target, byte[] encryptedPermission)
    {
        UserData userData = userMap.get(target);
        if (userData == null)
            return false;
        if (userData.followRequests.size() > CoreNode.MAX_PENDING_FOLLOWERS)
            return false;
        userData.followRequests.add(new ByteArrayWrapper(encryptedPermission));
        return true;
    }

    public synchronized byte[] getFollowRequests(UserPublicKey owner)
    {
        UserData userData = userMap.get(owner);
        if (userData == null)
            return new byte[4];

        ByteArrayOutputStream bout  =new ByteArrayOutputStream();
        DataOutput dout = new DataOutputStream(bout);
        try {
            dout.writeInt(userData.followRequests.size());
            for (ByteArrayWrapper req : userData.followRequests)
                Serialize.serialize(req.data, dout);
            return bout.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public boolean removeFollowRequest(UserPublicKey owner, byte[] data)
    {
        byte[] unsigned = owner.unsignMessage(data);
        ByteArrayWrapper baw = new ByteArrayWrapper(unsigned);
        synchronized(this)
        {
            if (owner == null || ! userMap.get(owner).followRequests.contains(baw))
                return false; 
        }

        return removeFollowRequest(owner, baw);
    }

    protected synchronized boolean removeFollowRequest(UserPublicKey target, ByteArrayWrapper baw)
    {
        UserData userData = userMap.get(target);
        if (userData == null)
            return false;
        return userData.followRequests.remove(baw);
    }

    public boolean setMetadataBlob(UserPublicKey owner, byte[] encodedSharingPublicKey, byte[] sharingKeySignedMapKeyPlusBlob)
    {
        UserPublicKey sharingKey = new UserPublicKey(encodedSharingPublicKey);

        try {
            byte[] payload = sharingKey.unsignMessage(sharingKeySignedMapKeyPlusBlob);
            byte[] mapKey = Arrays.copyOfRange(payload, 0, 32);
            byte[] metaDataBlob = Arrays.copyOfRange(payload, 32, payload.length);
            return addMetadataBlob(owner, sharingKey, mapKey, metaDataBlob);
        } catch (TweetNaCl.InvalidSignatureException e) {
            System.err.println("Invalid signature for owner: "+owner + " and sharer: "+sharingKey);
            return false;
        }
    }

    protected synchronized boolean addMetadataBlob(UserPublicKey owner, UserPublicKey sharingKey, byte[] mapKey, byte[] metadataBlob)
    {
        UserData userData = userMap.get(owner);

        if (userData == null)
            return false;

        Map<ByteArrayWrapper, byte[]> metadataBlobs = userData.metadata.get(sharingKey);
        if (metadataBlobs == null)
            return false;

        ByteArrayWrapper keyW = new ByteArrayWrapper(mapKey);
        if (metadataBlobs.containsKey(keyW)) {
            // for now just overwrite it. This is vulnerable to replay attacks so eventually we'll need to sign the
            // previous value as well, or some kind of version cas
            // TODO return false;
        }
        System.out.printf("Adding metadata blob at owner:%s writer:%s mapKey:%s\n",
                owner, sharingKey, ArrayOps.bytesToHex(mapKey));

        metadataBlobs.put(keyW, metadataBlob);
        return true;
    }

    public synchronized byte[] getMetadataBlob(UserPublicKey owner, byte[] encodedSharingKey, byte[] mapKey) {
        UserData userData = userMap.get(owner);
        UserPublicKey writer = new UserPublicKey(encodedSharingKey);
        if (userData == null) {
            System.out.printf("Returning EMPTY metadata blob from owner:%s writer:%s mapKey:%s\n",
                owner, writer, ArrayOps.bytesToHex(mapKey));
            return new byte[0];
        }

        Map<ByteArrayWrapper, byte[]> sharedFragments = userData.metadata.get(writer);
        System.out.printf("Getting metadata blob at owner:%s writer:%s mapKey:%s\n", owner, writer, ArrayOps.bytesToHex(mapKey));
        ByteArrayWrapper key = new ByteArrayWrapper(mapKey);
        if ((sharedFragments == null) || (!sharedFragments.containsKey(key)))
            return null;
        return sharedFragments.get(key);
    }

    public abstract void close() throws IOException;
}
