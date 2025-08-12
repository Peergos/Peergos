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
            "idx integer primary key,"+
            "hash bytes not null, " +
            "version text, " +
            "latest boolean not null," +
            "reachable boolean not null); " +
                "CREATE UNIQUE INDEX IF NOT EXISTS hash_reachable_index ON reachability (hash, version);" +
            "CREATE TABLE IF NOT EXISTS links (" +
            "parent integer references reachability(idx) not null," +
            "child integer references reachability(idx) not null" +
            ");" +
            "CREATE UNIQUE INDEX IF NOT EXISTS links_index ON links (parent, child);" +
            "CREATE TABLE IF NOT EXISTS emptylinks (" +
            "parent integer references reachability(idx) not null primary key" +
            ");";

    private static final String CLEAR_REACHABLE = "UPDATE reachability SET reachable=false";
    private static final String SET_REACHABLE = "UPDATE reachability SET reachable=true WHERE hash = ? AND latest = true";
    private static final String INSERT_SUFFIX = "INTO reachability (hash, version, latest, reachable) VALUES(?, ?, ?, false)";
    private static final String NOT_LATEST = "update reachability set latest=false WHERE hash=? AND version!=?";
    private static final String INSERT_LINK_SUFFIX = "INTO links (parent, child) VALUES(?, ?)";
    private static final String INSERT_EMPTY_LINKS_SUFFIX = "INTO emptylinks (parent) VALUES(?)";
    private static final String UNREACHABLE = "SELECT hash, version FROM reachability WHERE reachable = false";
    private static final String APPLY_TO_ALL_VERSIONS = "SELECT hash, version FROM reachability";
    private static final String COUNT = "SELECT COUNT(*) FROM reachability";
    private static final String BLOCK_INDEX = "SELECT idx FROM reachability WHERE hash=? AND latest=true";
    private static final String BLOCK_VERSION_INDEX = "SELECT idx FROM reachability WHERE hash=? AND VERSION=?";
    private static final String OLD_BLOCK_INDEX = "SELECT idx FROM reachability WHERE hash=? AND latest=false";
    private static final String BLOCK_BY_INDEX = "SELECT hash FROM reachability WHERE idx=?";
    private static final String LINKS = "SELECT child FROM links WHERE parent=?";
    private static final String UPDATE_LINK_PARENTS = "UPDATE links SET parent=? WHERE parent=?";
    private static final String UPDATE_LINK_KIDS = "UPDATE links SET child=? WHERE child=?";
    private static final String UPDATE_OLD_EMPTY_LINKS = "UPDATE emptylinks SET parent=? WHERE parent=?";
    private static final String DELETE_LINKS = "DELETE FROM links WHERE parent=?";
    private static final String DELETE_EMPTY_LINKS = "DELETE FROM emptylinks WHERE parent=?";
    private static final String DELETE_BLOCK = "DELETE FROM reachability WHERE hash=? AND version=?";
    private static final String EMPTY_LINKS = "SELECT COUNT(*) FROM emptylinks WHERE parent=?";

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
        if (versions.isEmpty())
            return;
        try (Connection conn = getNonCommittingConnection();
             PreparedStatement oldlatest = conn.prepareStatement(NOT_LATEST);
             PreparedStatement insert = conn.prepareStatement(cmds.insertOrIgnoreCommand("INSERT ", INSERT_SUFFIX));
             PreparedStatement linkParents = conn.prepareStatement(UPDATE_LINK_PARENTS);
             PreparedStatement linkKids = conn.prepareStatement(UPDATE_LINK_KIDS);
             PreparedStatement oldBlockIndices = conn.prepareStatement(OLD_BLOCK_INDEX);
             PreparedStatement blockIndex = conn.prepareStatement(BLOCK_INDEX);
             PreparedStatement updateOldEmptyLinkIndices = conn.prepareStatement(UPDATE_OLD_EMPTY_LINKS);
        ) {
            List<BlockVersion> distinct = versions.stream()
                    .distinct()
                    .collect(Collectors.toList());

            for (BlockVersion version : distinct) {
                insert.setBytes(1, version.cid.toBytes());
                insert.setString(2, version.version);
                insert.setBoolean(3, version.isLatest);
                insert.addBatch();
            }
            int[] inserted = insert.executeBatch();
            conn.commit();
            List<BlockVersion> newVersions = new ArrayList<>();
            for (int i=0; i < inserted.length; i++)
                if (inserted[i] == 1)
                    newVersions.add(distinct.get(i));
            Set<BlockVersion> newLatestVersions = newVersions.stream()
                    .filter(v -> v.isLatest && v.version != null)
                    .collect(Collectors.toSet());
            if (! newLatestVersions.isEmpty()) {
                for (BlockVersion latest : newLatestVersions) {
                    oldlatest.setBytes(1, latest.cid.toBytes());
                    oldlatest.setString(2, latest.version);
                    oldlatest.addBatch();
                }
                int[] changed = oldlatest.executeBatch();
                conn.commit();
            }
            for (BlockVersion latest : newLatestVersions) {
                blockIndex.setBytes(1, latest.cid.toBytes());
                ResultSet latestIdRes = blockIndex.executeQuery();
                if (!latestIdRes.next())
                    throw new IllegalStateException("Latest block not present: " + latest.cid);
                long latestId = latestIdRes.getLong(1);

                oldBlockIndices.setBytes(1, latest.cid.toBytes());
                ResultSet res = oldBlockIndices.executeQuery();
                Set<Long> oldVersionIndices = new HashSet<>();
                while (res.next())
                    oldVersionIndices.add(res.getLong(1));

                if (!oldVersionIndices.isEmpty()) {
                    // update all links that use old indices
                    for (long old : oldVersionIndices) {
                        linkParents.setLong(1, latestId);
                        linkParents.setLong(2, old);
                        linkParents.addBatch();
                    }
                    int[] changedLinkParents = linkParents.executeBatch();
                    conn.commit();
                    for (long old : oldVersionIndices) {
                        linkKids.setLong(1, latestId);
                        linkKids.setLong(2, old);
                        linkKids.addBatch();
                    }
                    int[] changedLinkKids = linkKids.executeBatch();
                    conn.commit();
                    for (long old : oldVersionIndices) {
                        updateOldEmptyLinkIndices.setLong(1, latestId);
                        updateOldEmptyLinkIndices.setLong(2, old);
                        updateOldEmptyLinkIndices.addBatch();
                    }
                    int[] changedEmptyLinks = updateOldEmptyLinkIndices.executeBatch();
                    conn.commit();
                }
            }
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
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

    public synchronized void clearReachable() {
        try (Connection conn = getConnection();
             PreparedStatement update = conn.prepareStatement(CLEAR_REACHABLE)) {
            update.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
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

    public synchronized void getUnreachable(Consumer<List<BlockVersion>> toDelete) {
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

    public void compact() {
        String vacuum = cmds.vacuumCommand();
        if (vacuum.isEmpty())
            return;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(vacuum)) {
            stmt.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private long getBlockIndex(Cid block) {
        try (Connection conn = getConnection();
             PreparedStatement query = conn.prepareStatement(BLOCK_INDEX)) {
            query.setBytes(1, block.toBytes());
            ResultSet res = query.executeQuery();
            if (!res.next())
                throw new IllegalStateException("Block not present: " + block);
            return res.getLong(1);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private long getBlockVersionIndex(BlockVersion block) {
        try (Connection conn = getConnection();
             PreparedStatement query = conn.prepareStatement(BLOCK_VERSION_INDEX)) {
            query.setBytes(1, block.cid.toBytes());
            query.setString(2, block.version);
            ResultSet res = query.executeQuery();
            if (!res.next())
                throw new IllegalStateException("Block version not present: " + block);
            return res.getLong(1);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private Cid getBlock(long index) {
        try (Connection conn = getConnection();
             PreparedStatement query = conn.prepareStatement(BLOCK_BY_INDEX)) {
            query.setLong(1, index);
            ResultSet res = query.executeQuery();
            if (! res.next())
                throw new IllegalStateException("Could get block for index " + index);
            return Cid.cast(res.getBytes(1));
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public synchronized void setLinks(Cid block, List<Cid> links) {
        long parentIndex = getBlockIndex(block);
        if (links.isEmpty()) {
            try (Connection conn = getConnection();
                 PreparedStatement insert = conn.prepareStatement(cmds.insertOrIgnoreCommand("INSERT ", INSERT_EMPTY_LINKS_SUFFIX))) {
                insert.setLong(1, parentIndex);
                int updated = insert.executeUpdate();
                if (updated != 1)
                    throw new IllegalStateException("Couldn't insert links!");
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            }
            return;
        }
        List<Long> linkIndices = links.stream()
                .map(this::getBlockIndex)
                .toList();
        try (Connection conn = getNonCommittingConnection();
             PreparedStatement insert = conn.prepareStatement(cmds.insertOrIgnoreCommand("INSERT ", INSERT_LINK_SUFFIX))) {
            for (Long linkIndex : linkIndices) {
                insert.setLong(1, parentIndex);
                insert.setLong(2, linkIndex);
                insert.addBatch();
            }
            int[] changed = insert.executeBatch();
            if (IntStream.of(changed).sum() < links.size()) {
                conn.rollback();
            } else
                conn.commit();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public synchronized Optional<List<Cid>> getLinks(Cid block) {
        long index;
        try {
            index = getBlockIndex(block);
        } catch (Exception e) {
            return Optional.empty();
        }
        try (Connection conn = getConnection();
             PreparedStatement query = conn.prepareStatement(EMPTY_LINKS)) {
            query.setLong(1, index);
            ResultSet res = query.executeQuery();
            res.next();
            if (res.getLong(1) > 0)
                return Optional.of(Collections.emptyList());
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }

        try (Connection conn = getConnection();
             PreparedStatement query = conn.prepareStatement(LINKS)) {
            query.setLong(1, index);
            ResultSet res = query.executeQuery();
            List<Cid> links = new ArrayList<>();
            while (res.next()) {
                links.add(getBlock(res.getLong(1)));
            }
            if (links.isEmpty())
                return Optional.empty();
            return Optional.of(links);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public synchronized void removeBlock(BlockVersion block) {
        long index;
        try {
            index = getBlockVersionIndex(block);
        } catch (Exception e) {
            return;
        }
        try (Connection conn = getConnection();
             PreparedStatement delete = conn.prepareStatement(DELETE_BLOCK);
             PreparedStatement deleteLinks = conn.prepareStatement(DELETE_LINKS);
             PreparedStatement deleteEmptyLinks = conn.prepareStatement(DELETE_EMPTY_LINKS)) {
            deleteLinks.setLong(1, index);
            deleteLinks.executeUpdate();
            deleteEmptyLinks.setLong(1, index);
            deleteEmptyLinks.executeUpdate();
            delete.setBytes(1, block.cid.toBytes());
            delete.setString(2, block.version);
            delete.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    public static SqliteBlockReachability createReachabilityDb(Path dbFile) {
        try {
            Connection file = Sqlite.build(dbFile.toString());
            // We need a connection that ignores close
            Connection instance = new Sqlite.UncloseableConnection(file);
            return new SqliteBlockReachability(() -> instance, new SqliteCommands());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] a) throws IOException {
        // This is a benchmark to test baseline speed of a blockstore GC
        String filename = System.nanoTime() + "temp.sql";
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
            long size = reachabilityDb.size();
            if (size != (i +1) * batchSize)
                throw new IllegalStateException("Incorrect size: " + size + ", expected " + (i+1)*batchSize);
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
            throw new IllegalStateException("Missing rows! " + size + ", expected " + count);

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
