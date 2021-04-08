package peergos.shared.messaging;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;

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

        public Join(String username, PublicKeyHash identity, OwnerProof chatIdentity) {
            this.username = username;
            this.identity = identity;
            this.chatIdentity = chatIdentity;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("u", new CborObject.CborString(username));
            state.put("i", identity);
            state.put("c", chatIdentity);
            return CborObject.CborMap.build(state);
        }

        public static Join fromCbor(Cborable cbor) {
            if (!(cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            String username = m.getString("u");
            PublicKeyHash identity = m.get("i", PublicKeyHash::fromCbor);
            OwnerProof chatIdentity = m.get("c", OwnerProof::fromCbor);
            return new Join(username, identity, chatIdentity);
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
