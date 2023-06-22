package peergos.shared.login.mfa;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.util.Either;

import java.util.*;

@JsType
public class MultiFactorAuthResponse implements Cborable {
    public final byte[] credentialId;
    public final Either<String, WebauthnResponse> response;

    public MultiFactorAuthResponse(byte[] credentialId, Either<String, WebauthnResponse> response) {
        this.credentialId = credentialId;
        this.response = response;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("i", new CborObject.CborByteArray(credentialId));
        state.put("r", response.map(code -> new CborObject.CborString(code), x -> x));
        return CborObject.CborMap.build(state);
    }

    public static MultiFactorAuthResponse fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for MultiFactorAuthResponse! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        Cborable response = m.get("r");
        return new MultiFactorAuthResponse(m.getByteArray("i"),
                response instanceof CborObject.CborString ?
                        Either.a(((CborObject.CborString) response).value) :
                        Either.b(WebauthnResponse.fromCbor(response)));
    }
}
