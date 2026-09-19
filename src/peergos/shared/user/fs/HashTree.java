package peergos.shared.user.fs;

import jsinterop.annotations.JsMethod;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.hash.Blake3;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.util.Futures;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

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

    /**
     * The value stored for one chunk of a file, which is also what a per-chunk comparison uses.
     *
     * Defined once because the three things that produce it - a whole file hash, a resumed
     * upload and {@link FileWrapper#overwriteChangedChunks} - have to agree exactly, and a
     * disagreement shows up as a wrong root hash written into a cryptree node rather than as an
     * error.
     *
     * For a legacy file that is the chunk's sha256. For a BLAKE3 file it is the chunk's chaining
     * value, which depends on where the chunk sits in the file - except for a single chunk file,
     * which has no merges at all, so the chunk's value is the file's root hash itself.
     */
    public static CompletableFuture<byte[]> chunkHash(byte[] data,
                                                      long chunkIndex,
                                                      int chunkSize,
                                                      boolean isOnlyChunk,
                                                      Hasher h) {
        if (! Chunk.usesBlake3(chunkSize))
            return h.sha256(data);
        if (isOnlyChunk)
            return h.blake3(data);
        return h.blake3ChainingValue(data, chunkIndex * (chunkSize / Blake3.CHUNK_SIZE));
    }

    private static CompletableFuture<byte[]> chunkHashOfSection(AsyncReader f,
                                                                long start,
                                                                long end,
                                                                long chunkIndex,
                                                                int chunkSize,
                                                                boolean isOnlyChunk,
                                                                Hasher h) {
        if (! Chunk.usesBlake3(chunkSize))
            return h.sha256Section(f, start, end);
        if (isOnlyChunk)
            return h.blake3Section(f, start, end);
        return h.blake3SectionChainingValue(f, start, end, chunkIndex * (chunkSize / Blake3.CHUNK_SIZE));
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
    public static CompletableFuture<HashTree> buildParallel(Function<Integer, AsyncReader> f,
                                                            int sizeHi,
                                                            int sizeLow,
                                                            int chunkSize,
                                                            Hasher hasher,
                                                            int parallelism) {
        long size = ((long)sizeHi) << 32 | (sizeLow & 0xFFFFFFFFL);
        long nChunks = size == 0 ? 1 : (size + chunkSize - 1) / chunkSize;
        long chunksPerThread = (nChunks + parallelism - 1) / parallelism;
        int actualParallelism = (int) Math.min((nChunks + chunksPerThread - 1)/chunksPerThread, Math.min(parallelism, nChunks));
        long chunksInLastThread = nChunks - ((actualParallelism - 1) * chunksPerThread);
        return Futures.combineAllInOrder(IntStream.range(0, actualParallelism).mapToObj(p -> {
                    boolean lastThread = p == actualParallelism - 1;
                    AsyncReader reader = f.apply(p);
                    long nChunksForThread = lastThread ? chunksInLastThread : chunksPerThread;
                    return Futures.reduceAll(
                            LongStream.range(0, nChunksForThread).boxed(),
                            new ArrayList<byte[]>(),
                            (hashes, i) -> {
                                long chunkIndex = p * chunksPerThread + i;
                                long chunkStart = chunkIndex * chunkSize;
                                long chunkEnd = Math.min(chunkStart + chunkSize, size);
                                return chunkHashOfSection(reader, chunkStart, chunkEnd, chunkIndex, chunkSize, nChunks == 1, hasher)
                                        .thenApply(hash -> {
                                            ArrayList<byte[]> next = new ArrayList<>(hashes);
                                            next.add(hash);
                                            return next;
                                        });
                            },
                            (a, b) -> { ArrayList<byte[]> res = new ArrayList<>(a); res.addAll(b); return res; }
                    );
                }).collect(Collectors.toList()))
                .thenApply(nested -> nested.stream()
                        .flatMap(Collection::stream)
                        .collect(Collectors.toList()))
                .thenCompose(level1 -> build(level1, chunkSize, hasher));
    }

    @JsMethod
    public static CompletableFuture<HashTree> build(AsyncReader f, int sizeHi, int sizeLow, int chunkSize, Hasher hasher) {
        long size = ((long)sizeHi) << 32 | (sizeLow & 0xFFFFFFFFL);
        long nChunks = size == 0 ? 1 : (size + chunkSize - 1) / chunkSize;
        byte[] chunk = new byte[(int) Math.min(chunkSize, size)];
        return Futures.combineAllInOrder(LongStream.range(0, nChunks)
                        .mapToObj(i -> {
                            boolean lastOfMultiChunk = i == nChunks - 1 && nChunks > 1;
                            long lastChunkSize = size % chunkSize;
                            int remaining = lastOfMultiChunk ? (int) (lastChunkSize == 0 ? chunkSize : lastChunkSize) : chunk.length;
                            return readChunk(f, lastOfMultiChunk ? new byte[remaining] : chunk, 0, remaining)
                                    .thenCompose(data -> chunkHash(data, i, chunkSize, nChunks == 1, hasher));
                        })
                        .collect(Collectors.toList()))
                .thenCompose(level1 -> build(level1, chunkSize, hasher));
    }

    public static CompletableFuture<HashTree> build(List<byte[]> chunkHashes,
                                                    int chunkSize,
                                                    Hasher hasher) {
        if (chunkHashes.isEmpty())
            throw new IllegalStateException("A file cannot have no chunk hashes.");
        if (Chunk.usesBlake3(chunkSize))
            return Futures.of(buildBlake3(chunkHashes));
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
                                                return hasher.sha256(new CborObject.CborList(level4).serialize())
                                                        .thenApply(RootHash::new)
                                                        .thenApply(r -> new HashTree(r, level1, level2, level3));
                                            }
                                            throw new IllegalStateException("Files bigger than 5 PiB are not supported in HashTree!");
                                        });
                            });
                });
    }

    /**
     * The same 1024 per blob storage as a legacy file, but the levels above level 1 are merges
     * rather than hashes of a serialised blob, so that the root is the file's real BLAKE3 hash.
     *
     * The grouping survives the change because 1024 is a power of two: blob j holds the chaining
     * values of chunks [1024j, 1024j + 1024), a power of two number of chunks starting at a
     * multiple of its own length, which is exactly BLAKE3's condition for a subtree. So a blob's
     * merge is a genuine node of the file's tree, and modifying one chunk still means rewriting
     * one blob per level rather than rehashing the file. Only the rightmost blob at each level
     * may be partial, and its merge is a valid node because it is rightmost.
     */
    private static HashTree buildBlake3(List<byte[]> chunkCVs) {
        List<ChunkHashList> level1 = buildLevel(chunkCVs);
        if (chunkCVs.size() == 1) // no merges at all: the single chunk's value is already the root
            return new HashTree(new RootHash(chunkCVs.get(0)), level1, Collections.emptyList(), Collections.emptyList());
        if (level1.size() == 1)
            return new HashTree(new RootHash(Blake3.mergeAsRoot(chunkCVs)), level1,
                    Collections.emptyList(), Collections.emptyList());
        List<byte[]> level2CVs = mergeBlobs(level1);
        List<ChunkHashList> level2 = buildLevel(level2CVs);
        if (level2.size() == 1)
            return new HashTree(new RootHash(Blake3.mergeAsRoot(level2CVs)), level1, level2, Collections.emptyList());
        List<byte[]> level3CVs = mergeBlobs(level2);
        List<ChunkHashList> level3 = buildLevel(level3CVs);
        if (level3.size() == 1)
            return new HashTree(new RootHash(Blake3.mergeAsRoot(level3CVs)), level1, level2, level3);
        throw new IllegalStateException("Files bigger than 4 EiB are not supported in HashTree!");
    }

    /** The chaining value of each blob's subtree. */
    private static List<byte[]> mergeBlobs(List<ChunkHashList> level) {
        return level.stream()
                .map(blob -> Blake3.mergeSubtree(blob.hashes()))
                .collect(Collectors.toList());
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
