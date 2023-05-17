package peergos.shared.login.mfa;

import peergos.shared.cbor.*;

import java.util.*;

public class MultiFactorAuthResponse implements Cborable {

    public final String uid;
    public final String code;

    public MultiFactorAuthResponse(String uid, String code) {
        this.uid = uid;
        this.code = code;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("u", new CborObject.CborString(uid));
        state.put("c", new CborObject.CborString(code));
        return CborObject.CborMap.build(state);
    }

    public static MultiFactorAuthResponse fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for MultiFactorAuthResponse! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new MultiFactorAuthResponse(m.getString("u"), m.getString("c"));
    }
}
