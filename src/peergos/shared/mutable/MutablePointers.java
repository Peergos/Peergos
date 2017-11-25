package peergos.shared.mutable;

import peergos.server.storage.IPFS;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.TweetNaCl;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.multihash.Multihash;
import peergos.shared.merklebtree.HashCasPair;
import peergos.shared.merklebtree.MaybeMultihash;
import peergos.shared.storage.ContentAddressedStorage;

import java.util.*;
import java.util.concurrent.*;

public interface MutablePointers {

    /** Update the hash that a public key maps to (doing a cas with the existing value)
     *
     * @param owner
     * @param writer
     * @param writerSignedBtreeRootHash the signed serialization of the HashCasPair
     * @return
     */
    CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash);

    /** Get the current hash a public key maps to
     *
     * @param writer
     * @return
     */
    CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash writer);

    /**
     * Get the CAS key-hash for the data pointed to by a writer-key.
     * @param writerKeyHash
     * @param ipfs
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    default CompletableFuture<MaybeMultihash> getPointerKeyHash(PublicKeyHash writerKeyHash, ContentAddressedStorage ipfs) {
        return getPointer(writerKeyHash)
            .thenCompose(current -> ipfs.getSigningKey(writerKeyHash)
                .thenApply(writerOpt -> {
                        PublicSigningKey writerKey = writerOpt.get();
                        return current
                            .map(signed -> HashCasPair.fromCbor(CborObject.fromByteArray(writerKey.unsignMessage(signed))).updated)
                            .orElse(MaybeMultihash.empty());

                }));
    }

}
