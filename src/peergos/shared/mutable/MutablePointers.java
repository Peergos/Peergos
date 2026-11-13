package peergos.shared.mutable;

import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.MaybeMultihash;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.storage.PointerCasException;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public interface MutablePointers {

    /** Update the hash that a public key maps to (doing a cas with the existing value)
     *
     * @param owner The owner of this signing key
     * @param writer The public signing key
     * @param writerSignedBtreeRootHash the signed serialization of the HashCasPair
     * @return True when successfully completed
     */
    CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash);

    /** Atomically update the hashes for a set of writers under the same owner.
     *  All updates succeed or none do.
     */
    CompletableFuture<Boolean> setPointers(PublicKeyHash owner, List<SignedPointerUpdate> updates);

    default CompletableFuture<Boolean> setPointer(PublicKeyHash owner, SigningPrivateKeyAndPublicHash writer, PointerUpdate casUpdate) {
        return writer.secret.signMessage(casUpdate.serialize(), SigningDomain.POINTER)
                .thenCompose(signed -> setPointer(owner, writer.publicKeyHash, signed));
    }

    /** Note pointer updates that have already been applied by another route - a bulk commit, which
     *  carries its blocks and its pointer updates in one call - so that any cache of them stays true.
     */
    default void recordApplied(PublicKeyHash owner, List<SignedPointerUpdate> updates) {}

    /** Get the current hash a public key maps to
     *
     * @param writer The public signing key
     * @return The signed cas of the pointer from its previous value to its current value
     */
    CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer);

    /**
     * Get the latest pointer state for a writer-key.
     * @param writer
     * @param ipfs
     * @return
     */
    default CompletableFuture<PointerUpdate> getPointerTarget(PublicKeyHash owner, PublicKeyHash writer, ContentAddressedStorage ipfs) {
        return getPointer(owner, writer)
                .thenCompose(current -> current.isPresent() ?
                        parsePointerTarget(current.get(), owner, writer, ipfs) :
                        Futures.of(PointerUpdate.empty()));
    }

    /** The source of committed state, bypassing any local write buffer. Retrievals which must not
     *  observe in-flight local writes use this.
     */
    default MutablePointers committed() {
        return this;
    }

    MutablePointers clearCache();

    static CompletableFuture<PointerUpdate> parsePointerTarget(byte[] pointerCas,
                                                               PublicKeyHash owner,
                                                               PublicKeyHash writerKeyHash,
                                                               ContentAddressedStorage ipfs) {
        return PointerKeys.verify(owner, writerKeyHash, pointerCas, ipfs);
    }

    static CompletableFuture<Boolean> isValidUpdate(Optional<PointerUpdate> current,
                                                    MaybeMultihash claimedCurrentHash,
                                                    Optional<Long> newSequence) {
        MaybeMultihash existing = current.map(p -> p.updated).orElse(MaybeMultihash.empty());
        Optional<Long> currentSequence = current.flatMap(p -> p.sequence);
        // check CAS [current hash, new hash]
        boolean validSequence = currentSequence.isEmpty() || (newSequence.isPresent() && newSequence.get() > currentSequence.get());
        if (!existing.equals(claimedCurrentHash))
            return Futures.errored(new PointerCasException(existing, currentSequence, claimedCurrentHash));
        if (!validSequence)
            return Futures.errored(new IllegalStateException("Invalid sequence number update in mutable pointer: " + currentSequence + " => " + newSequence));
        return Futures.of(true);
    }
}
