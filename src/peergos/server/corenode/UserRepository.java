package peergos.server.corenode;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class UserRepository implements CoreNode, MutablePointers {
    public static final boolean LOGGING = false;

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

            if (UserPublicKeyLink.isExpiredClaim(tail.get(tail.size() - 1)))
                return CompletableFuture.completedFuture(false);

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
    public CompletableFuture<Boolean> addFollowRequest(PublicKeyHash target, byte[] encryptedPermission) {
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
    public CompletableFuture<MaybeMultihash> getPointer(PublicKeyHash writer) {
        return store.getPointer(writer);
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        return getPointer(writer)
                .thenCompose(current -> ipfs.getSigningKey(writer)
                        .thenCompose(writerOpt -> {
                            try {
                                PublicSigningKey writerKey = writerOpt.get();
                                byte[] bothHashes = writerKey.unsignMessage(writerSignedBtreeRootHash);
                                // check CAS [current hash, new hash]
                                HashCasPair cas = HashCasPair.fromCbor(CborObject.fromByteArray(bothHashes));
                                MaybeMultihash claimedCurrentHash = cas.original;
                                Multihash newHash = cas.updated.get();
                                if (!current.equals(claimedCurrentHash))
                                    return CompletableFuture.completedFuture(false);
                                if (LOGGING)
                                    System.out.println("Core::setMetadata for " + writer + " from " + current + " to " + newHash);
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

    public static UserRepository buildSqlLite(String dbPath, ContentAddressedStorage ipfs) throws SQLException
    {
        try
        {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException cnfe) {
            throw new SQLException(cnfe);
        }

        String url = "jdbc:sqlite:"+dbPath;
        Connection conn = DriverManager.getConnection(url);
        conn.setAutoCommit(true);
        return new UserRepository(ipfs, new JDBCCoreNode(conn));
    }
}
