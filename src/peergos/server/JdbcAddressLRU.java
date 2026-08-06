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

    /** A peer legitimately announces a handful of addresses. The LRU bounds how many peers we hold,
     *  but a peer we keep serving is touched on every read and never evicted, so without this its
     *  address list grows without limit. */
    public static final int MAX_ADDRESSES_PER_PEER = 32;
    public static final long DEFAULT_TTL_MILLIS = 7 * 24 * 3600_000L;
    /** Separates an address from the time it was last announced. Multiaddrs never contain it. */
    private static final String TIME_SEPARATOR = "|";

    private volatile boolean isClosed;
    private Supplier<Connection> conn;
    private final int maxSize;
    private final Supplier<Long> time;

    public JdbcAddressLRU(int maxSize, Supplier<Connection> conn, SqlSupplier commands) {
        this(maxSize, conn, commands, System::currentTimeMillis);
    }

    public JdbcAddressLRU(int maxSize, Supplier<Connection> conn, SqlSupplier commands, Supplier<Long> time) {
        this.maxSize = maxSize;
        this.conn = conn;
        this.time = time;
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
        Map<Multiaddr, Long> updated = new HashMap<>(readAddrs(peerId));
        long now = time.get();
        for (Multiaddr addr : multiaddrs) {
            updated.put(addr, now);
        }
        writeAddrs(peerId, updated);
        return Futures.of(null);
    }

    @NotNull
    @Override
    public CompletableFuture<Collection<Multiaddr>> getAddrs(@NotNull PeerId peerId) {
        return Futures.of(new ArrayList<>(readAddrs(peerId).keySet()));
    }

    /** The addresses we hold for a peer with the time each was last announced, stale ones dropped. */
    private Map<Multiaddr, Long> readAddrs(@NotNull PeerId peerId) {
        try (Connection conn = getConnection();
             PreparedStatement present = conn.prepareStatement(GET);
             PreparedStatement touch = conn.prepareStatement(TOUCH)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            present.setString(1, peerId.toBase58());
            ResultSet rs = present.executeQuery();
            if (rs.next()) {
                touch.setString(1, peerId.toBase58());
                touch.executeUpdate();
                Map<Multiaddr, Long> addrs = parseAddresses(rs.getString("addresses"), time.get());
                prune(addrs);
                return addrs;
            } else
                return Collections.emptyMap();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    /** Addresses stored before they carried a last announced time are treated as just seen, so an
     *  upgrade doesn't drop a node's whole address book at once. */
    public static Map<Multiaddr, Long> parseAddresses(String stored, long now) {
        Map<Multiaddr, Long> res = new HashMap<>();
        for (String entry : stored.split(",")) {
            if (entry.isBlank())
                continue;
            int split = entry.lastIndexOf(TIME_SEPARATOR);
            if (split < 0) {
                res.put(new Multiaddr(entry), now);
                continue;
            }
            try {
                res.put(new Multiaddr(entry.substring(0, split)), Long.parseLong(entry.substring(split + 1)));
            } catch (NumberFormatException e) {
                res.put(new Multiaddr(entry.substring(0, split)), now);
            }
        }
        return res;
    }

    /** Drop addresses not announced within the ttl, then the oldest of what remains until we are
     *  within the per peer cap. */
    private void prune(Map<Multiaddr, Long> addrs) {
        long cutoff = time.get() - DEFAULT_TTL_MILLIS;
        addrs.values().removeIf(lastSeen -> lastSeen < cutoff);
        if (addrs.size() <= MAX_ADDRESSES_PER_PEER)
            return;
        List<Map.Entry<Multiaddr, Long>> oldestFirst = new ArrayList<>(addrs.entrySet());
        oldestFirst.sort(Map.Entry.comparingByValue());
        for (int i = 0; i < oldestFirst.size() - MAX_ADDRESSES_PER_PEER; i++)
            addrs.remove(oldestFirst.get(i).getKey());
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
        long now = time.get();
        Map<Multiaddr, Long> addrs = new HashMap<>();
        for (Multiaddr addr : multiaddrs) {
            addrs.put(addr, now);
        }
        writeAddrs(peerId, addrs);
        return Futures.of(null);
    }

    private void writeAddrs(@NotNull PeerId peerId, Map<Multiaddr, Long> addrs) {
        prune(addrs);
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(SET)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

            insert.setString(1, peerId.toBase58());
            insert.setString(2, addrs.entrySet().stream()
                    .map(e -> e.getKey().toString() + TIME_SEPARATOR + e.getValue())
                    .collect(Collectors.joining(",")));
            int changed = insert.executeUpdate();
            int size = size();
            if (size > maxSize) {
                removeOldest(size - maxSize*8/10);
            }
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    public synchronized void close() {
        if (isClosed)
            return;

        isClosed = true;
    }

    public static JdbcAddressLRU buildSqlite(int maxSize, String db) {
        return buildSqlite(maxSize, db, System::currentTimeMillis);
    }

    public static JdbcAddressLRU buildSqlite(int maxSize, String db, Supplier<Long> time) {
        try {
            Connection file = Sqlite.build(db);
            // We need a connection that ignores close
            Connection instance = new Sqlite.UncloseableConnection(file);
            return new JdbcAddressLRU(maxSize, () -> instance, new SqliteCommands(), time);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
