package peergos.shared.io.ipfs.cid;

import peergos.shared.io.ipfs.multibase.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class Cid extends Multihash {

    public static final class CidEncodingException extends RuntimeException {

        public CidEncodingException(String message) {
            super(message);
        }
    }

    public enum Codec {
        Raw(0x55),
        DagProtobuf(0x70),
        DagCbor(0x71),
        EthereumBlock(0x90),
        EthereumTx(0x91),
        BitcoinBlock(0xb0),
        BitcoinTx(0xb1),
        ZcashBlock(0xc0),
        ZcashTx(0xc1);

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

    private static final int MAX_VARINT_LEN64 = 10;

    private byte[] toBytesV1() {
        byte[] hashBytes = super.toBytes();
        byte[] res = new byte[2 * MAX_VARINT_LEN64 + hashBytes.length];
        int index = putUvarint(res, 0, version);
        index = putUvarint(res, index, codec.type);
        System.arraycopy(hashBytes, 0, res, index, hashBytes.length);
        return Arrays.copyOfRange(res, 0, index + hashBytes.length);
    }

    @Override
    public byte[] toBytes() {
        if (version == 0)
            return toBytesV0();
        else if (version == 1)
            return toBytesV1();
        throw new IllegalStateException("Unknown cid version: " + version);
    }

    @Override
    public String toString() {
        if (version == 0) {
            return super.toString();
        } else if (version == 1) {
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
        return Cid.build(0, Codec.DagProtobuf, h);
    }

    public static Cid buildCidV1(Codec c, Multihash.Type type, byte[] hash) {
        return new Cid(1, c, type, hash);
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

    public static Cid cast(byte[] data) {
        if (data.length == 34 && data[0] == 18 && data[1] == 32)
            return buildV0(Multihash.decode(data));

        InputStream in = new ByteArrayInputStream(data);
        try {
            long version = readVarint(in);
            if (version != 0 && version != 1)
                throw new CidEncodingException("Invalid Cid version number: " + version);

            long codec = readVarint(in);
            if (version != 0 && version != 1)
                throw new CidEncodingException("Invalid Cid version number: " + version);

            Multihash hash = Multihash.deserialize(new DataInputStream(in));

            return new Cid(version, Codec.lookup(codec), hash.type, hash.getHash());
        } catch (Exception e) {
            throw new CidEncodingException("Invalid cid bytes: " + ArrayOps.bytesToHex(data));
        }
    }

    private static long readVarint(InputStream in) throws IOException {
        long x = 0;
        int s=0;
        for (int i=0; i < 10; i++) {
            int b = in.read();
            if (b == -1)
                throw new EOFException();
            if (b < 0x80) {
                if (i > 9 || i == 9 && b > 1) {
                    throw new IllegalStateException("Overflow reading varint" +(-(i + 1)));
                }
                return x | (((long)b) << s);
            }
            x |= ((long)b & 0x7f) << s;
            s += 7;
        }
        throw new IllegalStateException("Varint too long!");
    }

    private static int putUvarint(byte[] buf, int index, long x) {
        while (x >= 0x80) {
            buf[index] = (byte)(x | 0x80);
            x >>= 7;
            index++;
        }
        buf[index] = (byte)x;
        return index + 1;
    }
}
