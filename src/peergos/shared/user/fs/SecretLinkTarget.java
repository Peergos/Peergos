package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class SecretLinkTarget implements Cborable {

    public final EncryptedCapability cap;
    public final Optional<LocalDateTime> expiry;
    public final Optional<Integer> maxRetrievals;

    public SecretLinkTarget(EncryptedCapability cap, Optional<LocalDateTime> expiry, Optional<Integer> maxRetrievals) {
        this.cap = cap;
        this.expiry = expiry;
        this.maxRetrievals = maxRetrievals;
    }

    public CompletableFuture<AbsoluteCapability> decrypt(String label, String password, Crypto c) {
        return cap.decryptFromPassword(label, password, c);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("cap", cap.toCbor());
        expiry.ifPresent(e -> state.put("expiry", new CborObject.CborLong(e.toEpochSecond(ZoneOffset.UTC))));
        maxRetrievals.ifPresent(m -> state.put("max", new CborObject.CborLong(m)));
        return CborObject.CborMap.build(state);
    }

    public static SecretLinkTarget fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for SecretLinkTarget! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new SecretLinkTarget(m.get("cap", EncryptedCapability::fromCbor),
                m.getOptionalLong("expiry").map(l -> LocalDateTime.ofEpochSecond(l, 0, ZoneOffset.UTC)),
                m.getOptionalLong("max").map(Long::intValue));
    }
}
