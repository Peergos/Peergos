package peergos.shared.crypto.asymmetric.mlkem;

import peergos.shared.Crypto;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.asymmetric.PublicBoxingKey;
import peergos.shared.crypto.asymmetric.SecretBoxingKey;
import peergos.shared.crypto.asymmetric.curve25519.Curve25519PublicKey;
import peergos.shared.crypto.asymmetric.curve25519.Curve25519SecretKey;
import peergos.shared.crypto.symmetric.TweetNaClKey;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.Futures;

import java.util.Arrays;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

public class HybridCurve25519MLKEMSecretKey implements SecretBoxingKey {

    public final Curve25519SecretKey curve25519;
    public final MlkemSecretKey mlkem;
    public final Crypto crypto;

    public HybridCurve25519MLKEMSecretKey(Curve25519SecretKey curve25519, MlkemSecretKey mlkem, Crypto crypto) {
        this.curve25519 = curve25519;
        this.mlkem = mlkem;
        this.crypto = crypto;
    }

    @Override
    public PublicBoxingKey.Type type() {
        return PublicBoxingKey.Type.HybridCurve25519MLKEM;
    }

    @Override
    public byte[] getSecretBoxingKey() {
        throw new IllegalStateException("This should not be called!");
    }

    @Override
    public CompletableFuture<byte[]> decryptMessage(byte[] hybridCipher, PublicBoxingKey from) {
        if (!(from instanceof HybridCurve25519MLKEMPublicKey))
            return Futures.errored(new IllegalStateException("Didn't provide a HybridCurve25519MLKEMPublicKey!"));
        HybridCipherText hybrid = HybridCipherText.fromCbor(CborObject.fromByteArray(hybridCipher));
        return curve25519.decryptMessage(hybrid.curve25519Ciphertext, ((HybridCurve25519MLKEMPublicKey) from).curve25519)
                .thenCompose(curve25519SharedSecret -> mlkem.decapsulate(hybrid.mlkemCipherText)
                        .thenCompose(mlkemSharedSecret -> crypto.hasher.hkdfKey(ArrayOps.concat(curve25519SharedSecret, mlkemSharedSecret))
                                .thenApply(combinedSecret -> new TweetNaClKey(combinedSecret, false, crypto.symmetricProvider, crypto.random))
                                .thenApply(combinedSecretKey -> combinedSecretKey.decrypt(hybrid.encryptedInput, hybrid.nonce))));
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", curve25519.toCbor());
        state.put("m", mlkem.toCbor());
        return new CborObject.CborList(Arrays.asList(new CborObject.CborLong(type().value), CborObject.CborMap.build(state)));
    }

    public static HybridCurve25519MLKEMSecretKey fromCbor(Cborable cbor, Crypto crypto) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for HybridCurve25519MLKEMSecretKey! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) ((CborObject.CborList) cbor).value.get(1);
        Curve25519SecretKey curve25519 = m.get("c", c -> Curve25519SecretKey.fromCbor(c, crypto.boxer));
        MlkemSecretKey mlkem = m.get("m", c -> MlkemSecretKey.fromCbor(c, crypto.mlkem));
        return new HybridCurve25519MLKEMSecretKey(curve25519, mlkem, crypto);
    }
}
