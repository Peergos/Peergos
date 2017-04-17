package peergos.shared.mutable;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.merklebtree.*;

import java.util.concurrent.*;

public interface MutablePointers {

    /** Update the hash that a public key maps to (doing a cas with the existing value)
     *
     * @param owner
     * @param writer
     * @param writerSignedBtreeRootHash the signed serialization of the HashCasPair
     * @return
     */
    CompletableFuture<Boolean> setPointer(PublicSigningKey owner, PublicSigningKey writer, byte[] writerSignedBtreeRootHash);

    /** Get the current hash a public key maps to
     *
     * @param encodedSharingKey
     * @return
     */
    CompletableFuture<MaybeMultihash> getPointer(PublicSigningKey encodedSharingKey);
}
