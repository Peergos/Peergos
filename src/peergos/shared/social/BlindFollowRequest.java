package peergos.shared.social;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;

import java.util.*;

public class BlindFollowRequest implements Cborable {

    public final PublicBoxingKey dummySource;
    public final PaddedAsymmetricCipherText cipher;

    public BlindFollowRequest(PublicBoxingKey dummySource, PaddedAsymmetricCipherText cipher) {
        this.dummySource = dummySource;
        this.cipher = cipher;
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> result = new TreeMap<>();
        result.put("b", dummySource.toCbor());
        result.put("k", cipher.toCbor());
        return CborObject.CborMap.build(result);
    }

    public static BlindFollowRequest fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for BlindFollowRequest: "+  cbor);
        PublicBoxingKey dummysource = PublicBoxingKey.fromCbor(((CborObject.CborMap) cbor).get("b"));
        PaddedAsymmetricCipherText cipher = PaddedAsymmetricCipherText.fromCbor(((CborObject.CborMap) cbor).get("k"));
        return new BlindFollowRequest(dummysource, cipher);
    }
}
