package peergos.shared.messaging;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class Message implements Cborable {
    public final Id author;
    public final TreeClock timestamp;
    public final byte[] payload;

    public Message(Id author, TreeClock timestamp, byte[] payload) {
        this.author = author;
        this.timestamp = timestamp;
        this.payload = payload;
    }

    public static class Invite implements Cborable {
        public final String username;
        public final PublicKeyHash identity;

        public Invite(String username, PublicKeyHash identity) {
            this.username = username;
            this.identity = identity;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("u", new CborObject.CborString(username));
            state.put("i", identity);
            return CborObject.CborMap.build(state);
        }

        public static Invite fromCbor(Cborable cbor) {
            if (!(cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            String username = m.getString("u");
            PublicKeyHash identity = m.get("i", PublicKeyHash::fromCbor);
            return new Invite(username, identity);
        }
    }

    public static class Join implements Cborable {
        public final String username;
        public final PublicKeyHash identity;
        public final OwnerProof chatIdentity;
        public final PublicSigningKey chatIdPublic;

        public Join(String username, PublicKeyHash identity, OwnerProof chatIdentity, PublicSigningKey chatIdPublic) {
            this.username = username;
            this.identity = identity;
            this.chatIdentity = chatIdentity;
            this.chatIdPublic = chatIdPublic;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("u", new CborObject.CborString(username));
            state.put("i", identity);
            state.put("c", chatIdentity);
            state.put("p", chatIdPublic);
            return CborObject.CborMap.build(state);
        }

        public static Join fromCbor(Cborable cbor) {
            if (!(cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            String username = m.getString("u");
            PublicKeyHash identity = m.get("i", PublicKeyHash::fromCbor);
            OwnerProof chatIdentity = m.get("c", OwnerProof::fromCbor);
            PublicSigningKey chatIdPublic = m.get("p", PublicSigningKey::fromCbor);
            return new Join(username, identity, chatIdentity, chatIdPublic);
        }
    }

    @JsType
    public interface ChatMessage {
        default String type() { return this.getClass().getName();}
    }

    @JsType
    public static class ConversationTitleMessage implements Cborable, ChatMessage {
        public final String username;
        public final String title;
        public final LocalDateTime postTime;

        public ConversationTitleMessage(String username, String title, LocalDateTime postTime) {
            this.postTime = postTime;
            this.username = username;
            this.title = title;
        }

        @Override
        public byte[] serialize() {
            return Cborable.super.serialize();
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("a", new CborObject.CborString(username));
            state.put("d", new CborObject.CborString(title));
            state.put("t", new CborObject.CborLong(postTime.toEpochSecond(ZoneOffset.UTC)));
            return CborObject.CborMap.build(state);
        }

        public static Message.ConversationTitleMessage fromCbor(Cborable cbor) {
            if (!(cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            String username = m.getString("a");
            String title = m.getString("d");
            LocalDateTime postTime = m.get("t", c -> LocalDateTime.ofEpochSecond(((CborObject.CborLong) c).value, 0, ZoneOffset.UTC));
            return new Message.ConversationTitleMessage(username, title, postTime);
        }
    }

    @JsType
    public static class StatusMessage implements Cborable, ChatMessage {
        public final String status;
        public final LocalDateTime postTime;

        public StatusMessage(String status, LocalDateTime postTime) {
            this.postTime = postTime;
            this.status = status;
        }

        @Override
        public byte[] serialize() {
            return Cborable.super.serialize();
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("s", new CborObject.CborString(status));
            state.put("t", new CborObject.CborLong(postTime.toEpochSecond(ZoneOffset.UTC)));
            return CborObject.CborMap.build(state);
        }

        public static Message.StatusMessage fromCbor(Cborable cbor) {
            if (!(cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            String status = m.getString("s");
            LocalDateTime postTime = m.get("t", c -> LocalDateTime.ofEpochSecond(((CborObject.CborLong) c).value, 0, ZoneOffset.UTC));
            return new Message.StatusMessage(status, postTime);
        }
    }

    @JsType
    public static class TextMessage implements Cborable, ChatMessage {
        public final String username;
        public final String message;
        public final LocalDateTime postTime;

        public TextMessage(String username, String message, LocalDateTime postTime) {
            this.postTime = postTime;
            this.username = username;
            this.message = message;
        }

        @Override
        public byte[] serialize() {
            return Cborable.super.serialize();
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("u", new CborObject.CborString(username));
            state.put("m", new CborObject.CborString(message));
            state.put("t", new CborObject.CborLong(postTime.toEpochSecond(ZoneOffset.UTC)));
            return CborObject.CborMap.build(state);
        }

        public static TextMessage fromCbor(Cborable cbor) {
            if (!(cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            String username = m.getString("u");
            String message = m.getString("m");
            LocalDateTime postTime = m.get("t", c -> LocalDateTime.ofEpochSecond(((CborObject.CborLong)c).value, 0, ZoneOffset.UTC));
            return new TextMessage(username, message, postTime);
        }
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("a", author);
        result.put("t", timestamp);
        result.put("p", new CborObject.CborByteArray(payload));
        return CborObject.CborMap.build(result);
    }

    public static Message fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        Id author = map.get("a", Id::fromCbor);
        TreeClock timestamp = map.get("t", TreeClock::fromCbor);
        byte[] payload = map.get("p", c -> ((CborObject.CborByteArray) c).value);
        return new Message(author, timestamp, payload);
    }

    @Override
    public String toString() {
        return author + "(" + timestamp + ") - " + new String(payload);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Message message = (Message) o;
        return Objects.equals(timestamp, message.timestamp) && Arrays.equals(payload, message.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(timestamp);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}
