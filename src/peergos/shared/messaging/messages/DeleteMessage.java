package peergos.shared.messaging.messages;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.messaging.*;

import java.util.*;
import java.util.concurrent.*;

/** A message to delete a prior message of ours.
 *
 */
@JsType
public class DeleteMessage implements Message {

    public final MessageRef target;

    public DeleteMessage(MessageRef target) {
        this.target = target;
    }

    @Override
    public Type type() {
        return Type.Delete;
    }

    @Override
    public CborObject.CborMap toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", new CborObject.CborLong(type().value));
        state.put("r", target);
        return CborObject.CborMap.build(state);
    }

    public static DeleteMessage fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        MessageRef target = m.get("r", MessageRef::fromCbor);
        return new DeleteMessage(target);
    }

    public static CompletableFuture<DeleteMessage> build(MessageEnvelope target, Hasher hasher) {
        return hasher.bareHash(target.serialize())
                .thenApply(h -> new DeleteMessage(new MessageRef(h)));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeleteMessage that = (DeleteMessage) o;
        return Objects.equals(target, that.target);
    }

    @Override
    public int hashCode() {
        return Objects.hash(target);
    }
}
