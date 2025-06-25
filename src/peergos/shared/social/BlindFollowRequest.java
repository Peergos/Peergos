package peergos.shared.social;

import peergos.shared.Crypto;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.asymmetric.mlkem.HybridCurve25519MLKEMPublicKey;
import peergos.shared.crypto.random.*;

import java.util.*;

public class BlindFollowRequest implements Cborable {

    public final PublicBoxingKey dummySource;
    public final PaddedAsymmetricCipherText followRequest;

    public BlindFollowRequest(PublicBoxingKey dummySource, PaddedAsymmetricCipherText followRequest) {
        this.dummySource = dummySource;
        this.followRequest = followRequest;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("k", dummySource.toCbor());
        result.put("f", followRequest.toCbor());
        return CborObject.CborMap.build(result);
    }

    public static BlindFollowRequest fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for BlindFollowRequest: "+  cbor);
        PublicBoxingKey dummysource = PublicBoxingKey.fromCbor(((CborObject.CborMap) cbor).get("k"));
        PaddedAsymmetricCipherText followRequest = PaddedAsymmetricCipherText.fromCbor(((CborObject.CborMap) cbor).get("f"));
        return new BlindFollowRequest(dummysource, followRequest);
    }

    public static BlindFollowRequest build(PublicBoxingKey targetBoxer, FollowRequest request, Crypto crypto) {
        // create a tmp keypair whose public key we can prepend to the request without leaking information
        BoxingKeyPair tmp = targetBoxer instanceof HybridCurve25519MLKEMPublicKey ?
                BoxingKeyPair.randomHybrid(crypto) :
                BoxingKeyPair.randomCurve25519(crypto.random, crypto.boxer);

        return new BlindFollowRequest(tmp.publicBoxingKey,
                PaddedAsymmetricCipherText.build(tmp.secretBoxingKey, targetBoxer, request, 512));
    }
}
