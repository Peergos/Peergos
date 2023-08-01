package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.auth.*;
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
    private final Optional<Bat> nextChunkBat;
    private final SymmetricKey dataKey;

    public EncryptedChunkRetriever(FragmentedPaddedCipherText linksToData,
                                   byte[] nextChunkLabel,
                                   Optional<Bat> nextChunkBat,
                                   SymmetricKey dataKey) {
        this.linksToData = linksToData;
        this.nextChunkLabel = nextChunkLabel;
        this.nextChunkBat = nextChunkBat;
        this.dataKey = dataKey;
    }

    @Override
    public CompletableFuture<AsyncReader> getFile(WriterData version,
                                                  NetworkAccess network,
                                                  Crypto crypto,
                                                  AbsoluteCapability ourCap,
                                                  Optional<byte[]> streamSecret,
                                                  long fileSize,
                                                  MaybeMultihash ourExistingHash,
                                                  int nBufferedChunks,
                                                  ProgressConsumer<Long> monitor) {
        return getChunk(version, network, crypto, 0, fileSize, ourCap, streamSecret, ourExistingHash, monitor)
                .thenApply(chunk -> {
                    AbsoluteCapability nextChunk = ourCap.withMapKey(nextChunkLabel, nextChunkBat);
                    Location nextChunkPointer = nextChunk.getLocation();
                    return new LazyInputStreamCombiner(version, 0,
                            chunk.get().chunk.data(), nextChunkPointer, nextChunkBat,
                            chunk.get().chunk.data(), ourCap.getMapKey(), ourCap.bat, streamSecret, nextChunkPointer,
                            nextChunkBat, network, crypto, ourCap.rBaseKey, fileSize, nBufferedChunks, monitor);
                });
    }

    public CompletableFuture<Optional<Pair<byte[], Optional<Bat>>>> getMapLabelAt(WriterData version,
                                                                                  AbsoluteCapability startCap,
                                                                                  Optional<byte[]> streamSecret,
                                                                                  long offset,
                                                                                  Hasher hasher,
                                                                                  NetworkAccess network) {
        if (offset < Chunk.MAX_SIZE)
            return CompletableFuture.completedFuture(Optional.of(new Pair<>(startCap.getMapKey(), startCap.bat)));
        if (offset < 2*Chunk.MAX_SIZE)
            return CompletableFuture.completedFuture(Optional.of(new Pair<>(nextChunkLabel, nextChunkBat))); // chunk at this location hasn't been written yet, only referenced by previous chunk
        if (streamSecret.isPresent()) {
            return FileProperties.calculateMapKey(streamSecret.get(), startCap.getMapKey(), startCap.bat, offset, hasher)
                    .thenApply(Optional::of);
        }
        return network.getMetadata(version, startCap.withMapKey(nextChunkLabel, nextChunkBat))
                .thenCompose(meta -> meta.isPresent() ?
                        meta.get().retriever(startCap.rBaseKey, streamSecret, nextChunkLabel, nextChunkBat, hasher)
                                .thenCompose(retriever ->
                                        retriever.getMapLabelAt(version, startCap.withMapKey(nextChunkLabel, nextChunkBat), streamSecret,
                                                offset - Chunk.MAX_SIZE, hasher, network)) :
                        CompletableFuture.completedFuture(Optional.empty())
                );
    }

    public CompletableFuture<Optional<LocatedChunk>> getChunk(WriterData version,
                                                              NetworkAccess network,
                                                              Crypto crypto,
                                                              long startIndex,
                                                              long truncateTo,
                                                              AbsoluteCapability ourCap,
                                                              Optional<byte[]> streamSecret,
                                                              MaybeMultihash ourExistingHash,
                                                              ProgressConsumer<Long> monitor) {
        if (startIndex >= Chunk.MAX_SIZE) {
            AbsoluteCapability nextChunkCap = ourCap.withMapKey(nextChunkLabel, nextChunkBat);
            return network.getMetadata(version, nextChunkCap)
                    .thenCompose(meta -> {
                        if (meta.isPresent())
                            return meta.get().retriever(ourCap.rBaseKey, streamSecret, nextChunkLabel, nextChunkBat, crypto.hasher)
                                    .thenCompose(retriever -> retriever
                                            .getChunk(version, network, crypto, startIndex - Chunk.MAX_SIZE,
                                                    truncateTo - Chunk.MAX_SIZE,
                                                    nextChunkCap, streamSecret, meta.get().committedHash(), l -> {}));
                        Chunk newEmptyChunk = new Chunk(new byte[0], dataKey, nextChunkLabel, dataKey.createNonce());
                        LocatedChunk withLocation = new LocatedChunk(nextChunkCap.getLocation(), nextChunkBat,
                                MaybeMultihash.empty(), newEmptyChunk);
                        return CompletableFuture.completedFuture(Optional.of(withLocation));
                    });
        }
        return linksToData.getAndDecrypt(ourCap.owner, dataKey, c -> ((CborObject.CborByteArray)c).value, crypto.hasher, network, monitor)
                .thenApply(data ->  Optional.of(new LocatedChunk(ourCap.getLocation(), ourCap.bat, ourExistingHash,
                        new Chunk(truncate(data, (int) Math.min(Chunk.MAX_SIZE, truncateTo)),
                                dataKey, ourCap.getMapKey(), ourCap.rBaseKey.createNonce()))));
    }

    public static byte[] truncate(byte[] in, int length) {
        if (in.length == length)
            return in;
        return Arrays.copyOfRange(in, 0, length);
    }
}
