package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;

import java.util.function.*;

public class PaddedAsymmetricCipherText implements Cborable {

    private final AsymmetricCipherText cipherText;

    public PaddedAsymmetricCipherText(AsymmetricCipherText cipherText) {
        this.cipherText = cipherText;
    }

    @Override
    public CborObject toCbor() {
        return cipherText.toCbor();
    }

    public static PaddedAsymmetricCipherText fromCbor(Cborable cbor) {
        return new PaddedAsymmetricCipherText(AsymmetricCipherText.fromCbor(cbor));
    }

    public static <T extends Cborable> PaddedAsymmetricCipherText build(SecretBoxingKey from, PublicBoxingKey to, T secret, int paddingBlockSize) {
        byte[] cipherText = to.encryptMessageFor(PaddedCipherText.pad(secret.serialize(), paddingBlockSize), from);
        return new PaddedAsymmetricCipherText(new AsymmetricCipherText(cipherText));
    }

    public <T> T decrypt(SecretBoxingKey to, PublicBoxingKey from, Function<Cborable, T> fromCbor) {
        return cipherText.decrypt(to, from, fromCbor);
    }
}
