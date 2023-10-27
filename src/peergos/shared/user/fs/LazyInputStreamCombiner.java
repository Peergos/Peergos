package peergos.shared.user.fs;

import peergos.shared.*;
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

    private final Map<Long, Pair<byte[], AbsoluteCapability>> bufferedChunks = new ConcurrentHashMap<>(); // and next chunk pointer
    private final Map<Long, CompletableFuture<Pair<byte[], AbsoluteCapability>>> inProgress = new ConcurrentHashMap<>();
    private final int nBufferedChunks;
    private long globalIndex; // index of beginning of current chunk in file
    private byte[] currentChunk;
    private AbsoluteCapability currentNextChunkPointer;
    private int index; // index within current chunk

    public LazyInputStreamCombiner(WriterData version,
                                   long globalIndex,
                                   byte[] chunk,
                                   Location nextChunkLoc,
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
        this.globalIndex = globalIndex;
        this.currentChunk = chunk;
        this.currentNextChunkPointer = AbsoluteCapability.build(nextChunkLoc, nextChunkBat, baseKey);
        bufferedChunks.put(globalIndex, new Pair<>(chunk, this.currentNextChunkPointer));
        this.index = 0;
        this.nBufferedChunks = nBufferedChunks;
    }

    private void prefetch(int nChunks) {
        ForkJoinPool.commonPool().execute(() -> syncPrefetch(nChunks));
    }

    private void syncPrefetch(int nChunks) {
        if (streamSecret.isEmpty()) // can only parallelise download in non legacy files
            return;

        long globalIndexCopy = globalIndex;
        if (globalIndexCopy + Chunk.MAX_SIZE > totalLength)
            return;

        long lastBufferedChunkInSequence = globalIndexCopy;
        for (int i=1; i <= nChunks; i++) {
            if (! bufferedChunks.containsKey(lastBufferedChunkInSequence + i * Chunk.MAX_SIZE)) {
                lastBufferedChunkInSequence = lastBufferedChunkInSequence + (i-1) * Chunk.MAX_SIZE;
                break;
            }
        }
        if (lastBufferedChunkInSequence + nChunks * Chunk.MAX_SIZE >= totalLength)
            nChunks = (int) ((totalLength - lastBufferedChunkInSequence - 1) / Chunk.MAX_SIZE);
        if (nChunks == 0)
            return;

        int finalCount = nChunks;
        AbsoluteCapability nextChunkCap = bufferedChunks.get(lastBufferedChunkInSequence).right;

        long finalBufferedChunk = lastBufferedChunkInSequence;
        System.out.println("Prefetching " + finalCount + " chunks, starting from chunk " + (lastBufferedChunkInSequence / Chunk.MAX_SIZE + 1));
        FileProperties.calculateSubsequentMapKeys(streamSecret.get(), nextChunkCap.getMapKey(), nextChunkCap.bat, finalCount - 1, crypto.hasher)
                .thenAccept(mapKeys -> parallelChunksDownload(finalCount, finalBufferedChunk, mapKeys, nextChunkCap));
    }

    private void parallelChunksDownload(int finalCount,
                                        long lastBufferedChunk,
                                        List<Pair<byte[], Optional<Bat>>> mapKeys,
                                        AbsoluteCapability nextChunkCap) {
        for (int i=1; i < finalCount + 1; i++) {
            int size = lastBufferedChunk / Chunk.MAX_SIZE + i < totalChunks ? Chunk.MAX_SIZE : (int) (totalLength % Chunk.MAX_SIZE);
            Pair<byte[], Optional<Bat>> mapKey = mapKeys.get(i - 1);
            long chunkOffset = lastBufferedChunk + (i * Chunk.MAX_SIZE);
            if (inProgress.containsKey(chunkOffset) || bufferedChunks.containsKey(chunkOffset))
                continue;

            System.out.println("Submitting chunk download " + (chunkOffset / Chunk.MAX_SIZE));
            ForkJoinPool.commonPool().execute(() -> getChunk(nextChunkCap.withMapKey(mapKey.left, mapKey.right), chunkOffset, size));
        }
    }

    private CompletableFuture<Pair<byte[], AbsoluteCapability>> getChunk(AbsoluteCapability cap, long chunkOffset, int len) {
        Pair<byte[], AbsoluteCapability> existing = bufferedChunks.get(chunkOffset);
        if (existing != null)
            return Futures.of(existing);
        CompletableFuture<Pair<byte[], AbsoluteCapability>> pending = inProgress.get(chunkOffset);
        if (pending != null)
            return pending;
        inProgress.put(chunkOffset, new CompletableFuture<>());

        System.out.println("Downloading chunk " + (chunkOffset / Chunk.MAX_SIZE));
        return getSubsequentMetadata(cap, 0)
                .thenCompose(access -> getChunk(access, cap.getMapKey(), cap.bat, len))
                .thenApply(p -> {
                    Pair<byte[], AbsoluteCapability> res = new Pair<>(p.left, p.right);
                    bufferedChunks.put(chunkOffset, res);
                    CompletableFuture<Pair<byte[], AbsoluteCapability>> fut = inProgress.remove(chunkOffset);
                    if (fut != null)
                        fut.complete(res);
                    System.out.println("Completed chunk " + (chunkOffset / Chunk.MAX_SIZE));
                    return res;
                }).exceptionally(t -> {
                    CompletableFuture<Pair<byte[], AbsoluteCapability>> fut = inProgress.remove(chunkOffset);
                    if (fut != null)
                        fut.completeExceptionally(t);
                    throw new RuntimeException(t);
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
        long available = bytesReady();

        if (skip <= available) {
            index += (int) skip;
            return CompletableFuture.completedFuture(this);
        }

        long toRead = Math.min(available, skip);

        long toSkipAfterThisChunk = skip - toRead;
            // skip through the cryptree nodes without downloading the data
            long finalOffset = index + globalIndex + skip;
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
                                        updateState(0, startOfTargetChunk, p.left, p.right);
                                        return skip(finalInternalIndex);});
                        });
            }
            return getSubsequentMetadata(nextChunkPointer(), chunksToSkip)
                    .thenCompose(access -> getChunk(access, nextChunkPointer().getMapKey(), nextChunkPointer().bat, truncateTo))
                    .thenCompose(p -> {
                        updateState(0, startOfTargetChunk, p.left, p.right);
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
            return skip(seek - globalOffset);
        return reset().thenCompose(x -> ((LazyInputStreamCombiner)x).skip(seek));
    }

    private AbsoluteCapability nextChunkPointer() {
        return bufferedChunks.get(globalIndex).right;
    }

    private synchronized int bytesReady() {
        return currentChunk.length - index;
    }

    public void close() {}

    private void resetBuffer() {
        bufferedChunks.put(0L, new Pair<>(originalChunk, originalNextPointer));
    }

    public synchronized CompletableFuture<AsyncReader> reset() {
        resetBuffer();
        this.globalIndex = 0;
        this.currentChunk = originalChunk;
        this.currentNextChunkPointer = originalNextPointer;
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
        synchronized (this) {
            System.arraycopy(currentChunk, index, res, offset, toRead);
            index += toRead;
        }
        long globalOffset = globalIndex + index;

        prefetch(5);

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
        long nextChunk = globalIndex + Chunk.MAX_SIZE;
        return getChunk(nextChunkPointer(), nextChunk, nextChunkSize).thenCompose(current -> {
            index = 0;
            globalIndex = nextChunk;
            currentChunk = current.left;
            currentNextChunkPointer = current.right;
            ensureBufferWithinLimit();
            return this.readIntoArray(res, offset + toRead, length - toRead).thenApply(bytesRead -> bytesRead + toRead);
        });
    }

    private void ensureBufferWithinLimit() {
        if (bufferedChunks.size() > nBufferedChunks) {
            List<Long> sorted = bufferedChunks.keySet().stream()
                    .sorted()
                    .collect(Collectors.toList());
            long first = sorted.get(0);
            if (first < globalIndex)
                bufferedChunks.remove(first);
            else {
                long last = sorted.get(sorted.size() - 1);
                if (last > globalIndex)
                    bufferedChunks.remove(last);
            }
        }
    }

    private synchronized void updateState(int index,
                                          long globalIndex,
                                          byte[] chunk,
                                          AbsoluteCapability nextChunkPointer) {
        this.index = index;
        this.globalIndex = globalIndex;
        this.currentChunk = chunk;
        this.currentNextChunkPointer = nextChunkPointer;
        bufferedChunks.put(globalIndex, new Pair<>(chunk, nextChunkPointer));
    }
}
