package peergos.shared.crypto.asymmetric;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.curve25519.Curve25519SecretKey;
import peergos.shared.crypto.asymmetric.mlkem.HybridCurve25519MLKEMSecretKey;

import java.io.*;
import java.util.concurrent.CompletableFuture;

public interface SecretBoxingKey extends Cborable {

    PublicBoxingKey.Type type();

    @JsMethod
    byte[] getSecretBoxingKey();

    @JsMethod
    CompletableFuture<byte[]> decryptMessage(byte[] cipher, PublicBoxingKey from);

    static SecretBoxingKey fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for SecretBoxingKey! " + cbor);
        CborObject.CborLong type = (CborObject.CborLong) ((CborObject.CborList) cbor).value.get(0);
        PublicBoxingKey.Type t = PublicBoxingKey.Type.byValue((int) type.value);
        switch (t) {
            case Curve25519:
                return Curve25519SecretKey.fromCbor(cbor, PublicBoxingKey.PROVIDERS.get(PublicBoxingKey.Type.Curve25519));
            case HybridCurve25519MLKEM:
                return HybridCurve25519MLKEMSecretKey.fromCbor(cbor, PublicBoxingKey.MLKEM_PROVIDERS.get(PublicBoxingKey.Type.HybridCurve25519MLKEM));
            default: throw new IllegalStateException("Unknown Secret Boxing Key type: "+t.name());
        }
    }
}
