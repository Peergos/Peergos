package peergos.shared.mutable;

import peergos.shared.*;
import peergos.shared.cbor.*;

import java.util.*;

public class PointerUpdate implements Cborable {

    public final MaybeMultihash original;
    public final MaybeMultihash updated;
    public final Optional<Long> sequence;

    public PointerUpdate(MaybeMultihash original, MaybeMultihash updated, Optional<Long> sequence) {
        if (Objects.equals(original, updated))
            throw new IllegalStateException("Tried to create a CAS pair with original == target!");
        this.original = original;
        this.updated = updated;
        this.sequence = sequence;
    }

    public static Optional<Long> increment(Optional<Long> sequence) {
        return sequence.map(s -> Optional.of(s+1)).orElse(Optional.of(1L));
    }

    public static PointerUpdate empty() {
        return new PointerUpdate(null, MaybeMultihash.empty(), Optional.empty());
    }

    @Override
    public String toString() {
        return sequence.orElse(0L) + "(" + original.toString() + ", " + updated.toString() + ")";
    }

    @Override
    public CborObject toCbor() {
        if (sequence.isPresent())
            return new CborObject.CborList(Arrays.asList(
                    original.toCbor(),
                    updated.toCbor(),
                    new CborObject.CborLong(sequence.get())
            ));
        return new CborObject.CborList(Arrays.asList(
                original.toCbor(),
                updated.toCbor()
        ));
    }

    public static PointerUpdate fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for HashCasPair: " + cbor);

        List<? extends Cborable> value = ((CborObject.CborList) cbor).value;
        return new PointerUpdate(MaybeMultihash.fromCbor(value.get(0)),
                MaybeMultihash.fromCbor(value.get(1)),
                value.size() < 3 ? Optional.empty() : Optional.of(((CborObject.CborLong)value.get(2)).value));
    }
}
