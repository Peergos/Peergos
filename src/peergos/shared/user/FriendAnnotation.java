package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;

import java.util.*;

public class FriendAnnotation implements Cborable {

    private final String username;
    private final boolean isVerified;
    private final List<PublicKeyHash> keysAtVerification;

    @JsConstructor
    public FriendAnnotation(String username, boolean isVerified, List<PublicKeyHash> keysAtVerification) {
        this.username = username;
        this.isVerified = isVerified;
        this.keysAtVerification = keysAtVerification;
    }

    public String getUsername() {
        return username;
    }

    @JsMethod
    public boolean isVerified() {
        return isVerified;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("u", new CborObject.CborString(username));
        state.put("v", new CborObject.CborBoolean(isVerified));
        state.put("k", new CborObject.CborList(keysAtVerification));
        return CborObject.CborMap.build(state);
    }

    public static FriendAnnotation fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for FriendAnnotation: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        String username = m.getString("u");
        boolean isVerified = m.getBoolean("v");
        List<PublicKeyHash> keys = m.getList("k", PublicKeyHash::fromCbor);
        return new FriendAnnotation(username, isVerified, keys);
    }
}
