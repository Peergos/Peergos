package peergos.server.corenode;

import peergos.server.util.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class UserRepository implements SocialNetwork, MutablePointers {

    private final ContentAddressedStorage ipfs;
    private final JdbcIpnsAndSocial store;

    public UserRepository(ContentAddressedStorage ipfs, JdbcIpnsAndSocial store) {
        this.ipfs = ipfs;
        this.store = store;
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner, byte[] signedTime) {
        TimeLimited.isAllowedTime(signedTime, 300, ipfs, owner);
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
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        return store.getPointer(writer);
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        return getPointer(owner, writer)
                .thenCompose(current -> ipfs.getSigningKey(writer)
                        .thenCompose(writerOpt -> {
                            try {
                                if (! writerOpt.isPresent())
                                    throw new IllegalStateException("Couldn't retrieve writer key from ipfs with hash " + writer);
                                PublicSigningKey writerKey = writerOpt.get();
                                if (! MutablePointers.isValidUpdate(writerKey, current, writerSignedBtreeRootHash))
                                    return CompletableFuture.completedFuture(false);

                                return store.setPointer(writer, current, writerSignedBtreeRootHash);
                            } catch (TweetNaCl.InvalidSignatureException e) {
                                System.err.println("Invalid signature during setMetadataBlob for sharer: " + writer);
                                return CompletableFuture.completedFuture(false);
                            }
                        }));

    }

    public static UserRepository buildSqlLite(String dbPath, ContentAddressedStorage ipfs) throws SQLException {
        JdbcIpnsAndSocial sqlNode = new JdbcIpnsAndSocial(JdbcIpnsAndSocial.buildSqlLite(dbPath));
        return buildSqlLite(ipfs, sqlNode);
    }

    public static UserRepository buildSqlLite(ContentAddressedStorage ipfs, JdbcIpnsAndSocial sqlNode) {
        return new UserRepository(ipfs, sqlNode);
    }
}
