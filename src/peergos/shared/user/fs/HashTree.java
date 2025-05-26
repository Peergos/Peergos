package peergos.shared.user.fs;

import jsinterop.annotations.JsMethod;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.util.Futures;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

public class HashTree implements Cborable {

    public final RootHash rootHash;
    public final List<ChunkHashList> level1;
    public final List<ChunkHashList> level2;
    public final List<ChunkHashList> level3;

    public HashTree(RootHash rootHash, List<ChunkHashList> level1, List<ChunkHashList> level2, List<ChunkHashList> level3) {
        this.rootHash = rootHash;
        this.level1 = level1;
        this.level2 = level2;
        this.level3 = level3;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("r", rootHash.toCbor());
        state.put("ll1", new CborObject.CborList(level1));
        state.put("ll2", new CborObject.CborList(level2));
        state.put("ll3", new CborObject.CborList(level3));
        return CborObject.CborMap.build(state);
    }

    public static HashTree fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for HashTree! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        RootHash root = m.get("r", RootHash::fromCbor);
        List<ChunkHashList> level1 = m.getList("ll1", ChunkHashList::fromCbor);
        List<ChunkHashList> level2 = m.getList("ll2", ChunkHashList::fromCbor);
        List<ChunkHashList> level3 = m.getList("ll3", ChunkHashList::fromCbor);
        return new HashTree(root, level1, level2, level3);
    }

    public HashBranch branch(long chunkIndex) {
        return new HashBranch(rootHash,
                level1.stream().skip(chunkIndex / 1024).findFirst(),
                level2.stream().skip(chunkIndex / 1024 / 1024).findFirst(),
                level3.stream().skip(chunkIndex / 1024 / 1024 / 1024).findFirst());
    }

    @Override
    public String toString() {
        return rootHash.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HashTree hashTree = (HashTree) o;
        return Objects.equals(rootHash, hashTree.rootHash) && Objects.equals(level1, hashTree.level1) && Objects.equals(level2, hashTree.level2) && Objects.equals(level3, hashTree.level3);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rootHash, level1, level2, level3);
    }

    public static HashTree fromBranches(List<HashBranch> branches) {
        List<ChunkHashList> level1 = branches.stream().flatMap(b -> b.level1.stream()).collect(Collectors.toList());
        List<ChunkHashList> level2 = branches.stream().flatMap(b -> b.level2.stream()).collect(Collectors.toList());
        List<ChunkHashList> level3 = branches.stream().flatMap(b -> b.level3.stream()).collect(Collectors.toList());
        return new HashTree(branches.get(0).rootHash, level1, level2, level3);
    }

    private static CompletableFuture<byte[]> readChunk(AsyncReader f, byte[] buf, int offset, int remaining) {
        if (remaining == 0)
            return Futures.of(buf);
        return f.readIntoArray(buf, offset, remaining)
                .thenCompose(read -> read == remaining ?
                        Futures.of(buf) :
                        readChunk(f, buf, offset + read, remaining - read));
    }

    @JsMethod
    public static CompletableFuture<HashTree> build(AsyncReader f, int sizeHi, int sizeLow, Hasher hasher) {
        long size = ((long)sizeHi) << 32 | (sizeLow & 0xFFFFFFFFL);
        long nChunks = size == 0 ? 1 : (size + Chunk.MAX_SIZE - 1) / Chunk.MAX_SIZE;
        byte[] chunk = new byte[(int) Math.min(Chunk.MAX_SIZE, size)];
        return Futures.combineAllInOrder(LongStream.range(0, nChunks)
                        .mapToObj(i -> {
                            boolean lastOfMultiChunk = i == nChunks - 1 && nChunks > 1;
                            int remaining = lastOfMultiChunk ? (int) (size % Chunk.MAX_SIZE) : chunk.length;
                            return readChunk(f, lastOfMultiChunk ? new byte[remaining] : chunk, 0, remaining)
                                    .thenCompose(data -> hasher.sha256(data));
                        })
                        .collect(Collectors.toList()))
                .thenCompose(level1 -> build(level1, hasher));
    }

    public static CompletableFuture<HashTree> build(List<byte[]> chunkHashes,
                                                    Hasher hasher) {
        if (chunkHashes.isEmpty())
            throw new IllegalStateException("A file cannot have no chunk hashes.");
        List<ChunkHashList> level1 = buildLevel(chunkHashes);
        if (level1.size() == 1) {
            return hasher.sha256(new CborObject.CborList(level1).serialize())
                    .thenApply(RootHash::new)
                    .thenApply(r -> new HashTree(r, level1, Collections.emptyList(), Collections.emptyList()));
        }
        return buildLevel(level1, hasher)
                .thenCompose(level2 -> {
                    if (level2.size() == 1) {
                        return hasher.sha256(new CborObject.CborList(level2).serialize())
                                .thenApply(RootHash::new)
                                .thenApply(r -> new HashTree(r, level1, level2, Collections.emptyList()));
                    }
                    return buildLevel(level2, hasher)
                            .thenCompose(level3 -> {
                                if (level3.size() == 1) {
                                    return hasher.sha256(new CborObject.CborList(level3).serialize())
                                            .thenApply(RootHash::new)
                                            .thenApply(r -> new HashTree(r, level1, level2, level3));
                                }
                                return buildLevel(level3, hasher)
                                        .thenCompose(level4 -> {
                                            if (level4.size() == 1) {
                                                return hasher.sha256(new CborObject.CborList(level3).serialize())
                                                        .thenApply(RootHash::new)
                                                        .thenApply(r -> new HashTree(r, level1, level2, level3));
                                            }
                                            throw new IllegalStateException("Files bigger than 5 PiB are not supported in HashTree!");
                                        });
                            });
                });
    }

    private static List<ChunkHashList> buildLevel(List<byte[]> chunkHashes) {
        List<ChunkHashList> level = new ArrayList<>();

        for (int i=0; i < chunkHashes.size(); i += 1024) {
            int nChunks = Math.min(1024, chunkHashes.size() - i);
            byte[] chunkHashesBytes = new byte[nChunks * 32];
            for (int c=0; c < nChunks; c++)
                System.arraycopy(chunkHashes.get(i + c), 0, chunkHashesBytes, c * 32, 32);
            ChunkHashList level1Section = new ChunkHashList(chunkHashesBytes);
            level.add(level1Section);
        }
        return level;
    }

    private static CompletableFuture<List<ChunkHashList>> buildLevel(List<ChunkHashList> level, Hasher h) {
        return Futures.combineAllInOrder(level.stream()
                        .map(l1 -> h.sha256(l1.serialize()))
                        .collect(Collectors.toList()))
                .thenApply(HashTree::buildLevel);
    }
}
