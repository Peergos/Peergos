package peergos.server.tests;

import io.libp2p.core.PeerId;
import io.libp2p.core.multiformats.Multiaddr;
import org.junit.*;
import peergos.server.*;
import peergos.server.routing.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public class HintTests {
    private static final String PEER = "12D3KooWFeNXWhKkHecwJZGZEanQbniMs57XhJMvMhbXZkN4hPmF";

    @Test
    public void hintsCarryIpLiteralsRelaysAndOnionsButNoNames() {
        for (String ok : List.of("/ip4/203.0.113.7/tcp/4001", "/ip6/2001:db8::1/udp/4001/quic-v1",
                "/ip4/203.0.113.7/tcp/4001/p2p/" + PEER + "/p2p-circuit",
                "/onion3/vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd:4001"))
            Assert.assertTrue(ok, ServerHint.isAllowed(ok));
        for (String bad : List.of("/dns4/peergos.net/tcp/443", "/dnsaddr/peergos.net", "/dns/x/tcp/1",
                "/ip4/peergos.net/tcp/4001", "/ip4/1.2.3/tcp/4001", "/ip4/1.2.3.4/tcp/70000", "/ip4/1.2.3.4/ws",
                "/tcp/4001", "ip4/1.2.3.4/tcp/4001", "/ip4/1.2.3.4/tcp"))
            Assert.assertFalse(bad, ServerHint.isAllowed(bad));
        try {
            new ServerHint(Cid.decodePeerId(PEER), List.of("/dns4/peergos.net/tcp/443"));
            Assert.fail("a hint with a DNS name");
        } catch (IllegalStateException expected) {}

        ServerHint hint = new ServerHint(Cid.decodePeerId(PEER), List.of("/ip4/203.0.113.7/tcp/4001"));
        ServerHint back = ServerHint.fromCbor(CborObject.fromByteArray(hint.serialize()));
        Assert.assertEquals(hint.peerId, back.peerId);
        Assert.assertEquals(hint.addrs, back.addrs);
    }

    @Test
    public void aPrivateAddressIsDialledOnlyFromTheSameKindOfNetwork() throws Exception {
        List<InetAddress> home = List.of(InetAddress.getByName("192.168.1.20"), InetAddress.getByName("203.0.113.9"));
        Assert.assertTrue(Hints.mayDial("/ip4/198.51.100.1/tcp/4001", home));
        Assert.assertTrue(Hints.mayDial("/ip4/192.168.1.30/tcp/4001", home));
        Assert.assertFalse(Hints.mayDial("/ip4/10.0.0.5/tcp/4001", home));
        Assert.assertFalse(Hints.mayDial("/ip4/127.0.0.1/tcp/4001", home));
        Assert.assertFalse(Hints.mayDial("/ip4/0.0.0.0/tcp/4001", home));
        Assert.assertFalse(Hints.mayDial("/ip4/169.254.1.1/tcp/4001", home));
        Assert.assertFalse(Hints.mayDial("/ip6/::1/tcp/4001", home));
        Assert.assertFalse(Hints.mayDial("/ip6/fd00::5/tcp/4001", home));
        Assert.assertTrue(Hints.mayDial("/ip6/fd00::5/tcp/4001", List.of(InetAddress.getByName("fd12::1"))));
    }

    @Test
    public void lanAddressesExpandWhatTheServerListensOn() throws Exception {
        List<InetAddress> interfaces = List.of(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("192.168.1.20"),
                InetAddress.getByName("203.0.113.9"), InetAddress.getByName("fd12::1"));
        Assert.assertEquals(List.of("/ip4/192.168.1.20/tcp/4001", "/ip6/fd12:0:0:0:0:0:0:1/tcp/4001", "/ip4/192.168.1.20/udp/4001/quic-v1"),
                Hints.lanAddresses(List.of("/ip6/::/tcp/4001", "/ip4/0.0.0.0/udp/4001/quic-v1"), interfaces));
        Assert.assertEquals(List.of(), Hints.lanAddresses(List.of("/ip4/127.0.0.1/tcp/4001"), interfaces));
    }

    @Test
    public void hintsRankLastExpireAndSurviveReResolving() {
        AtomicLong now = new AtomicLong(1_000_000L);
        HintedAddressBook book = new HintedAddressBook(JdbcAddressLRU.buildSqlite(10, ":memory:"), now::get);
        PeerId peer = PeerId.random();
        Multiaddr known = new Multiaddr("/ip4/198.51.100.1/tcp/4001"), logged = new Multiaddr("/ip4/198.51.100.2/tcp/4001"),
                hinted = new Multiaddr("/ip4/198.51.100.3/tcp/4001");
        book.setLogged(p -> p.equals(peer) ? List.of(logged) : List.of());
        book.addHint(peer, List.of(hinted, known));
        Assert.assertEquals(List.of(logged, hinted, known), new ArrayList<>(book.getAddrs(peer).join()));
        book.setAddrs(peer, 0, known);
        Assert.assertEquals(List.of(known, logged, hinted), new ArrayList<>(book.getAddrs(peer).join()));
        now.addAndGet(HintedAddressBook.HINT_MILLIS + 1);
        Assert.assertEquals(List.of(known, logged), new ArrayList<>(book.getAddrs(peer).join()));
    }
}
