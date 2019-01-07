package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.symmetric.*;

import java.util.*;
import java.util.function.*;

/** This class pads the secret up to a multiple of the given block size before encrypting.
 *
 * This hides the exact size of the secret.
 */
public class PaddedCipherText implements Cborable {

    private final CipherText cipherText;

    public PaddedCipherText(CipherText cipherText) {
        this.cipherText = cipherText;
    }

    @Override
    public CborObject toCbor() {
        return cipherText.toCbor();
    }

    public static PaddedCipherText fromCbor(Cborable cbor) {
        return new PaddedCipherText(CipherText.fromCbor(cbor));
    }

    protected static byte[] pad(byte[] input, int blockSize) {
        int nBlocks = (input.length + blockSize - 1) / blockSize;
        return Arrays.copyOfRange(input, 0, nBlocks * blockSize);
    }

    public static <T extends Cborable> PaddedCipherText build(SymmetricKey from, T secret, int paddingBlockSize) {
        if (paddingBlockSize < 1)
            throw new IllegalStateException("Invalid padding block size: " + paddingBlockSize);
        byte[] nonce = from.createNonce();
        byte[] cipherText = from.encrypt(pad(secret.serialize(), paddingBlockSize), nonce);
        return new PaddedCipherText(new CipherText(nonce, cipherText));
    }

    public <T> T decrypt(SymmetricKey from, Function<CborObject, T> fromCbor) {
        return cipherText.decrypt(from, fromCbor);
    }
}
