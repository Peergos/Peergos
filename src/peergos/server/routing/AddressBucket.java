package peergos.server.routing;

import java.net.*;
import java.util.*;

/** An IPv4 /16 or an IPv6 /32, as Bitcoin Core groups peers (remove-pki §4.3). Servers not yet placed by reaching
 *  them at a direct address share one bucket, {@link #UNVERIFIED}, with its own rate.
 */
public final class AddressBucket {
    public static final AddressBucket UNVERIFIED = new AddressBucket(new byte[0]);

    private final byte[] prefix;

    private AddressBucket(byte[] prefix) {
        this.prefix = prefix;
    }

    public static AddressBucket of(InetAddress addr) {
        byte[] raw = addr.getAddress();
        if (addr instanceof Inet6Address && isMappedV4(raw))
            raw = Arrays.copyOfRange(raw, 12, 16);
        return new AddressBucket(Arrays.copyOf(raw, raw.length == 4 ? 2 : 4));
    }

    private static boolean isMappedV4(byte[] raw) {
        for (int i = 0; i < 10; i++)
            if (raw[i] != 0)
                return false;
        return raw[10] == (byte) 0xff && raw[11] == (byte) 0xff;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AddressBucket && Arrays.equals(prefix, ((AddressBucket) o).prefix);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(prefix);
    }

    @Override
    public String toString() {
        if (prefix.length == 0)
            return "unverified";
        if (prefix.length == 2)
            return (prefix[0] & 0xff) + "." + (prefix[1] & 0xff) + "/16";
        return String.format("%02x%02x:%02x%02x::/32", prefix[0], prefix[1], prefix[2], prefix[3]);
    }
}
