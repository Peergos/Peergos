package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;

import java.sql.*;
import java.util.*;

public class JdbcUsageStoreTests {

    @Test
    public void ownedKeys() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        String username = "bob";
        store.addUserIfAbsent(username);
        PublicKeyHash owner = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter(username, owner);
        PublicKeyHash writer = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter(username, writer);
        store.updateWriterUsage(owner, MaybeMultihash.empty(), Collections.emptySet(), Set.of(writer), 0);

        Set<PublicKeyHash> allWriters = store.getAllWriters(owner);
        Assert.assertTrue(allWriters.size() == 2);
        Assert.assertTrue(allWriters.contains(writer));

        int usageDelta = 1_000_000_000;
        store.confirmUsage(username, owner, usageDelta, false);
        Map<String, Long> allUsage = store.getAllUsage();
        Assert.assertTrue(allUsage.get(username) == usageDelta);
    }
}
