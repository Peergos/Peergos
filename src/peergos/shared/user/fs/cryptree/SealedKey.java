package peergos.shared.user.fs.cryptree;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.util.*;

import java.util.*;

/** A child's full key, sealed to its directory's public key by someone with only the directory's restricted key.
 *  They can give it to the directory's full holders, but can't read anyone else's.
 *
 *  An anonymous box, as in libsodium: an ephemeral key pair, and a nonce derived from both public keys.
 */
public class SealedKey implements Cborable {
    public static final String SEAL = "seal";

    private final byte[] ephemeralPublic, cipherText;

    public SealedKey(byte[] ephemeralPublic, byte[] cipherText) {
        this.ephemeralPublic = ephemeralPublic;
        this.cipherText = cipherText;
    }

    private static Curve25519 boxer() {
        return PublicBoxingKey.PROVIDERS.get(PublicBoxingKey.Type.Curve25519);
    }

    private static byte[] nonce(byte[] ephemeralPublic, byte[] recipient) {
        return Arrays.copyOfRange(Hash.sha256(ArrayOps.concat(ephemeralPublic, recipient)), 0, Curve25519PublicKey.BOX_NONCE_BYTES);
    }

    /** The public half of a directory's sealing key pair, derived from its full key. */
    public static byte[] publicKey(SymmetricKey dirFullKey) {
        byte[] pub = new byte[32];
        boxer().crypto_box_keypair(pub, dirFullKey.derive(SEAL).getKey());
        return pub;
    }

    public static SealedKey seal(SymmetricKey toSeal, byte[] recipient, SafeRandom random) {
        byte[] ephemeralSecret = random.randomBytes(32);
        byte[] ephemeralPublic = new byte[32];
        boxer().crypto_box_keypair(ephemeralPublic, ephemeralSecret);
        byte[] cipherText = boxer().crypto_box(toSeal.serialize(), nonce(ephemeralPublic, recipient), recipient, ephemeralSecret);
        return new SealedKey(ephemeralPublic, cipherText);
    }

    public SymmetricKey open(SymmetricKey dirFullKey, byte[] dirPublicKey) {
        byte[] secret = dirFullKey.derive(SEAL).getKey();
        byte[] raw = boxer().crypto_box_open(cipherText, nonce(ephemeralPublic, dirPublicKey), ephemeralPublic, secret);
        if (raw == null)
            throw new IllegalStateException("Couldn't open sealed key");
        return SymmetricKey.fromByteArray(raw);
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(
                new CborObject.CborByteArray(ephemeralPublic),
                new CborObject.CborByteArray(cipherText)));
    }

    public static SealedKey fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for SealedKey: " + cbor);
        List<? extends Cborable> parts = ((CborObject.CborList) cbor).value;
        return new SealedKey(((CborObject.CborByteArray) parts.get(0)).value, ((CborObject.CborByteArray) parts.get(1)).value);
    }
}
