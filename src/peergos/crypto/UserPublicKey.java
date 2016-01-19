package peergos.crypto;

import peergos.crypto.asymmetric.PublicBoxingKey;
import peergos.crypto.asymmetric.PublicSigningKey;
import peergos.crypto.asymmetric.SecretBoxingKey;
import peergos.util.ArrayOps;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
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

    public static UserPublicKey deserialize(DataInputStream din) throws IOException {
        PublicSigningKey signingKey = PublicSigningKey.deserialize(din);
        PublicBoxingKey boxingKey = PublicBoxingKey.deserialize(din);
        return new UserPublicKey(signingKey, boxingKey);
    }

    public byte[] serializePublicKeys()
    {
        return ArrayOps.concat(publicSigningKey.serialize(), publicBoxingKey.serialize());
    }

    public byte[] getPublicSigningKey()
    {
        return publicSigningKey.getPublicSigningKey();
    }

    public byte[] getPublicBoxingKey()
    {
        return publicBoxingKey.getPublicBoxingKey();
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

        return publicBoxingKey.equals(((UserPublicKey) o).publicBoxingKey) && publicSigningKey.equals(((UserPublicKey) o).publicSigningKey);
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
        return ArrayOps.compare(serializePublicKeys(), userPublicKey.serializePublicKeys());
    }

    public String toString() {
        return new String(Base64.getEncoder().encode(serializePublicKeys()));
    }

    public static UserPublicKey fromString(String b64) throws IOException {
        return deserialize(new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(b64))));
    }
}
