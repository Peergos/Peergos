package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** An instance of EncryptedChunkRetriever holds a list of fragment hashes for a chunk, and the nonce used in
 *  decrypting the resulting chunk, along with a link to the next chunk (if any).
 *
 */
public class EncryptedChunkRetriever implements FileRetriever {

    private final FragmentedPaddedCipherText linksToData;
    private final byte[] nextChunkLabel;

    public EncryptedChunkRetriever(FragmentedPaddedCipherText linksToData, byte[] nextChunkLabel) {
        this.linksToData = linksToData;
        this.nextChunkLabel = nextChunkLabel;
    }

    @Override
    public CompletableFuture<AsyncReader> getFile(NetworkAccess network,
                                                  SafeRandom random,
                                                  SymmetricKey baseKey,
                                                  long fileSize,
                                                  Location ourLocation,
                                                  MaybeMultihash ourExistingHash,
                                                  ProgressConsumer<Long> monitor) {
        AbsoluteCapability ourCap = AbsoluteCapability.build(ourLocation, baseKey);
        return getChunkInputStream(network, random, 0, fileSize, ourCap, ourExistingHash, monitor)
                .thenApply(chunk -> {
                    Location nextChunkPointer = ourLocation.withMapKey(nextChunkLabel);
                    return new LazyInputStreamCombiner(0,
                            chunk.get().chunk.data(), nextChunkPointer,
                            chunk.get().chunk.data(), nextChunkPointer,
                            network, random, baseKey, fileSize, monitor);
                });
    }

    public CompletableFuture<Optional<byte[]>> getMapLabelAt(AbsoluteCapability startCap, long offset, NetworkAccess network) {
        if (offset < Chunk.MAX_SIZE)
            return CompletableFuture.completedFuture(Optional.of(startCap.getMapKey()));
        if (offset < 2*Chunk.MAX_SIZE)
            return CompletableFuture.completedFuture(Optional.of(nextChunkLabel)); // chunk at this location hasn't been written yet, only referenced by previous chunk
        return network.getMetadata(startCap.withMapKey(nextChunkLabel))
                .thenCompose(meta -> meta.isPresent() ?
                        meta.get().retriever(startCap.rBaseKey).getMapLabelAt(startCap.withMapKey(nextChunkLabel), offset - Chunk.MAX_SIZE, network) :
                        CompletableFuture.completedFuture(Optional.empty())
                );
    }

    public CompletableFuture<Optional<LocatedChunk>> getChunkInputStream(NetworkAccess network,
                                                                         SafeRandom random,
                                                                         long startIndex,
                                                                         long truncateTo,
                                                                         AbsoluteCapability ourCap,
                                                                         MaybeMultihash ourExistingHash,
                                                                         ProgressConsumer<Long> monitor) {
        return linksToData.getAndDecrypt(ourCap.rBaseKey, c -> ((CborObject.CborByteArray)c).value, network, monitor)
                .thenApply(data ->  Optional.of(new LocatedChunk(ourCap.getLocation(), ourExistingHash,
                        new Chunk(truncate(data, Math.min(Chunk.MAX_SIZE, (int)(truncateTo - startIndex))),
                                ourCap.rBaseKey, ourCap.getMapKey(), ourCap.rBaseKey.createNonce()))));
    }

    public static byte[] truncate(byte[] in, int length) {
        if (in.length == length)
            return in;
        return Arrays.copyOfRange(in, 0, length);
    }

    private static List<FragmentWithHash> reorder(List<FragmentWithHash> fragments, List<Multihash> hashes) {
        FragmentWithHash[] res = new FragmentWithHash[fragments.size()];
        for (FragmentWithHash f: fragments) {
            for (int index = 0; index < res.length; index++)
                if (hashes.get(index).equals(f.hash))
                    res[index] = f;
        }
        return Arrays.asList(res);
    }
}
