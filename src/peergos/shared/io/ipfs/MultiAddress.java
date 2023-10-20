package peergos.shared.io.ipfs;


import java.io.*;
import java.util.*;

public class MultiAddress
{
    private final byte[] raw;

    public MultiAddress(Multihash hash) {
        this("/ipfs/" + hash.toString());
    }

    public MultiAddress(String address) {
        this(decodeFromString(address));
    }

    public MultiAddress(byte[] raw) {
        encodeToString(raw); // check validity
        this.raw = raw;
    }

    public byte[] getBytes() {
        return Arrays.copyOfRange(raw, 0, raw.length);
    }

    public boolean isTCPIP() {
        String[] parts = toString().substring(1).split("/");
        if (parts.length != 4)
            return false;
        if (!parts[0].startsWith("ip"))
            return false;
        if (!parts[2].equals("tcp"))
            return false;
        return true;
    }

    public String getHost() {
        String[] parts = toString().substring(1).split("/");
        if (parts[0].startsWith("ip"))
            return parts[1];
        throw new IllegalStateException("This multiaddress doesn't have a host: "+toString());
    }

    public int getTCPPort() {
        String[] parts = toString().substring(1).split("/");
        if (parts[2].startsWith("tcp"))
            return Integer.parseInt(parts[3]);
        throw new IllegalStateException("This multiaddress doesn't have a tcp port: "+toString());
    }

    private static byte[] decodeFromString(String addr) {
        while (addr.endsWith("/"))
            addr = addr.substring(0, addr.length()-1);
        String[] parts = addr.split("/");
        if (parts[0].length() != 0)
            throw new IllegalStateException("MultiAddress must start with a /");

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try {
            for (int i = 1; i < parts.length;) {
                String part = parts[i++];
                Protocol p = Protocol.get(part);
                p.appendCode(bout);
                if (p.size() == 0)
                    continue;

                String component = parts[i++];
                if (component.length() == 0)
                    throw new IllegalStateException("Protocol requires address, but non provided!");

                bout.write(p.addressToBytes(component));
            }
            return bout.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Error decoding multiaddress: "+addr);
        }
    }

    private static String encodeToString(byte[] raw) {
        StringBuilder b = new StringBuilder();
        InputStream in = new ByteArrayInputStream(raw);
        try {
            while (true) {
                int code = (int)Protocol.readVarint(in);
                Protocol p = Protocol.get(code);
                b.append("/" + p.name());
                if (p.size() == 0)
                    continue;

                String addr = p.readAddress(in);
                if (addr.length() > 0)
                    b.append("/" +addr);
            }
        }
        catch (EOFException eof) {}
        catch (IOException e) {
            throw new RuntimeException(e);
        }

        return b.toString();
    }

    @Override
    public String toString() {
        return encodeToString(raw);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MultiAddress))
            return false;
        return Arrays.equals(raw, ((MultiAddress) other).raw);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(raw);
    }
}
