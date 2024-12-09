package peergos.shared.user.fs;

import peergos.shared.crypto.hash.Hasher;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

public class HashTreeBuilder {

    private final byte[][] chunkHashes;

    public HashTreeBuilder(long filesize) {
        this.chunkHashes = new byte[filesize == 0 ? 1 : ((int)((filesize + Chunk.MAX_SIZE - 1) / Chunk.MAX_SIZE))][];
    }

    public CompletableFuture<Boolean> setChunk(int chunkIndex, byte[] chunk, Hasher h) {
        return h.sha256(chunk)
                .thenApply(hash -> {
                    chunkHashes[chunkIndex] = hash;
                    return true;
                });
    }

    public void setChunkHash(int chunkIndex, byte[] hash) {
        chunkHashes[chunkIndex] = hash;
    }

    public CompletableFuture<HashTree> complete(Hasher h) {
        for (int i=0; i < chunkHashes.length; i++)
            if (chunkHashes[i] == null)
                throw new IllegalStateException("Incomplete tree hash state!");
        return HashTree.build(Arrays.asList(chunkHashes), h);
    }
}
