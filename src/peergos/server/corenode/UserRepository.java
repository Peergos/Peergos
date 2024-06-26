package peergos.server.corenode;

import peergos.server.crypto.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class UserRepository implements SocialNetwork, MutablePointers {
    public static final int MAX_POINTER_SIZE = TweetNaCl.SIGNATURE_SIZE_BYTES + 2 + 2*36 + 9; // Signature overhead + 2 cids + 2 (cbor list[3]) + cbor long

    private final DeletableContentAddressedStorage ipfs;
    private final JdbcIpnsAndSocial store;
    private final List<Multihash> us;

    public UserRepository(DeletableContentAddressedStorage ipfs, JdbcIpnsAndSocial store) {
        this.ipfs = ipfs;
        this.store = store;
        this.us = List.of(ipfs.id().join());
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
        return ipfs.getSigningKey(owner, owner).thenCompose(signerOpt -> {
            try {
                byte[] unsigned = signerOpt.get().unsignMessage(data).join();
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
                .thenCompose(current -> ipfs.getSigningKey(owner, writer)
                        .thenCompose(writerOpt -> {
                            try {
                                if (writerSignedBtreeRootHash.length > MAX_POINTER_SIZE)
                                    throw new IllegalStateException("Pointer update too big! " + writerSignedBtreeRootHash.length);
                                if (! writerOpt.isPresent())
                                    throw new IllegalStateException("Couldn't retrieve writer key from ipfs with hash " + writer);
                                PublicSigningKey writerKey = writerOpt.get();
                                byte[] bothHashes = writerKey.unsignMessage(writerSignedBtreeRootHash).join();
                                PointerUpdate cas = PointerUpdate.fromCbor(CborObject.fromByteArray(bothHashes));
                                MaybeMultihash claimedCurrentHash = cas.original;

                                return MutablePointers.isValidUpdate(writerKey, current, claimedCurrentHash, cas.sequence)
                                        .thenCompose(x -> {

                                            // check the new target is valid for this writer (or a deletion)
                                            if (cas.updated.isPresent()) {
                                                Multihash newHash = cas.updated.get();
                                                CommittedWriterData newWriterData = DeletableContentAddressedStorage.getWriterData(us, (Cid) newHash, cas.sequence, ipfs).join();
                                                if (!newWriterData.props.get().controller.equals(writer))
                                                    return Futures.of(false);
                                            }

                                            return store.setPointer(writer, current, writerSignedBtreeRootHash);
                                        });
                            } catch (TweetNaCl.InvalidSignatureException e) {
                                System.err.println("Invalid signature during setMetadataBlob for sharer: " + writer);
                                return Futures.of(false);
                            }
                        }));

    }

    @Override
    public MutablePointers clearCache() {
        return this;
    }

    public static UserRepository build(DeletableContentAddressedStorage ipfs, JdbcIpnsAndSocial sqlNode) {
        return new UserRepository(ipfs, sqlNode);
    }
}
