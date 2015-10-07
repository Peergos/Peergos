package peergos.corenode;

import peergos.crypto.*;

import java.io.*;
import java.sql.*;

public interface CoreNode {
    int MAX_PENDING_FOLLOWERS = 100;

    UserPublicKey getPublicKey(String username);

    String getUsername(byte[] encodedUserKey);

    boolean addUsername(String username, byte[] encodedUserKey, byte[] signed, byte[] staticData);

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
