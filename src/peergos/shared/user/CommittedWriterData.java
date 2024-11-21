package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.io.ipfs.*;

import java.util.*;
import java.util.concurrent.*;

public class CommittedWriterData implements Cborable {

    public final MaybeMultihash hash;
    public final Optional<Long> sequence;
    public final Optional<WriterData> props;

    public CommittedWriterData(MaybeMultihash hash, Optional<WriterData> props, Optional<Long> sequence) {
        this.hash = hash;
        this.props = props;
        this.sequence = sequence;
    }

    public CommittedWriterData(MaybeMultihash hash, WriterData props, Optional<Long> sequence) {
        this(hash, Optional.of(props), sequence);
    }

    @Override
    public String toString() {
        return sequence.map(Object::toString).orElse("") + ":" + hash.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommittedWriterData that = (CommittedWriterData) o;
        return Objects.equals(hash, that.hash) && sequence.equals(that.sequence);
    }

    @Override
    public int hashCode() {
        return hash.hashCode() ^ sequence.hashCode();
    }

    public interface Retriever {
        CompletableFuture<CommittedWriterData> getWriterData(Cid hash, Optional<Long> sequence);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("h", hash.toCbor());
        sequence.ifPresent(seq -> state.put("s", new CborObject.CborLong(seq)));
        props.ifPresent(p -> state.put("p", p.toCbor()));
        return CborObject.CborMap.build(state);
    }

    public static CommittedWriterData fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for CommittedWriterData! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        MaybeMultihash hash = m.get("h", MaybeMultihash::fromCbor);
        Optional<Long> sequence = m.getOptionalLong("s");
        Optional<WriterData> props = m.getOptional("p", WriterData::fromCbor);
        return new CommittedWriterData(hash, props, sequence);
    }
}
