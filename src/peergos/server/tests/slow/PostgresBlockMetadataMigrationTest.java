package peergos.server.tests.slow;

import org.junit.*;
import peergos.server.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.*;

/** Rekeying a cid keyed blockmetadata table, on sqlite and, with -Dpg.port etc, on postgres or cockroachdb.
 *  Drops and recreates the blockmetadata tables, so point it at a throwaway database.
 */
public class PostgresBlockMetadataMigrationTest {
    private static final int NULL_ROWS = Integer.getInteger("null.rows", 250_000);
    private static final int OWNED_ROWS = 1_000;
    private static final Random r = new Random(42);

    private static boolean isSqlite() {
        return System.getProperty("pg.port") == null;
    }

    private static Supplier<Connection> connect() throws Exception {
        if (isSqlite()) {
            Path file = Files.createTempDirectory("peergos-metadb-migration").resolve("meta.sql");
            Connection conn = new Sqlite.UncloseableConnection(Sqlite.build(file.toString()));
            return () -> conn;
        }
        Args a = Args.parse(new String[0], Optional.empty(), false)
                .with("metadb.postgres.host", System.getProperty("pg.host", "localhost"))
                .with("metadb.postgres.port", System.getProperty("pg.port"))
                .with("metadb.postgres.database", System.getProperty("pg.database", "peergostest"))
                .with("metadb.postgres.username", System.getProperty("pg.user", "testuser"))
                .with("metadb.postgres.password", System.getProperty("pg.password", "testpassword"));
        return Builder.getPostgresConnector(a, "metadb.");
    }

    private static SqlSupplier commands() {
        return isSqlite() ? new SqliteCommands() : new PostgresCommands();
    }

    private static String bytes() {
        return isSqlite() ? "blob" : "BYTEA";
    }

    private static void exec(Supplier<Connection> db, String sql) throws SQLException {
        try (Connection c = db.get(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static long count(Supplier<Connection> db, String sql) throws SQLException {
        try (Connection c = db.get(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Cid randomCid() {
        byte[] hash = new byte[32];
        r.nextBytes(hash);
        return new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
    }

    /** A table as the old code created it, with its primary key under the given name */
    private static List<Cid> createOldTable(Supplier<Connection> db, String primaryKeyName, PublicKeyHash owner) throws SQLException {
        exec(db, "DROP TABLE IF EXISTS blockmetadata;");
        exec(db, "DROP TABLE IF EXISTS blockmetadatarelisted;");
        String key = primaryKeyName == null ? "" : "CONSTRAINT \"" + primaryKeyName + "\" ";
        exec(db, "CREATE TABLE blockmetadata (owner " + bytes() + ", cid " + bytes() + " not null, version varchar(160), " +
                "size integer not null, links " + bytes() + " not null, batids " + bytes() + " not null, " + key + "PRIMARY KEY (cid));");
        Assert.assertEquals(1, count(db, commands().blockMetadataPrimaryKeySizeCommand()));

        byte[] emptyList = new CborObject.CborList(Collections.emptyList()).toByteArray();
        List<Cid> owned = new ArrayList<>();
        int rows = NULL_ROWS + OWNED_ROWS, batch = 1000;
        for (int start = 0; start < rows; start += batch) {
            int n = Math.min(batch, rows - start);
            try (Connection c = db.get();
                 PreparedStatement insert = c.prepareStatement("INSERT INTO blockmetadata (owner, cid, version, size, links, batids) VALUES " +
                         SqlSupplier.valuePlaceholders(6, n) + ";")) {
                for (int i = 0; i < n; i++) {
                    boolean isOwned = (start + i) % ((rows / OWNED_ROWS)) == 0 && owned.size() < OWNED_ROWS;
                    Cid cid = randomCid();
                    if (isOwned)
                        owned.add(cid);
                    insert.setBytes(i * 6 + 1, isOwned ? owner.toBytes() : null);
                    insert.setBytes(i * 6 + 2, cid.toBytes());
                    insert.setString(i * 6 + 3, isOwned ? "v1" : null);
                    insert.setLong(i * 6 + 4, 10);
                    insert.setBytes(i * 6 + 5, emptyList);
                    insert.setBytes(i * 6 + 6, emptyList);
                }
                insert.executeUpdate();
            }
        }
        Assert.assertEquals(rows, count(db, "SELECT COUNT(*) FROM blockmetadata;"));
        return owned;
    }

    private static void assertMigrated(Supplier<Connection> db, List<Cid> owned, PublicKeyHash owner) throws Exception {
        long t0 = System.currentTimeMillis();
        BlockMetadataStore store = new JdbcBlockMetadataStore(db, commands());
        System.out.println("Migration took " + (System.currentTimeMillis() - t0) + "ms");
        Assert.assertEquals("rekeyed by owner and cid", 2, count(db, commands().blockMetadataPrimaryKeySizeCommand()));
        Assert.assertEquals("legacy rows are dropped", owned.size(), count(db, "SELECT COUNT(*) FROM blockmetadata;"));
        Assert.assertEquals(owned.size(), store.getAll(owner, owned).size());
        if (! isSqlite())
            Assert.assertEquals("the old key isn't kept as a unique index", 0,
                    count(db, "SELECT COUNT(*) FROM information_schema.table_constraints " +
                            "WHERE table_name = 'blockmetadata' AND constraint_type = 'UNIQUE';"));
        try (Connection c = db.get();
             PreparedStatement insert = c.prepareStatement("INSERT INTO blockmetadata (owner, cid, size, links, batids) VALUES (null, ?, 1, ?, ?);")) {
            insert.setBytes(1, randomCid().toBytes());
            insert.setBytes(2, new byte[1]);
            insert.setBytes(3, new byte[1]);
            insert.executeUpdate();
            Assert.fail("owner must be not null");
        } catch (SQLException expected) {}

        PublicKeyHash other = new PublicKeyHash(randomCid());
        Cid shared = owned.get(0);
        store.put(other, shared, null, new BlockMetadata(10, Collections.emptyList(), Collections.emptyList()));
        Assert.assertTrue("a second owner gets its own row", store.get(other, shared).isPresent());
        store.remove(owner, List.of(shared));
        Assert.assertTrue(store.get(owner, shared).isEmpty());
        Assert.assertTrue("another owner's row survives", store.get(other, shared).isPresent());
        store.setVersion(other, shared, "v2");

        Assert.assertTrue(store.needsBlockstoreListing(owner));
        store.blockstoreListed(owner);
        Assert.assertFalse(store.needsBlockstoreListing(owner));
        Assert.assertTrue(store.needsBlockstoreListing(other));

        BlockMetadataStore reopened = new JdbcBlockMetadataStore(db, commands());
        Assert.assertEquals(2, count(db, commands().blockMetadataPrimaryKeySizeCommand()));
        Assert.assertTrue(reopened.get(other, shared).isPresent());
        Assert.assertFalse(reopened.needsBlockstoreListing(owner));
        Assert.assertTrue(reopened.needsBlockstoreListing(other));
    }

    @Test
    public void migratesCidKeyedTable() throws Exception {
        Supplier<Connection> db = connect();
        List<String> names = isSqlite() ? Collections.singletonList(null) : List.of("blockmetadata_pkey", "primary");
        for (String name : names) {
            System.out.println("Old primary key name: " + name);
            PublicKeyHash owner = new PublicKeyHash(randomCid());
            List<Cid> owned = createOldTable(db, name, owner);
            assertMigrated(db, owned, owner);
        }
    }

    /** A migration that stopped after deleting the legacy rows and setting not null, but before rekeying */
    @Test
    public void resumesAPartialMigration() throws Exception {
        Assume.assumeFalse("sqlite rekeys in one transaction", isSqlite());
        Supplier<Connection> db = connect();
        PublicKeyHash owner = new PublicKeyHash(randomCid());
        List<Cid> owned = createOldTable(db, "primary", owner);
        exec(db, "CREATE TABLE IF NOT EXISTS blockmetadatarelisted (owner BYTEA primary key not null);");
        exec(db, "DELETE FROM blockmetadata WHERE owner IS NULL;");
        exec(db, "ALTER TABLE blockmetadata ALTER COLUMN owner SET NOT NULL;");
        assertMigrated(db, owned, owner);
    }

    @Test
    public void aNewTableNeedsNoMigration() throws Exception {
        Supplier<Connection> db = connect();
        exec(db, "DROP TABLE IF EXISTS blockmetadata;");
        exec(db, "DROP TABLE IF EXISTS blockmetadatarelisted;");
        BlockMetadataStore fresh = new JdbcBlockMetadataStore(db, commands());
        Assert.assertEquals(2, count(db, commands().blockMetadataPrimaryKeySizeCommand()));
        Assert.assertFalse(fresh.needsBlockstoreListing(new PublicKeyHash(randomCid())));
    }
}
