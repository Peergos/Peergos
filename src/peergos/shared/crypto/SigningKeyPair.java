package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.asymmetric.SecretSigningKey;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.random.*;
import peergos.shared.io.ipfs.bases.*;

import java.util.*;
import java.util.concurrent.*;

public class SigningKeyPair implements Cborable
{
    public final PublicSigningKey publicSigningKey;
    public final SecretSigningKey secretSigningKey;

    public SigningKeyPair(PublicSigningKey publicSigningKey, SecretSigningKey secretSigningKey)
    {
        this.publicSigningKey = publicSigningKey;
        this.secretSigningKey = secretSigningKey;
    }

    public CompletableFuture<byte[]> signMessage(byte[] message)
    {
        return secretSigningKey.signMessage(message);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SigningKeyPair that = (SigningKeyPair) o;

        if (publicSigningKey != null ? !publicSigningKey.equals(that.publicSigningKey) : that.publicSigningKey != null)
            return false;
        return secretSigningKey != null ? secretSigningKey.equals(that.secretSigningKey) : that.secretSigningKey == null;
    }

    @Override
    public int hashCode() {
        int result = publicSigningKey != null ? publicSigningKey.hashCode() : 0;
        result = 31 * result + (secretSigningKey != null ? secretSigningKey.hashCode() : 0);
        return result;
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(
                publicSigningKey.toCbor(),
                secretSigningKey.toCbor()));
    }

    @Override
    public String toString() {
        return Multibase.encode(Multibase.Base.Base58BTC, serialize());
    }

    public static SigningKeyPair fromString(String multibaseEncoded) {
        return fromByteArray(Multibase.decode(multibaseEncoded));
    }

    public static SigningKeyPair fromByteArray(byte[] raw) {
        return fromCbor(CborObject.fromByteArray(raw));
    }

    public static SigningKeyPair fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for SigningKeyPair: " + cbor);

        List<? extends Cborable> values = ((CborObject.CborList) cbor).value;
        PublicSigningKey pub = PublicSigningKey.fromCbor(values.get(0));
        SecretSigningKey secret = SecretSigningKey.fromCbor(values.get(1));
        return new SigningKeyPair(pub, secret);
    }

    public static SigningKeyPair random(SafeRandom random, Ed25519 signer) {

        byte[] secretSignBytes = new byte[64];
        byte[] publicSignBytes = new byte[32];

        random.randombytes(secretSignBytes, 0, 32);

        return random(secretSignBytes, publicSignBytes, signer);
    }

    private static SigningKeyPair random(byte[] secretSignBytes, byte[] publicSignBytes, Ed25519 signer) {
        signer.crypto_sign_keypair(publicSignBytes, secretSignBytes);

        return new SigningKeyPair(
                new Ed25519PublicKey(publicSignBytes, signer),
                new Ed25519SecretKey(secretSignBytes, signer));
    }
}
