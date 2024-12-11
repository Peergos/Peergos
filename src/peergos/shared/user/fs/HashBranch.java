package peergos.shared.user.fs;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.*;

/** A branch of a hash tree using sha256
 *
 */
public class HashBranch implements Cborable {

    public final RootHash rootHash;
    public final Optional<ChunkHashList> level1; // This is present on the first chunk of every 1024 chunks (5 GiB with 5 MiB chunks)
    public final Optional<ChunkHashList> level2; // This is present on the first chunk of every 1024*1024 chunks (5 TiB with 5 MiB chunks)
    public final Optional<ChunkHashList> level3; // This is present on the first chunk of every 1024*1024*1024 chunks (5 PiB with 5 MiB chunks)

    public HashBranch(RootHash rootHash, Optional<ChunkHashList> level1, Optional<ChunkHashList> level2, Optional<ChunkHashList> level3) {
        if (level2.isPresent() && level1.isEmpty())
            throw new IllegalArgumentException("Invalid chunk hash tree state!");
        if (level3.isPresent() && level2.isEmpty())
            throw new IllegalArgumentException("Invalid chunk hash tree state!");
        this.rootHash = rootHash;
        this.level1 = level1;
        this.level2 = level2;
        this.level3 = level3;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("r", rootHash.toCbor());
        level1.ifPresent(b -> state.put("l1", b.toCbor()));
        level2.ifPresent(b -> state.put("l2", b.toCbor()));
        level3.ifPresent(b -> state.put("l3", b.toCbor()));
        return CborObject.CborMap.build(state);
    }

    public static HashBranch fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for HashBranch! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        RootHash hash = m.get("r", RootHash::fromCbor);
        Optional<ChunkHashList> level1 = m.getOptional("l1", ChunkHashList::fromCbor);
        Optional<ChunkHashList> level2 = m.getOptional("l2", ChunkHashList::fromCbor);
        Optional<ChunkHashList> level3 = m.getOptional("l3", ChunkHashList::fromCbor);
        return new HashBranch(hash, level1, level2, level3);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HashBranch that = (HashBranch) o;
        return Objects.equals(rootHash, that.rootHash) && Objects.equals(level1, that.level1) && Objects.equals(level2, that.level2) && Objects.equals(level3, that.level3);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rootHash, level1, level2, level3);
    }
}
