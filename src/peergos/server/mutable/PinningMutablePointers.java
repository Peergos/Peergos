package peergos.server.mutable;
import java.util.logging.*;

import peergos.server.util.Logging;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class PinningMutablePointers implements MutablePointers {
	private static final Logger LOG = Logging.LOG();
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
            return (cas.original.isPresent() ?
                    (cas.updated.isPresent() ?
                            storage.pinUpdate(owner, cas.original.get(), cas.updated.get()) :
                            Futures.of(Collections.emptyList())) :
                    storage.recursivePin(owner, cas.updated.get())).thenCompose(pins -> {
                if (cas.updated.isPresent() && !pins.contains(cas.updated.get())) {
                    CompletableFuture<Boolean> err = new CompletableFuture<>();
                    err.completeExceptionally(new IllegalStateException("Couldn't pin new hash: " + cas.updated.get()));
                    return err;
                }
                long t2 = System.currentTimeMillis();
                if (LOGGING)
                    LOG.info("Tree:Pin update " + cas.updated + " took: " + (t2 - t1) + " mS");
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
                                    storage.recursiveUnpin(owner, cas.original.get())
                                            .thenApply(unpins -> {
                                                long t4 = System.currentTimeMillis();
                                                if (LOGGING)
                                                    LOG.info("Unpinning " + cas.original + " took: " + (t4 - t3) + " mS");
                                                return unpins.contains(cas.original.get());
                                            });
                        });
            });
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        return target.getPointer(owner, writer);
    }
}
