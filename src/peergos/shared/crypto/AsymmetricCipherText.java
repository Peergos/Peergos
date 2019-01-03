package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.symmetric.*;

import java.util.*;
import java.util.function.*;

public class AsymmetricCipherText implements Cborable {

    private final byte[] cipherText;

    public AsymmetricCipherText(byte[] cipherText) {
        this.cipherText = cipherText;
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborByteArray(cipherText);
    }

    public static AsymmetricCipherText fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborByteArray))
            throw new IllegalStateException("Invalid cbor for asymmetric cipher text: " + cbor);

        return new AsymmetricCipherText(((CborObject.CborByteArray) cbor).value);
    }

    public static <T extends Cborable> AsymmetricCipherText build(SecretBoxingKey from, PublicBoxingKey to, T secret) {
        byte[] cipherText = to.encryptMessageFor(secret.serialize(), from);
        return new AsymmetricCipherText(cipherText);
    }

    public <T> T decrypt(SecretBoxingKey to, PublicBoxingKey from, Function<Cborable, T> fromCbor) {
        byte[] secret = to.decryptMessage(cipherText, from);
        return fromCbor.apply(CborObject.fromByteArray(secret));
    }
}
