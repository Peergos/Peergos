package peergos.shared.messaging.messages;

import peergos.shared.cbor.*;
import peergos.shared.display.*;

import java.util.*;

public class ApplicationMessage implements Message {
    public final MsgContent body;

    public ApplicationMessage(MsgContent body) {
        this.body = body;
    }

    @Override
    public Type type() {
        return Type.Application;
    }

    @Override
    public CborObject.CborMap toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", new CborObject.CborLong(type().value));
        state.put("b", body);
        return CborObject.CborMap.build(state);
    }

    public static ApplicationMessage fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        MsgContent body = m.get("b", MsgContent::fromCbor);
        return new ApplicationMessage(body);
    }

    public static ApplicationMessage text(String text) {
        return new ApplicationMessage(new Text(text));
    }
}
