package peergos.shared.routing;

import peergos.shared.cbor.*;

import java.util.*;

/** A direct address, naming the transport that dials it, so one record can serve several stacks
 *  (remove-pki §4.1). A reader skips a transport it does not know.
 */
public class Address implements Cborable {
    public static final String LIBP2P = "libp2p";
    public static final String IROH = "iroh";

    public final String transport;
    public final byte[] address;

    public Address(String transport, byte[] address) {
        this.transport = transport;
        this.address = address;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("t", new CborObject.CborString(transport));
        m.put("a", new CborObject.CborByteArray(address));
        return CborObject.CborMap.build(m);
    }

    public static Address fromCbor(Cborable cbor) {
        CborObject.CborMap m = RoutingCbor.map(cbor, "Address");
        return new Address(m.getString("t"), m.getByteArray("a"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (! (o instanceof Address)) return false;
        Address that = (Address) o;
        return transport.equals(that.transport) && Arrays.equals(address, that.address);
    }

    @Override
    public int hashCode() {
        return 31 * transport.hashCode() + Arrays.hashCode(address);
    }
}
