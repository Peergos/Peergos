package peergos.shared.messaging;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.messaging.messages.*;

import java.util.*;

@JsType
public class MessageEnvelope implements Cborable {

    public final Id author;
    public final TreeClock timestamp;
    public final Message payload;

    public MessageEnvelope(Id author, TreeClock timestamp, Message payload) {
        this.author = author;
        this.timestamp = timestamp;
        this.payload = payload;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("a", author);
        result.put("t", timestamp);
        result.put("p", payload);
        return CborObject.CborMap.build(result);
    }

    public static MessageEnvelope fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        Id author = map.get("a", Id::fromCbor);
        TreeClock timestamp = map.get("t", TreeClock::fromCbor);
        Message payload = map.get("p", Message::fromCbor);
        return new MessageEnvelope(author, timestamp, payload);
    }

    @Override
    public String toString() {
        return author + "(" + timestamp + ") - " + payload;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MessageEnvelope message = (MessageEnvelope) o;
        return Objects.equals(timestamp, message.timestamp) && payload.equals(message.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(timestamp);
        result = 31 * result + payload.hashCode();
        return result;
    }
}
