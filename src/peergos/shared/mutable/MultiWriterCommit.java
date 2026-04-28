package peergos.shared.mutable;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class MultiWriterCommit implements Cborable {
    public final List<SignedPointerUpdate> updates;

    public MultiWriterCommit(List<SignedPointerUpdate> updates) {
        this.updates = updates;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("p", new CborObject.CborList(updates));
        return CborObject.CborMap.build(state);
    }

    public static MultiWriterCommit fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for MultiWriterCommit! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new MultiWriterCommit(m.getList("p", SignedPointerUpdate::fromCbor));
    }
}
