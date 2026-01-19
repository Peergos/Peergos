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
    private static final String GET_INFO = "SELECT * FROM blockmetadata WHERE cid = ?;";
    private static final String GET_OWNER = "SELECT owner FROM blockmetadata WHERE cid = ?;";
    private static final String REMOVE = "DELETE FROM blockmetadata where cid = ?;";
    public static final int PAGE_LIMIT = 100_000;
    private static final String LIST_PAGINATED_FIRST = "SELECT cid, version FROM blockmetadata ORDER BY cid LIMIT " + PAGE_LIMIT + ";";
    private static final String LIST_PAGINATED = "SELECT cid, version FROM blockmetadata WHERE cid > ? ORDER BY cid LIMIT " + PAGE_LIMIT + ";";
    private static final String LIST_SIZE_PAGINATED_FIRST = "SELECT cid, size FROM blockmetadata ORDER BY cid LIMIT " + PAGE_LIMIT + ";";
    private static final String LIST_SIZE_PAGINATED = "SELECT cid, size FROM blockmetadata WHERE cid > ? ORDER BY cid LIMIT " + PAGE_LIMIT + ";";
    private static final String LIST_ALL = "SELECT cid, version FROM blockmetadata WHERE owner=?;";
    private static final String SIZE = "SELECT COUNT(*) FROM blockmetadata WHERE owner=?;";
    private static final String EMPTY = "SELECT * FROM blockmetadata LIMIT 1;";
    private Supplier<Connection> conn;
    private final SqlSupplier commands;

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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    public void remove(Cid block) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(REMOVE)) {

            insert.setBytes(1, block.toBytes());
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public Optional<BlockMetadata> get(Cid block) {
        try (Connection conn = getConnection(false, false);
             PreparedStatement stmt = conn.prepareStatement(GET_INFO)) {
            stmt.setBytes(1, block.toBytes());
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                List<Cid> links = ((CborObject.CborList) CborObject.fromByteArray(rs.getBytes("links")))
                        .map(cbor -> Cid.cast(((CborObject.CborByteArray)cbor).value));
                List<BatId> batIds = ((CborObject.CborList) CborObject.fromByteArray(rs.getBytes("batids")))
                        .map(BatId::fromCbor);
                return Optional.of(new BlockMetadata(rs.getInt("size"), links, batIds));
            }
            return Optional.empty();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public List<Cid> hasBlocks(List<Cid> blocks) {
        String placeholders = blocks.stream()
                .map(b -> "?")
                .collect(Collectors.joining(","));

        String sql = "SELECT cid FROM blockmetadata WHERE cid IN (" + placeholders + ");";

        try (Connection conn = getConnection(false, false);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < blocks.size(); i++) {
                stmt.setBytes(i + 1, blocks.get(i).toBytes());
            }

            ResultSet rs = stmt.executeQuery();
            List<Cid> present = new ArrayList<>();

            while (rs.next()) {
                present.add(Cid.cast(rs.getBytes("cid")));
            }

            return present;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public Optional<PublicKeyHash> getOwner(Cid block) {
        try (Connection conn = getConnection(false, false);
             PreparedStatement stmt = conn.prepareStatement(GET_OWNER)) {
            stmt.setBytes(1, block.toBytes());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.ofNullable(rs.getBytes("owner"))
                        .map(PublicKeyHash::decode);
            }
            return Optional.empty();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void setOwner(PublicKeyHash owner, Cid block) {
        try (Connection conn = getConnection();
             PreparedStatement update = conn.prepareStatement(commands.updateMetadataCommand())) {

            update.setBytes(1, owner.toBytes());
            update.setBytes(2, block.toBytes());
            update.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void setOwnerAndVersion(PublicKeyHash owner, Cid block, String version) {
        try (Connection conn = getConnection();
             PreparedStatement update = conn.prepareStatement(commands.setMetadataVersionAndOwnerCommand())) {

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
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(commands.addMetadataCommand())) {

            insert.setBytes(1, owner != null ? owner.toBytes() : null);
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
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }
}
