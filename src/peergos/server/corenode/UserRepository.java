package peergos.server.corenode;

import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;

import java.io.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class UserRepository implements CoreNode, SocialNetwork, MutablePointers {

    private final ContentAddressedStorage ipfs;
    private final JDBCCoreNode store;

    public UserRepository(ContentAddressedStorage ipfs, JDBCCoreNode store) {
        this.ipfs = ipfs;
        this.store = store;
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        return store.getUsername(key);
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return store.getUsernames(prefix);
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return store.getChain(username);
    }

    @Override
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> tail) {
        return UserPublicKeyLink.validChain(tail, username, ipfs).thenCompose(valid -> {
            if (! valid)
                return CompletableFuture.completedFuture(false);

            UserPublicKeyLink last = tail.get(tail.size() - 1);
            if (UserPublicKeyLink.isExpiredClaim(last))
                return CompletableFuture.completedFuture(false);

            if (LocalDate.now().plusYears(1).isBefore(last.claim.expiry)) {
                System.err.println("Rejecting username claim expiring more than 1 year from now: " + username);
                return CompletableFuture.completedFuture(false);
            }

            if (tail.size() > 2)
                return CompletableFuture.completedFuture(false);

            return store.getChain(username)
                    .thenCompose(existing -> UserPublicKeyLink.merge(existing, tail, ipfs)
                            .thenApply(merged -> store.updateChain(username, existing, tail, merged)));
        });
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner) {
        return store.getFollowRequests(owner);
    }

    @Override
    public CompletableFuture<Boolean> sendFollowRequest(PublicKeyHash target, byte[] encryptedPermission) {
        return store.addFollowRequest(target, encryptedPermission);
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] data) {
        return ipfs.getSigningKey(owner).thenCompose(signerOpt -> {
            try {
                byte[] unsigned = signerOpt.get().unsignMessage(data);
                return store.removeFollowRequest(owner, unsigned);
            } catch (TweetNaCl.InvalidSignatureException e) {
                return CompletableFuture.completedFuture(false);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash writer) {
        return store.getPointer(writer);
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        return getPointer(writer)
                .thenCompose(current -> ipfs.getSigningKey(writer)
                        .thenCompose(writerOpt -> {
                            try {
                                PublicSigningKey writerKey = writerOpt.get();
                                if (! MutablePointers.isValidUpdate(writerKey, current, writerSignedBtreeRootHash))
                                    return CompletableFuture.completedFuture(false);

                                return store.setPointer(owner, writer, writerSignedBtreeRootHash);
                            } catch (TweetNaCl.InvalidSignatureException e) {
                                System.err.println("Invalid signature during setMetadataBlob for sharer: " + writer);
                                return CompletableFuture.completedFuture(false);
                            }
                        }));

    }

    @Override
    public void close() throws IOException {

    }

    public static UserRepository buildSqlLite(String dbPath, ContentAddressedStorage ipfs, int maxUserCount) throws SQLException
    {
        JDBCCoreNode coreNode = new JDBCCoreNode(
            JDBCCoreNode.buildSqlLite(dbPath), maxUserCount);

        return new UserRepository(ipfs, coreNode);
    }
}
