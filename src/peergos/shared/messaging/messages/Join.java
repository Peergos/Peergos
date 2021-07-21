package peergos.shared.messaging.messages;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.messaging.*;

import java.util.*;

public class Join implements Message {
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
    public Type type() {
        return Type.Join;
    }

    @Override
    public CborObject.CborMap toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", new CborObject.CborLong(type().value));
        state.put("u", new CborObject.CborString(username));
        state.put("i", identity);
        state.put("ci", chatIdentity);
        state.put("p", chatIdPublic);
        return CborObject.CborMap.build(state);
    }

    public static Join fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        String username = m.getString("u");
        PublicKeyHash identity = m.get("i", PublicKeyHash::fromCbor);
        OwnerProof chatIdentity = m.get("ci", OwnerProof::fromCbor);
        PublicSigningKey chatIdPublic = m.get("p", PublicSigningKey::fromCbor);
        return new Join(username, identity, chatIdentity, chatIdPublic);
    }

    @Override
    public String toString() {
        return "JOIN(" + username + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Join join = (Join) o;
        return Objects.equals(username, join.username) &&
                Objects.equals(identity, join.identity) &&
                Objects.equals(chatIdentity, join.chatIdentity) &&
                Objects.equals(chatIdPublic, join.chatIdPublic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, identity, chatIdentity, chatIdPublic);
    }
}
