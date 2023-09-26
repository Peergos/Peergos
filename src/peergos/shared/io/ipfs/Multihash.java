package peergos.shared.io.ipfs;

import jsinterop.annotations.JsConstructor;
import jsinterop.annotations.JsIgnore;
import jsinterop.annotations.JsType;
import peergos.shared.io.ipfs.bases.*;

import java.io.*;
import java.util.*;

/** Note we don't support the full range of Multihash types, because some of them are insecure
 *
 */
@JsType
public class Multihash implements Comparable<Multihash> {
    public static final int LEGACY_MAX_IDENTITY_HASH_SIZE = 4112;
    public static final int MAX_IDENTITY_HASH_SIZE = 36; // can handle 32 byte Ed25519/Curve25519 public keys plus our type annotation

    @JsType
    public enum Type {
        id(0, -1),
        sha2_256(0x12, 32),
        sha2_512(0x13, 64),
        sha3(0x14, 64),
        blake2b(0x40, 64),
        blake2s(0x41, 32);

        public int index, length;

        Type(int index, int length) {
            this.index = index;
            this.length = length;
        }

        private static Map<Integer, Type> lookup = new TreeMap<>();
        static {
            for (Type t: Type.values())
                lookup.put(t.index, t);
        }

        public static Type lookup(int t) {
            if (!lookup.containsKey(t))
                throw new IllegalStateException("Unknown Multihash type: "+t);
            return lookup.get(t);
        }
    }

    public final Type type;
    private final byte[] hash;

    @JsConstructor
    public Multihash(Type type, byte[] hash) {
        if (hash.length > 127 && type != Type.id)
            throw new IllegalStateException("Unsupported hash size: "+hash.length);
        // This check can be changed to non legacy value once all existing data has been migrated
        if (hash.length > LEGACY_MAX_IDENTITY_HASH_SIZE)
            throw new IllegalStateException("Unsupported hash size: "+hash.length);
        if (hash.length != type.length && type != Type.id)
            throw new IllegalStateException("Incorrect hash length: " + hash.length + " != "+type.length);
        this.type = type;
        this.hash = hash;
    }

    public boolean isIdentity() {
        return type == Type.id;
    }

    public Multihash bareMultihash() {
        return this;
    }

    public static Multihash decode(byte[] multihash) {
        return new Multihash(Type.lookup(multihash[0] & 0xff), Arrays.copyOfRange(multihash, 2, multihash.length));
    }
    @Override
    public int compareTo(Multihash that) {
        int compare = Integer.compare(this.hash.length, that.hash.length);
        if (compare != 0)
            return compare;
        for (int i = 0; i < this.hash.length; i++) {
            compare = Byte.compare(this.hash[i], that.hash[i]);
            if (compare != 0)
                return compare;
        }
        return Integer.compare(type.index, that.type.index);
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream res = new ByteArrayOutputStream();
            putUvarint(res, type.index);
            putUvarint(res, hash.length);
            res.write(hash);
            return res.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] getHash() {
        return Arrays.copyOfRange(hash, 0, hash.length);
    }
    @SuppressWarnings("unusable-by-js")
    public void serializeObj(OutputStream out) {
        try {
            putUvarint(out, type.index);
            putUvarint(out, hash.length);
            out.write(hash);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    @SuppressWarnings("unusable-by-js")
    public static Multihash deserializeObj(InputStream din) throws IOException {
        int type = (int)readVarint(din);
        int len = (int)readVarint(din);
        Type t = Type.lookup(type);
        byte[] hash = new byte[len];
        int total = 0;
        while (total < len) {
            int read = din.read(hash);
            if (read < 0)
                throw new EOFException();
            else
                total += read;
        }
        return new Multihash(t, hash);
    }

    @Override
    public String toString() {
        return toBase58();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Multihash))
            return false;
        return type == ((Multihash) o).type && Arrays.equals(hash, ((Multihash) o).hash);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash) ^ type.hashCode();
    }

    public String toBase58() {
        return Base58.encode(toBytes());
    }

    public static Multihash fromBase58(String base58) {
        return Multihash.decode(Base58.decode(base58));
    }

    @JsIgnore
    public static long readVarint(InputStream in) throws IOException {
        long x = 0;
        int s=0;
        for (int i=0; i < 10; i++) {
            int b = in.read();
            if (b < 0x80) {
                if (i == 9 && b > 1) {
                    throw new IllegalStateException("Overflow reading varint!");
                } else if (b == 0 && s > 0) // We should never finish on a zero byte if there is more than 1 byte
                    throw new IllegalStateException("Non minimal varint encoding!");
                return x | (((long)b) << s);
            }
            x |= ((long)b & 0x7f) << s;
            s += 7;
        }
        throw new IllegalStateException("Varint too long!");
    }

    @JsIgnore
    public static void putUvarint(OutputStream out, long x) throws IOException {
        while (x >= 0x80) {
            out.write((byte)(x | 0x80));
            x >>= 7;
        }
        out.write((byte)x);
    }
}
