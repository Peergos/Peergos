package peergos.shared.user.fs;

import peergos.shared.crypto.hash.Hasher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class HashTreeBuilder {

    private final List<byte[]> chunkHashes;

    public HashTreeBuilder(long filesize) {
        this.chunkHashes = new ArrayList<>( (int)(filesize/ Chunk.MAX_SIZE));
    }

    public CompletableFuture<Boolean> setChunk(int chunkIndex, byte[] chunk, Hasher h) {
        return h.sha256(chunk)
                .thenApply(hash -> chunkHashes.set(chunkIndex, hash))
                .thenApply(x -> true);
    }

    public void setChunkHash(int chunkIndex, byte[] hash) {
        chunkHashes.set(chunkIndex, hash);
    }

    public CompletableFuture<HashTree> complete(Hasher h) {
        return HashTree.build(chunkHashes, h);
    }
}
