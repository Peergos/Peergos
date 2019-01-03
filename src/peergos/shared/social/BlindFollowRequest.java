package peergos.shared.social;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.random.*;

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

    public static BlindFollowRequest build(PublicBoxingKey targetBoxer, FollowRequest request, SafeRandom random, Curve25519 boxer) {
        // create a tmp keypair whose public key we can prepend to the request without leaking information
        BoxingKeyPair tmp = BoxingKeyPair.random(random, boxer);

        return new BlindFollowRequest(tmp.publicBoxingKey,
                PaddedAsymmetricCipherText.build(tmp.secretBoxingKey, targetBoxer, request, 512));
    }
}
