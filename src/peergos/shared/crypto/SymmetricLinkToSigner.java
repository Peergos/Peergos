package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.mldsa.*;
import peergos.shared.crypto.hash.*;
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

    private static final byte[] UPGRADE_DOMAIN = "peergos/ml-dsa-44-upgrade".getBytes();

    public SigningPrivateKeyAndPublicHash target(SymmetricKey from) {
        return withUpgrade(cipherText.decrypt(from, SigningPrivateKeyAndPublicHash::fromCbor), from);
    }

    /** An Ed25519 writer upgrades to a composite key that keeps its Ed25519 half and takes its ML-DSA seed from the
     *  key protecting this link. Everyone who can sign as the writer has that key and a CRQC can't recover it, so
     *  they all derive the same upgrade and nothing in the cryptree has to change to hold it.
     */
    private static SigningPrivateKeyAndPublicHash withUpgrade(SigningPrivateKeyAndPublicHash signer, SymmetricKey from) {
        if (! signer.publicKeyHash.isIdentity() || signer.secret.type() != PublicSigningKey.Type.Ed25519)
            return signer;
        Mldsa mldsa = PublicSigningKey.MLDSA_PROVIDERS.get(PublicSigningKey.Type.HybridEd25519MLDSA);
        if (mldsa == null)
            return signer;
        Sha256 sha = new Sha256();
        sha.update(UPGRADE_DOMAIN);
        sha.update(from.getKey());
        sha.update(signer.publicKeyHash.toBytes());
        return new SigningPrivateKeyAndPublicHash(signer.publicKeyHash,
                new HybridEd25519MLDSASecretKey(signer.secret, sha.digest(), mldsa));
    }

    public static SymmetricLinkToSigner fromCbor(Cborable cbor) {
        return new SymmetricLinkToSigner(CipherText.fromCbor(cbor));
    }

    public static SymmetricLinkToSigner fromPair(SymmetricKey from, SigningPrivateKeyAndPublicHash to) {
        return new SymmetricLinkToSigner(CipherText.build(from, to));
    }
}
