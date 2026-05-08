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
        store.updateWriterUsageAtomically(owner, MaybeMultihash.empty(), MaybeMultihash.empty(), Collections.emptySet(), Set.of(writer), 0, 0, false);
        store.updateWriterUsageAtomically(owner, MaybeMultihash.empty(), MaybeMultihash.empty(), Collections.emptySet(), Set.of(owner), 0, 0, false);

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

    /** CAS from NULL target succeeds and updates total_bytes atomically */
    @Test
    public void atomicUpdateFromNullSucceeds() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        store.addUserIfAbsent("alice");
        PublicKeyHash writer = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter("alice", writer);
        MaybeMultihash newTarget = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{1}));

        boolean updated = store.updateWriterUsageAtomically(writer, MaybeMultihash.empty(), newTarget,
                Collections.emptySet(), Collections.emptySet(), 1000, 1000, false);

        Assert.assertTrue(updated);
        Assert.assertEquals(newTarget, store.getUsage(writer).target());
        Assert.assertEquals(1000, store.getUsage(writer).directRetainedStorage());
        Assert.assertEquals(1000, store.getUsage("alice").totalUsage());
    }

    /** CAS fails when provided old target doesn't match stored target; DB is unchanged */
    @Test
    public void atomicUpdateCasFailsOnWrongOldTarget() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        store.addUserIfAbsent("alice");
        PublicKeyHash writer = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter("alice", writer);
        MaybeMultihash target1 = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{1}));
        MaybeMultihash target2 = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{2}));
        store.updateWriterUsageAtomically(writer, MaybeMultihash.empty(), target1,
                Collections.emptySet(), Collections.emptySet(), 1000, 1000, false);

        // Supply wrong old target (empty instead of target1)
        boolean updated = store.updateWriterUsageAtomically(writer, MaybeMultihash.empty(), target2,
                Collections.emptySet(), Collections.emptySet(), 500, 500, false);

        Assert.assertFalse(updated);
        Assert.assertEquals(target1, store.getUsage(writer).target());
        Assert.assertEquals(1000, store.getUsage(writer).directRetainedStorage());
        Assert.assertEquals(1000, store.getUsage("alice").totalUsage());
    }

    /** Successful CAS update from one non-null target to another */
    @Test
    public void atomicUpdateFromNonNullSucceeds() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        store.addUserIfAbsent("alice");
        PublicKeyHash writer = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter("alice", writer);
        MaybeMultihash target1 = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{1}));
        MaybeMultihash target2 = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{2}));
        store.updateWriterUsageAtomically(writer, MaybeMultihash.empty(), target1,
                Collections.emptySet(), Collections.emptySet(), 1000, 1000, false);

        boolean updated = store.updateWriterUsageAtomically(writer, target1, target2,
                Collections.emptySet(), Collections.emptySet(), 1500, 500, false);

        Assert.assertTrue(updated);
        Assert.assertEquals(target2, store.getUsage(writer).target());
        Assert.assertEquals(1500, store.getUsage(writer).directRetainedStorage());
        Assert.assertEquals(1500, store.getUsage("alice").totalUsage());
    }

    /** Deleting a writer atomically clears target and subtracts its size from total */
    @Test
    public void atomicDeleteResetsToZero() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        store.addUserIfAbsent("alice");
        PublicKeyHash writer = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter("alice", writer);
        MaybeMultihash target1 = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{1}));
        store.updateWriterUsageAtomically(writer, MaybeMultihash.empty(), target1,
                Collections.emptySet(), Collections.emptySet(), 1000, 1000, false);

        boolean updated = store.updateWriterUsageAtomically(writer, target1, MaybeMultihash.empty(),
                Collections.emptySet(), Collections.emptySet(), 0, -1000, false);

        Assert.assertTrue(updated);
        Assert.assertEquals(MaybeMultihash.empty(), store.getUsage(writer).target());
        Assert.assertEquals(0, store.getUsage(writer).directRetainedStorage());
        Assert.assertEquals(0, store.getUsage("alice").totalUsage());
    }

    /** Pending bytes are cleared to zero on a successful atomic update */
    @Test
    public void pendingUsageResetOnAtomicUpdate() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        store.addUserIfAbsent("alice");
        PublicKeyHash writer = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter("alice", writer);
        store.addPendingUsage("alice", writer, 500);
        Assert.assertEquals(500, store.getUsage("alice").getPending(writer));
        MaybeMultihash target1 = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{1}));

        store.updateWriterUsageAtomically(writer, MaybeMultihash.empty(), target1,
                Collections.emptySet(), Collections.emptySet(), 1000, 1000, false);

        Assert.assertEquals(0, store.getUsage("alice").getPending(writer));
    }

    /** Pending bytes are preserved when CAS fails */
    @Test
    public void pendingUsageNotResetOnFailedCas() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        store.addUserIfAbsent("alice");
        PublicKeyHash writer = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter("alice", writer);
        MaybeMultihash target1 = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{1}));
        MaybeMultihash target2 = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{2}));
        store.updateWriterUsageAtomically(writer, MaybeMultihash.empty(), target1,
                Collections.emptySet(), Collections.emptySet(), 1000, 1000, false);
        store.addPendingUsage("alice", writer, 500);

        // CAS with wrong old target
        store.updateWriterUsageAtomically(writer, MaybeMultihash.empty(), target2,
                Collections.emptySet(), Collections.emptySet(), 500, 500, false);

        Assert.assertEquals(500, store.getUsage("alice").getPending(writer));
    }

    /** Owned keys added in atomic update are visible via getUsage */
    @Test
    public void atomicUpdateAddsOwnedKey() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        store.addUserIfAbsent("alice");
        PublicKeyHash writer = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        PublicKeyHash owned = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter("alice", writer);
        store.addWriter("alice", owned);
        MaybeMultihash target1 = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{1}));

        store.updateWriterUsageAtomically(writer, MaybeMultihash.empty(), target1,
                Collections.emptySet(), Set.of(owned), 1000, 1000, false);

        Assert.assertTrue(store.getUsage(writer).ownedKeys().contains(owned));
    }

    /** Owned keys removed in atomic update are no longer visible via getUsage */
    @Test
    public void atomicUpdateRemovesOwnedKey() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        store.addUserIfAbsent("alice");
        PublicKeyHash writer = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        PublicKeyHash owned = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter("alice", writer);
        store.addWriter("alice", owned);
        MaybeMultihash target1 = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{1}));
        MaybeMultihash target2 = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{2}));
        store.updateWriterUsageAtomically(writer, MaybeMultihash.empty(), target1,
                Collections.emptySet(), Set.of(owned), 1000, 1000, false);

        store.updateWriterUsageAtomically(writer, target1, target2,
                Set.of(owned), Collections.emptySet(), 1500, 500, false);

        Assert.assertFalse(store.getUsage(writer).ownedKeys().contains(owned));
    }

    /** Owned keys are not changed when the CAS fails */
    @Test
    public void ownedKeysNotModifiedOnFailedCas() throws Exception {
        Crypto crypto = Main.initCrypto();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore store = new JdbcUsageStore(() -> db, new SqliteCommands());
        store.addUserIfAbsent("alice");
        PublicKeyHash writer = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        PublicKeyHash owned = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        store.addWriter("alice", writer);
        store.addWriter("alice", owned);
        MaybeMultihash target1 = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{1}));
        MaybeMultihash target2 = MaybeMultihash.of(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[]{2}));
        store.updateWriterUsageAtomically(writer, MaybeMultihash.empty(), target1,
                Collections.emptySet(), Set.of(owned), 1000, 1000, false);

        // CAS with wrong old target - should not remove the owned key
        store.updateWriterUsageAtomically(writer, MaybeMultihash.empty(), target2,
                Set.of(owned), Collections.emptySet(), 1500, 500, false);

        Assert.assertTrue(store.getUsage(writer).ownedKeys().contains(owned));
    }
}
