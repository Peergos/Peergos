package peergos.server.corenode;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.UserPublicKeyLink;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
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
            if (!pins.contains(cas.updated.get()))
                return CompletableFuture.completedFuture(false);
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                System.out.println("Pinning "+cas.updated+" took: " + (t2 -t1) + " mS");
            return target.setPointer(owner, signer, sharingKeySignedBtreeRootHashes)
                    .thenCompose(b -> {
                        if (!b)
                            return CompletableFuture.completedFuture(false);
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
