package peergos.shared.login.mfa;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.SortedMap;
import java.util.TreeMap;

@JsType
public class WebauthnResponse implements Cborable {
    public final byte[] authenticatorData, clientDataJson, signature;

    public WebauthnResponse(byte[] authenticatorData, byte[] clientDataJson, byte[] signature) {
        this.authenticatorData = authenticatorData;
        this.clientDataJson = clientDataJson;
        this.signature = signature;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("a", new CborObject.CborByteArray(authenticatorData));
        state.put("c", new CborObject.CborByteArray(clientDataJson));
        state.put("s", new CborObject.CborByteArray(signature));

        return CborObject.CborMap.build(state);
    }

    public static WebauthnResponse fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for WebauthnResponse! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new WebauthnResponse(m.getByteArray("a"), m.getByteArray("c"), m.getByteArray("s"));
    }
}
