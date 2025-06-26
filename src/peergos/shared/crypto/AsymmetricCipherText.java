package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.symmetric.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
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

    public static <T extends Cborable> CompletableFuture<AsymmetricCipherText> build(SecretBoxingKey from, PublicBoxingKey to, T secret) {
        return to.encryptMessageFor(secret.serialize(), from)
                .thenApply(cipherText -> new AsymmetricCipherText(cipherText));
    }

    public <T> CompletableFuture<T> decrypt(SecretBoxingKey to, PublicBoxingKey from, Function<Cborable, T> fromCbor) {
        return to.decryptMessage(cipherText, from)
                .thenApply(secret -> fromCbor.apply(CborObject.fromByteArray(secret)));
    }
}
