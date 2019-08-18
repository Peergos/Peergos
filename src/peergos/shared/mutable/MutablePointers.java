package peergos.shared.mutable;

import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.MaybeMultihash;
import peergos.shared.storage.ContentAddressedStorage;

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

    /** Get the current hash a public key maps to
     *
     * @param writer The public signing key
     * @return The signed cas of the pointer from its previous value to its current value
     */
    CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer);

    /**
     * Get the CAS key-hash for the data pointed to by a writer-key.
     * @param writerKeyHash
     * @param ipfs
     * @return
     */
    default CompletableFuture<MaybeMultihash> getPointerTarget(PublicKeyHash owner, PublicKeyHash writerKeyHash, ContentAddressedStorage ipfs) {
        return getPointer(owner, writerKeyHash)
                .thenCompose(current -> current.isPresent() ?
                        parsePointerTarget(current.get(), writerKeyHash, ipfs) :
                        CompletableFuture.completedFuture(MaybeMultihash.empty()));
    }

    static CompletableFuture<MaybeMultihash> parsePointerTarget(byte[] pointerCas,
                                                                PublicKeyHash writerKeyHash,
                                                                ContentAddressedStorage ipfs) {
        return ipfs.getSigningKey(writerKeyHash)
                .thenApply(writerOpt -> writerOpt.map(writerKey -> Optional.of(pointerCas)
                        .map(signed -> HashCasPair.fromCbor(CborObject.fromByteArray(writerKey.unsignMessage(signed))).updated)
                        .orElse(MaybeMultihash.empty()))
                        .orElse(MaybeMultihash.empty()));
    }

    static boolean isValidUpdate(PublicSigningKey writerKey, Optional<byte[]> current, byte[] writerSignedBtreeRootHash) {
        byte[] bothHashes = writerKey.unsignMessage(writerSignedBtreeRootHash);
        // check CAS [current hash, new hash]
        HashCasPair cas = HashCasPair.fromCbor(CborObject.fromByteArray(bothHashes));
        MaybeMultihash claimedCurrentHash = cas.original;
        Multihash newHash = cas.updated.get();

        MaybeMultihash existing = current
                .map(signed -> HashCasPair.fromCbor(CborObject.fromByteArray(writerKey.unsignMessage(signed))).updated)
                .orElse(MaybeMultihash.empty());
        return existing.equals(claimedCurrentHash);
    }
}
