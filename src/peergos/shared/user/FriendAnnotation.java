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

    public boolean isVerifiedFor(PublicKeyHash currentIdentity) {
        return isVerified && ! keysAtVerification.isEmpty() && keysAtVerification.get(0).equals(currentIdentity);
    }

    /** Verified against all the keys the friend has now, not just the identity key.
     *  A key substitution that only replaces the boxing key is caught by this check.
     */
    public boolean isVerifiedFor(List<PublicKeyHash> currentKeys) {
        return isVerified && keysAtVerification.equals(currentKeys);
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
