package peergos.crypto;

import peergos.crypto.asymmetric.*;
import peergos.crypto.asymmetric.curve25519.*;
import peergos.util.*;

import java.io.*;
import java.util.*;

public class UserPublicKey implements Comparable<UserPublicKey>
{
    public static final int MAX_SIZE = 1024*1024;

    public final PublicSigningKey publicSigningKey;
    public final PublicBoxingKey publicBoxingKey;

    public UserPublicKey(PublicSigningKey publicSigningKey, PublicBoxingKey publicBoxingKey)
    {
        this.publicSigningKey = publicSigningKey;
        this.publicBoxingKey = publicBoxingKey;
    }

    public static UserPublicKey deserialize(DataInput din) {
        try {
            PublicSigningKey signingKey = PublicSigningKey.deserialize(din);
            PublicBoxingKey boxingKey = PublicBoxingKey.deserialize(din);
            return new UserPublicKey(signingKey, boxingKey);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid serialized UserPublicKey", e);
        }
    }

    public void serialize(DataOutput dout) throws IOException {
        publicSigningKey.serialize(dout);
        publicBoxingKey.serialize(dout);
    }

    public static UserPublicKey fromByteArray(byte[] raw) {
        return deserialize(new DataInputStream(new ByteArrayInputStream(raw)));
    }

    public static UserPublicKey fromPublicKeys(byte[] raw) {
        return fromByteArray(raw);
    }

    public byte[] serialize()
    {
        return ArrayOps.concat(publicSigningKey.toByteArray(), publicBoxingKey.toByteArray());
    }

    public byte[] getPublicSigningKey()
    {
        return publicSigningKey.getPublicSigningKey();
    }

    public byte[] getPublicBoxingKey()
    {
        return publicBoxingKey.getPublicBoxingKey();
    }

    public byte[] getPublicKeys() {
        try {
            DataSink buf = new DataSink();
            this.publicSigningKey.serialize(buf);
            this.publicBoxingKey.serialize(buf);
            return buf.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] encryptMessageFor(byte[] input, SecretBoxingKey ourSecretBoxingKey)
    {
        return publicBoxingKey.encryptMessageFor(input, ourSecretBoxingKey);
    }

    public byte[] unsignMessage(byte[] signed)
    {
        return publicSigningKey.unsignMessage(signed);
    }

    public UserPublicKey toUserPublicKey() {
        return new UserPublicKey(publicSigningKey, publicBoxingKey);
    }

    public boolean equals(Object o)
    {
        if (! (o instanceof UserPublicKey))
            return false;

        UserPublicKey other = (UserPublicKey) o;
        return publicBoxingKey.equals(other.publicBoxingKey) && publicSigningKey.equals(other.publicSigningKey);
    }

    public int hashCode()
    {
        return publicBoxingKey.hashCode() ^ publicSigningKey.hashCode();
    }

    public boolean isValidSignature(byte[] signed, byte[] raw)
    {
        return Arrays.equals(unsignMessage(signed), raw);
    }

    @Override
    public int compareTo(UserPublicKey userPublicKey) {
        return ArrayOps.compare(serialize(), userPublicKey.serialize());
    }

    public String toString() {
        return new String(Base64.getEncoder().encode(serialize()));
    }

    public static UserPublicKey fromString(String b64) {
        return deserialize(new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(b64))));
    }

    public static UserPublicKey createNull() {
        return new UserPublicKey(
                new Ed25519PublicKey(new byte[32], PublicSigningKey.PROVIDERS.get(PublicSigningKey.Type.Ed25519)),
                new Curve25519PublicKey(new byte[32], PublicBoxingKey.PROVIDERS.get(PublicBoxingKey.Type.Curve25519),
                        PublicBoxingKey.RNG_PROVIDERS.get(PublicBoxingKey.Type.Curve25519)));
    }
}
