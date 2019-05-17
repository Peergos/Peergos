package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** An instance of EncryptedChunkRetriever holds a list of fragment hashes for a chunk, and the nonce used in
 *  decrypting the resulting chunk, along with a link to the next chunk (if any).
 *
 */
public class EncryptedChunkRetriever implements FileRetriever {

    private final FragmentedPaddedCipherText linksToData;
    private final byte[] nextChunkLabel;
    private final SymmetricKey dataKey;

    public EncryptedChunkRetriever(FragmentedPaddedCipherText linksToData,
                                   byte[] nextChunkLabel,
                                   SymmetricKey dataKey) {
        this.linksToData = linksToData;
        this.nextChunkLabel = nextChunkLabel;
        this.dataKey = dataKey;
    }

    @Override
    public CompletableFuture<AsyncReader> getFile(WriterData version,
                                                  NetworkAccess network,
                                                  SafeRandom random,
                                                  AbsoluteCapability ourCap,
                                                  long fileSize,
                                                  MaybeMultihash ourExistingHash,
                                                  ProgressConsumer<Long> monitor) {
        return getChunk(version, network, random, 0, fileSize, ourCap, ourExistingHash, monitor)
                .thenApply(chunk -> {
                    Location nextChunkPointer = ourCap.withMapKey(nextChunkLabel).getLocation();
                    return new LazyInputStreamCombiner(version, 0,
                            chunk.get().chunk.data(), nextChunkPointer,
                            chunk.get().chunk.data(), nextChunkPointer,
                            network, random, ourCap.rBaseKey, fileSize, monitor);
                });
    }

    public CompletableFuture<Optional<byte[]>> getMapLabelAt(WriterData version,
                                                             AbsoluteCapability startCap,
                                                             long offset,
                                                             NetworkAccess network) {
        if (offset < Chunk.MAX_SIZE)
            return CompletableFuture.completedFuture(Optional.of(startCap.getMapKey()));
        if (offset < 2*Chunk.MAX_SIZE)
            return CompletableFuture.completedFuture(Optional.of(nextChunkLabel)); // chunk at this location hasn't been written yet, only referenced by previous chunk
        return network.getMetadata(version, startCap.withMapKey(nextChunkLabel))
                .thenCompose(meta -> meta.isPresent() ?
                        meta.get().retriever(startCap.rBaseKey).getMapLabelAt(version,
                                startCap.withMapKey(nextChunkLabel), offset - Chunk.MAX_SIZE, network) :
                        CompletableFuture.completedFuture(Optional.empty())
                );
    }

    public CompletableFuture<Optional<LocatedChunk>> getChunk(WriterData version,
                                                              NetworkAccess network,
                                                              SafeRandom random,
                                                              long startIndex,
                                                              long truncateTo,
                                                              AbsoluteCapability ourCap,
                                                              MaybeMultihash ourExistingHash,
                                                              ProgressConsumer<Long> monitor) {
        if (startIndex >= Chunk.MAX_SIZE) {
            AbsoluteCapability nextChunkCap = ourCap.withMapKey(nextChunkLabel);
            return network.getMetadata(version, nextChunkCap)
                    .thenCompose(meta -> {
                        if (meta.isPresent())
                            return meta.get().retriever(ourCap.rBaseKey)
                                    .getChunk(version, network, random, startIndex - Chunk.MAX_SIZE,
                                            truncateTo - Chunk.MAX_SIZE,
                                            nextChunkCap, meta.get().committedHash(), l -> {});
                        Chunk newEmptyChunk = new Chunk(new byte[0], dataKey, nextChunkLabel, dataKey.createNonce());
                        LocatedChunk withLocation = new LocatedChunk(nextChunkCap.getLocation(),
                                MaybeMultihash.empty(), newEmptyChunk);
                        return CompletableFuture.completedFuture(Optional.of(withLocation));
                    });
        }
        return linksToData.getAndDecrypt(dataKey, c -> ((CborObject.CborByteArray)c).value, network, monitor)
                .thenApply(data ->  Optional.of(new LocatedChunk(ourCap.getLocation(), ourExistingHash,
                        new Chunk(truncate(data, (int) Math.min(Chunk.MAX_SIZE, truncateTo)),
                                dataKey, ourCap.getMapKey(), ourCap.rBaseKey.createNonce()))));
    }

    public static byte[] truncate(byte[] in, int length) {
        if (in.length == length)
            return in;
        return Arrays.copyOfRange(in, 0, length);
    }
}
