package peergos.crypto.asymmetric.curve25519;

import peergos.crypto.TweetNaCl;
import peergos.crypto.asymmetric.PublicSigningKey;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class Ed25519PublicKey implements PublicSigningKey {

    private final byte[] publicKey;

    public Ed25519PublicKey(byte[] publicKey) {
        this.publicKey = publicKey;
    }

    public PublicSigningKey.Type type() {
        return PublicSigningKey.Type.Ed25519;
    }

    public byte[] serialize() {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);
            dout.writeByte(type().ordinal());
            dout.write(publicKey);
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] getPublicSigningKey() {
        return Arrays.copyOfRange(publicKey, 0, publicKey.length);
    }

    public byte[] unsignMessage(byte[] signed) {
        return TweetNaCl.crypto_sign_open(signed, publicKey);
    }
}
