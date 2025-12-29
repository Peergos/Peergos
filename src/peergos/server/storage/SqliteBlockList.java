package peergos.server.storage;

import peergos.server.sql.SqlSupplier;
import peergos.server.sql.SqliteCommands;
import peergos.server.util.Logging;
import peergos.server.util.Sqlite;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.util.ArrayOps;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class SqliteBlockList {
    private static final Logger LOG = Logging.LOG();
    private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS blocks (" +
            "username string, " +
            "hash bytes primary key not null, " +
            "version text); " +
                "CREATE UNIQUE INDEX IF NOT EXISTS hash_reachable_index ON blocks (hash, version);";

    private static final String INSERT_SUFFIX = "INTO blocks (username, hash, version) VALUES(?, ?, ?)";
    private static final String APPLY_TO_ALL_VERSIONS = "SELECT hash, version FROM blocks";
    private static final String HAS_BLOCK = "SELECT hash, version FROM blocks WHERE hash=? AND username=?;";
    private static final String GET_VERSIONS = "SELECT version FROM blocks WHERE hash=? AND username=?;";
    private static final String COUNT = "SELECT COUNT(*) FROM blocks";

    private final Supplier<Connection> conn;
    private final SqlSupplier cmds;
    public SqliteBlockList(Supplier<Connection> conn, SqlSupplier cmds) {
        this.conn = conn;
        this.cmds = cmds;
        init(cmds);
    }

    private Connection getConnection() {
        Connection connection = conn.get();
        try {
            connection.setAutoCommit(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            return connection;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Connection getNonCommittingConnection() {
        Connection connection = conn.get();
        try {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            return connection;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void init(SqlSupplier commands) {
        try (Connection conn = getConnection()) {
            commands.createTable(CREATE_TABLE, conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void addBlocks(List<UserBlockVersion> versions) {
        if (versions.isEmpty())
            return;
        try (Connection conn = getNonCommittingConnection();
             PreparedStatement insert = conn.prepareStatement(cmds.insertOrIgnoreCommand("INSERT ", INSERT_SUFFIX));
        ) {
            List<UserBlockVersion> distinct = versions.stream()
                    .distinct()
                    .collect(Collectors.toList());

            for (UserBlockVersion version : distinct) {
                insert.setString(1, version.username);
                insert.setBytes(2, version.cid.toBytes());
                insert.setString(3, version.version);
                insert.addBatch();
            }
            int[] inserted = insert.executeBatch();
            conn.commit();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    public synchronized boolean hasBlock(String username, Cid block) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(HAS_BLOCK)) {
            stmt.setBytes(1, block.toBytes());
            stmt.setString(2, username);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public synchronized List<String> getVersions(String username, Cid block) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_VERSIONS)) {
            stmt.setBytes(1, block.toBytes());
            stmt.setString(2, username);
            ResultSet rs = stmt.executeQuery();
            List<String> res = new ArrayList<>();
            while (rs.next()) {
                res.add(rs.getString(1));
            }
            return res;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public synchronized void applyToAllVersions(Consumer<List<BlockVersion>> out) {
        try (Connection conn = getConnection();
             PreparedStatement update = conn.prepareStatement(APPLY_TO_ALL_VERSIONS)) {
            ResultSet res = update.executeQuery();
            ArrayList<BlockVersion> buf = new ArrayList<>();
            while (res.next()) {
                buf.add(new BlockVersion(Cid.cast(res.getBytes(1)), res.getString(2), false));
                if (buf.size() == 1000) {
                    out.accept(buf);
                    buf.clear();
                }
            }
            out.accept(buf);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public synchronized long size() {
        try (Connection conn = getConnection();
             PreparedStatement query = conn.prepareStatement(COUNT)) {
            ResultSet res = query.executeQuery();
            res.next();
            return res.getLong(1);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public static SqliteBlockList createBlockListDb(Path dbFile) {
        try {
            Connection file = Sqlite.build(dbFile.toString());
            // We need a connection that ignores close
            Connection instance = new Sqlite.UncloseableConnection(file);
            return new SqliteBlockList(() -> instance, new SqliteCommands());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] a) throws IOException {
        // This is a benchmark to test baseline speed of a blockstore partition
        String filename = System.nanoTime() + "temp.sql";
        Path file = Path.of(filename);
        SqliteBlockList blocksDb = createBlockListDb(file);
        List<UserBlockVersion> versions = new ArrayList<>();
        int count = 1_000_000;
        boolean versioned = true;
        Random rnd = new Random(28);

        for (int i = 0; i < count; i++) {
            byte[] hash = new byte[32];
            rnd.nextBytes(hash);
            Cid cid = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
            if (versioned)
                versions.add(new UserBlockVersion(null, cid, ArrayOps.bytesToHex(hash), true));
            else
                versions.add(new UserBlockVersion(null, cid, null, true));
        }
        System.out.println("Starting Db load...");
        long t0 = System.nanoTime();
        int batchSize = 10_000;
        for (int i = 0; i < count / batchSize; i++) {
            blocksDb.addBlocks(versions.subList(i * batchSize, (i+1)* batchSize));
            long size = blocksDb.size();
            if (size != (i +1) * batchSize)
                throw new IllegalStateException("Incorrect size: " + size + ", expected " + (i+1)*batchSize);
        }
        long t1 = System.nanoTime();
        System.out.println("Load duration " + (t1-t0)/1_000_000_000 + "s, batch size = " + batchSize);

        long size = blocksDb.size();
        if (size != count)
            throw new IllegalStateException("Missing rows! " + size + ", expected " + count);

        // put the same block version in multiple times (should be idempotent)
        long priorSize = blocksDb.size();
        byte[] hash1 = new byte[32];
        rnd.nextBytes(hash1);
        Cid cid1 = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash1);
        UserBlockVersion v1 = new UserBlockVersion(null, cid1, ArrayOps.bytesToHex(hash1), true);
        blocksDb.addBlocks(Arrays.asList(v1, v1, v1, v1, v1, v1, v1, v1, v1, v1));

        try {
            blocksDb.addBlocks(Arrays.asList(v1));
        } catch (Exception e) {}
        long with1Block = blocksDb.size();
        if (with1Block != priorSize + 1)
            throw new IllegalStateException("Adding not idempotent!");
    }
}
