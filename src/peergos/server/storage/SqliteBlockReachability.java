package peergos.server.storage;

import peergos.server.sql.*;
import peergos.server.util.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

public class SqliteBlockReachability {
    private static final Logger LOG = peergos.server.util.Logging.LOG();
    private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS reachability (" +
            "hash bytes not null, " +
            "version text, " +
            "latest boolean not null," +
            "reachable boolean not null); " +
                "CREATE UNIQUE INDEX IF NOT EXISTS hash_reachable_index ON reachability (hash, version);";

    private static final String SET_REACHABLE = "UPDATE reachability SET reachable=true WHERE hash = ? AND latest = true";
    private static final String INSERT_SUFFIX = "INTO reachability (hash, version, latest, reachable) VALUES(?, ?, ?, false)";
    private static final String UNREACHABLE = "SELECT hash, version FROM reachability WHERE reachable = false";
    private static final String COUNT = "SELECT COUNT(*) FROM reachability";

    private final Supplier<Connection> conn;
    private final SqlSupplier cmds;
    public SqliteBlockReachability(Supplier<Connection> conn, SqlSupplier cmds) {
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

    public synchronized void addBlocks(List<BlockVersion> versions) {
        try (Connection conn = getNonCommittingConnection();
             PreparedStatement insert = conn.prepareStatement(cmds.insertOrIgnoreCommand("INSERT ", INSERT_SUFFIX))) {
            List<BlockVersion> distinct = versions.stream().distinct().collect(Collectors.toList());
            for (BlockVersion version : distinct) {
                insert.setBytes(1, version.cid.toBytes());
                insert.setString(2, version.version);
                insert.setBoolean(3, version.isLatest);
                insert.addBatch();
            }
            int[] changed = insert.executeBatch();
            conn.commit();
            if (IntStream.of(changed).sum() < distinct.size())
                throw new IllegalStateException("Couldn't insert blocks!");
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    public synchronized void setReachable(List<Cid> blocks, AtomicLong totalReachable) {
        if (blocks.isEmpty())
            return;
        try (Connection conn = getNonCommittingConnection();
             PreparedStatement update = conn.prepareStatement(SET_REACHABLE)) {
            for (Cid block : blocks) {
                update.setBytes(1, block.toBytes());
                update.addBatch();
            }
            int[] res = update.executeBatch();
            int changed = IntStream.of(res).sum();
            totalReachable.addAndGet(changed);
            if (changed > 0)
                conn.commit();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public long size() {
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

    public void getUnreachable(Consumer<List<BlockVersion>> toDelete) {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(UNREACHABLE)) {
            ResultSet res = select.executeQuery();
            int batchSize = 1000;
            List<BlockVersion> tmp = new ArrayList<>(batchSize);
            while (res.next()) {
                tmp.add(new BlockVersion(Cid.cast(res.getBytes(1)), res.getString(2), false));
                if (tmp.size() % batchSize == 0) {
                    toDelete.accept(tmp);
                    tmp = new ArrayList<>(batchSize);
                }
            }
            if (! tmp.isEmpty())
                toDelete.accept(tmp);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    public static SqliteBlockReachability createReachabilityDb(Path dbFile) {
        try {
            if (Files.exists(dbFile))
                Files.delete(dbFile);
            Connection memory = Sqlite.build(dbFile.toString());
            // We need a connection that ignores close
            Connection instance = new Sqlite.UncloseableConnection(memory);
            return new SqliteBlockReachability(() -> instance, new SqliteCommands());
        } catch (SQLException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] a) throws IOException {
        // This is a benchmark to test baseline speed of a blockstore GC
        String filename = "temp.sql";
        Path file = Path.of(filename);
        SqliteBlockReachability reachabilityDb = createReachabilityDb(file);
        List<BlockVersion> versions = new ArrayList<>();
        int count = 1_000_000;
        boolean versioned = true;
        Random rnd = new Random(28);

        for (int i = 0; i < count; i++) {
            byte[] hash = new byte[32];
            rnd.nextBytes(hash);
            Cid cid = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
            if (versioned)
                versions.add(new BlockVersion(cid, ArrayOps.bytesToHex(hash), true));
            else
                versions.add(new BlockVersion(cid, null, true));
        }
        System.out.println("Starting Db load...");
        long t0 = System.nanoTime();
        int batchSize = 10_000;
        for (int i = 0; i < count / batchSize; i++) {
            reachabilityDb.addBlocks(versions.subList(i * batchSize, (i+1)* batchSize));
        }
        long t1 = System.nanoTime();
        System.out.println("Load duration " + (t1-t0)/1_000_000_000 + "s, batch size = " + batchSize);
        int markBatchSize = 1000;
        AtomicLong totalReachable = new AtomicLong();
        for (int i = 0; i < count / markBatchSize; i++) {
            List<Cid> batch = versions.subList(i * markBatchSize, (i + 1) * markBatchSize)
                    .stream()
                    .map(v -> v.cid)
                    .collect(Collectors.toList());
            reachabilityDb.setReachable(batch, totalReachable);
        }
        long t2 = System.nanoTime();
        System.out.println("Marking reachable took " + (t2-t1)/1_000_000_000 + "s, batch size = " + markBatchSize);
        reachabilityDb.setReachable(Arrays.asList(versions.get(0).cid), totalReachable);

        List<BlockVersion> unreachable = new ArrayList<>();
        long t3 = System.nanoTime();
        reachabilityDb.getUnreachable(unreachable::addAll);
        if (!unreachable.isEmpty())
            throw new IllegalStateException("Incorrect garbage! This would lose data!");
        long t4 = System.nanoTime();
        System.out.println("Listing garbage took " + (t4-t3)/1_000_000 + "ms");

        long size = reachabilityDb.size();
        if (size != count)
            throw new IllegalStateException("Missing rows!");

        // Now double the size with unreachable blocks
        for (int i = 0; i < count; i++) {
            byte[] hash = new byte[32];
            rnd.nextBytes(hash);
            Cid cid = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
            if (versioned)
                versions.add(new BlockVersion(cid, ArrayOps.bytesToHex(hash), true));
            else
                versions.add(new BlockVersion(cid, null, true));
        }
        for (int i = 0; i < count / batchSize; i++) {
            reachabilityDb.addBlocks(versions.subList(count + i * batchSize, count + (i+1)* batchSize));
        }
        long t5 = System.nanoTime();
        reachabilityDb.getUnreachable(unreachable::addAll);
        long t6 = System.nanoTime();
        if (unreachable.size() != count)
            throw new IllegalStateException("Incorrect garbage!");
        System.out.println("Listing garbage took " + (t6-t5)/1_000_000 + "ms");

        // put the same block version in multiple times (should be idempotent)
        long priorSize = reachabilityDb.size();
        byte[] hash1 = new byte[32];
        rnd.nextBytes(hash1);
        Cid cid1 = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash1);
        BlockVersion v1 = new BlockVersion(cid1, ArrayOps.bytesToHex(hash1), true);
        reachabilityDb.addBlocks(Arrays.asList(v1, v1, v1, v1, v1, v1, v1, v1, v1, v1));

        reachabilityDb.setReachable(versions.subList(0, 10).stream().map(v ->v.cid).collect(Collectors.toList()), totalReachable);
        reachabilityDb.setReachable(versions.subList(0, 10).stream().map(v ->v.cid).collect(Collectors.toList()), totalReachable);
        try {
            reachabilityDb.addBlocks(Arrays.asList(v1));
        } catch (Exception e) {}
        long with1Block = reachabilityDb.size();
        if (with1Block != priorSize + 1)
            throw new IllegalStateException("Adding not idempotent!");
    }
}
