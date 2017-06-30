package peergos.server.mutable;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.ContentAddressedStorage;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class PinningMutablePointers implements MutablePointers {
    private static final boolean LOGGING = true;
    private final MutablePointers target;
    private final ContentAddressedStorage storage;

    public PinningMutablePointers(MutablePointers target, ContentAddressedStorage storage) {
        this.target = target;
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash signerHash, byte[] sharingKeySignedBtreeRootHashes) {
        // first pin new root
        return storage.getSigningKey(signerHash).thenCompose(signer -> {
            byte[] message = signer.get().unsignMessage(sharingKeySignedBtreeRootHashes);
            HashCasPair cas = HashCasPair.fromCbor(CborObject.fromByteArray(message));
            long t1 = System.currentTimeMillis();
            return (cas.original.isPresent() ? storage.pinUpdate(cas.original.get(), cas.updated.get())
                    .thenApply(PinningMutablePointers::convert) :
                    storage.recursivePin(cas.updated.get())).thenCompose(pins -> {
                if (!pins.contains(cas.updated.get())) {
                    CompletableFuture<Boolean> err = new CompletableFuture<>();
                    err.completeExceptionally(new IllegalStateException("Couldn't pin new hash: " + cas.updated.get()));
                    return err;
                }
                long t2 = System.currentTimeMillis();
                if (LOGGING)
                    System.out.println("Pinning " + cas.updated + " took: " + (t2 - t1) + " mS");
                return target.setPointer(owner, signerHash, sharingKeySignedBtreeRootHashes)
                        .thenCompose(b -> {
                            if (!b) {
                                CompletableFuture<Boolean> err = new CompletableFuture<>();
                                err.completeExceptionally(new IllegalStateException("Couldn't update mutable pointer, cas failed: " + cas));
                                return err;
                            }
                            long t3 = System.currentTimeMillis();
                            // unpin old root
                            return !cas.original.isPresent() ?
                                    CompletableFuture.completedFuture(true) :
                                    storage.recursiveUnpin(cas.original.get())
                                            .thenApply(unpins -> {
                                                long t4 = System.currentTimeMillis();
                                                if (LOGGING)
                                                    System.out.println("Unpinning " + cas.original + " took: " + (t4 - t3) + " mS");
                                                return unpins.contains(cas.original.get());
                                            });
                        });
            });
        });
    }

    private static List<Multihash> convert(List<MultiAddress> addresses) {
        return addresses.stream()
                .filter(addr -> addr.toString().startsWith("/ipfs/"))
                .map(addr -> Cid.decode(addr.toString().substring(6)))
                .collect(Collectors.toList());
    }

    @Override
    public CompletableFuture<MaybeMultihash> getPointer(PublicKeyHash encodedSharingKey) {
        return target.getPointer(encodedSharingKey);
    }
}
