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

    /** Two writes that would each fit alone can't both be reserved where only one fits. */
    @Test
    public void aReservationIsOneStep() {
        UserUsage usage = new UserUsage(700);
        PublicKeyHash a = key(), b = key();
        Assert.assertTrue(usage.reserve(a, 200, 1000, 0));
        Assert.assertFalse(usage.reserve(b, 200, 1000, 0));
        Assert.assertEquals("the refused write reserved nothing", 900, usage.expectedUsage());
        Assert.assertEquals("the first reservation stands", 200, usage.getPending(a));
        // refused once, the usage is errored and the tolerance applies
        Assert.assertTrue(usage.reserve(b, 200, 1000, 200));
    }

    /** A writer's own commit that changes its owned keys has landed: its bytes in flight go from every cap. */
    @Test
    public void aWritersOwnCommitClearsItsBytesInFlight() throws Exception {
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        WriterQuotas quotas = new WriterQuotas(store);
        PublicKeyHash cap = key(), other = key();
        quotas.getUsage(cap).addPending(cap, 300);
        quotas.getUsage(cap).addPending(other, 200);
        quotas.usageChanged(cap, 300, true);
        Assert.assertEquals("the committing writer's bytes are cleared, the other's are kept", 200, quotas.getUsage(cap).expectedUsage());
    }

    /** Bytes reserved for a write that never lands expire, rather than holding a cap for good. */
    @Test
    public void staleReservationsExpire() {
        UserUsage usage = new UserUsage(50);
        PublicKeyHash writer = key();
        usage.addPending(writer, 100);
        usage.reloadTotal(50, System.currentTimeMillis() - 60_000);
        Assert.assertEquals("a recent reservation is kept", 150, usage.expectedUsage());
        usage.reloadTotal(50, System.currentTimeMillis() + 1);
        Assert.assertEquals("an old one goes", 50, usage.expectedUsage());
    }

    /** Giving back a reservation a refusal already dropped doesn't go below nothing. */
    @Test
    public void aReleaseNeverGoesNegative() {
        UserUsage usage = new UserUsage(0);
        PublicKeyHash writer = key();
        usage.addPending(writer, 100);
        usage.releasePending(writer, 300);
        Assert.assertEquals(0, usage.expectedUsage());
        Assert.assertEquals(0, usage.getPending(writer));
    }
}
