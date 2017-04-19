package peergos.server.mutable;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.ContentAddressedStorage;

import java.util.concurrent.*;

public class PinningMutablePointers implements MutablePointers {
    private static final boolean LOGGING = true;
    private final MutablePointers target;
    private final ContentAddressedStorage storage;

    public PinningMutablePointers(MutablePointers target, ContentAddressedStorage storage) {
        this.target = target;
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicSigningKey owner, PublicSigningKey signer, byte[] sharingKeySignedBtreeRootHashes) {
        // first pin new root
        byte[] message = signer.unsignMessage(sharingKeySignedBtreeRootHashes);
        HashCasPair cas = HashCasPair.fromCbor(CborObject.fromByteArray(message));
        long t1 = System.currentTimeMillis();
        return storage.recursivePin(cas.updated.get()).thenCompose(pins -> {
            if (!pins.contains(cas.updated.get())) {
                CompletableFuture<Boolean> err = new CompletableFuture<>();
                err.completeExceptionally(new IllegalStateException("Couldn't pin new hash: " + cas.updated.get()));
                return err;
            }
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                System.out.println("Pinning "+cas.updated+" took: " + (t2 -t1) + " mS");
            return target.setPointer(owner, signer, sharingKeySignedBtreeRootHashes)
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
                                                System.out.println("Unpinning "+cas.original+" took: " + (t4 -t3) + " mS");
                                            return unpins.contains(cas.original.get());
                                        });
                    });
        });
    }

    @Override
    public CompletableFuture<MaybeMultihash> getPointer(PublicSigningKey encodedSharingKey) {
        return target.getPointer(encodedSharingKey);
    }
}
