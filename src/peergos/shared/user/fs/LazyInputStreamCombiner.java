package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class LazyInputStreamCombiner implements AsyncReader {
    private final WriterData version;
    private final NetworkAccess network;
    private final Crypto crypto;
    private final SymmetricKey baseKey;
    private final ProgressConsumer<Long> monitor;
    private final long totalLength;
    private final long totalChunks;

    private final byte[] originalChunk;
    private final byte[] originalChunkLocation;
    private final Optional<Bat> originalChunkBat;
    private final Optional<byte[]> streamSecret;
    private final AbsoluteCapability originalNextPointer;

    private final SortedMap<Long, Pair<byte[], AbsoluteCapability>> bufferedChunks = new TreeMap<>(); // and next chunk pointer
    private final int nBufferedChunks;
    private long globalIndex; // index of beginning of current chunk in file
    private int index; // index within current chunk

    public LazyInputStreamCombiner(WriterData version,
                                   long globalIndex,
                                   byte[] chunk,
                                   Location nextChunkPointer,
                                   Optional<Bat> nextChunkBat,
                                   byte[] originalChunk,
                                   byte[] originalChunkLocation,
                                   Optional<Bat> originalChunkBat,
                                   Optional<byte[]> streamSecret,
                                   Location originalNextChunkPointer,
                                   Optional<Bat> originalNextChunkBat,
                                   NetworkAccess network,
                                   Crypto crypto,
                                   SymmetricKey baseKey,
                                   long totalLength,
                                   int nBufferedChunks,
                                   ProgressConsumer<Long> monitor) {
        if (chunk == null)
            throw new IllegalStateException("Null initial chunk!");
        this.version = version;
        this.network = network;
        this.crypto = crypto;
        this.baseKey = baseKey;
        this.monitor = monitor;
        this.totalLength = totalLength;
        this.totalChunks = (totalLength + Chunk.MAX_SIZE - 1) / Chunk.MAX_SIZE;
        this.originalChunk = originalChunk;
        this.originalChunkLocation = originalChunkLocation;
        this.originalChunkBat = originalChunkBat;
        this.streamSecret = streamSecret;
        this.originalNextPointer = AbsoluteCapability.build(originalNextChunkPointer, originalNextChunkBat, baseKey);
        bufferedChunks.put(globalIndex, new Pair<>(chunk, AbsoluteCapability.build(nextChunkPointer, nextChunkBat, baseKey)));
        this.globalIndex = globalIndex;
        this.index = 0;
        this.nBufferedChunks = nBufferedChunks;
    }

    private LazyInputStreamCombiner(WriterData version, NetworkAccess network, Crypto crypto, SymmetricKey baseKey,
                                    ProgressConsumer<Long> monitor, long totalLength, byte[] originalChunk,
                                    byte[] originalChunkLocation, Optional<Bat> originalChunkBat, Optional<byte[]> streamSecret,
                                    AbsoluteCapability originalNextPointer, byte[] currentChunk,
                                    AbsoluteCapability nextChunkPointer, long globalIndex, int index, int nBufferedChunks) {
        this.version = version;
        this.network = network;
        this.crypto = crypto;
        this.baseKey = baseKey;
        this.monitor = monitor;
        this.totalLength = totalLength;
        this.totalChunks = (totalLength + Chunk.MAX_SIZE - 1) / Chunk.MAX_SIZE;
        this.originalChunk = originalChunk;
        this.originalChunkLocation = originalChunkLocation;
        this.originalChunkBat = originalChunkBat;
        this.streamSecret = streamSecret;
        this.originalNextPointer = originalNextPointer;
        bufferedChunks.put(globalIndex, new Pair<>(currentChunk, nextChunkPointer));
        this.globalIndex = globalIndex;
        this.index = index;
        this.nBufferedChunks = nBufferedChunks;
        prefetch(nBufferedChunks);
    }

    private LazyInputStreamCombiner copy() {
        return new LazyInputStreamCombiner( version, network, crypto, baseKey, monitor, totalLength, originalChunk, originalChunkLocation,
                originalChunkBat, streamSecret, originalNextPointer, currentChunk(), nextChunkPointer(), globalIndex, index, nBufferedChunks);
    }

    public void prefetch(int nChunks) {
        ForkJoinPool.commonPool().execute(() -> syncPrefetch(nBufferedChunks));
    }

    public CompletableFuture<Boolean> syncPrefetch(int nChunks) {
        if (streamSecret.isEmpty()) // can only parallelise download in non legacy files
            return Futures.of(true);

        long globalIndexCopy = globalIndex;

        if (globalIndexCopy + Chunk.MAX_SIZE > totalLength)
            return Futures.of(true);
        if (globalIndexCopy + nChunks * Chunk.MAX_SIZE > totalLength)
            nChunks = (int) ((totalLength - globalIndexCopy + Chunk.MAX_SIZE - 1) / Chunk.MAX_SIZE);
        long lastBufferedChunk = bufferedChunks.lastKey();
        int finalCount = nChunks - (int)((lastBufferedChunk - globalIndexCopy) / Chunk.MAX_SIZE);
        AbsoluteCapability nextChunkCap = bufferedChunks.get(lastBufferedChunk).right;

        return FileProperties.calculateSubsequentMapKeys(streamSecret.get(), nextChunkCap.getMapKey(), nextChunkCap.bat, nChunks, crypto.hasher)
                .thenCompose(mapKeys -> Futures.combineAll(IntStream.range(1, finalCount + 1).parallel().mapToObj(i -> {
                    int size = globalIndexCopy / Chunk.MAX_SIZE + i < totalChunks ? Chunk.MAX_SIZE : (int) (totalLength % Chunk.MAX_SIZE);
                    return getChunk(nextChunkCap.withMapKey(mapKeys.get(i).left, mapKeys.get(i).right), lastBufferedChunk + (i * Chunk.MAX_SIZE), size);
                }).collect(Collectors.toList())))
                .thenApply(x -> true);
    }

    private CompletableFuture<Boolean> getChunk(AbsoluteCapability cap, long chunkOffset, int len) {
        if (bufferedChunks.containsKey(chunkOffset))
            return Futures.of(true);
        return getSubsequentMetadata(cap, 0)
                .thenCompose(access -> getChunk(access, cap.getMapKey(), cap.bat, len))
                .thenApply(p -> {
                    bufferedChunks.put(chunkOffset, new Pair<>(p.left, p.right));
                    return true;
                });
    }

    private CompletableFuture<Pair<byte[], AbsoluteCapability>> getChunk(CryptreeNode access, byte[] chunkLocation, Optional<Bat> bat, int truncateTo) {
        if (access.isDirectory())
                throw new IllegalStateException("File linked to a directory for its next chunk!");
        return access.retriever(baseKey, streamSecret, chunkLocation, bat, crypto.hasher)
                .thenCompose(retriever -> {
                    return access.getNextChunkLocation(baseKey, streamSecret, chunkLocation, bat, crypto.hasher)
                            .thenCompose(mapKeyAndBat -> {
                                AbsoluteCapability newNextChunkPointer = originalNextPointer.withMapKey(mapKeyAndBat.left, mapKeyAndBat.right);
                                return retriever.getChunk(version, network, crypto, 0, truncateTo,
                                                originalNextPointer.withMapKey(chunkLocation, bat), streamSecret, access.committedHash(), monitor)
                                        .thenApply(x -> {
                                            byte[] nextData = x.get().chunk.data();
                                            return new Pair<>(nextData, newNextChunkPointer);
                                        });
                            });
                });
    }

    private CompletableFuture<CryptreeNode> getSubsequentMetadata(AbsoluteCapability nextCap, long chunks) {
        if (nextCap == null) {
            CompletableFuture<CryptreeNode> err = new CompletableFuture<>();
            err.completeExceptionally(new EOFException());
            return err;
        }

        return network.getMetadata(version, nextCap)
                .thenCompose(meta -> {
                    if (!meta.isPresent()) {
                        CompletableFuture<CryptreeNode> err = new CompletableFuture<>();
                        err.completeExceptionally(new EOFException());
                        return err;
                    }
                    return CompletableFuture.completedFuture(meta.get());
                }).thenCompose(access -> {
                    if (chunks == 0)
                        return CompletableFuture.completedFuture(access);
                    return access.getNextChunkLocation(baseKey, streamSecret, nextCap.getMapKey(), nextCap.bat, crypto.hasher)
                            .thenCompose(mapKeyAndBat -> {
                                AbsoluteCapability newNextCap = nextCap.withMapKey(mapKeyAndBat.left, mapKeyAndBat.right);
                                return getSubsequentMetadata(newNextCap, chunks - 1);
                            });
                });
    }

    private CompletableFuture<AsyncReader> skip(long skip) {
        bufferedChunks.clear();
        long available = bytesReady();

        if (skip <= available) {
            index += (int) skip;
            return CompletableFuture.completedFuture(this);
        }

        long toRead = Math.min(available, skip);

        long toSkipAfterThisChunk = skip - toRead;
            // skip through the cryptree nodes without downloading the data
            long finalOffset = globalIndex + skip;
            long finalInternalIndex = finalOffset % Chunk.MAX_SIZE;
            long startOfTargetChunk = finalOffset - finalInternalIndex;
            long chunksToSkip = toSkipAfterThisChunk / Chunk.MAX_SIZE;
            int truncateTo = (int) Math.min(Chunk.MAX_SIZE, totalLength - startOfTargetChunk);
            // short circuit for files in the new deterministic (but still secret) format
            if (streamSecret.isPresent()) {
                return FileProperties.calculateMapKey(streamSecret.get(), originalChunkLocation, originalChunkBat,
                        finalOffset, crypto.hasher)
                        .thenCompose(targetChunkLocation -> {
                            AbsoluteCapability targetPointer = nextChunkPointer().withMapKey(targetChunkLocation.left, targetChunkLocation.right);
                            return getSubsequentMetadata(targetPointer, 0)
                                    .thenCompose(access -> getChunk(access, targetPointer.getMapKey(), targetPointer.bat, truncateTo))
                                    .thenCompose(p -> {
                                        updateState(index, finalOffset, p.left, p.right);
                                        return skip(finalInternalIndex);});
                        });
            }
            return getSubsequentMetadata(nextChunkPointer(), chunksToSkip)
                    .thenCompose(access -> getChunk(access, nextChunkPointer().getMapKey(), nextChunkPointer().bat, truncateTo))
                    .thenCompose(p -> {
                        updateState(index, finalOffset, p.left, p.right);
                        return skip(finalInternalIndex);
                    });
    }

    @Override
    public CompletableFuture<AsyncReader> seekJS(int hi32, int low32) {
        long seek = ((long) (hi32) << 32) | (low32 & 0xFFFFFFFFL);

        if (totalLength < seek)
            throw new IllegalStateException("Cannot seek to position "+ seek + " in file of length " + totalLength);
        long globalOffset = globalIndex + index;
        if (seek > globalOffset)
            return copy().skip(seek - globalOffset);
        return copy().reset().thenCompose(x -> ((LazyInputStreamCombiner)x).skip(seek));
    }

    private byte[] currentChunk() {
        return bufferedChunks.get(globalIndex).left;
    }

    private AbsoluteCapability nextChunkPointer() {
        return bufferedChunks.get(globalIndex).right;
    }

    private int bytesReady() {
        return this.currentChunk().length - this.index;
    }

    public void close() {}

    public CompletableFuture<AsyncReader> reset() {
        this.globalIndex = 0;
        bufferedChunks.clear();
        bufferedChunks.put(0L, new Pair<>(originalChunk, originalNextPointer));
        this.index = 0;
        return CompletableFuture.completedFuture(this);
    }

    /**
     *
     * @param res array to store data in
     * @param offset initial index to store data in res
     * @param length number of bytes to read
     * @return number of bytes read
     */
    public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        int available = bytesReady();
        int toRead = Math.min(available, length);
        System.arraycopy(currentChunk(), index, res, offset, toRead);
        index += toRead;
        long globalOffset = globalIndex + index;

        if (available >= length) // we are done
            return CompletableFuture.completedFuture(length);
        if (globalOffset > totalLength) {
            CompletableFuture<Integer> err=  new CompletableFuture<>();
            err.completeExceptionally(new EOFException());
            return err;
        }
        int nextChunkSize = totalLength - globalOffset > Chunk.MAX_SIZE ?
                Chunk.MAX_SIZE :
                (int) (totalLength - globalOffset);
        long currentChunk = globalIndex;
        long nextChunk = globalIndex + Chunk.MAX_SIZE;
        return getChunk(nextChunkPointer(), nextChunk, nextChunkSize).thenCompose(done -> {
            index = 0;
            globalIndex = nextChunk;
            bufferedChunks.remove(currentChunk);
            prefetch(nBufferedChunks);
            return this.readIntoArray(res, offset + toRead, length - toRead).thenApply(bytesRead -> bytesRead + toRead);
        });
    }

    private void updateState(int index,
                             long globalIndex,
                             byte[] chunk,
                             AbsoluteCapability nextChunkPointer) {
        this.index = index;
        this.globalIndex = globalIndex;
        bufferedChunks.put(globalIndex, new Pair<>(chunk, nextChunkPointer));
    }
}
