package peergos.shared.user.fs;

import peergos.shared.crypto.hash.Hasher;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class HashTreeBuilder {

    private final byte[][] chunkHashes;
    private final int chunkSize;

    public HashTreeBuilder(long filesize, int chunkSize) {
        this.chunkHashes = new byte[filesize == 0 ? 1 : ((int)((filesize + chunkSize - 1) / chunkSize))][];
        this.chunkSize = chunkSize;
    }

    /** Fill in the hashes not yet set of the first count chunks, which a write left as they were, from those stored
     *  for the file when it was oldSize bytes, if they include one for each of its chunks.
     */
    public void keep(Optional<HashBranch> stored, long oldSize, int count) {
        int oldChunks = FileWrapper.getNumberOfChunks(oldSize, chunkSize);
        Optional<ChunkHashList> level1 = stored.flatMap(h -> h.level1);
        if (level1.isEmpty() || level1.get().nChunks() != oldChunks)
            return;
        // BLAKE3 hashes a file's only chunk as the whole file, not as the first of several
        boolean firstChanged = Chunk.usesBlake3(chunkSize) && (oldChunks == 1) != (chunkHashes.length == 1);
        for (int i = firstChanged ? 1 : 0; i < count; i++)
            if (chunkHashes[i] == null)
                chunkHashes[i] = Arrays.copyOfRange(level1.get().chunkHashes, i * 32, (i + 1) * 32);
    }

    public CompletableFuture<Boolean> setChunk(int chunkIndex, byte[] chunk, Hasher h) {
        return HashTree.chunkHash(chunk, chunkIndex, chunkSize, chunkHashes.length == 1, h)
                .thenApply(hash -> {
                    chunkHashes[chunkIndex] = hash;
                    return true;
                });
    }

    public void setChunkHash(int chunkIndex, byte[] hash) {
        chunkHashes[chunkIndex] = hash;
    }

    public boolean isComplete() {
        for (int i=0; i < chunkHashes.length; i++)
            if (chunkHashes[i] == null)
                return false;
        return true;
    }

    public CompletableFuture<HashTree> complete(Hasher h) {
        if (! isComplete())
            throw new IllegalStateException("Incomplete tree hash state!");
        return HashTree.build(Arrays.asList(chunkHashes), chunkSize, h);
    }
}
