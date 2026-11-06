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
import peergos.shared.util.LRUCache;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class JdbcAddressLRU implements AddressBook {
    private static final Logger LOG = Logging.LOG();

    // Milliseconds, so the LRU can order peers used within the same second; sorts with older second-resolution rows
    private static final String NOW = "strftime('%Y-%m-%d %H:%M:%f', 'now')";
    private static final String SET = "INSERT OR REPLACE INTO addressbook (peerid, addresses, lastaccess) VALUES(?, ?, " + NOW + ")";
    private static final String GET = "SELECT addresses, lastaccess < datetime('now', '-1 hour') AS stale FROM addressbook WHERE peerid = ?;";
    private static final String TOUCH = "UPDATE addressbook SET lastaccess=" + NOW + " WHERE peerid = ?;";
    private static final String COUNT = "SELECT COUNT(*) FROM addressbook;";
    private static final String DELETE = "DELETE FROM addressbook WHERE peerid IN " +
            "(SELECT peerid FROM addressbook ORDER BY lastaccess ASC LIMIT ?);";

    /** A peer legitimately announces a handful of addresses. The LRU bounds how many peers we hold,
     *  but a peer we keep serving is touched on every read and never evicted, so without this its
     *  address list grows without limit. */
    public static final int MAX_ADDRESSES_PER_PEER = 32;
    public static final long DEFAULT_TTL_MILLIS = 7 * 24 * 3600_000L;
    /** Re-announcing a known address only rewrites the row once this old, see addAddrs. */
    public static final long REFRESH_MILLIS = 3600_000L;
    /** Separates an address from the time it was last announced. Multiaddrs never contain it. */
    private static final String TIME_SEPARATOR = "|";

    private volatile boolean isClosed;
    private Supplier<Connection> conn;
    private final int maxSize;
    private final Supplier<Long> time;
    /** Mirrors the table, as the DHT reads ~20 peers' addresses per request it serves. */
    private final LRUCache<PeerId, Cached> cache;
    /** Peers read (getAddrs) since lastaccess was last written for them. Reads only write lastaccess hourly, but DHT
     *  churn can turn the whole book over in minutes, so these are touched in one batch before evicting. */
    private final LRUCache<PeerId, Boolean> readSinceTouch;

    private record Cached(Map<Multiaddr, Long> addrs, long lastTouched) {}

    public JdbcAddressLRU(int maxSize, Supplier<Connection> conn, SqlSupplier commands) {
        this(maxSize, conn, commands, System::currentTimeMillis);
    }

    public JdbcAddressLRU(int maxSize, Supplier<Connection> conn, SqlSupplier commands, Supplier<Long> time) {
        this.maxSize = maxSize;
        this.conn = conn;
        this.time = time;
        this.cache = new LRUCache<>(maxSize + 1);
        this.readSinceTouch = new LRUCache<>(maxSize + 1);
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
        Map<Multiaddr, Long> existing = readAddrs(peerId);
        Map<Multiaddr, Long> updated = new HashMap<>(existing);
        long now = time.get();
        boolean changed = false;
        for (Multiaddr addr : multiaddrs) {
            Long lastSeen = existing.get(addr);
            if (lastSeen == null || lastSeen < now - REFRESH_MILLIS)
                changed = true;
            updated.put(addr, now);
        }
        // Peers re-announce the same addresses on every connection and identify, and each write is
        // several fsyncs, so only write when something new arrives or a timestamp is getting old.
        if (changed)
            writeAddrs(peerId, updated, existing.isEmpty());
        return Futures.of(null);
    }

    @NotNull
    @Override
    public CompletableFuture<Collection<Multiaddr>> getAddrs(@NotNull PeerId peerId) {
        // Only reads count as use: writes set lastaccess themselves
        markUsed(peerId);
        return Futures.of(new ArrayList<>(readAddrs(peerId).keySet()));
    }

    /** The addresses we hold for a peer with the time each was last announced, stale ones dropped. */
    private Map<Multiaddr, Long> readAddrs(@NotNull PeerId peerId) {
        long now = time.get();
        Cached cached;
        synchronized (cache) {
            cached = cache.get(peerId);
        }
        if (cached != null) {
            if (cached.lastTouched < now - REFRESH_MILLIS) {
                touch(peerId);
                synchronized (cache) {
                    cache.put(peerId, new Cached(cached.addrs, now));
                }
            }
            Map<Multiaddr, Long> addrs = new HashMap<>(cached.addrs);
            prune(addrs);
            return addrs;
        }
        Map<Multiaddr, Long> addrs = readAddrsFromDb(peerId);
        synchronized (cache) {
            cache.put(peerId, new Cached(Map.copyOf(addrs), now));
        }
        return addrs;
    }

    private void markUsed(PeerId peerId) {
        synchronized (readSinceTouch) {
            readSinceTouch.put(peerId, Boolean.TRUE);
        }
    }

    private void touch(PeerId peerId) {
        try (Connection conn = getConnection();
             PreparedStatement touch = conn.prepareStatement(TOUCH)) {
            touch.setString(1, peerId.toBase58());
            touch.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    private Map<Multiaddr, Long> readAddrsFromDb(@NotNull PeerId peerId) {
        String id = peerId.toBase58();
        try (Connection conn = getConnection();
             PreparedStatement present = conn.prepareStatement(GET);
             PreparedStatement touch = conn.prepareStatement(TOUCH)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            present.setString(1, id);
            ResultSet rs = present.executeQuery();
            if (rs.next()) {
                // The DHT reads addresses for ~20 peers per request it serves, so a write per read
                // makes every DHT query cost ~20 fsync'd transactions. Hourly recency is plenty for the LRU.
                if (rs.getBoolean("stale")) {
                    touch.setString(1, id);
                    touch.executeUpdate();
                }
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
        List<String> recent;
        synchronized (readSinceTouch) {
            recent = readSinceTouch.keySet().stream().map(PeerId::toBase58).collect(Collectors.toList());
            readSinceTouch.clear();
        }
        try (Connection conn = getConnection();
             PreparedStatement touch = conn.prepareStatement(TOUCH);
             PreparedStatement delete = conn.prepareStatement(DELETE)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                for (String id : recent) {
                    touch.setString(1, id);
                    touch.addBatch();
                }
                if (! recent.isEmpty())
                    touch.executeBatch();
                delete.setInt(1, toRemove);
                delete.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
            synchronized (cache) {
                cache.clear();
            }
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    @NotNull
    @Override
    public CompletableFuture<Void> setAddrs(@NotNull PeerId peerId, long ttl, @NotNull Multiaddr... multiaddrs) {
        long now = time.get();
        Map<Multiaddr, Long> existing = readAddrs(peerId);
        Map<Multiaddr, Long> addrs = new HashMap<>();
        for (Multiaddr addr : multiaddrs) {
            addrs.put(addr, now);
        }
        boolean unchanged = existing.keySet().equals(addrs.keySet()) &&
                existing.values().stream().allMatch(t -> t >= now - REFRESH_MILLIS);
        if (! unchanged)
            writeAddrs(peerId, addrs, existing.isEmpty());
        return Futures.of(null);
    }

    private void writeAddrs(@NotNull PeerId peerId, Map<Multiaddr, Long> addrs, boolean newPeer) {
        prune(addrs);
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(SET)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

            insert.setString(1, peerId.toBase58());
            insert.setString(2, addrs.entrySet().stream()
                    .map(e -> e.getKey().toString() + TIME_SEPARATOR + e.getValue())
                    .collect(Collectors.joining(",")));
            insert.executeUpdate();
            synchronized (cache) {
                cache.put(peerId, new Cached(Map.copyOf(addrs), time.get()));
            }
            if (newPeer) {
                int size = size();
                if (size > maxSize) {
                    removeOldest(size - maxSize*8/10);
                }
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
            // This is a cache, so trade durability on power loss for not fsyncing every write
            try (Statement s = file.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL;");
                s.execute("PRAGMA synchronous=NORMAL;");
            }
            // We need a connection that ignores close
            Connection instance = new Sqlite.UncloseableConnection(file);
            return new JdbcAddressLRU(maxSize, () -> instance, new SqliteCommands(), time);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
