package peergos.corenode;

import peergos.crypto.*;
import peergos.util.*;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.zip.*;

public interface CoreNode {
    int MAX_PENDING_FOLLOWERS = 100;
    int MAX_USERNAME_SIZE = 100;

    UserPublicKey getPublicKey(String username);

    String getUsername(byte[] encodedUserKey);

    boolean addUsername(String username, byte[] encodedUserKey, byte[] signed, byte[] staticData);

    byte[] getAllUsernamesGzip() throws IOException;

    default List<String> getAllUsernames() throws IOException {
        DataInput din = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(getAllUsernamesGzip())));
        List<String> res = new ArrayList<>();
        while (true) {
            try {
                String uname = Serialize.deserializeString(din, MAX_USERNAME_SIZE);
                res.add(uname);
            } catch (IOException e) {
                break;
            }
        }
        return res;
    }

    boolean setStaticData(UserPublicKey owner, byte[] signedStaticData);

    byte[] getStaticData(UserPublicKey owner);

    boolean followRequest(UserPublicKey target, byte[] encryptedPermission);

    byte[] getFollowRequests(UserPublicKey owner);

    boolean removeFollowRequest(UserPublicKey owner, byte[] data);

    boolean setMetadataBlob(UserPublicKey owner, byte[] encodedSharingPublicKey, byte[] sharingKeySignedMapKeyPlusBlob);

    boolean removeMetadataBlob(UserPublicKey owner, byte[] encodedSharingPublicKey, byte[] sharingKeySignedMapKeyPlusBlob);

    byte[] getMetadataBlob(UserPublicKey owner, byte[] encodedSharingKey, byte[] mapKey);

    void close() throws IOException;

    static CoreNode getDefault() {
        try {
            return SQLiteCoreNode.build(":memory:");
        } catch (SQLException s) {
            throw new IllegalStateException(s);
        }
    }
}
