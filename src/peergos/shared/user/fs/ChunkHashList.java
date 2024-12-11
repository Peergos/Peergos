package peergos.shared.user.fs;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.util.Pair;

import java.util.*;

public class ChunkHashList implements Cborable {

    public final byte[] chunkHashes;

    public ChunkHashList(byte[] chunkHashes) {
        if (chunkHashes.length > 32*1024)
            throw new IllegalStateException("Chunk hash list too large! " + chunkHashes.length);
        this.chunkHashes = chunkHashes;
    }

    public int nChunks() {
        return chunkHashes.length/32;
    }

    public boolean equalAt(int chunkIndex, ChunkHashList other) {
        if (other.chunkHashes.length < (chunkIndex + 1) * 32)
            return false;
        for (int i= chunkIndex* 32; i < (chunkIndex + 1) * 32; i++)
            if (chunkHashes[i] != other.chunkHashes[i])
                return false;
        return true;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("h", new CborObject.CborByteArray(chunkHashes));
        return CborObject.CborMap.build(state);
    }

    public static ChunkHashList fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for ChunkHashList! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new ChunkHashList(m.getByteArray("h"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChunkHashList that = (ChunkHashList) o;
        return Objects.deepEquals(chunkHashes, that.chunkHashes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(chunkHashes);
    }
}
