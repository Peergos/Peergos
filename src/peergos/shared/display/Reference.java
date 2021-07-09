package peergos.shared.display;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;

import java.util.*;

@JsType
public
class Reference implements Content {
    public final FileRef ref;

    public Reference(FileRef ref) {
        this.ref = ref;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("t", new CborObject.CborString("Ref"));
        state.put("r", ref);
        return CborObject.CborMap.build(state);
    }

    @Override
    public String inlineText() {
        return "";
    }

    @Override
    public Optional<FileRef> reference() {
        return Optional.of(ref);
    }

    @Override
    public String toString() {
        return "REFERENCE(" + ref.path + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        peergos.shared.display.Reference reference = (peergos.shared.display.Reference) o;
        return Objects.equals(ref, reference.ref);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ref);
    }
}
