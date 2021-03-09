package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.symmetric.*;

/** A symmetric link is a link from a symmetric key to a signing keypair, as defined in cryptree.
 *
 * This means the target keys are encrypted with the source key.
 *
 */
public class SymmetricLinkToSigner implements Cborable
{
    private final CipherText cipherText;

    public SymmetricLinkToSigner(CipherText cipherText) {
        this.cipherText = cipherText;
    }

    @Override
    public CborObject toCbor() {
        return cipherText.toCbor();
    }

    public SigningPrivateKeyAndPublicHash target(SymmetricKey from) {
        return cipherText.decrypt(from, SigningPrivateKeyAndPublicHash::fromCbor);
    }

    public static SymmetricLinkToSigner fromCbor(Cborable cbor) {
        return new SymmetricLinkToSigner(CipherText.fromCbor(cbor));
    }

    public static SymmetricLinkToSigner fromPair(SymmetricKey from, SigningPrivateKeyAndPublicHash to) {
        return new SymmetricLinkToSigner(CipherText.build(from, to));
    }
}
