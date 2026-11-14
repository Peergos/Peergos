package peergos.server.routing;

import peergos.server.sql.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;

import java.sql.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

/** The routing log on disk: one row per identity or server, holding its tagged records and their state hash. */
public class JdbcRoutingStore {
    private static final Logger LOG = Logging.LOG();

    private static final String GET = "SELECT records FROM routingrecords WHERE kind = ? AND subject = ?;";
    private static final String PUT = "INSERT INTO routingrecords (kind, subject, records, statehash) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (kind, subject) DO UPDATE SET records = excluded.records, statehash = excluded.statehash;";
    private static final String UNINDEXED = "SELECT subject, statehash FROM routingrecords r WHERE kind = ? AND subject > ? " +
            "AND NOT EXISTS (SELECT 1 FROM routingpending p WHERE p.kind = r.kind AND p.subject = r.subject) " +
            "ORDER BY subject LIMIT ?;";
    private static final String GET_STATEHASH = "SELECT statehash FROM routingrecords WHERE kind = ? AND subject = ?;";
    private static final String IS_PENDING = "SELECT 1 FROM routingpending WHERE kind = ? AND subject = ?;";
    private static final String STATES = "SELECT subject, statehash FROM routingstates WHERE kind = ? AND slot >= ? AND slot <= ? " +
            "AND (slot > ? OR (slot = ? AND subject > ?)) ORDER BY slot, subject LIMIT ?;";
    private static final String GET_STATE = "SELECT statehash FROM routingstates WHERE kind = ? AND slot = ? AND subject = ?;";
    private static final String PUT_STATE = "INSERT INTO routingstates (kind, slot, subject, statehash) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (kind, slot, subject) DO UPDATE SET statehash = excluded.statehash;";
    private static final String REMOVE_STATE = "DELETE FROM routingstates WHERE kind = ? AND slot = ? AND subject = ?;";
    private static final String GET_SUM = "SELECT count, sum FROM routingsums WHERE kind = ? AND depth = ? AND prefix = ?;";
    private static final String CHILD_SUMS = "SELECT prefix, count, sum FROM routingsums WHERE kind = ? AND depth = ? " +
            "AND prefix >= ? AND prefix <= ? ORDER BY prefix;";
    private static final String PUT_SUM = "INSERT INTO routingsums (kind, depth, prefix, count, sum) VALUES (?, ?, ?, ?, ?) " +
            "ON CONFLICT (kind, depth, prefix) DO UPDATE SET count = excluded.count, sum = excluded.sum;";
    private static final String REMOVE_SUM = "DELETE FROM routingsums WHERE kind = ? AND depth = ? AND prefix = ?;";
    private static final String INDEXED = "index";
    private static final String COUNT = "SELECT COUNT(*) FROM routingrecords WHERE kind = ?;";
    private static final String ADD_PENDING = "INSERT INTO routingpending (kind, subject) VALUES (?, ?) ON CONFLICT DO NOTHING;";
    private static final String REMOVE_PENDING = "DELETE FROM routingpending WHERE kind = ? AND subject = ?;";
    private static final String PENDING = "SELECT kind, subject FROM routingpending;";
    private static final String GET_NEXT_KEY = "SELECT private FROM routingnextkeys WHERE peerid = ?;";
    private static final String GET_LEGACY = "SELECT legacy FROM routinglegacy WHERE kind = ? AND subject = ?;";
    private static final String PUT_LEGACY = "INSERT INTO routinglegacy (kind, subject, legacy) VALUES (?, ?, ?) ON CONFLICT DO NOTHING;";
    private static final String PUT_ALIAS = "INSERT INTO routingaliases (kind, alias, subject) VALUES (?, ?, ?) ON CONFLICT DO NOTHING;";
    private static final String GET_ALIAS = "SELECT subject FROM routingaliases WHERE kind = ? AND alias = ?;";
    private static final String REMOVE_PEERS = "DELETE FROM routingpeers WHERE subject = ?;";
    private static final String PUT_PEER = "INSERT INTO routingpeers (peer, subject, keyed) VALUES (?, ?, ?) " +
            "ON CONFLICT (peer) DO UPDATE SET subject = excluded.subject, keyed = excluded.keyed " +
            "WHERE routingpeers.keyed = 0 OR excluded.keyed = 1;";
    private static final String GET_PEER = "SELECT subject FROM routingpeers WHERE peer = ?;";
    private static final String GET_META = "SELECT value FROM routingmeta WHERE name = ?;";
    private static final String SET_META = "INSERT INTO routingmeta (name, value) VALUES (?, ?) " +
            "ON CONFLICT (name) DO UPDATE SET value = excluded.value;";
    private static final String PUT_RECOVERY_KEY = "INSERT INTO recoverykeys (identity, commitment, wrapped) VALUES (?, ?, ?) ON CONFLICT DO NOTHING;";
    private static final String GET_RECOVERY_KEY = "SELECT wrapped FROM recoverykeys WHERE identity = ? AND commitment = ?;";
    private static final String ADD_RECOVERY_COPY = "INSERT INTO recoverycopies (identity, created, copy) VALUES (?, ?, ?);";
    private static final String RECOVERY_COPIES = "SELECT copy FROM recoverycopies WHERE identity = ? ORDER BY created DESC;";
    private static final String COUNT_RECOVERY_COPIES = "SELECT COUNT(*) FROM recoverycopies WHERE identity = ? AND created >= ?;";
    private static final String SET_NEXT_KEY = "INSERT INTO routingnextkeys (peerid, private) VALUES (?, ?) ON CONFLICT DO NOTHING;";

    public static final class Legacy {
        public final Subject subject;
        public final Cborable legacy;
        public final List<Multihash> aliases;

        public Legacy(Subject subject, Cborable legacy, List<Multihash> aliases) {
            this.subject = subject;
            this.legacy = legacy;
            this.aliases = aliases;
        }
    }

    /** A record as stored: in full, or without its signature and with the hash it had with it. */
    public static final class Stored {
        public final SignedRecord record;
        public final Optional<byte[]> hash;

        public Stored(SignedRecord record, Optional<byte[]> hash) {
            this.record = record;
            this.hash = hash;
        }
    }

    public static final class State {
        public final Subject subject;
        public final byte[] hash;

        public State(Subject subject, byte[] hash) {
            this.subject = subject;
            this.hash = hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof State && subject.equals(((State) o).subject) && Arrays.equals(hash, ((State) o).hash);
        }

        @Override
        public int hashCode() {
            return subject.hashCode() * 31 + Arrays.hashCode(hash);
        }
    }

    /** A subject's records and the hash of them, to store. */
    public static final class Put {
        public final Subject subject;
        public final List<Stored> records;
        public final byte[] stateHash;

        public Put(Subject subject, List<Stored> records, byte[] stateHash) {
            this.subject = subject;
            this.records = records;
            this.stateHash = stateHash;
        }
    }

    private final Supplier<Connection> conn;
    /** One transaction at a time: each reads the sums it changes before writing them. */
    private final Object writes = new Object();

    public JdbcRoutingStore(Supplier<Connection> conn, SqlSupplier commands) {
        this.conn = conn;
        try (Connection c = getConnection()) {
            commands.createTable(commands.createRoutingTablesCommand(), c);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        if (getMeta(INDEXED).isEmpty())
            reindex();
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

    public List<Stored> get(Subject s) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(GET)) {
            select.setInt(1, s.kind);
            select.setBytes(2, s.idBytes());
            ResultSet rs = select.executeQuery();
            if (! rs.next())
                return Collections.emptyList();
            return ((CborObject.CborList) CborObject.fromByteArray(rs.getBytes(1))).value.stream()
                    .map(entry -> {
                        CborObject.CborMap m = (CborObject.CborMap) entry;
                        return new Stored(RecordType.untag(m), m.containsKey("h") ?
                                Optional.of(m.get("h", x -> ((CborObject.CborByteArray) x).value)) :
                                Optional.<byte[]>empty());
                    })
                    .collect(Collectors.toList());
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void put(Subject s, List<Stored> records, byte[] stateHash) {
        putAll(List.of(new Put(s, records, stateHash)));
    }

    /** Stores several subjects in one transaction, as when taking a page of a peer's log. */
    public void putAll(List<Put> puts) {
        transact(c -> {
            Sums sums = new Sums();
            try (PreparedStatement insert = c.prepareStatement(PUT)) {
                for (Put p : puts) {
                    insert.setInt(1, p.subject.kind);
                    insert.setBytes(2, p.subject.idBytes());
                    insert.setBytes(3, encode(p.records));
                    insert.setBytes(4, p.stateHash);
                    insert.executeUpdate();
                    if (! isPending(c, p.subject))
                        index(c, p.subject, Optional.of(p.stateHash), sums);
                }
            }
            sums.write(c);
        });
    }

    private interface Transaction {
        void run(Connection c) throws SQLException;
    }

    /** Begun and committed as statements, not through the connection's auto-commit flag: the connection may be one
     *  every caller shares, each of them setting that flag as it starts.
     */
    private void transact(Transaction t) {
        synchronized (writes) {
            try (Connection c = getConnection()) {
                execute(c, "BEGIN");
                try {
                    t.run(c);
                    execute(c, "COMMIT");
                } catch (SQLException | RuntimeException e) {
                    try {
                        execute(c, "ROLLBACK");
                    } catch (SQLException x) {}
                    throw e;
                }
            } catch (SQLException e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** What a transaction adds to each range's count and fingerprint, written once at its end. */
    private static final class Sums {
        final Map<List<Integer>, long[]> counts = new HashMap<>();
        final Map<List<Integer>, java.math.BigInteger> sums = new HashMap<>();

        void add(int kind, int slot, long count, java.math.BigInteger item) {
            for (int depth = 0; depth <= Reconciliation.MAX_DEPTH; depth++) {
                List<Integer> key = List.of(kind, depth, slot >> (8 * (Reconciliation.MAX_DEPTH - depth)));
                counts.computeIfAbsent(key, k -> new long[1])[0] += count;
                sums.merge(key, item, java.math.BigInteger::add);
            }
        }

        void write(Connection c) throws SQLException {
            try (PreparedStatement select = c.prepareStatement(GET_SUM);
                 PreparedStatement insert = c.prepareStatement(PUT_SUM);
                 PreparedStatement remove = c.prepareStatement(REMOVE_SUM)) {
                for (Map.Entry<List<Integer>, java.math.BigInteger> e : sums.entrySet()) {
                    List<Integer> key = e.getKey();
                    for (int i = 0; i < 3; i++)
                        select.setInt(i + 1, key.get(i));
                    long count = counts.get(key)[0];
                    java.math.BigInteger sum = e.getValue();
                    try (ResultSet rs = select.executeQuery()) {
                        if (rs.next()) {
                            count += rs.getLong(1);
                            sum = sum.add(new java.math.BigInteger(1, rs.getBytes(2)));
                        }
                    }
                    PreparedStatement update = count == 0 ? remove : insert;
                    for (int i = 0; i < 3; i++)
                        update.setInt(i + 1, key.get(i));
                    if (count != 0) {
                        update.setLong(4, count);
                        update.setBytes(5, Reconciliation.bytes(Reconciliation.add(java.math.BigInteger.ZERO, sum)));
                    }
                    update.executeUpdate();
                }
            }
        }
    }

    private static boolean isPending(Connection c, Subject s) throws SQLException {
        try (PreparedStatement select = c.prepareStatement(IS_PENDING)) {
            select.setInt(1, s.kind);
            select.setBytes(2, s.idBytes());
            try (ResultSet rs = select.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Sets, or with no state hash removes, what reconciliation sees of a subject. */
    private static void index(Connection c, Subject s, Optional<byte[]> stateHash, Sums sums) throws SQLException {
        byte[] id = s.idBytes();
        int slot = Reconciliation.slot(id);
        Optional<byte[]> old;
        try (PreparedStatement select = c.prepareStatement(GET_STATE)) {
            select.setInt(1, s.kind);
            select.setInt(2, slot);
            select.setBytes(3, id);
            try (ResultSet rs = select.executeQuery()) {
                old = rs.next() ? Optional.of(rs.getBytes(1)) : Optional.empty();
            }
        }
        if (old.isEmpty() && stateHash.isEmpty())
            return;
        if (old.isPresent() && stateHash.isPresent() && Arrays.equals(old.get(), stateHash.get()))
            return;
        try (PreparedStatement update = c.prepareStatement(stateHash.isPresent() ? PUT_STATE : REMOVE_STATE)) {
            update.setInt(1, s.kind);
            update.setInt(2, slot);
            update.setBytes(3, id);
            if (stateHash.isPresent())
                update.setBytes(4, stateHash.get());
            update.executeUpdate();
        }
        if (old.isPresent())
            sums.add(s.kind, slot, -1, Reconciliation.item(id, old.get()).negate());
        if (stateHash.isPresent())
            sums.add(s.kind, slot, 1, Reconciliation.item(id, stateHash.get()));
    }

    /** Builds what reconciliation reads from the records held, for a store written before it was kept. */
    private void reindex() {
        transact(c -> {
            execute(c, "DELETE FROM routingstates");
            execute(c, "DELETE FROM routingsums");
            Sums sums = new Sums();
            for (int kind : new int[]{Subject.IDENTITY, Subject.SERVER}) {
                byte[] after = new byte[0];
                while (true) {
                    List<State> page = new ArrayList<>();
                    try (PreparedStatement select = c.prepareStatement(UNINDEXED)) {
                        select.setInt(1, kind);
                        select.setBytes(2, after);
                        select.setInt(3, 1_000);
                        try (ResultSet rs = select.executeQuery()) {
                            while (rs.next())
                                page.add(new State(subject(kind, rs.getBytes(1)), rs.getBytes(2)));
                        }
                    }
                    for (State st : page)
                        index(c, st.subject, Optional.of(st.hash), sums);
                    if (page.size() < 1_000)
                        break;
                    after = page.get(page.size() - 1).subject.idBytes();
                }
            }
            sums.write(c);
        });
        setMeta(INDEXED, new byte[]{1});
    }

    private static byte[] encode(List<Stored> records) {
        List<Cborable> res = new ArrayList<>();
        for (Stored r : records) {
            CborObject.CborMap tagged = RecordType.tag(r.record);
            if (r.hash.isEmpty()) {
                res.add(tagged);
                continue;
            }
            Map<String, Cborable> m = new TreeMap<>();
            for (String k : tagged.keySet())
                m.put(k, tagged.get(k));
            m.put("h", new CborObject.CborByteArray(r.hash.get()));
            res.add(CborObject.CborMap.build(m));
        }
        return new CborObject.CborList(res).toByteArray();
    }

    /** States in a range after a place in it, ordered by slot and then subject id: what reconciliation compares,
     *  so not the inceptions this server still owes proof of work for.
     */
    public List<State> states(int kind, Reconciliation.Range range, Reconciliation.Cursor after, int limit) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(STATES)) {
            select.setInt(1, kind);
            select.setInt(2, range.from());
            select.setInt(3, range.to());
            select.setInt(4, after.slot);
            select.setInt(5, after.slot);
            select.setBytes(6, after.id);
            select.setInt(7, limit);
            ResultSet rs = select.executeQuery();
            List<State> res = new ArrayList<>();
            while (rs.next())
                res.add(new State(subject(kind, rs.getBytes(1)), rs.getBytes(2)));
            return res;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /** A range's count and fingerprint, kept up to date as subjects change rather than read from them. */
    public Reconciliation.Summary summary(int kind, Reconciliation.Range range) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(GET_SUM)) {
            select.setInt(1, kind);
            select.setInt(2, range.depth);
            select.setInt(3, range.prefix);
            ResultSet rs = select.executeQuery();
            return rs.next() ? new Reconciliation.Summary(rs.getLong(1), rs.getBytes(2)) : Reconciliation.Summary.EMPTY;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /** The summaries of a range's children that hold anything, by the last byte of their prefix. */
    public Map<Integer, Reconciliation.Summary> children(int kind, Reconciliation.Range parent) {
        if (parent.depth >= Reconciliation.MAX_DEPTH)
            throw new IllegalStateException("A range at depth " + parent.depth + " has no children");
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(CHILD_SUMS)) {
            select.setInt(1, kind);
            select.setInt(2, parent.depth + 1);
            select.setInt(3, parent.prefix << 8);
            select.setInt(4, (parent.prefix << 8) + Reconciliation.FANOUT - 1);
            ResultSet rs = select.executeQuery();
            Map<Integer, Reconciliation.Summary> res = new TreeMap<>();
            while (rs.next())
                res.put(rs.getInt(1) & 0xff, new Reconciliation.Summary(rs.getLong(2), rs.getBytes(3)));
            return res;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public long count(int kind) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(COUNT)) {
            select.setInt(1, kind);
            ResultSet rs = select.executeQuery();
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private static Subject subject(int kind, byte[] id) {
        return new Subject(kind, kind == Subject.IDENTITY ? Cid.cast(id) : Multihash.decode(id));
    }

    /** An inception this server owes proof of work for, which reconciliation doesn't see until it is done. */
    public void addPending(Subject s) {
        transact(c -> {
            update(c, ADD_PENDING, s);
            Sums sums = new Sums();
            index(c, s, Optional.empty(), sums);
            sums.write(c);
        });
    }

    public void removePending(Subject s) {
        transact(c -> {
            update(c, REMOVE_PENDING, s);
            Optional<byte[]> stateHash;
            try (PreparedStatement select = c.prepareStatement(GET_STATEHASH)) {
                select.setInt(1, s.kind);
                select.setBytes(2, s.idBytes());
                try (ResultSet rs = select.executeQuery()) {
                    stateHash = rs.next() ? Optional.of(rs.getBytes(1)) : Optional.empty();
                }
            }
            Sums sums = new Sums();
            index(c, s, stateHash, sums);
            sums.write(c);
        });
    }

    private static void update(Connection c, String sql, Subject s) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(sql)) {
            stmt.setInt(1, s.kind);
            stmt.setBytes(2, s.idBytes());
            stmt.executeUpdate();
        }
    }

    public List<Subject> pending() {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(PENDING)) {
            ResultSet rs = select.executeQuery();
            List<Subject> res = new ArrayList<>();
            while (rs.next())
                res.add(subject(rs.getInt(1), rs.getBytes(2)));
            return res;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /** The secret key a server's inception commits to, while the operator has not moved it off the server. */
    public Optional<byte[]> nextKey(Multihash peerId) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(GET_NEXT_KEY)) {
            select.setBytes(1, peerId.toBytes());
            ResultSet rs = select.executeQuery();
            return rs.next() ? Optional.of(rs.getBytes(1)) : Optional.empty();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void setNextKey(Multihash peerId, byte[] secret) {
        try (Connection c = getConnection();
             PreparedStatement insert = c.prepareStatement(SET_NEXT_KEY)) {
            insert.setBytes(1, peerId.toBytes());
            insert.setBytes(2, secret);
            insert.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /** Stores legacy bases and their aliases, all in one transaction. */
    public void putLegacy(List<Legacy> entries) {
        try (Connection c = getConnection();
             PreparedStatement legacy = c.prepareStatement(PUT_LEGACY);
             PreparedStatement alias = c.prepareStatement(PUT_ALIAS)) {
            c.setAutoCommit(false);
            try {
                for (Legacy e : entries) {
                    legacy.setInt(1, e.subject.kind);
                    legacy.setBytes(2, e.subject.idBytes());
                    legacy.setBytes(3, e.legacy.serialize());
                    legacy.addBatch();
                    for (Multihash a : e.aliases) {
                        alias.setInt(1, e.subject.kind);
                        alias.setBytes(2, new Subject(e.subject.kind, a).idBytes());
                        alias.setBytes(3, e.subject.idBytes());
                        alias.addBatch();
                    }
                }
                legacy.executeBatch();
                alias.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public Optional<CborObject> getLegacy(Subject s) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(GET_LEGACY)) {
            select.setInt(1, s.kind);
            select.setBytes(2, s.idBytes());
            ResultSet rs = select.executeQuery();
            return rs.next() ? Optional.of(CborObject.fromByteArray(rs.getBytes(1))) : Optional.empty();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /** The subject a later legacy key or peerid belongs to. */
    public Optional<Subject> resolveAlias(Subject alias) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(GET_ALIAS)) {
            select.setInt(1, alias.kind);
            select.setBytes(2, alias.idBytes());
            ResultSet rs = select.executeQuery();
            return rs.next() ? Optional.of(subject(alias.kind, rs.getBytes(1))) : Optional.empty();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /** Records the peerids a server is reached by other than its first: its current key's, which its rotations
     *  prove, and its endpoint's, which it only claims, so a key's mapping is never replaced by a claim.
     */
    public void putPeers(Subject server, Optional<Multihash> key, Optional<Multihash> endpoint) {
        try (Connection c = getConnection();
             PreparedStatement remove = c.prepareStatement(REMOVE_PEERS);
             PreparedStatement insert = c.prepareStatement(PUT_PEER)) {
            remove.setBytes(1, server.idBytes());
            remove.executeUpdate();
            for (int i = 0; i < 2; i++) {
                Optional<Multihash> peer = i == 0 ? key : endpoint;
                if (peer.isEmpty())
                    continue;
                insert.setBytes(1, peer.get().bareMultihash().toBytes());
                insert.setBytes(2, server.idBytes());
                insert.setInt(3, i == 0 ? 1 : 0);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public Optional<Subject> resolvePeer(Multihash peer) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(GET_PEER)) {
            select.setBytes(1, peer.bareMultihash().toBytes());
            ResultSet rs = select.executeQuery();
            return rs.next() ? Optional.of(Subject.server(Multihash.decode(rs.getBytes(1)))) : Optional.empty();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public Optional<byte[]> getMeta(String name) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(GET_META)) {
            select.setString(1, name);
            ResultSet rs = select.executeQuery();
            return rs.next() ? Optional.of(rs.getBytes(1)) : Optional.empty();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void setMeta(String name, byte[] value) {
        try (Connection c = getConnection();
             PreparedStatement insert = c.prepareStatement(SET_META)) {
            insert.setString(1, name);
            insert.setBytes(2, value);
            insert.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /** Write once per commitment, so a key wrapped before a compromise cannot be replaced after it. */
    public boolean putRecoveryKey(byte[] identity, byte[] commitment, byte[] wrapped) {
        try (Connection c = getConnection();
             PreparedStatement insert = c.prepareStatement(PUT_RECOVERY_KEY)) {
            insert.setBytes(1, identity);
            insert.setBytes(2, commitment);
            insert.setBytes(3, wrapped);
            return insert.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public Optional<byte[]> getRecoveryKey(byte[] identity, byte[] commitment) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(GET_RECOVERY_KEY)) {
            select.setBytes(1, identity);
            select.setBytes(2, commitment);
            ResultSet rs = select.executeQuery();
            return rs.next() ? Optional.of(rs.getBytes(1)) : Optional.empty();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void addRecoveryCopy(byte[] identity, long created, byte[] copy) {
        try (Connection c = getConnection();
             PreparedStatement insert = c.prepareStatement(ADD_RECOVERY_COPY)) {
            insert.setBytes(1, identity);
            insert.setLong(2, created);
            insert.setBytes(3, copy);
            insert.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /** Newest first. */
    public List<byte[]> getRecoveryCopies(byte[] identity) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(RECOVERY_COPIES)) {
            select.setBytes(1, identity);
            ResultSet rs = select.executeQuery();
            List<byte[]> res = new ArrayList<>();
            while (rs.next())
                res.add(rs.getBytes(1));
            return res;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public long countRecoveryCopies(byte[] identity, long since) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(COUNT_RECOVERY_COPIES)) {
            select.setBytes(1, identity);
            select.setLong(2, since);
            ResultSet rs = select.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
