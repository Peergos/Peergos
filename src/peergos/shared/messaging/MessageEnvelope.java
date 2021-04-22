package peergos.shared.messaging;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.messaging.messages.*;

import java.time.*;
import java.util.*;

@JsType
public class MessageEnvelope implements Cborable {

    public final Id author;
    public final TreeClock timestamp;
    public final LocalDateTime creationTime; // Stored accurate to millisecond
    public final Message payload;

    public MessageEnvelope(Id author, TreeClock timestamp, LocalDateTime creationTime, Message payload) {
        this.author = author;
        this.timestamp = timestamp;
        this.creationTime = creationTime;
        this.payload = payload;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("a", author);
        result.put("t", timestamp);
        result.put("u", new CborObject.CborLong(1000*creationTime.toEpochSecond(ZoneOffset.UTC) + creationTime.getNano()/1_000_000));
        result.put("p", payload);
        return CborObject.CborMap.build(result);
    }

    public static MessageEnvelope fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        Id author = map.get("a", Id::fromCbor);
        TreeClock timestamp = map.get("t", TreeClock::fromCbor);
        LocalDateTime creationTime = map.get("u", c -> parseUtcMillis(((CborObject.CborLong)c).value));
        Message payload = map.get("p", Message::fromCbor);
        return new MessageEnvelope(author, timestamp, creationTime, payload);
    }

    private static LocalDateTime parseUtcMillis(long millis) {
        return LocalDateTime.ofEpochSecond(millis/1_000, ((int)(millis % 1000)) * 1_000_000, ZoneOffset.UTC);
    }

    @Override
    public String toString() {
        return author + "(" + timestamp + ") - " + payload;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MessageEnvelope that = (MessageEnvelope) o;
        return Objects.equals(author, that.author) &&
                Objects.equals(timestamp, that.timestamp) &&
                Objects.equals(creationTime, that.creationTime) &&
                Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(author, timestamp, creationTime, payload);
    }
}
