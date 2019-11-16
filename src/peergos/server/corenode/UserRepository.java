package peergos.server.corenode;

import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

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
                                byte[] bothHashes = writerKey.unsignMessage(writerSignedBtreeRootHash);
                                HashCasPair cas = HashCasPair.fromCbor(CborObject.fromByteArray(bothHashes));
                                MaybeMultihash claimedCurrentHash = cas.original;
                                Multihash newHash = cas.updated.get();
                                if (! MutablePointers.isValidUpdate(writerKey, current, claimedCurrentHash))
                                    return Futures.of(false);

                                // check the new target is valid for this writer
                                CommittedWriterData newWriterData = WriterData.getWriterData(newHash, ipfs).join();
                                if (! newWriterData.props.controller.equals(writer))
                                    return Futures.of(false);

                                return store.setPointer(writer, current, writerSignedBtreeRootHash);
                            } catch (TweetNaCl.InvalidSignatureException e) {
                                System.err.println("Invalid signature during setMetadataBlob for sharer: " + writer);
                                return Futures.of(false);
                            }
                        }));

    }

    public static UserRepository build(ContentAddressedStorage ipfs, JdbcIpnsAndSocial sqlNode) {
        return new UserRepository(ipfs, sqlNode);
    }
}
