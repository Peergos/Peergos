package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.symmetric.SymmetricKey;

/** A symmetric link is a link from one symmetric key to another, as defined in cryptree.
 *
 * This means the target key is encrypted with the source key.
 *
 */
public class SymmetricLink implements Cborable
{
    private final CipherText cipherText;

    public SymmetricLink(CipherText cipherText) {
        this.cipherText = cipherText;
    }

    @Override
    public CborObject toCbor() {
        return cipherText.toCbor();
    }

    public SymmetricKey target(SymmetricKey from) {
        return cipherText.decrypt(from, SymmetricKey::fromCbor);
    }

    public static SymmetricLink fromCbor(Cborable cbor) {
        return new SymmetricLink(CipherText.fromCbor(cbor));
    }

    public static SymmetricLink fromPair(SymmetricKey from, SymmetricKey to) {
        return new SymmetricLink(CipherText.build(from, to));
    }
}
