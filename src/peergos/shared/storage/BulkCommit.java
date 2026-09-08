package peergos.shared.storage;

import peergos.shared.cbor.*;

import java.util.*;
import java.util.stream.*;

/** A single logical write: every block and every pointer update it consists of, for one owner.
 *
 *  Applied atomically by the server: nothing is durably applied unless the quota, closure and
 *  signature checks all pass.
 */
public class BulkCommit implements Cborable {

    /** An open transaction protecting blocks written directly to S3, to be closed on success. */
    public final Optional<TransactionId> tid;
    public final List<WriterCommit> writers;

    public BulkCommit(Optional<TransactionId> tid, List<WriterCommit> writers) {
        this.tid = tid;
        this.writers = writers;
    }

    public int inlineSize() {
        return writers.stream().mapToInt(WriterCommit::inlineSize).sum();
    }

    public int blockCount() {
        return writers.stream().mapToInt(WriterCommit::blockCount).sum();
    }

    public boolean hasPointerUpdate() {
        return writers.stream().anyMatch(w -> w.pointer.isPresent());
    }

    public boolean isEmpty() {
        return writers.stream().allMatch(WriterCommit::isEmpty);
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        tid.ifPresent(t -> state.put("t", new CborObject.CborString(t.toString())));
        state.put("w", new CborObject.CborList(writers.stream()
                .map(WriterCommit::toCbor)
                .collect(Collectors.toList())));
        return CborObject.CborMap.build(state);
    }

    public static BulkCommit fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for BulkCommit! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new BulkCommit(m.getOptionalString("t").map(TransactionId::new),
                m.getList("w", WriterCommit::fromCbor));
    }

    @Override
    public String toString() {
        return "BulkCommit(" + writers + ")";
    }
}
