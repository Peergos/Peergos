package peergos.shared.messaging;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;

import java.util.*;

public class Member {
    public final String username;
    public final Id id;
    public final PublicKeyHash identity;
    public Optional<OwnerProof> chatIdentity;
    public long messagesMergedUpto;
    public int membersInvited;

    public Member(String username, Id id, PublicKeyHash identity, Optional<OwnerProof> chatIdentity, long messagesMergedUpto, int membersInvited) {
        this.username = username;
        this.id = id;
        this.identity = identity;
        this.chatIdentity = chatIdentity;
        this.messagesMergedUpto = messagesMergedUpto;
        this.membersInvited = membersInvited;
    }

    public Member(String username, Id id, PublicKeyHash identity, long messagesMergedUpto, int membersInvited) {
        this(username, id, identity, Optional.empty(), messagesMergedUpto, membersInvited);
    }

    public Member withChatId(OwnerProof proof) {
        return new Member(username, id, identity, Optional.of(proof), messagesMergedUpto, membersInvited);
    }

    public Member copy() {
        return new Member(username, id, identity, chatIdentity, messagesMergedUpto, membersInvited);
    }
}
