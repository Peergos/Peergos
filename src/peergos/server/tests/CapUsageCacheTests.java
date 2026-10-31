package peergos.server.tests;

import org.junit.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;

import java.sql.*;
import java.util.*;

/** The in memory usage of a capped writing space, and the bytes of writes in flight under it. */
public class CapUsageCacheTests {

    private static PublicKeyHash key() {
        byte[] hash = new byte[32];
        new Random().nextBytes(hash);
        return new PublicKeyHash(new Cid(Cid.V1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash));
    }

    /** An owned key change reloads a cap's committed usage. The bytes of writes admitted but not yet
     *  committed have to survive that, or a burst of writes could each be judged against the committed
     *  usage alone - and a writer under the cap can cause the change itself, by making a child writer. */
    @Test
    public void inFlightBytesSurviveAnOwnedKeyChange() throws Exception {
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        WriterQuotas quotas = new WriterQuotas(store);
        store.addUsageListener(quotas);
        String user = "alice";
        PublicKeyHash cap = key(), child = key();
        store.addUserIfAbsent(user);
        store.addWriter(user, cap);
        store.addWriter(user, child);

        UserUsage usage = quotas.getUsage(cap);
        usage.addPending(cap, 300);
        Assert.assertEquals(300, quotas.getUsage(cap).expectedUsage());

        store.addOwnedKey(cap, child);
        Assert.assertEquals("the bytes in flight are kept across the reload", 300, quotas.getUsage(cap).expectedUsage());
        Assert.assertSame("and stay on the same object, which the write in flight still holds", usage, quotas.getUsage(cap));
    }
}
