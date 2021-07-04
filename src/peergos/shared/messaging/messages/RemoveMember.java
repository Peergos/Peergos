package peergos.shared.messaging.messages;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.messaging.*;

import java.util.*;
import java.util.concurrent.*;

/** A message to remove a member from a chat. Anyone can remove themselves, or an admin can remove anyone.
 *
 */
@JsType
public class RemoveMember implements Message {

    public final String chatUid;
    public final Id memberToRemove;

    public RemoveMember(String chatUid, Id memberToRemove) {
        this.chatUid = chatUid;
        this.memberToRemove = memberToRemove;
    }

    @Override
    public Type type() {
        return Type.RemoveMember;
    }

    @Override
    public CborObject.CborMap toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", new CborObject.CborLong(type().value));
        state.put("u", new CborObject.CborString(chatUid));
        state.put("m", memberToRemove);
        return CborObject.CborMap.build(state);
    }

    public static RemoveMember fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        String chatUid = m.getString("u");
        Id member = m.get("m", Id::fromCbor);
        return new RemoveMember(chatUid, member);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RemoveMember that = (RemoveMember) o;
        return Objects.equals(chatUid, that.chatUid) && Objects.equals(memberToRemove, that.memberToRemove);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chatUid, memberToRemove);
    }
}
