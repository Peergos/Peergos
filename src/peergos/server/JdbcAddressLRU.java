package peergos.server;

import io.libp2p.core.AddressBook;
import io.libp2p.core.PeerId;
import io.libp2p.core.multiformats.Multiaddr;
import org.jetbrains.annotations.NotNull;
import peergos.server.sql.SqlSupplier;
import peergos.server.sql.SqliteCommands;
import peergos.server.util.Logging;
import peergos.server.util.Sqlite;
import peergos.shared.cbor.CborObject;
import peergos.shared.corenode.PkiCache;
import peergos.shared.corenode.UserPublicKeyLink;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.util.Futures;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class JdbcAddressLRU implements AddressBook {
    private static final Logger LOG = Logging.LOG();

    private static final String SET = "INSERT OR REPLACE INTO addressbook (peerid, addresses, lastaccess) VALUES(?, ?, current_timestamp)";
    private static final String GET = "SELECT addresses FROM addressbook WHERE peerid = ?;";
    private static final String TOUCH = "UPDATE addressbook SET lastaccess=current_timestamp WHERE peerid = ?;";
    private static final String COUNT = "SELECT COUNT(*) FROM addressbook;";
    private static final String DELETE = "DELETE FROM addressbook WHERE peerid IN " +
            "(SELECT peerid FROM addressbook ORDER BY lastaccess ASC LIMIT ?);";

    private volatile boolean isClosed;
    private Supplier<Connection> conn;
    private final int maxSize;

    public JdbcAddressLRU(int maxSize, Supplier<Connection> conn, SqlSupplier commands) {
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
            commands.createTable("CREATE TABLE IF NOT EXISTS addressbook " +
                    "(peerid text primary key not null, addresses text not null, lastaccess int not null); " +
                    "CREATE UNIQUE INDEX IF NOT EXISTS addressbook_index ON addressbook (peerid);" +
                    "CREATE INDEX IF NOT EXISTS addressbooklru_index ON addressbook (lastaccess);", conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    @Override
    public CompletableFuture<Void> addAddrs(@NotNull PeerId peerId, long ttl, @NotNull Multiaddr... multiaddrs) {
        Collection<Multiaddr> existing = getAddrs(peerId).join();
        HashSet<Multiaddr> updated = new HashSet<>();
        updated.addAll(existing);
        for (Multiaddr addr : multiaddrs) {
            updated.add(addr);
        }
        setAddrs(peerId, 0L, updated.toArray(Multiaddr[]::new));
        return Futures.of(null);
    }

    @NotNull
    @Override
    public CompletableFuture<Collection<Multiaddr>> getAddrs(@NotNull PeerId peerId) {
        try (Connection conn = getConnection();
             PreparedStatement present = conn.prepareStatement(GET);
             PreparedStatement touch = conn.prepareStatement(TOUCH)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            present.setString(1, peerId.toBase58());
            ResultSet rs = present.executeQuery();
            if (rs.next()) {
                touch.setString(1, peerId.toBase58());
                touch.executeUpdate();
                return Futures.of(Arrays.stream(rs.getString("addresses").split(","))
                        .map(Multiaddr::new)
                        .toList());
            } else
                return Futures.of(Collections.emptySet());
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
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
             PreparedStatement delete = conn.prepareStatement(DELETE)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            delete.setInt(1, toRemove);
            int changed = delete.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    @NotNull
    @Override
    public CompletableFuture<Void> setAddrs(@NotNull PeerId peerId, long ttl, @NotNull Multiaddr... multiaddrs) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(SET)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

            insert.setString(1, peerId.toBase58());
            insert.setString(2, new String(Arrays.stream(multiaddrs)
                    .map(a -> a.toString())
                    .collect(Collectors.joining(","))));
            int changed = insert.executeUpdate();
            int size = size();
            if (size > maxSize) {
                removeOldest(size - maxSize*8/10);
            }
            return Futures.of(null);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            return Futures.of(null);
        }
    }

    public synchronized void close() {
        if (isClosed)
            return;

        isClosed = true;
    }

    public static JdbcAddressLRU buildSqlite(int maxSize, String db) {
        try {
            Connection file = Sqlite.build(db);
            // We need a connection that ignores close
            Connection instance = new Sqlite.UncloseableConnection(file);
            return new JdbcAddressLRU(maxSize, () -> instance, new SqliteCommands());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
