package peergos.server.corenode;

import peergos.shared.cbor.*;
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
        try {
            Optional<PublicSigningKey> ownerOpt = ipfs.getSigningKey(owner).get();
            if (! ownerOpt.isPresent())
                throw new IllegalStateException("Couldn't retrieve owner key during getFollowRequests() call!");
            byte[] raw = ownerOpt.get().unsignMessage(signedTime);
            CborObject cbor = CborObject.fromByteArray(raw);
            if (! (cbor instanceof CborObject.CborLong))
                throw new IllegalStateException("Invalid cbor for getFollowRequests authorisation!");
            long utcMillis = ((CborObject.CborLong) cbor).value;
            long now = System.currentTimeMillis();
            if (Math.abs(now - utcMillis) > 300_000)
                throw new IllegalStateException("Stale auth time in getFollowRequests, is your clock accurate?");
            // This is a valid request
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    public static UserRepository buildSqlLite(String dbPath, ContentAddressedStorage ipfs) throws SQLException
    {
        JdbcIpnsAndSocial coreNode = new JdbcIpnsAndSocial(JdbcIpnsAndSocial.buildSqlLite(dbPath));
        return new UserRepository(ipfs, coreNode);
    }
}
