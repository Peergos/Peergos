package peergos.server.tests;

import io.ipfs.multihash.Multihash;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.PrivKey;
import org.junit.Assert;
import org.junit.Test;
import org.peergos.HostBuilder;
import org.peergos.RamAddressBook;
import org.peergos.protocol.ipns.IPNS;
import org.peergos.protocol.ipns.IpnsMapping;
import org.peergos.protocol.ipns.IpnsRecord;
import peergos.server.JdbcRecordLRU;
import peergos.shared.util.Triple;

import java.time.LocalDateTime;
import java.util.Optional;

public class JdbcRecordStoreTests {

    private Triple<PrivKey, PeerId, Multihash> randomId() {
        PrivKey priv = new HostBuilder(new RamAddressBook()).generateIdentity().getPrivateKey();
        PeerId peerId = PeerId.fromPubKey(priv.publicKey());
        Multihash publisher = Multihash.deserialize(peerId.getBytes());
        return new Triple<>(priv, peerId, publisher);
    }

    private IpnsRecord createRecord(PrivKey priv, Multihash publisher) {
        byte[] signedRecord = IPNS.createSignedRecord("G'day mate!".getBytes(),
                LocalDateTime.now().plusMonths(1), 56,
                365*86400_000_000_000L,
                Optional.empty(),
                Optional.empty(),
                priv);
        byte[] key = IPNS.getKey(publisher);
        Optional<IpnsMapping> parsed = IPNS.parseAndValidateIpnsEntry(key, signedRecord);
        return parsed.get().value;
    }

    @Test
    public void lru() {
        JdbcRecordLRU lru = JdbcRecordLRU.buildSqlite(10, ":memory:");
        Triple<PrivKey, PeerId, Multihash> id = randomId();
        PrivKey priv = id.left;
        Multihash publisher = id.right;
        IpnsRecord record = createRecord(priv, publisher);

        for (int i=0; i < 10; i++) {
            Assert.assertEquals(i, lru.size());
            io.ipfs.multihash.Multihash peer = randomId().right;
            lru.put(peer, record);
        }
        lru.put(randomId().right, record);
        Assert.assertEquals(8, lru.size());
    }

    @Test
    public void overwrite() {
        JdbcRecordLRU lru = JdbcRecordLRU.buildSqlite(10, ":memory:");
        Triple<PrivKey, PeerId, Multihash> id = randomId();
        PrivKey priv = id.left;
        Multihash publisher = id.right;
        IpnsRecord record = createRecord(priv, publisher);
        lru.put(publisher, record);
        Assert.assertArrayEquals(record.raw, lru.get(publisher).get().raw);
        IpnsRecord record2 = createRecord(priv, publisher);
        lru.put(publisher, record2);
        Assert.assertArrayEquals(record2.raw, lru.get(publisher).get().raw);
    }

    @Test
    public void remove() {
        JdbcRecordLRU lru = JdbcRecordLRU.buildSqlite(10, ":memory:");
        Triple<PrivKey, PeerId, Multihash> id = randomId();
        PrivKey priv = id.left;
        Multihash publisher = id.right;
        IpnsRecord record = createRecord(priv, publisher);
        lru.put(publisher, record);
        Assert.assertArrayEquals(record.raw, lru.get(publisher).get().raw);
        lru.remove(publisher);
        Assert.assertEquals(Optional.empty(), lru.get(publisher));
    }
}
