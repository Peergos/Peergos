package peergos.server.sync;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.user.fs.*;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.Pair;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

public class FileState implements Cborable {
    public final String relPath;
    public final long modificationTime;
    public final long size;
    public final HashTree hashTree;

    public FileState(String relPath, long modificationTime, long size, HashTree hashTree) {
        if (relPath.contains("..")) {
            if (Arrays.asList(relPath.split("/")).contains(".."))
                throw new IllegalStateException("Invalid path: " + relPath);
        }
        this.relPath = relPath;
        this.modificationTime = modificationTime;
        this.size = size;
        this.hashTree = hashTree;
    }

    public FileState withModtime(Optional<LocalDateTime> modtime) {
        return new FileState(relPath, modtime.map(t -> t.toInstant(ZoneOffset.UTC).toEpochMilli() / 1000 * 1000).orElse(modificationTime), size, hashTree);
    }

    public String prettyPrint() {
        return "[" + relPath + ", size: " + size + ", modTime: " + modificationTime + ", hash: " + ArrayOps.bytesToHex(hashTree.rootHash.hash)+"]";
    }

    public List<Pair<Long, Long>> diffRanges(FileState other) {
        if (other == null)
            return List.of(new Pair<>(0L, size));
        if (hashTree.rootHash.equals(other.hashTree.rootHash))
            return Collections.emptyList();

        List<ChunkHashList> a = hashTree.level1;
        List<ChunkHashList> b = other.hashTree.level1;
        List<Long> diffChunks = new ArrayList<>();
        for (int i=0; i < a.size(); i++) {
            ChunkHashList aList = a.get(i);
            ChunkHashList bList = b.get(i);
            if (bList == null) {
                diffChunks.addAll(LongStream.range(0, aList.nChunks())
                        .mapToObj(x -> x)
                        .collect(Collectors.toList()));
            } else {
                for (int j=0; j < aList.nChunks(); j++){
                    if (! aList.equalAt(j, bList))
                        diffChunks.add(i * 1024L + j);
                }
                for (int j= aList.nChunks(); j < bList.nChunks(); j++)
                    diffChunks.add(i * 1024L + j);
            }
        }
        return diffChunks.stream()
                .map(c -> new Pair<>(c * Chunk.MAX_SIZE, Math.min((c + 1) * Chunk.MAX_SIZE, size)))
                .collect(Collectors.toList());
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("r", new CborObject.CborString(relPath));
        state.put("m", new CborObject.CborLong(modificationTime));
        state.put("s", new CborObject.CborLong(size));
        state.put("h", hashTree.toCbor());

        return CborObject.CborMap.build(state);
    }

    public static FileState fromCbor(Cborable c) {
        CborObject.CborMap map = (CborObject.CborMap) c;
        String relPath = map.getString("r");
        long modTime = map.getLong("m");
        long size = map.getLong("s");
        HashTree hash = map.get("h", HashTree::fromCbor);
        return new FileState(relPath, modTime, size, hash);
    }

    public boolean equalsIgnoreModtime(FileState other) {
        return other != null && relPath.equals(other.relPath) && size == other.size && hashTree.equals(other.hashTree);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileState fileState = (FileState) o;
        return modificationTime == fileState.modificationTime && size == fileState.size && Objects.equals(relPath, fileState.relPath) && Objects.equals(hashTree.rootHash, fileState.hashTree.rootHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relPath, modificationTime, size, hashTree.rootHash);
    }

    @Override
    public String toString() {
        return relPath;
    }
}
