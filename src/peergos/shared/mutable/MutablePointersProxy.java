package peergos.shared.mutable;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;

import java.util.*;
import java.util.concurrent.*;

/** A Mutable Pointers extension that proxies all calls over a p2p stream
 *
 */
public interface MutablePointersProxy extends MutablePointers {

    /** Update the hash that a public key maps to (doing a cas with the existing value)
     *
     * @param targetServerId
     * @param owner
     * @param writer
     * @param writerSignedBtreeRootHash the signed serialization of the HashCasPair
     * @return
     */
    CompletableFuture<Boolean> setPointer(Multihash targetServerId, PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash);

    /** Get the current hash a public key maps to
     *
     * @param targetServerId
     * @param writer
     * @return
     */
    CompletableFuture<Optional<byte[]>> getPointer(Multihash targetServerId, PublicKeyHash writer);

}
