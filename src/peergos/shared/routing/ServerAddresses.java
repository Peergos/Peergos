package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** ServerAddresses { server Si, endpoint E, kem, addrs, relays, mode, relaying, counter } signed by Si
 *  (remove-pki §4.1). A server behind NAT lists no direct address: its current IP is observed, not published. One
 *  whose public address AutoNAT confirmed, rather than its operator announced, lists its relays as well.
 */
public class ServerAddresses extends SignedRecord implements Update {

    public enum Mode {
        Archival("archival"), Compact("compact"), Light("light");

        public final String name;

        Mode(String name) {
            this.name = name;
        }

        public static Mode byName(String name) {
            for (Mode m : values())
                if (m.name.equals(name))
                    return m;
            throw new IllegalStateException("Unknown mode: " + name);
        }
    }

    public final PublicSigningKey server, endpoint;
    public final Optional<byte[]> kem;
    public final List<Address> addrs;
    public final List<Multihash> relays;
    public final Mode mode;
    public final boolean relaying;
    public final long counter;

    public ServerAddresses(PublicSigningKey server,
                           PublicSigningKey endpoint,
                           Optional<byte[]> kem,
                           List<Address> addrs,
                           List<Multihash> relays,
                           Mode mode,
                           boolean relaying,
                           long counter,
                           byte[] signature) {
        super(signature);
        this.server = server;
        this.endpoint = endpoint;
        this.kem = kem;
        this.addrs = Collections.unmodifiableList(new ArrayList<>(addrs));
        this.relays = Collections.unmodifiableList(new ArrayList<>(relays));
        this.mode = mode;
        this.relaying = relaying;
        this.counter = counter;
    }

    public static CompletableFuture<ServerAddresses> build(SigningKeyPair server,
                                                           PublicSigningKey endpoint,
                                                           Optional<byte[]> kem,
                                                           List<Address> addrs,
                                                           List<Multihash> relays,
                                                           Mode mode,
                                                           boolean relaying,
                                                           long counter) {
        ServerAddresses unsigned = new ServerAddresses(server.publicSigningKey, endpoint, kem, addrs, relays, mode, relaying, counter, new byte[0]);
        return Signing.sign(server.secretSigningKey, unsigned.context(), unsigned.body().toByteArray())
                .thenApply(sig -> new ServerAddresses(server.publicSigningKey, endpoint, kem, addrs, relays, mode, relaying, counter, sig));
    }

    @Override
    public PublicSigningKey key() {
        return server;
    }

    @Override
    public long counter() {
        return counter;
    }

    @Override
    public CborObject.CborMap body() {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("k", server.toCbor());
        m.put("e", endpoint.toCbor());
        kem.ifPresent(k -> m.put("q", new CborObject.CborByteArray(k)));
        m.put("a", new CborObject.CborList(addrs));
        m.put("r", RoutingCbor.peerIds(relays));
        m.put("o", new CborObject.CborString(mode.name));
        m.put("y", new CborObject.CborBoolean(relaying));
        m.put("c", new CborObject.CborLong(counter));
        return CborObject.CborMap.build(m);
    }

    @Override
    public String context() {
        return Signing.SERVER_ADDRESSES;
    }

    @Override
    public PublicSigningKey signer() {
        return server;
    }

    @Override
    protected List<? extends Cborable> keys() {
        List<Cborable> keys = new ArrayList<>(Arrays.asList(server, endpoint));
        kem.ifPresent(k -> keys.add(new CborObject.CborByteArray(k)));
        return keys;
    }

    @Override
    protected void checkFields() {
        if (addrs.size() > Bounds.MAX_ADDRESSES)
            throw new IllegalStateException("Too many addresses: " + addrs.size());
        for (Address a : addrs) {
            if (a.address.length > Bounds.MAX_ADDRESS_BYTES)
                throw new IllegalStateException("Address too large");
            if (a.transport.isEmpty() || a.transport.length() > Bounds.MAX_TRANSPORT_NAME)
                throw new IllegalStateException("Invalid transport name");
        }
        if (relays.size() > Bounds.MAX_RELAYS)
            throw new IllegalStateException("Too many relays: " + relays.size());
        Bounds.checkPeerIds(relays);
        if (relaying && addrs.isEmpty())
            throw new IllegalStateException("A relay must list a direct address");
        if (endpoint.type() != PublicSigningKey.Type.Ed25519)
            throw new IllegalStateException("The endpoint key must be Ed25519");
        Bounds.checkCounter(counter);
    }

    public static ServerAddresses fromCbor(Cborable cbor) {
        CborObject.CborMap m = RoutingCbor.map(cbor, "ServerAddresses");
        CborObject.CborMap body = RoutingCbor.map(m.get("b"), "ServerAddresses body");
        return new ServerAddresses(PublicSigningKey.fromCbor(body.get("k")),
                PublicSigningKey.fromCbor(body.get("e")),
                body.getOptionalByteArray("q"),
                body.getList("a", Address::fromCbor),
                RoutingCbor.peerIds(body.get("r")),
                Mode.byName(body.getString("o")),
                body.getBoolean("y"),
                body.getLong("c"),
                m.getByteArray("s"));
    }
}
