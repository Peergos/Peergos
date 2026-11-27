package peergos.server.storage;

import peergos.server.sql.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.storage.auth.*;
import peergos.shared.io.ipfs.Cid;

import java.sql.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

public class JdbcBlockMetadataStore implements BlockMetadataStore {

    private static final Logger LOG = Logging.LOG();
    private static final String SET_VERSION = "UPDATE blockmetadata SET version=? WHERE owner=? AND cid=?;";
    public static final int PAGE_LIMIT = 100_000;
    private static final int INSERT_COLUMNS = 6;
    // keep the bound parameter count per statement well below sqlite's limit of 32766
    private static final int MAX_INSERT_ROWS = 1_000;
    private static final int MAX_DELETE_ROWS = 1_000;
    private static final String LIST_PAGINATED_FIRST = "SELECT DISTINCT cid FROM blockmetadata ORDER BY cid LIMIT " + PAGE_LIMIT + ";";
    private static final String LIST_PAGINATED = "SELECT DISTINCT cid FROM blockmetadata WHERE cid > ? ORDER BY cid LIMIT " + PAGE_LIMIT + ";";
    private static final String LIST_SIZE_PAGINATED_FIRST = "SELECT cid, MAX(size) AS size FROM blockmetadata GROUP BY cid ORDER BY cid LIMIT " + PAGE_LIMIT + ";";
    private static final String LIST_SIZE_PAGINATED = "SELECT cid, MAX(size) AS size FROM blockmetadata WHERE cid > ? GROUP BY cid ORDER BY cid LIMIT " + PAGE_LIMIT + ";";
    private static final String LIST_ALL = "SELECT cid, version FROM blockmetadata WHERE owner=?;";
    private static final String SIZE = "SELECT COUNT(*) FROM blockmetadata WHERE owner=?;";
    private static final String EMPTY = "SELECT * FROM blockmetadata LIMIT 1;";
    private static final String RELISTED_TABLE = "blockmetadatarelisted";
    private Supplier<Connection> conn;
    private final SqlSupplier commands;
    private volatile boolean relistPending;

    public JdbcBlockMetadataStore(Supplier<Connection> conn, SqlSupplier commands) {
        this.conn = conn;
        this.commands = commands;
        init(commands);
    }

    private Connection getConnection() {
        return getConnection(true, true);
    }

    private Connection getConnection(boolean autocommit, boolean serializable) {
        Connection connection = conn.get();
        try {
            if (autocommit)
                connection.setAutoCommit(true);
            if (serializable)
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            else
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            return connection;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void init(SqlSupplier commands) {
        try (Connection conn = getConnection()) {
            commands.createTable(commands.createBlockMetadataStoreTableCommand(), conn);
            try { // sqlite doesn't have an "if not exists" modifier on "add column"
                commands.createTable(commands.ensureColumnExistsCommand("blockmetadata", "owner", commands.getByteArrayType() + " DEFAULT null"), conn);
            } catch (SQLException f) {
                if (!f.getMessage().contains("duplicate column"))
                    throw new RuntimeException(f);
            }
            migrateToOwnerKey(conn, commands);
            try (PreparedStatement stmt = conn.prepareStatement(commands.tableExistsCommand())) {
                stmt.setString(1, RELISTED_TABLE);
                relistPending = stmt.executeQuery().next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The table used to be keyed by cid alone, so only the first owner of a block got a row, and the blocks of
     *  any later owner were invisible to gc. Dropping the legacy null owner rows is safe, as the store is
     *  rebuilt from the blockstore on demand. A gc listing from the blockstore picks up any blocks left without
     *  a row.
     */
    private static void migrateToOwnerKey(Connection conn, SqlSupplier commands) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(commands.blockMetadataPrimaryKeySizeCommand());
             ResultSet rs = stmt.executeQuery()) {
            if (! rs.next() || rs.getInt(1) != 1)
                return;
        }
        LOG.info("Rekeying block metadata by owner and cid...");
        long t0 = System.currentTimeMillis();
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            for (String command : commands.migrateBlockMetadataToOwnerKeyCommands())
                stmt.executeUpdate(command);
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + RELISTED_TABLE + " (owner " + commands.getByteArrayType() + " primary key not null);");
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
        LOG.info("Rekeyed block metadata in " + (System.currentTimeMillis() - t0) / 1000 + "s");
    }

    public void compact() {
        String vacuum = commands.vacuumCommand();
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

    @Override
    public void remove(PublicKeyHash owner, List<Cid> blocks) {
        for (int start = 0; start < blocks.size(); start += MAX_DELETE_ROWS) {
            List<Cid> chunk = blocks.subList(start, Math.min(start + MAX_DELETE_ROWS, blocks.size()));
            String placeholders = chunk.stream()
                    .map(b -> "?")
                    .collect(Collectors.joining(","));
            try (Connection conn = getConnection();
                 PreparedStatement delete = conn.prepareStatement("DELETE FROM blockmetadata WHERE owner = ? AND cid IN (" + placeholders + ");")) {
                delete.setBytes(1, owner.toBytes());
                for (int i = 0; i < chunk.size(); i++)
                    delete.setBytes(i + 2, chunk.get(i).toBytes());
                delete.executeUpdate();
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                throw new RuntimeException(sqe);
            }
        }
    }

    @Override
    public Map<Cid, BlockMetadata> getAll(PublicKeyHash owner, List<Cid> blocks) {
        if (owner == null || blocks.isEmpty())
            return Collections.emptyMap();

        String placeholders = blocks.stream()
                .map(b -> "?")
                .collect(Collectors.joining(","));

        String sql = "SELECT cid, links, batids, size FROM blockmetadata WHERE cid IN (" + placeholders + ") AND owner = ?;";

        try (Connection conn = getConnection(false, false);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < blocks.size(); i++) {
                stmt.setBytes(i + 1, blocks.get(i).toBytes());
            }
            stmt.setBytes(blocks.size() + 1, owner.toBytes());

            ResultSet rs = stmt.executeQuery();
            Map<Cid, BlockMetadata> present = new HashMap<>();

            while (rs.next()) {
                Cid h = Cid.cast(rs.getBytes("cid"));
                List<Cid> links = ((CborObject.CborList) CborObject.fromByteArray(rs.getBytes("links")))
                        .map(cbor -> Cid.cast(((CborObject.CborByteArray)cbor).value));
                List<BatId> batIds = ((CborObject.CborList) CborObject.fromByteArray(rs.getBytes("batids")))
                        .map(BatId::fromCbor);
                present.put(h, new BlockMetadata(rs.getInt("size"), links, batIds));
            }

            return present;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void setVersion(PublicKeyHash owner, Cid block, String version) {
        try (Connection conn = getConnection();
             PreparedStatement update = conn.prepareStatement(SET_VERSION)) {

            update.setString(1, version);
            update.setBytes(2, owner.toBytes());
            update.setBytes(3, block.toBytes());
            update.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void put(PublicKeyHash owner, Cid block, String version, BlockMetadata meta) {
        if (owner == null)
            return;
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(commands.addMetadataCommand(1))) {

            insert.setBytes(1, owner.toBytes());
            insert.setBytes(2, block.toBytes());
            insert.setString(3, version);
            insert.setLong(4, meta.size);
            insert.setBytes(5, new CborObject.CborList(meta.links.stream()
                    .map(Cid::toBytes)
                    .map(CborObject.CborByteArray::new)
                    .collect(Collectors.toList()))
                    .toByteArray());
            insert.setBytes(6, new CborObject.CborList(meta.batids)
                    .toByteArray());
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void put(PublicKeyHash owner, List<Cid> blocks, List<byte[]> data) {
        if (owner == null)
            return;
        // Insert each chunk with a single statement, otherwise each row is committed (and fsynced) separately.
        // We can't use a transaction here because the sqlite connection is shared between all threads.
        for (int start = 0; start < blocks.size(); start += MAX_INSERT_ROWS) {
            int end = Math.min(start + MAX_INSERT_ROWS, blocks.size());
            putChunk(owner, blocks.subList(start, end), data.subList(start, end));
        }
    }

    private void putChunk(PublicKeyHash owner, List<Cid> blocks, List<byte[]> data) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(commands.addMetadataCommand(blocks.size()))) {
            byte[] ownerBytes = owner.toBytes();
            for (int i=0; i < blocks.size(); i++) {
                Cid block = blocks.get(i);
                BlockMetadata meta = BlockMetadataStore.extractMetadata(block, data.get(i));
                int param = i * INSERT_COLUMNS;
                insert.setBytes(param + 1, ownerBytes);
                insert.setBytes(param + 2, block.toBytes());
                insert.setString(param + 3, null);
                insert.setLong(param + 4, meta.size);
                insert.setBytes(param + 5, new CborObject.CborList(meta.links.stream()
                        .map(Cid::toBytes)
                        .map(CborObject.CborByteArray::new)
                        .collect(Collectors.toList()))
                        .toByteArray());
                insert.setBytes(param + 6, new CborObject.CborList(meta.batids)
                        .toByteArray());
            }
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public boolean needsBlockstoreListing(PublicKeyHash owner) {
        if (! relistPending)
            return false;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT owner FROM " + RELISTED_TABLE + " WHERE owner = ?;")) {
            stmt.setBytes(1, owner.toBytes());
            return ! stmt.executeQuery().next();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void blockstoreListed(PublicKeyHash owner) {
        if (! needsBlockstoreListing(owner))
            return;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + RELISTED_TABLE + " (owner) VALUES (?);")) {
            stmt.setBytes(1, owner.toBytes());
            stmt.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public long size(PublicKeyHash owner) {
        try (Connection conn = getConnection();
             PreparedStatement size = conn.prepareStatement(SIZE)) {
            size.setBytes(1, owner != null ? owner.toBytes() : null);
            ResultSet rs = size.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public boolean isEmpty() {
        try (Connection conn = getConnection();
             PreparedStatement size = conn.prepareStatement(EMPTY)) {
            ResultSet rs = size.executeQuery();
            return ! rs.next();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void applyToAll(Consumer<Cid> action) {
        Cid prevLast = null;
        while (true) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(prevLast == null ? LIST_PAGINATED_FIRST : LIST_PAGINATED)) {
                if (prevLast != null)
                    stmt.setBytes(1, prevLast.toBytes());
                ResultSet rs = stmt.executeQuery();
                int added = 0;
                while (rs.next()) {
                    Cid cid = Cid.cast(rs.getBytes("cid"));
                    action.accept(cid);
                    added++;
                    prevLast = cid;
                }
                if (added == 0)
                    break;
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                throw new RuntimeException(sqe);
            }
        }
    }

    @Override
    public void applyToAllSizes(BiConsumer<Cid, Long> action) {
        Cid prevLast = null;
        while (true) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(prevLast == null ? LIST_SIZE_PAGINATED_FIRST : LIST_SIZE_PAGINATED)) {
                if (prevLast != null)
                    stmt.setBytes(1, prevLast.toBytes());
                ResultSet rs = stmt.executeQuery();
                int added = 0;
                while (rs.next()) {
                    Cid cid = Cid.cast(rs.getBytes("cid"));
                    long size = rs.getLong("size");
                    action.accept(cid, size);
                    added++;
                    prevLast = cid;
                }
                if (added == 0)
                    break;
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                throw new RuntimeException(sqe);
            }
        }
    }

    @Override
    public Stream<BlockVersion> list(PublicKeyHash owner) {
        try (Connection conn = getConnection();
             PreparedStatement list = conn.prepareStatement(LIST_ALL)) {
            list.setBytes(1, owner == null ? null : owner.toBytes());
            ResultSet rs = list.executeQuery();
            List<BlockVersion> res = new ArrayList<>();
            while (rs.next()) {
                res.add(new BlockVersion(Cid.cast(rs.getBytes("cid")), rs.getString("version"), true));
            }
            return res.stream();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void listCbor(PublicKeyHash owner, Consumer<List<BlockVersion>> results) {
        try (Connection conn = getConnection();
             PreparedStatement list = conn.prepareStatement(LIST_ALL)) {
            list.setBytes(1, owner == null ? null : owner.toBytes());
            ResultSet rs = list.executeQuery();
            List<BlockVersion> res = new ArrayList<>();
            while (rs.next()) {
                Cid cid = Cid.cast(rs.getBytes("cid"));
                String version = rs.getString("version");
                if (! cid.isRaw()) {
                    res.add(new BlockVersion(cid, version, true));
                    if (res.size() == 1000) {
                        results.accept(res);
                        res = new ArrayList<>(1000);
                    }
                }
            }
            // The batching only bounds memory - every cbor block still has to be reported, and the
            // final batch is rarely full. The gc builds the set of blocks it knows about from this,
            // so anything dropped here is never a candidate for collection.
            if (! res.isEmpty())
                results.accept(res);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }
}
