package peergos.server;

import io.ipfs.multibase.binary.Base32;
import io.ipfs.multihash.Multihash;
import org.peergos.protocol.dht.RecordStore;
import org.peergos.protocol.ipns.IpnsRecord;
import peergos.server.sql.SqlSupplier;
import peergos.server.sql.SqliteCommands;
import peergos.server.util.Logging;
import peergos.server.util.Sqlite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JdbcRecordLRU implements RecordStore {
    private static final Logger LOG = Logging.LOG();
    private static final String RECORD_TABLE = "records";
    private static final int SIZE_OF_VAL = 10 * 1024; // 10KiB
    private static final int SIZE_OF_PEERID = 100;
    private static final String SET = "INSERT OR REPLACE INTO " + RECORD_TABLE
            + " (peerId, raw, sequence, ttlNanos, expiryUTC, val, lastaccess) VALUES (?, ?, ?, ?, ?, ?, current_timestamp);";
    private static final String GET = "SELECT raw, sequence, ttlNanos, expiryUTC, val FROM " + RECORD_TABLE + " WHERE peerId=?;";
    private static final String TOUCH = "UPDATE " + RECORD_TABLE + " SET lastaccess=current_timestamp WHERE peerid = ?;";
    private static final String DELETE = "DELETE FROM " + RECORD_TABLE + " WHERE peerId=?";
    private static final String DELETE_BULK = "DELETE FROM " + RECORD_TABLE + " WHERE peerid IN " +
            "(SELECT peerid FROM " + RECORD_TABLE + " ORDER BY lastaccess ASC LIMIT ?);";
    private static final String COUNT = "SELECT COUNT(*) FROM " + RECORD_TABLE + ";";

    private volatile boolean isClosed;
    private Supplier<Connection> conn;
    private final int maxSize;

    public JdbcRecordLRU(int maxSize, Supplier<Connection> conn, SqlSupplier commands) {
        this.maxSize = maxSize;
        this.conn = conn;
        init(commands);
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
        if (isClosed)
            return;

        try (Connection conn = getConnection()) {
            commands.createTable("create table if not exists " + RECORD_TABLE
                    + " (peerId VARCHAR(" + SIZE_OF_PEERID + ") primary key not null, raw BLOB not null, "
                    + "sequence BIGINT not null, ttlNanos BIGINT not null, expiryUTC BIGINT not null, "
                    + "val VARCHAR(" + SIZE_OF_VAL + ") not null, "
                    + "lastaccess int not null);", conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String hashToKey(Multihash hash) {
        String padded = new Base32().encodeAsString(hash.toBytes());
        int padStart = padded.indexOf("=");
        return padStart > 0 ? padded.substring(0, padStart) : padded;
    }

    @Override
    public void put(Multihash peerId, IpnsRecord record) {
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(SET)) {
            pstmt.setString(1, hashToKey(peerId));
            pstmt.setBytes(2, record.raw);
            pstmt.setLong(3, record.sequence);
            pstmt.setLong(4, record.ttlNanos);
            pstmt.setLong(5, record.expiry.toEpochSecond(ZoneOffset.UTC));
            pstmt.setString(6, new String(record.value.length > SIZE_OF_VAL ?
                    Arrays.copyOfRange(record.value, 0, SIZE_OF_VAL) : record.value));
            pstmt.executeUpdate();
            int size = size();
            if (size > maxSize) {
                removeOldest(size - maxSize*8/10);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public Optional<IpnsRecord> get(Multihash peerId) {
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(GET);
             PreparedStatement touch = conn.prepareStatement(TOUCH)) {
            pstmt.setString(1, hashToKey(peerId));
            touch.setString(1, hashToKey(peerId));
            touch.executeUpdate();
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    try (InputStream input = rs.getBinaryStream("raw")) {
                        byte[] buffer = new byte[1024];
                        ByteArrayOutputStream bout = new ByteArrayOutputStream();
                        for (int len; (len = input.read(buffer)) != -1; ) {
                            bout.write(buffer, 0, len);
                        }
                        LocalDateTime expiry = LocalDateTime.ofEpochSecond(rs.getLong("expiryUTC"),
                                0, ZoneOffset.UTC);
                        IpnsRecord record = new IpnsRecord(bout.toByteArray(), rs.getLong("sequence"),
                                rs.getLong("ttlNanos"),  expiry, rs.getString("val").getBytes());
                        return Optional.of(record);
                    } catch (IOException readEx) {
                        throw new IllegalStateException(readEx);
                    }
                } else {
                    return Optional.empty();
                }
            } catch (SQLException rsEx) {
                throw new IllegalStateException(rsEx);
            }
        } catch (SQLException sqlEx) {
            throw new IllegalStateException(sqlEx);
        }
    }

    @Override
    public void remove(Multihash peerId) {
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(DELETE)) {
            pstmt.setString(1, hashToKey(peerId));
            pstmt.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public int size() {
        try (Connection conn = getConnection();
             PreparedStatement present = conn.prepareStatement(COUNT)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            ResultSet rs = present.executeQuery();
            return rs.getInt(1);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private void removeOldest(int toRemove) {
        try (Connection conn = getConnection();
             PreparedStatement delete = conn.prepareStatement(DELETE_BULK)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            delete.setInt(1, toRemove);
            int changed = delete.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    public synchronized void close() {
        if (isClosed)
            return;

        isClosed = true;
    }

    public static JdbcRecordLRU buildSqlite(int maxSize, String db) {
        try {
            Connection file = Sqlite.build(db);
            // We need a connection that ignores close
            Connection instance = new Sqlite.UncloseableConnection(file);
            return new JdbcRecordLRU(maxSize, () -> instance, new SqliteCommands());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
