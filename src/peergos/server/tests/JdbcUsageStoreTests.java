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
        store.updateWriterUsage(owner, MaybeMultihash.empty(), Collections.emptySet(), Set.of(owner), 0);

        Set<PublicKeyHash> allWriters = store.getAllWriters(owner);
        Assert.assertTrue(allWriters.size() == 2);
        Assert.assertTrue(allWriters.contains(writer));

        Set<PublicKeyHash> byName = store.getAllWriters(username);
        Assert.assertTrue(byName.size() == 2);
        Assert.assertTrue(byName.contains(owner));
        Assert.assertTrue(byName.contains(writer));

        int usageDelta = 1_000_000_000;
        store.confirmUsage(username, owner, usageDelta, false);
        Map<String, Long> allUsage = store.getAllUsage();
        Assert.assertTrue(allUsage.get(username) == usageDelta);

        // Now delete the user
        Assert.assertFalse(store.getAllWriters().isEmpty());
        store.removeUser(username);
        Set<PublicKeyHash> empty = store.getAllWriters();
        Assert.assertTrue(empty.isEmpty());
    }

    /** A user added with addUserIfAbsent but no writers yet should have usage 0, not -1 */
    @Test
    public void usageNotNegativeForUserWithNoWriters() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        String username = "alice";
        store.addUserIfAbsent(username);
        // No addWriter call - user has a userusage row but no pendingusage rows.
        // The INNER JOIN in getUsage() returns no rows so totalBytes stays at -1 sentinel.
        UserUsage usage = store.getUsage(username);
        Assert.assertFalse("Usage should not be negative for a new user with no writers; was: " + usage.totalUsage(),
                usage.totalUsage() < 0);
        Assert.assertEquals("Expected 0 bytes used for new user", 0, usage.totalUsage());
    }

    /** Confirmed usage stored in userusage must be readable even after pending bytes are reset to zero */
    @Test
    public void storedUsageVisibleWithZeroPending() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        String username = "carol";
        store.addUserIfAbsent(username);
        PublicKeyHash owner = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter(username, owner);
        long size = 5_000_000L;
        store.confirmUsage(username, owner, size, false);
        // confirmUsage resets pending to 0 for this writer; getUsage must still return total_bytes
        UserUsage usage = store.getUsage(username);
        Assert.assertEquals("Confirmed usage should be returned correctly", size, usage.totalUsage());
        Assert.assertFalse("Usage must not be negative", usage.totalUsage() < 0);
    }

    /** Multiple sequential confirmUsage calls accumulate correctly */
    @Test
    public void usageSummedAcrossWriters() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        String username = "dave";
        store.addUserIfAbsent(username);
        PublicKeyHash owner = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        PublicKeyHash writer = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter(username, owner);
        store.addWriter(username, writer);
        long ownerSize = 3_000_000L;
        long writerSize = 2_000_000L;
        store.confirmUsage(username, owner, ownerSize, false);
        store.confirmUsage(username, writer, writerSize, false);
        UserUsage usage = store.getUsage(username);
        Assert.assertEquals("Total usage should be sum of all writers", ownerSize + writerSize, usage.totalUsage());
    }

    /** Deleting all data (applying negative delta equal to stored size) should give 0, not negative */
    @Test
    public void deletingDataDoesNotMakeUsageNegative() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        String username = "eve";
        store.addUserIfAbsent(username);
        PublicKeyHash owner = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter(username, owner);
        long size = 4_000_000L;
        store.confirmUsage(username, owner, size, false);
        store.confirmUsage(username, owner, -size, false);
        UserUsage usage = store.getUsage(username);
        Assert.assertEquals("Usage should be 0 after deleting all data", 0, usage.totalUsage());
        Assert.assertFalse("Usage must not be negative after delete", usage.totalUsage() < 0);
    }
}
