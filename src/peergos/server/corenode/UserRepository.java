package peergos.server.corenode;

import peergos.server.crypto.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.mldsa.*;
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
import java.util.stream.*;

public class UserRepository implements SocialNetwork, MutablePointers {
    private static final int MAX_POINTER_PAYLOAD = 2 + 2*36 + 9; // 2 cids + 2 (cbor list[3]) + cbor long
    public static final int MAX_POINTER_SIZE = TweetNaCl.SIGNATURE_SIZE_BYTES + MAX_POINTER_PAYLOAD;

    public static int maxPointerSize(boolean compositeSigned) {
        return compositeSigned ?
                HybridEd25519MLDSAPublicKey.SIGNATURE_SIZE_BYTES + MAX_POINTER_PAYLOAD :
                MAX_POINTER_SIZE;
    }

    private final DeletableContentAddressedStorage ipfs;
    private final JdbcIpnsAndSocial store;
    private final List<Multihash> us;
    private final Hasher hasher;

    public UserRepository(DeletableContentAddressedStorage ipfs, JdbcIpnsAndSocial store, Hasher hasher) {
        this.ipfs = ipfs;
        this.store = store;
        this.us = List.of(ipfs.id().join());
        this.hasher = hasher;
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner, byte[] signedTime) {
        TimeLimited.isAllowedTime(signedTime, 300, ipfs, owner);
        return store.getFollowRequests(owner);
    }

    @Override
    public CompletableFuture<Boolean> sendFollowRequest(PublicKeyHash target, byte[] encryptedPermission) {
        // anyone can send one, and a malformed one would stop the target logging in
        if (BlindFollowRequest.parse(encryptedPermission).isEmpty())
            return CompletableFuture.completedFuture(false);
        return store.addFollowRequest(target, encryptedPermission);
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] data) {
        return ipfs.getSigningKey(owner, owner).thenCompose(signerOpt -> {
            try {
                byte[] unsigned = signerOpt.get().unsignMessage(data, SigningDomain.FOLLOW_REQUEST_REMOVAL).join();
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
                .thenCompose(current -> validate(owner, writer, current, writerSignedBtreeRootHash)
                        .thenCompose(valid -> valid ?
                                store.setPointer(writer, current, writerSignedBtreeRootHash) :
                                Futures.of(false)))
                .exceptionally(t -> {
                    if (Exceptions.getRootCause(t) instanceof TweetNaCl.InvalidSignatureException) {
                        System.err.println("Invalid signature during setMetadataBlob for sharer: " + writer);
                        return false;
                    }
                    throw t instanceof CompletionException ? (CompletionException) t : new CompletionException(t);
                });
    }

    @Override
    public CompletableFuture<Boolean> setPointers(PublicKeyHash owner, List<SignedPointerUpdate> updates) {
        // Validate all signatures and CAS constraints concurrently, then write atomically.
        List<CompletableFuture<Optional<byte[]>>> validations = updates.stream()
                .map(u -> getPointer(owner, u.writer)
                        .thenCompose(current -> validate(owner, u.writer, current, u.signed).thenApply(valid -> {
                            if (! valid)
                                throw new IllegalStateException("WriterData controller mismatch for " + u.writer);
                            return current;
                        })))
                .collect(Collectors.toList());
        return Futures.combineAllInOrder(validations)
                .thenCompose(current -> store.setPointers(current, updates));
    }

    private CompletableFuture<Boolean> validate(PublicKeyHash owner, PublicKeyHash writer, Optional<byte[]> current, byte[] signed) {
        if (signed.length > maxPointerSize(PointerKeys.isCompositeSigned(signed)))
            throw new IllegalStateException("Pointer update too big! " + signed.length);
        return PointerKeys.checkUpdate(owner, writer, current, signed, ipfs, Collections.emptyMap())
                .thenApply(p -> {
                    PointerUpdate cas = p.right;
                    // check the new target is valid for this writer (or a deletion)
                    if (cas.updated.isPresent()) {
                        CommittedWriterData newWriterData = DeletableContentAddressedStorage.getWriterData(us, owner,
                                (Cid) cas.updated.get(), cas.sequence, false, (Cid) us.get(0), hasher, ipfs).join();
                        return PointerKeys.isValidController(writer, newWriterData.props.get().controller, p.left);
                    }
                    return true;
                });
    }

    @Override
    public MutablePointers clearCache() {
        return this;
    }

    public static UserRepository build(DeletableContentAddressedStorage ipfs, JdbcIpnsAndSocial sqlNode, Hasher h) {
        return new UserRepository(ipfs, sqlNode, h);
    }
}
