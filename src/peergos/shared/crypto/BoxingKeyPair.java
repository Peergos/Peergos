package peergos.shared.crypto;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.random.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class BoxingKeyPair
{
    public final PublicBoxingKey publicBoxingKey;
    public final SecretBoxingKey secretBoxingKey;

    public BoxingKeyPair(PublicBoxingKey publicBoxingKey, SecretBoxingKey secretBoxingKey) {
        this.publicBoxingKey = publicBoxingKey;
        this.secretBoxingKey = secretBoxingKey;
    }

    public static BoxingKeyPair deserialize(DataInput din) {
        try {
            SecretBoxingKey secretKey = SecretBoxingKey.deserialize(din);
            PublicBoxingKey publicKey = PublicBoxingKey.deserialize(din);
            return new BoxingKeyPair(publicKey, secretKey);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid serialized User", e);
        }
    }

    public void serialize(DataOutput dout) throws IOException {
        dout.write(serialize());
    }

    public byte[] serialize() {
        return ArrayOps.concat(secretBoxingKey.serialize(), publicBoxingKey.serialize());
    }

    public static BoxingKeyPair random(SafeRandom random, Curve25519 boxer) {

        byte[] secretBoxBytes = new byte[32];
        byte[] publicBoxBytes = new byte[32];

        random.randombytes(secretBoxBytes, 0, 32);

        return random(secretBoxBytes, publicBoxBytes, boxer, random);
    }

    private static BoxingKeyPair random(byte[] secretBoxBytes, byte[] publicBoxBytes,
                                        Curve25519 boxer, SafeRandom random) {
        boxer.crypto_box_keypair(publicBoxBytes, secretBoxBytes);

        return new BoxingKeyPair(
                new Curve25519PublicKey(publicBoxBytes, boxer, random),
                new Curve25519SecretKey(secretBoxBytes, boxer));
    }

    public static BoxingKeyPair insecureRandom() {

        byte[] secretBoxBytes = new byte[32];
        byte[] publicBoxBytes = new byte[32];

        Random rnd = new Random();
        rnd.nextBytes(secretBoxBytes);
        rnd.nextBytes(publicBoxBytes);
        return random(secretBoxBytes, publicBoxBytes, new Curve25519.Java(), new SafeRandom.Java());
    }
}
