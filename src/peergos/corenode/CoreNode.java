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

    UserPublicKey getPublicKey(String username) throws IOException;

    String getUsername(byte[] encodedUserKey) throws IOException;

    boolean addUsername(String username, byte[] encodedUserKey, byte[] signed, byte[] staticData) throws IOException;

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

    boolean followRequest(UserPublicKey target, byte[] encryptedPermission);

    byte[] getFollowRequests(UserPublicKey owner);

    boolean removeFollowRequest(UserPublicKey owner, byte[] data);

    boolean setMetadataBlob(byte[] ownerPublicKey, byte[] encodedSharingPublicKey, byte[] sharingKeySignedBtreeRootHash) throws IOException;

    boolean removeMetadataBlob(byte[] encodedSharingPublicKey, byte[] sharingKeySignedMapKeyPlusBlob) throws IOException;

    byte[] getMetadataBlob(byte[] encodedSharingKey);

    void close() throws IOException;

    static CoreNode getDefault() {
        try {
            return SQLiteCoreNode.build(":memory:");
        } catch (SQLException s) {
            throw new IllegalStateException(s);
        }
    }
}
