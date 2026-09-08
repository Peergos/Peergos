package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.mutable.*;

import java.util.*;
import java.util.stream.*;

/** All the writes of a single writer within a {@link BulkCommit}.
 *
 *  Small blocks travel inline, large raw blocks are written direct to S3 beforehand and only their
 *  hashes are named here. The pointer update, when present, signs the new root which transitively
 *  authenticates every block in this commit.
 */
public class WriterCommit implements Cborable {

    public final PublicKeyHash writer;
    public final List<byte[]> cborBlocks;
    public final List<byte[]> rawBlocks;
    public final List<Cid> preWritten;
    public final Optional<SignedPointerUpdate> pointer;
    /** Signature over the ordered list of block hashes, required exactly when there is no pointer update. */
    public final Optional<byte[]> blockListSignature;

    public WriterCommit(PublicKeyHash writer,
                        List<byte[]> cborBlocks,
                        List<byte[]> rawBlocks,
                        List<Cid> preWritten,
                        Optional<SignedPointerUpdate> pointer,
                        Optional<byte[]> blockListSignature) {
        if (pointer.isPresent() && ! pointer.get().writer.equals(writer))
            throw new IllegalArgumentException("Pointer update for a different writer! " + pointer.get().writer + " != " + writer);
        this.writer = writer;
        this.cborBlocks = cborBlocks;
        this.rawBlocks = rawBlocks;
        this.preWritten = preWritten;
        this.pointer = pointer;
        this.blockListSignature = blockListSignature;
    }

    public int inlineSize() {
        return cborBlocks.stream().mapToInt(b -> b.length).sum() + rawBlocks.stream().mapToInt(b -> b.length).sum();
    }

    public int blockCount() {
        return cborBlocks.size() + rawBlocks.size();
    }

    public boolean isEmpty() {
        return blockCount() == 0 && preWritten.isEmpty() && pointer.isEmpty();
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("w", writer);
        state.put("c", new CborObject.CborList(cborBlocks.stream()
                .map(CborObject.CborByteArray::new)
                .collect(Collectors.toList())));
        state.put("r", new CborObject.CborList(rawBlocks.stream()
                .map(CborObject.CborByteArray::new)
                .collect(Collectors.toList())));
        state.put("p", new CborObject.CborList(preWritten.stream()
                .map(CborObject.CborMerkleLink::new)
                .collect(Collectors.toList())));
        pointer.ifPresent(p -> state.put("u", p));
        blockListSignature.ifPresent(s -> state.put("s", new CborObject.CborByteArray(s)));
        return CborObject.CborMap.build(state);
    }

    public static WriterCommit fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for WriterCommit! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new WriterCommit(m.get("w", PublicKeyHash::fromCbor),
                m.getList("c", c -> ((CborObject.CborByteArray) c).value),
                m.getList("r", c -> ((CborObject.CborByteArray) c).value),
                m.getList("p", c -> (Cid) ((CborObject.CborMerkleLink) c).target),
                m.getOptional("u", SignedPointerUpdate::fromCbor),
                m.getOptionalByteArray("s"));
    }

    @Override
    public String toString() {
        return "WriterCommit(" + writer + ", " + cborBlocks.size() + " cbor, " + rawBlocks.size() + " raw, "
                + preWritten.size() + " pre-written, pointer=" + pointer.isPresent() + ")";
    }
}
