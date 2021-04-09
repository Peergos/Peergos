package peergos.shared.messaging;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;

import java.util.*;

public class PrivateChatState implements Cborable {

    public final SigningPrivateKeyAndPublicHash chatIdentity;
    public final PublicSigningKey chatIdPublic;

    public PrivateChatState(SigningPrivateKeyAndPublicHash chatIdentity, PublicSigningKey chatIdPublic) {
        this.chatIdentity = chatIdentity;
        this.chatIdPublic = chatIdPublic;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("ci", chatIdentity);
        result.put("p", chatIdPublic);
        return CborObject.CborMap.build(result);
    }

    public static PrivateChatState fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        SigningPrivateKeyAndPublicHash chatIdentity = m.get("ci", SigningPrivateKeyAndPublicHash::fromCbor);
        PublicSigningKey chatIdPublic = m.get("p", PublicSigningKey::fromCbor);
        return new PrivateChatState(chatIdentity, chatIdPublic);
    }
}
