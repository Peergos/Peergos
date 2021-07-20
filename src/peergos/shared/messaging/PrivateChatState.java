package peergos.shared.messaging;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;

import java.util.*;
import java.util.stream.*;

public class PrivateChatState implements Cborable {

    public final SigningPrivateKeyAndPublicHash chatIdentity;
    public final PublicSigningKey chatIdPublic;
    public final Set<String> deletedMembers;

    public PrivateChatState(SigningPrivateKeyAndPublicHash chatIdentity,
                            PublicSigningKey chatIdPublic,
                            Set<String> deletedMembers) {
        this.chatIdentity = chatIdentity;
        this.chatIdPublic = chatIdPublic;
        this.deletedMembers = deletedMembers;
    }

    public PrivateChatState addDeleted(String username) {
        HashSet<String> newDeleted = new HashSet<>(deletedMembers);
        newDeleted.add(username);
        return new PrivateChatState(chatIdentity, chatIdPublic, newDeleted);
    }

    public PrivateChatState apply(PrivateChatState newer) {
        HashSet<String> newDeleted = new HashSet<>(deletedMembers);
        newDeleted.addAll(newer.deletedMembers);
        return new PrivateChatState(newer.chatIdentity, newer.chatIdPublic, newDeleted);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("ci", chatIdentity);
        result.put("p", chatIdPublic);
        List<CborObject.CborString> deleted = deletedMembers.stream()
                .sorted()
                .map(CborObject.CborString::new)
                .collect(Collectors.toList());
        result.put("d", new CborObject.CborList(deleted));
        return CborObject.CborMap.build(result);
    }

    public static PrivateChatState fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        SigningPrivateKeyAndPublicHash chatIdentity = m.get("ci", SigningPrivateKeyAndPublicHash::fromCbor);
        PublicSigningKey chatIdPublic = m.get("p", PublicSigningKey::fromCbor);
        List<String> deleted = m.getList("d", CborObject.CborString::getString);
        return new PrivateChatState(chatIdentity, chatIdPublic, new HashSet<>(deleted));
    }
}
