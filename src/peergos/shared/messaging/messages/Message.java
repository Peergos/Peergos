package peergos.shared.messaging.messages;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;

import java.util.*;
@JsType
public interface Message extends Cborable {

    Type type();

    static Message fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;

        Type category = map.get("c", c -> Type.byValue((int) ((CborObject.CborLong) c).value));
        switch (category) {
            case Join: return Join.fromCbor(cbor);
            case Invite: return Invite.fromCbor(cbor);
            case Application: return ApplicationMessage.fromCbor(cbor);
            case GroupState: return SetGroupState.fromCbor(cbor);
            case ReplyTo: return ReplyTo.fromCbor(cbor);
            case Edit: return EditMessage.fromCbor(cbor);
            case Delete: return DeleteMessage.fromCbor(cbor);
            case RemoveMember: return RemoveMember.fromCbor(cbor);
            default: throw new IllegalStateException("Invalid message type!");
        }
    }

    Map<Integer, Type> byValue = new HashMap<>();
    @JsType
    enum Type {
        Join(0),
        Invite(1),
        Application(2),
        GroupState(3),
        ReplyTo(4),
        Delete(5),
        Edit(6),
        RemoveMember(7);

        public final int value;

        Type(int value) {
            this.value = value;
            byValue.put(value, this);
        }

        public static Type byValue(int val) {
            if (!byValue.containsKey(val))
                throw new IllegalStateException("Unknown message type: " + val);
            return byValue.get(val);
        }
    }
}
