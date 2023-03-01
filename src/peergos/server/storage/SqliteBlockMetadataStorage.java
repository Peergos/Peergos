package peergos.server.storage;

import peergos.server.sql.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.cid.*;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

public class SqliteBlockMetadataStorage implements BlockMetadataStore {

    private static final Logger LOG = Logging.LOG();
    private static final String CREATE = "INSERT INTO blockmetadata (cid, size, links, accesstime) VALUES(?, ?, ?, ?)";
    private static final String TOUCH = "UPDATE blockmetadata set accesstime=? WHERE cid = ?";
    private static final String GET_INFO = "SELECT * FROM blockmetadata WHERE cid = ?;";
    private static final String OLDEST = "SELECT * FROM blockmetadata ORDER BY accesstime;";
    private static final String REMOVE = "DELETE FROM blockmetadata where cid = ?;";
    private static final String VACUUM = "VACUUM;";

    private Supplier<Connection> conn;
    private final long maxFileSize;
    private final File sqlFile;

    public SqliteBlockMetadataStorage(Supplier<Connection> conn, SqlSupplier commands, long maxFileSize, File sqlFile) {
        this.conn = conn;
        this.maxFileSize = maxFileSize;
        this.sqlFile = sqlFile;
        init(commands);
    }

    public long currentSize() {
        return sqlFile.length();
    }

    public void ensureWithinSize() {
        if (maxFileSize == 0)
            return;
        long currentSize = currentSize();
        if (currentSize < maxFileSize * 0.9)
            return;
        long toRecover = currentSize / 2;
        long recovered = 0;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(OLDEST)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next() && recovered < toRecover) {
                byte[] rawCid = rs.getBytes("cid");
                recovered += rawCid.length;
                Cid block = Cid.cast(rawCid);
                byte[] rawLinks = rs.getBytes("links");
                recovered += rawLinks.length;
                remove(block);
            }
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
        compact();
    }

    public void compact() {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(VACUUM)) {
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

    private synchronized void init(SqlSupplier commands) {
        try (Connection conn = getConnection()) {
            commands.createTable(commands.createBlockMetadataStoreTableCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<BlockMetadata> get(Cid block) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_INFO)) {
            stmt.setBytes(1, block.toBytes());
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                List<Cid> links = ((CborObject.CborList) CborObject.fromByteArray(rs.getBytes("links")))
                        .map(cbor -> Cid.cast(((CborObject.CborByteArray)cbor).value));
                touch(block);
                return Optional.of(new BlockMetadata(rs.getInt("size"), links));
            }
            return Optional.empty();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private void touch(Cid block) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(TOUCH)) {

            insert.setBytes(1, block.toBytes());
            insert.setLong(2, System.currentTimeMillis());
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void put(Cid block, BlockMetadata meta) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(CREATE)) {

            insert.setBytes(1, block.toBytes());
            insert.setLong(2, meta.size);
            insert.setBytes(3, new CborObject.CborList(meta.links.stream()
                    .map(Cid::toBytes)
                    .map(CborObject.CborByteArray::new)
                    .collect(Collectors.toList()))
                    .toByteArray());
            insert.setLong(4, System.currentTimeMillis());
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }
}
