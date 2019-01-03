package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;

import java.util.*;

@JsType
public class FollowRequest implements Cborable {

    public final Optional<EntryPoint> entry;
    public final Optional<SymmetricKey> key;

    public FollowRequest(Optional<EntryPoint> entry, Optional<SymmetricKey> key) {
        this.entry = entry;
        this.key = key;
    }

    public boolean isAccepted() {
        return entry.isPresent();
    }

    public boolean isReciprocated() {
        return key.isPresent();
    }

    public CborObject toCbor() {
        Map<String, CborObject> result = new TreeMap<>();
        entry.ifPresent(e -> result.put("e", e.toCbor()));
        key.ifPresent(k -> result.put("k", k.toCbor()));
        return CborObject.CborMap.build(result);
    }

    public static FollowRequest fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FollowRequest: " + cbor);
        Optional<EntryPoint> entryPoint = Optional.ofNullable(((CborObject.CborMap) cbor).get("e"))
                .map(EntryPoint::fromCbor);
        Optional<SymmetricKey> key = Optional.ofNullable(((CborObject.CborMap) cbor).get("k"))
                .map(SymmetricKey::fromCbor);
        return new FollowRequest(entryPoint, key);
    }
}
