package peergos.shared.crypto.asymmetric;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.curve25519.Curve25519SecretKey;

import java.io.*;

public interface SecretBoxingKey extends Cborable {

    PublicBoxingKey.Type type();

    @JsMethod
    byte[] getSecretBoxingKey();

    @JsMethod
    byte[] decryptMessage(byte[] cipher, PublicBoxingKey from);

    static SecretBoxingKey fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for PublicBoxingKey! " + cbor);
        CborObject.CborLong type = (CborObject.CborLong) ((CborObject.CborList) cbor).value.get(0);
        PublicBoxingKey.Type t = PublicBoxingKey.Type.byValue((int) type.value);
        switch (t) {
            case Curve25519:
                return Curve25519SecretKey.fromCbor(cbor, PublicBoxingKey.PROVIDERS.get(PublicBoxingKey.Type.Curve25519));
            default: throw new IllegalStateException("Unknown Secret Boxing Key type: "+t.name());
        }
    }
}
