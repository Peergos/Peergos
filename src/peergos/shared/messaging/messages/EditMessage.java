package peergos.shared.messaging.messages;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.messaging.*;

import java.util.*;
import java.util.concurrent.*;

/** A message to edit an earlier message.
 *
 */
@JsType
public class EditMessage implements Message {

    public final MessageRef priorVersion;
    public final ApplicationMessage content;

    public EditMessage(MessageRef priorVersion, ApplicationMessage content) {
        this.priorVersion = priorVersion;
        this.content = content;
    }

    @Override
    public Type type() {
        return Type.Edit;
    }

    @Override
    public CborObject.CborMap toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", new CborObject.CborLong(type().value));
        state.put("r", priorVersion);
        state.put("b", content);
        return CborObject.CborMap.build(state);
    }

    public static EditMessage fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        MessageRef parent = m.get("r", MessageRef::fromCbor);
        ApplicationMessage content = m.get("b", ApplicationMessage::fromCbor);
        return new EditMessage(parent, content);
    }

    public static CompletableFuture<EditMessage> build(MessageEnvelope prior, ApplicationMessage content, Hasher hasher) {
        return hasher.bareHash(prior.serialize())
                .thenApply(h -> new EditMessage(new MessageRef(h), content));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EditMessage replyTo = (EditMessage) o;
        return Objects.equals(priorVersion, replyTo.priorVersion) &&
                Objects.equals(content, replyTo.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(priorVersion, content);
    }
}
