package peergos.shared.messaging.messages;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.messaging.*;

import java.util.*;
import java.util.concurrent.*;

/** A message to reply to another message.
 *
 */
@JsType
public class ReplyTo implements Message {

    public final MessageRef parent;
    public final ApplicationMessage content;

    public ReplyTo(MessageRef parent, ApplicationMessage content) {
        this.parent = parent;
        this.content = content;
    }

    @Override
    public Type type() {
        return Type.ReplyTo;
    }

    @Override
    public CborObject.CborMap toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", new CborObject.CborLong(type().value));
        state.put("r", parent);
        state.put("b", content);
        return CborObject.CborMap.build(state);
    }

    public static ReplyTo fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        MessageRef parent = m.get("r", MessageRef::fromCbor);
        ApplicationMessage content = m.get("b", ApplicationMessage::fromCbor);
        return new ReplyTo(parent, content);
    }

    public static CompletableFuture<ReplyTo> build(MessageEnvelope parent, ApplicationMessage content, Hasher hasher) {
        return hasher.bareHash(parent.serialize())
                .thenApply(h -> new ReplyTo(new MessageRef(h), content));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReplyTo replyTo = (ReplyTo) o;
        return Objects.equals(parent, replyTo.parent) &&
                Objects.equals(content, replyTo.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parent, content);
    }
}
