package peergos.shared.io.ipfs;

import peergos.shared.io.ipfs.bases.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class Cid extends Multihash {
    public static final int V0 = 0;
    public static final int V1 = 1;

    public static final class CidEncodingException extends RuntimeException {

        public CidEncodingException(String message) {
            super(message);
        }
    }

    public enum Codec {
        Raw(0x55),
        DagProtobuf(0x70),
        DagCbor(0x71),
        LibP2pKey(0x72);

        public long type;

        Codec(long type) {
            this.type = type;
        }

        private static Map<Long, Codec> lookup = new TreeMap<>();
        static {
            for (Codec c: Codec.values())
                lookup.put(c.type, c);
        }

        public static Codec lookup(long c) {
            if (!lookup.containsKey(c))
                throw new IllegalStateException("Unknown Codec type: " + c);
            return lookup.get(c);
        }
    }

    public final long version;
    public final Codec codec;

    public Cid(long version, Codec codec, Multihash.Type type, byte[] hash) {
        super(type, hash);
        this.version = version;
        this.codec = codec;
    }

    public static Cid build(long version, Codec codec, Multihash h) {
        return new Cid(version, codec, h.type, h.getHash());
    }

    private byte[] toBytesV0() {
        return super.toBytes();
    }

    private byte[] toBytesV1() {
        try {
            ByteArrayOutputStream res = new ByteArrayOutputStream();
            putUvarint(res, version);
            putUvarint(res, codec.type);
            super.serializeObj(res);
            return res.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] toBytes() {
        if (version == V0)
            return toBytesV0();
        else if (version == V1)
            return toBytesV1();
        throw new IllegalStateException("Unknown cid version: " + version);
    }

    public boolean isRaw() {
        return codec == Codec.Raw;
    }

    @Override
    public Multihash bareMultihash() {
        return new Multihash(type, getHash());
    }

    @Override
    public String toString() {
        if (version == V0) {
            return super.toString();
        } else if (version == V1) {
            return Multibase.encode(Multibase.Base.Base58BTC, toBytesV1());
        }
        throw new IllegalStateException("Unknown Cid version: " + version);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (! (o instanceof Multihash)) return false;
        if (!super.equals(o)) return false;

        if (o instanceof Cid) {
            Cid cid = (Cid) o;

            if (version != cid.version) return false;
            return codec == cid.codec;
        }
        // o must be a Multihash
        return version == 0 && super.equals(o);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        if (version == 0)
            return result;
        result = 31 * result + (int) (version ^ (version >>> 32));
        result = 31 * result + (codec != null ? codec.hashCode() : 0);
        return result;
    }

    public static Cid buildV0(Multihash h) {
        return Cid.build(V0, Codec.DagProtobuf, h);
    }

    public static Cid buildCidV1(Codec c, Multihash.Type type, byte[] hash) {
        return new Cid(V1, c, type, hash);
    }

    public static Cid decode(String v) {
        if (v.length() < 2)
            throw new IllegalStateException("Cid too short!");

        // support legacy format
        if (v.length() == 46 && v.startsWith("Qm"))
            return buildV0(Multihash.fromBase58(v));

        byte[] data = Multibase.decode(v);
        return cast(data);
    }

    public static Cid decodePeerId(String peerId) {
        if (peerId.startsWith("1")) {
            // convert base58 encoded identity multihash to cidV1
            Multihash hash = Multihash.decode(Base58.decode(peerId));
            return new Cid(1, Cid.Codec.LibP2pKey, hash.type, hash.getHash());
        }
        return Cid.decode(peerId);
    }

    public static Cid cast(byte[] data) {
        if (data.length == 34 && data[0] == 18 && data[1] == 32)
            return buildV0(Multihash.decode(data));

        InputStream in = new ByteArrayInputStream(data);
        try {
            long version = readVarint(in);
            if (version != V0 && version != V1)
                throw new CidEncodingException("Invalid Cid version number: " + version);

            long codec = readVarint(in);
            Multihash hash = Multihash.deserializeObj(in);

            return new Cid(version, Codec.lookup(codec), hash.type, hash.getHash());
        } catch (Exception e) {
            throw new CidEncodingException("Invalid cid bytes: " + ArrayOps.bytesToHex(data));
        }
    }
}
