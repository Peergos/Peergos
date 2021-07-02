package peergos.shared.messaging;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;

import java.util.*;

public class Member implements Cborable {
    public final String username;
    public final Id id;
    public final PublicKeyHash identity;
    public Optional<OwnerProof> chatIdentity;
    public long messagesMergedUpto;
    public int membersInvited;
    public boolean removed;

    public Member(String username,
                  Id id,
                  PublicKeyHash identity,
                  Optional<OwnerProof> chatIdentity,
                  long messagesMergedUpto,
                  int membersInvited,
                  boolean removed) {
        this.username = username;
        this.id = id;
        this.identity = identity;
        this.chatIdentity = chatIdentity;
        this.messagesMergedUpto = messagesMergedUpto;
        this.membersInvited = membersInvited;
        this.removed = removed;
    }

    public Member(String username, Id id, PublicKeyHash identity, long messagesMergedUpto, int membersInvited) {
        this(username, id, identity, Optional.empty(), messagesMergedUpto, membersInvited, false);
    }

    public Member withChatId(OwnerProof proof) {
        return new Member(username, id, identity, Optional.of(proof), messagesMergedUpto, membersInvited, removed);
    }

    public Member copy() {
        return new Member(username, id, identity, chatIdentity, messagesMergedUpto, membersInvited, removed);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("u", new CborObject.CborString(username));
        result.put("i", id);
        result.put("p", identity);
        chatIdentity.ifPresent(c -> result.put("s", c));
        result.put("m", new CborObject.CborLong(messagesMergedUpto));
        result.put("c", new CborObject.CborLong(membersInvited));
        result.put("r", new CborObject.CborBoolean(removed));
        return CborObject.CborMap.build(result);
    }

    public static Member fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        String username = m.getString("u");
        Id id = m.get("i", Id::fromCbor);
        PublicKeyHash publicIdentity = m.get("p", PublicKeyHash::fromCbor);
        Optional<OwnerProof> chatIdentity = m.getOptional("s", OwnerProof::fromCbor);
        long messagesMergedUpTo = m.getLong("m");
        int membersInvited = (int) m.getLong("c");
        boolean removed = m.getBoolean("r");
        return new Member(username, id, publicIdentity, chatIdentity, messagesMergedUpTo, membersInvited, removed);
    }
}
