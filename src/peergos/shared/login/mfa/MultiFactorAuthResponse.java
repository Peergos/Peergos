package peergos.shared.login.mfa;

import peergos.shared.cbor.*;

import java.util.*;

public class MultiFactorAuthResponse implements Cborable {

    public final byte[] credentialId;
    public final Cborable responseCbor;

    public MultiFactorAuthResponse(byte[] credentialId, Cborable responseCbor) {
        this.credentialId = credentialId;
        this.responseCbor = responseCbor;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("i", new CborObject.CborByteArray(credentialId));
        state.put("r", responseCbor);
        return CborObject.CborMap.build(state);
    }

    public static MultiFactorAuthResponse fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for MultiFactorAuthResponse! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new MultiFactorAuthResponse(m.getByteArray("i"), m.get("r"));
    }
}
