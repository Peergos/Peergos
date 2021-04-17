package peergos.shared.messaging.messages;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.messaging.*;

import java.util.*;

public class Invite implements Message {
    public final String username;
    public final PublicKeyHash identity;

    public Invite(String username, PublicKeyHash identity) {
        this.username = username;
        this.identity = identity;
    }

    @Override
    public Type type() {
        return Type.Invite;
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
