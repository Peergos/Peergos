package peergos.corenode;

import org.ipfs.api.Multihash;
import peergos.crypto.*;
import peergos.server.merklebtree.*;
import peergos.util.*;

import java.io.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

public interface CoreNode {
    int MAX_PENDING_FOLLOWERS = 100;
    int MAX_USERNAME_SIZE = 100;

    String getUsername(UserPublicKey key) throws IOException;

    List<UserPublicKeyLink> getChain(String username);

    boolean updateChain(String username, List<UserPublicKeyLink> chain);

    default Optional<UserPublicKey> getPublicKey(String username) throws IOException {
        List<UserPublicKeyLink> chain = getChain(username);
        if (chain.size() == 0)
            return Optional.empty();
        return Optional.of(chain.get(chain.size()-1).owner);
    }

    byte[] getAllUsernamesGzip() throws IOException;

    default List<String> getAllUsernames() throws IOException {
        DataInput din = new DataInputStream(new ByteArrayInputStream(getAllUsernamesGzip()));
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

    boolean setMetadataBlob(UserPublicKey ownerPublicKey, UserPublicKey encodedSharingPublicKey, byte[] sharingKeySignedBtreeRootHash) throws IOException;

    boolean removeMetadataBlob(UserPublicKey encodedSharingPublicKey, byte[] sharingKeySignedMapKeyPlusBlob) throws IOException;

    MaybeMultihash getMetadataBlob(UserPublicKey encodedSharingKey);

    void close() throws IOException;

    static CoreNode getDefault() {
        try {
            return SQLiteCoreNode.build(":memory:");
        } catch (SQLException s) {
            throw new IllegalStateException(s);
        }
    }
}
