package peergos.shared.display;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.social.*;

import java.util.*;

@JsType
public
class Reference implements Content {
    public final SocialPost.Ref ref;

    public Reference(SocialPost.Ref ref) {
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
    public Optional<SocialPost.Ref> reference() {
        return Optional.of(ref);
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
