package peergos.shared.messaging.messages;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.display.*;

import java.util.*;
@JsType
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApplicationMessage that = (ApplicationMessage) o;
        return Objects.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(body);
    }

    public static ApplicationMessage text(String text) {
        return new ApplicationMessage(new Text(text));
    }
}
