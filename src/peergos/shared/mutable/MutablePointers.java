package peergos.shared.mutable;

import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.MaybeMultihash;
import peergos.shared.storage.CasException;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public interface MutablePointers {

    /** Update the hash that a public key maps to (doing a cas with the existing value)
     *
     * @param owner The owner of this signing key
     * @param writer The public signing key
     * @param writerSignedBtreeRootHash the signed serialization of the HashCasPair
     * @return True when successfully completed
     */
    CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash);

    default CompletableFuture<Boolean> setPointer(PublicKeyHash owner, SigningPrivateKeyAndPublicHash writer, PointerUpdate casUpdate) {
        return writer.secret.signMessage(casUpdate.serialize())
                .thenCompose(signed -> setPointer(owner, writer.publicKeyHash, signed));
    }

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

    MutablePointers clearCache();

    static CompletableFuture<PointerUpdate> parsePointerTarget(byte[] pointerCas,
                                                               PublicKeyHash owner,
                                                               PublicKeyHash writerKeyHash,
                                                               ContentAddressedStorage ipfs) {
        return ipfs.getSigningKey(owner, writerKeyHash)
                .thenCompose(writerOpt -> writerOpt.map(writerKey -> writerKey.unsignMessage(pointerCas)
                                .thenApply(signed -> PointerUpdate.fromCbor(CborObject.fromByteArray(signed))))
                        .orElse(Futures.of(PointerUpdate.empty())));
    }

    static CompletableFuture<Boolean> isValidUpdate(PublicSigningKey writerKey, Optional<byte[]> current, byte[] writerSignedBtreeRootHash) {
        return writerKey.unsignMessage(writerSignedBtreeRootHash).thenCompose(bothHashes -> {
            PointerUpdate cas = PointerUpdate.fromCbor(CborObject.fromByteArray(bothHashes));
            MaybeMultihash claimedCurrentHash = cas.original;
            Multihash newHash = cas.updated.get();

            return isValidUpdate(writerKey, current, claimedCurrentHash, cas.sequence);
        });
    }

    static CompletableFuture<Boolean> isValidUpdate(PublicSigningKey writerKey,
                                                    Optional<byte[]> current,
                                                    MaybeMultihash claimedCurrentHash,
                                                    Optional<Long> newSequence) {
        Optional<CompletableFuture<PointerUpdate>> decoded = current.map(signed ->
                writerKey.unsignMessage(signed)
                        .thenApply(msg -> PointerUpdate.fromCbor(CborObject.fromByteArray(msg))));
        return decoded.map(f -> f.thenApply(p -> p.updated)).orElse(Futures.of(MaybeMultihash.empty()))
                .thenCompose(existing -> decoded.map(f -> f.thenApply(p -> p.sequence))
                        .orElse(Futures.of(Optional.empty()))
                        .thenCompose(currentSequence -> {
                            // check CAS [current hash, new hash]
                            boolean validSequence = currentSequence.isEmpty() || (newSequence.isPresent() && newSequence.get() > currentSequence.get());
                            if (!existing.equals(claimedCurrentHash))
                                return Futures.errored(new CasException(existing, claimedCurrentHash));
                            if (!validSequence)
                                return Futures.errored(new IllegalStateException("Invalid sequence number update in mutable pointer: " + currentSequence + " => " + newSequence));
                            return Futures.of(true);
                        }));
    }
}
