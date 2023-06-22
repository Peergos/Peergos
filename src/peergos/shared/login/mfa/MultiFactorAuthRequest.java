package peergos.shared.login.mfa;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;

import java.util.*;

@JsType
public class MultiFactorAuthRequest implements Cborable {

    public final List<MultiFactorAuthMethod> methods;
    public final byte[] challenge;

    public MultiFactorAuthRequest(List<MultiFactorAuthMethod> methods, byte[] challenge) {
        this.methods = methods;
        this.challenge = challenge;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("m", new CborObject.CborList(methods));
        state.put("c", new CborObject.CborByteArray(challenge));
        return CborObject.CborMap.build(state);
    }

    public static MultiFactorAuthRequest fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for MultiFactorAuthRequest! ");

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new MultiFactorAuthRequest(m.getList("m", MultiFactorAuthMethod::fromCbor), m.getByteArray("c"));
    }
}
