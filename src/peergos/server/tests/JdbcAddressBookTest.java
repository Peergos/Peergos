package peergos.server.tests;

import io.libp2p.core.PeerId;
import io.libp2p.core.multiformats.Multiaddr;
import org.junit.Assert;
import org.junit.Test;
import peergos.server.JdbcAddressLRU;

import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;

public class JdbcAddressBookTest {

    @Test
    public void lru() {
        JdbcAddressLRU lru = JdbcAddressLRU.buildSqlite(10, ":memory:");
        Multiaddr addr = new Multiaddr("/ip4/127.0.0.0/tcp/8000");
        HashMap<PeerId, Multiaddr> ram = new HashMap<>();
        for (int i=0; i < 10; i++) {
            Assert.assertEquals(i, lru.size());
            PeerId peer = PeerId.random();
            lru.setAddrs(peer, 0, addr);
            ram.put(peer, addr);
        }
        lru.setAddrs(PeerId.random(), 0, addr);
        Assert.assertEquals(8, lru.size());
    }

    @Test
    public void add() {
        JdbcAddressLRU lru = JdbcAddressLRU.buildSqlite(10, ":memory:");
        Multiaddr addr1 = new Multiaddr("/ip4/127.0.0.0/tcp/8000");
        PeerId peer = PeerId.random();
        lru.setAddrs(peer, 0, addr1);
        Assert.assertEquals(Set.of(addr1), lru.getAddrs(peer).join().stream().collect(Collectors.toSet()));
        Multiaddr addr2 = new Multiaddr("/ip4/127.0.0.0/tcp/9000");
        lru.addAddrs(peer, 0, addr2);
        Assert.assertEquals(Set.of(addr1, addr2), lru.getAddrs(peer).join().stream().collect(Collectors.toSet()));
    }

    @Test
    public void set() {
        JdbcAddressLRU lru = JdbcAddressLRU.buildSqlite(10, ":memory:");
        Multiaddr addr1 = new Multiaddr("/ip4/127.0.0.0/tcp/8000");
        PeerId peer = PeerId.random();
        lru.setAddrs(peer, 0, addr1);
        Assert.assertEquals(Set.of(addr1), lru.getAddrs(peer).join().stream().collect(Collectors.toSet()));
        Multiaddr addr2 = new Multiaddr("/ip4/127.0.0.0/tcp/9000");
        lru.setAddrs(peer, 0, addr2);
        Assert.assertEquals(Set.of(addr2), lru.getAddrs(peer).join().stream().collect(Collectors.toSet()));
    }
}
