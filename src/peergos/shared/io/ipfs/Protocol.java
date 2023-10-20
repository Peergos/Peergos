package peergos.shared.io.ipfs;

import peergos.shared.io.ipfs.bases.*;

import java.io.*;
import java.net.*;
import java.util.*;

public class Protocol {
    public static int LENGTH_PREFIXED_VAR_SIZE = -1;
    private static final String IPV4_REGEX = "\\A(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}\\z";

    enum Type {
        IP4(4, 32, "ip4"),
        TCP(6, 16, "tcp"),
        DCCP(33, 16, "dccp"),
        IP6(41, 128, "ip6"),
        DNS(53, LENGTH_PREFIXED_VAR_SIZE, "dns"),
        DNS4(54, LENGTH_PREFIXED_VAR_SIZE, "dns4"),
        DNS6(55, LENGTH_PREFIXED_VAR_SIZE, "dns6"),
        DNSADDR(56, LENGTH_PREFIXED_VAR_SIZE, "dnsaddr"),
        SCTP(132, 16, "sctp"),
        UDP(273, 16, "udp"),
        UTP(301, 0, "utp"),
        UDT(302, 0, "udt"),
        UNIX(400, LENGTH_PREFIXED_VAR_SIZE, "unix"),
        P2P(421, LENGTH_PREFIXED_VAR_SIZE, "p2p"),
        IPFS(421, LENGTH_PREFIXED_VAR_SIZE, "ipfs"),
        HTTPS(443, 0, "https"),
        ONION(444, 80, "onion"),
        ONION3(445, 296, "onion3"),
        GARLIC64(446, LENGTH_PREFIXED_VAR_SIZE, "garlic64"),
        GARLIC32(447, LENGTH_PREFIXED_VAR_SIZE, "garlic32"),
        QUIC(460, 0, "quic"),
        WS(477, 0, "ws"),
        WSS(478, 0, "wss"),
        P2PCIRCUIT(290, 0, "p2p-circuit"),
        HTTP(480, 0, "http");

        public final int code, size;
        public final String name;
        private final byte[] encoded;

        Type(int code, int size, String name) {
            this.code = code;
            this.size = size;
            this.name = name;
            this.encoded = encode(code);
        }

        static byte[] encode(int code) {
            byte[] varint = new byte[(32 - Integer.numberOfLeadingZeros(code)+6)/7];
            putUvarint(varint, code);
            return varint;
        }
    }

    public final Type type;

    public Protocol(Type type) {
        this.type = type;
    }

    public void appendCode(OutputStream out) throws IOException {
        out.write(type.encoded);
    }

    public boolean isTerminal() {
        return type == Type.UNIX;
    }

    public int size() {
        return type.size;
    }

    public String name() {
        return type.name;
    }

    public int code() {
        return type.code;
    }

    @Override
    public String toString() {
        return name();
    }

    public byte[] addressToBytes(String addr) {
        try {
            switch (type) {
                case IP4:
                    if (! addr.matches(IPV4_REGEX))
                        throw new IllegalStateException("Invalid IPv4 address: " + addr);
                    return Inet4Address.getByName(addr).getAddress();
                case IP6:
                    return Inet6Address.getByName(addr).getAddress();
                case TCP:
                case UDP:
                case DCCP:
                case SCTP:
                    int x = Integer.parseInt(addr);
                    if (x > 65535)
                        throw new IllegalStateException("Failed to parse "+type.name+" address "+addr + " (> 65535");
                    return new byte[]{(byte)(x >>8), (byte)x};
                case P2P:
                case IPFS: {
                    Multihash hash = Cid.decodePeerId(addr);
                    ByteArrayOutputStream bout = new ByteArrayOutputStream();
                    byte[] hashBytes = hash.toBytes();
                    byte[] varint = new byte[(32 - Integer.numberOfLeadingZeros(hashBytes.length) + 6) / 7];
                    putUvarint(varint, hashBytes.length);
                    bout.write(varint);
                    bout.write(hashBytes);
                    return bout.toByteArray();
                }
                case ONION: {
                    String[] split = addr.split(":");
                    if (split.length != 2)
                        throw new IllegalStateException("Onion address needs a port: " + addr);

                    // onion address without the ".onion" substring
                    if (split[0].length() != 16)
                        throw new IllegalStateException("failed to parse " + name() + " addr: " + addr + " not a Tor onion address.");

                    byte[] onionHostBytes = Multibase.decode(Multibase.Base.Base32.prefix + split[0]);
                    if (onionHostBytes.length != 10)
                        throw new IllegalStateException("Invalid onion address host: " + split[0]);
                    int port = Integer.parseInt(split[1]);
                    if (port > 65535)
                        throw new IllegalStateException("Port is > 65535: " + port);

                    if (port < 1)
                        throw new IllegalStateException("Port is < 1: " + port);

                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream dout = new DataOutputStream(b);
                    dout.write(onionHostBytes);
                    dout.writeShort(port);
                    dout.flush();
                    return b.toByteArray();
                }
                case ONION3: {
                    String[] split = addr.split(":");
                    if (split.length != 2)
                        throw new IllegalStateException("Onion3 address needs a port: " + addr);

                    // onion3 address without the ".onion" substring
                    if (split[0].length() != 56)
                        throw new IllegalStateException("failed to parse " + name() + " addr: " + addr + " not a Tor onion3 address.");

                    byte[] onionHostBytes = Multibase.decode(Multibase.Base.Base32.prefix + split[0]);
                    if (onionHostBytes.length != 35)
                        throw new IllegalStateException("Invalid onion3 address host: " + split[0]);
                    int port = Integer.parseInt(split[1]);
                    if (port > 65535)
                        throw new IllegalStateException("Port is > 65535: " + port);

                    if (port < 1)
                        throw new IllegalStateException("Port is < 1: " + port);

                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream dout = new DataOutputStream(b);
                    dout.write(onionHostBytes);
                    dout.writeShort(port);
                    dout.flush();
                    return b.toByteArray();
                } case GARLIC32: {
                    // an i2p base32 address with a length of greater than 55 characters is
                    // using an Encrypted Leaseset v2. all other base32 addresses will always be
                    // exactly 52 characters
                    if (addr.length() < 55 && addr.length() != 52 || addr.contains(":")) {
                        throw new IllegalStateException("Invalid garlic addr: " + addr + " not a i2p base32 address. len: " + addr.length());
                    }

                    while (addr.length() % 8 != 0) {
                        addr += "=";
                    }

                    ByteArrayOutputStream bout = new ByteArrayOutputStream();
                    byte[] hashBytes =  Multibase.decode(Multibase.Base.Base32.prefix + addr);
                    byte[] varint = new byte[(32 - Integer.numberOfLeadingZeros(hashBytes.length) + 6) / 7];
                    putUvarint(varint, hashBytes.length);
                    bout.write(varint);
                    bout.write(hashBytes);
                    return bout.toByteArray();
                } case GARLIC64: {
                    // i2p base64 address will be between 516 and 616 characters long, depending on certificate type
                    if (addr.length() < 516 || addr.length() > 616 || addr.contains(":")) {
                        throw new IllegalStateException("Invalid garlic addr: " + addr + " not a i2p base64 address. len: " + addr.length());
                    }

                    ByteArrayOutputStream bout = new ByteArrayOutputStream();
                    byte[] hashBytes =  Multibase.decode(Multibase.Base.Base64.prefix + addr.replaceAll("-", "+").replaceAll("~", "/"));
                    byte[] varint = new byte[(32 - Integer.numberOfLeadingZeros(hashBytes.length) + 6) / 7];
                    putUvarint(varint, hashBytes.length);
                    bout.write(varint);
                    bout.write(hashBytes);
                    return bout.toByteArray();
                } case UNIX: {
                    if (addr.startsWith("/"))
                        addr = addr.substring(1);
                    byte[] path = addr.getBytes();
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream dout = new DataOutputStream(b);
                    byte[] length = new byte[(32 - Integer.numberOfLeadingZeros(path.length)+6)/7];
                    putUvarint(length, path.length);
                    dout.write(length);
                    dout.write(path);
                    dout.flush();
                    return b.toByteArray();
                }
                case DNS4:
                case DNS6:
                case DNSADDR: {
                    ByteArrayOutputStream bout = new ByteArrayOutputStream();
                    byte[] hashBytes = addr.getBytes();
                    byte[] varint = new byte[(32 - Integer.numberOfLeadingZeros(hashBytes.length) + 6) / 7];
                    putUvarint(varint, hashBytes.length);
                    bout.write(varint);
                    bout.write(hashBytes);
                    return bout.toByteArray();
                }
                default:
                    throw new IllegalStateException("Unknown multiaddr type: " + type);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String readAddress(InputStream in) throws IOException {
        int sizeForAddress = sizeForAddress(in);
        byte[] buf;
        switch (type) {
            case IP4:
                buf = new byte[sizeForAddress];
                read(in, buf);
                return Inet4Address.getByAddress(buf).toString().substring(1);
            case IP6:
                buf = new byte[sizeForAddress];
                read(in, buf);
                return Inet6Address.getByAddress(buf).toString().substring(1);
            case TCP:
            case UDP:
            case DCCP:
            case SCTP:
                return Integer.toString((in.read() << 8) | (in.read()));
            case IPFS:
                buf = new byte[sizeForAddress];
                read(in, buf);
                return Cid.cast(buf).toString();
            case ONION: {
                byte[] host = new byte[10];
                read(in, host);
                String port = Integer.toString((in.read() << 8) | (in.read()));
                return Multibase.encode(Multibase.Base.Base32, host).substring(1) + ":" + port;
            } case ONION3: {
                byte[] host = new byte[35];
                read(in, host);
                String port = Integer.toString((in.read() << 8) | (in.read()));
                return Multibase.encode(Multibase.Base.Base32, host).substring(1) + ":" + port;
            } case GARLIC32: {
                buf = new byte[sizeForAddress];
                read(in, buf);
                // an i2p base32 for an Encrypted Leaseset v2 will be at least 35 bytes
                // long other than that, they will be exactly 32 bytes
                if (buf.length < 35 && buf.length != 32) {
                    throw new IllegalStateException("Invalid garlic addr length: " + buf.length);
                }
                return Multibase.encode(Multibase.Base.Base32, buf).substring(1);
            } case GARLIC64: {
                buf = new byte[sizeForAddress];
                read(in, buf);
                // A garlic64 address will always be greater than 386 bytes
                if (buf.length < 386) {
                    throw new IllegalStateException("Invalid garlic64 addr length: " + buf.length);
                }
                return Multibase.encode(Multibase.Base.Base64, buf).substring(1).replaceAll("\\+", "-").replaceAll("/", "~");
            } case UNIX:
                buf = new byte[sizeForAddress];
                read(in, buf);
                return new String(buf);
            case DNS4:
            case DNS6:
            case DNSADDR:
                buf = new byte[sizeForAddress];
                read(in, buf);
                return new String(buf);
        }
        throw new IllegalStateException("Unimplemented protocol type: "+type.name);
    }

    private static void read(InputStream in, byte[] b) throws IOException {
        read(in, b, 0, b.length);
    }

    private static void read(InputStream in, byte[] b, int offset, int len) throws IOException {
        int total=0, r=0;
        while (total < len && r != -1) {
            r = in.read(b, offset + total, len - total);
            if (r >=0)
                total += r;
        }
    }

    public int sizeForAddress(InputStream in) throws IOException {
        if (type.size > 0)
            return type.size/8;
        if (type.size == 0)
            return 0;
        return (int)readVarint(in);
    }

    static int putUvarint(byte[] buf, long x) {
        int i = 0;
        while (x >= 0x80) {
            buf[i] = (byte)(x | 0x80);
            x >>= 7;
            i++;
        }
        buf[i] = (byte)x;
        return i + 1;
    }

    static long readVarint(InputStream in) throws IOException {
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

    private static Map<String, Protocol> byName = new HashMap<>();
    private static Map<Integer, Protocol> byCode = new HashMap<>();

    static {
        for (Protocol.Type t: Protocol.Type.values()) {
            Protocol p = new Protocol(t);
            byName.put(p.name(), p);
            byCode.put(p.code(), p);
        }

    }

    public static Protocol get(String name) {
        if (byName.containsKey(name))
            return byName.get(name);
        throw new IllegalStateException("No protocol with name: "+name);
    }

    public static Protocol get(int code) {
        if (byCode.containsKey(code))
            return byCode.get(code);
        throw new IllegalStateException("No protocol with code: "+code);
    }
}