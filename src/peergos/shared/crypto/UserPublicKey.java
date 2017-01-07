package peergos.shared.crypto;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class UserPublicKey implements Cborable, Comparable<UserPublicKey>
{
    public static final int MAX_SIZE = 1024*1024;

    @JsProperty
    public final PublicSigningKey publicSigningKey;

    @JsConstructor
    public UserPublicKey(PublicSigningKey publicSigningKey)
    {
        this.publicSigningKey = publicSigningKey;
    }

    public static UserPublicKey deserialize(DataInput din) {
        try {
            PublicSigningKey signingKey = PublicSigningKey.deserialize(din);
            return new UserPublicKey(signingKey);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid serialized UserPublicKey", e);
        }
    }

    public void serialize(DataOutput dout) throws IOException {
        publicSigningKey.serialize(dout);
    }

    @JsMethod
    public static UserPublicKey fromByteArray(byte[] raw) {
        return deserialize(new DataInputStream(new ByteArrayInputStream(raw)));
    }

    public static UserPublicKey fromPublicKeys(byte[] raw) {
        return fromByteArray(raw);
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborByteArray(serialize());
    }

    public static UserPublicKey fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborByteArray))
            throw new IllegalStateException("UserPublicKEy cbor must be a byte[]! " + cbor);
        return fromByteArray(((CborObject.CborByteArray) cbor).value);
    }

    @JsMethod
    public byte[] serialize()
    {
        return publicSigningKey.toByteArray();
    }

    public byte[] getPublicSigningKey()
    {
        return publicSigningKey.getPublicSigningKey();
    }

    public byte[] getPublicKeys() {
        try {
            DataSink buf = new DataSink();
            this.publicSigningKey.serialize(buf);
            return buf.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @JsMethod
    public byte[] unsignMessage(byte[] signed)
    {
        return publicSigningKey.unsignMessage(signed);
    }

    public UserPublicKey toUserPublicKey() {
        return new UserPublicKey(publicSigningKey);
    }

    public boolean equals(Object o)
    {
        if (! (o instanceof UserPublicKey))
            return false;

        UserPublicKey other = (UserPublicKey) o;
        return publicSigningKey.equals(other.publicSigningKey);
    }

    public int hashCode()
    {
        return publicSigningKey.hashCode();
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
                new Ed25519PublicKey(new byte[32], PublicSigningKey.PROVIDERS.get(PublicSigningKey.Type.Ed25519)));
    }
}
