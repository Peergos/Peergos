package peergos.shared.crypto.asymmetric.mlkem;

import peergos.shared.Crypto;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.asymmetric.PublicBoxingKey;
import peergos.shared.crypto.asymmetric.SecretBoxingKey;
import peergos.shared.crypto.asymmetric.curve25519.Curve25519PublicKey;
import peergos.shared.crypto.symmetric.TweetNaClKey;
import peergos.shared.util.ArrayOps;

import java.util.Arrays;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

public class HybridCurve25519MLKEMPublicKey implements PublicBoxingKey {

    public final Curve25519PublicKey curve25519;
    public final MlkemPublicKey mlkem;
    public final Crypto crypto;

    public HybridCurve25519MLKEMPublicKey(Curve25519PublicKey curve25519, MlkemPublicKey mlkem, Crypto crypto) {
        this.curve25519 = curve25519;
        this.mlkem = mlkem;
        this.crypto = crypto;
    }

    @Override
    public Type type() {
        return Type.HybridCurve25519MLKEM;
    }

    @Override
    public byte[] getPublicBoxingKey() {
        throw new IllegalStateException("This should not be called!");
    }

    @Override
    public CompletableFuture<byte[]> encryptMessageFor(byte[] input, SecretBoxingKey from) {
        if (!(from instanceof HybridCurve25519MLKEMSecretKey))
            throw new IllegalStateException("Didn't provide a HybridCurve25519MLKEMSecretKey!");
        byte[] curve25519SharedSecret = crypto.random.randomBytes(32);
        return mlkem.encapsulate().thenCompose(encapsulated -> {
            byte[] mlkemSharedSecret = encapsulated.sharedSecret;
            return crypto.hasher.hkdfKey(ArrayOps.concat(curve25519SharedSecret, mlkemSharedSecret)).thenCompose(combinedSecret -> {
                TweetNaClKey combinedSecretKey = new TweetNaClKey(combinedSecret, false, crypto.symmetricProvider, crypto.random);
                return curve25519.encryptMessageFor(curve25519SharedSecret, ((HybridCurve25519MLKEMSecretKey) from).curve25519).thenApply(curve25519Ciphertext -> {
                    byte[] mlkemCipherText = encapsulated.cipherText;
                    byte[] symmetricNonce = combinedSecretKey.createNonce();
                    byte[] encryptedInput = combinedSecretKey.encrypt(input, symmetricNonce);
                    // now combine the 3 ciphertexts with cbor
                    return new HybridCipherText(curve25519Ciphertext, mlkemCipherText, encryptedInput, symmetricNonce).serialize();
                });
            });
        });
    }

    @Override
    public byte[] createNonce() {
        throw new IllegalStateException("This should not be called!");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HybridCurve25519MLKEMPublicKey that = (HybridCurve25519MLKEMPublicKey) o;
        return Objects.equals(curve25519, that.curve25519) &&
                Objects.equals(mlkem, that.mlkem);
    }

    @Override
    public int hashCode() {
        return Objects.hash(curve25519, mlkem);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", curve25519.toCbor());
        state.put("m", mlkem.toCbor());
        return new CborObject.CborList(Arrays.asList(new CborObject.CborLong(type().value), CborObject.CborMap.build(state)));
    }

    public static HybridCurve25519MLKEMPublicKey fromCbor(Cborable cbor, Crypto crypto) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for HybridCurve25519MLKEMPublicKey! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) ((CborObject.CborList) cbor).value.get(1);
        Curve25519PublicKey curve25519 = m.get("c", c -> Curve25519PublicKey.fromCbor(c, crypto.boxer, crypto.random));
        MlkemPublicKey mlkem = m.get("m", c -> MlkemPublicKey.fromCbor(c, crypto.mlkem));
        return new HybridCurve25519MLKEMPublicKey(curve25519, mlkem, crypto);
    }
}
