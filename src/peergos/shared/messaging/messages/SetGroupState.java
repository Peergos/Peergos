package peergos.shared.messaging.messages;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;

import java.util.*;

/** A message to set a key value pair in the shared group state.
 *
 * Concurrent sets are tie-broken by Id thus forming a CRDT.
 *
 */
@JsType
public class SetGroupState implements Message {

    public final String key, value;

    public SetGroupState(String key, String value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public Type type() {
        return Type.GroupState;
    }

    @Override
    public CborObject.CborMap toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", new CborObject.CborLong(type().value));
        state.put("k", new CborObject.CborString(key));
        state.put("v", new CborObject.CborString(value));
        return CborObject.CborMap.build(state);
    }

    public static SetGroupState fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        String key = m.get("k", c -> ((CborObject.CborString)c).value);
        String value = m.get("v", c -> ((CborObject.CborString)c).value);
        return new SetGroupState(key, value);
    }

    @Override
    public String toString() {
        return "SET-GROUP-STATE(" + key + ", " + value + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SetGroupState that = (SetGroupState) o;
        return Objects.equals(key, that.key) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value);
    }
}
