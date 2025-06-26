package peergos.shared.crypto;

import peergos.shared.Crypto;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.asymmetric.mlkem.HybridCurve25519MLKEMPublicKey;
import peergos.shared.crypto.asymmetric.mlkem.HybridCurve25519MLKEMSecretKey;
import peergos.shared.crypto.random.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class BoxingKeyPair implements Cborable
{
    public final PublicBoxingKey publicBoxingKey;
    public final SecretBoxingKey secretBoxingKey;

    public BoxingKeyPair(PublicBoxingKey publicBoxingKey, SecretBoxingKey secretBoxingKey) {
        this.publicBoxingKey = publicBoxingKey;
        this.secretBoxingKey = secretBoxingKey;
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(
                publicBoxingKey.toCbor(),
                secretBoxingKey.toCbor()));
    }

    public static BoxingKeyPair fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for SigningKeyPair: " + cbor);

        List<? extends Cborable> values = ((CborObject.CborList) cbor).value;
        PublicBoxingKey pub = PublicBoxingKey.fromCbor(values.get(0));
        SecretBoxingKey secret = SecretBoxingKey.fromCbor(values.get(1));
        return new BoxingKeyPair(pub, secret);
    }

    public static CompletableFuture<BoxingKeyPair> randomHybrid(Crypto crypto) {
        BoxingKeyPair curve25519 = randomCurve25519(crypto.random, crypto.boxer);
        return crypto.mlkem.generateKeyPair().thenApply(mlkemKeyPair -> {
            HybridCurve25519MLKEMPublicKey hybridPublic = new HybridCurve25519MLKEMPublicKey(
                    (Curve25519PublicKey) curve25519.publicBoxingKey, mlkemKeyPair.publicKey, crypto);
            HybridCurve25519MLKEMSecretKey hybridSecret = new HybridCurve25519MLKEMSecretKey(
                    (Curve25519SecretKey) curve25519.secretBoxingKey, mlkemKeyPair.secretKey, crypto);
            return new BoxingKeyPair(hybridPublic, hybridSecret);
        });
    }

    public static BoxingKeyPair randomCurve25519(SafeRandom random, Curve25519 boxer) {

        byte[] secretBoxBytes = new byte[32];
        byte[] publicBoxBytes = new byte[32];

        random.randombytes(secretBoxBytes, 0, 32);

        return randomCurve25519(secretBoxBytes, publicBoxBytes, boxer, random);
    }

    private static BoxingKeyPair randomCurve25519(byte[] secretBoxBytes, byte[] publicBoxBytes,
                                                  Curve25519 boxer, SafeRandom random) {
        boxer.crypto_box_keypair(publicBoxBytes, secretBoxBytes);

        return new BoxingKeyPair(
                new Curve25519PublicKey(publicBoxBytes, boxer, random),
                new Curve25519SecretKey(secretBoxBytes, boxer));
    }
}
