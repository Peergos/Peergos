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
    private static final String NOT_PENDING = "AND NOT EXISTS (SELECT 1 FROM routingpending p WHERE p.kind = r.kind AND p.subject = r.subject) ";
    private static final String STATES = "SELECT subject, statehash FROM routingrecords r WHERE kind = ? AND subject > ? " +
            NOT_PENDING + "ORDER BY subject LIMIT ?;";
    private static final String STATES_UP_TO = "SELECT subject, statehash FROM routingrecords r WHERE kind = ? AND subject > ? " +
            "AND subject <= ? " + NOT_PENDING + "ORDER BY subject LIMIT ?;";
    private static final String COUNT = "SELECT COUNT(*) FROM routingrecords WHERE kind = ?;";
    private static final String ADD_PENDING = "INSERT INTO routingpending (kind, subject) VALUES (?, ?) ON CONFLICT DO NOTHING;";
    private static final String REMOVE_PENDING = "DELETE FROM routingpending WHERE kind = ? AND subject = ?;";
    private static final String PENDING = "SELECT kind, subject FROM routingpending;";
    private static final String GET_NEXT_KEY = "SELECT private FROM routingnextkeys WHERE peerid = ?;";
    private static final String GET_LEGACY = "SELECT legacy FROM routinglegacy WHERE kind = ? AND subject = ?;";
    private static final String PUT_LEGACY = "INSERT INTO routinglegacy (kind, subject, legacy) VALUES (?, ?, ?) ON CONFLICT DO NOTHING;";
    private static final String PUT_ALIAS = "INSERT INTO routingaliases (kind, alias, subject) VALUES (?, ?, ?) ON CONFLICT DO NOTHING;";
    private static final String GET_ALIAS = "SELECT subject FROM routingaliases WHERE kind = ? AND alias = ?;";
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
    }

    private final Supplier<Connection> conn;

    public JdbcRoutingStore(Supplier<Connection> conn, SqlSupplier commands) {
        this.conn = conn;
        try (Connection c = getConnection()) {
            commands.createTable(commands.createRoutingTablesCommand(), c);
        } catch (SQLException e) {
            throw new RuntimeException(e);
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
        try (Connection c = getConnection();
             PreparedStatement insert = c.prepareStatement(PUT)) {
            insert.setInt(1, s.kind);
            insert.setBytes(2, s.idBytes());
            insert.setBytes(3, encode(records));
            insert.setBytes(4, stateHash);
            insert.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
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

    /** States in subject order after the given subject id, for reconciliation and snapshots. */
    public List<State> states(int kind, byte[] after, int limit) {
        return states(kind, after, Optional.empty(), limit);
    }

    /** States with subject ids after one id, and up to another if given, in order: what reconciliation compares,
     *  so not the inceptions this server still owes proof of work for.
     */
    public List<State> states(int kind, byte[] after, Optional<byte[]> upTo, int limit) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(upTo.isPresent() ? STATES_UP_TO : STATES)) {
            select.setInt(1, kind);
            select.setBytes(2, after);
            if (upTo.isPresent())
                select.setBytes(3, upTo.get());
            select.setInt(upTo.isPresent() ? 4 : 3, limit);
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

    public void addPending(Subject s) {
        update(ADD_PENDING, s);
    }

    public void removePending(Subject s) {
        update(REMOVE_PENDING, s);
    }

    private void update(String sql, Subject s) {
        try (Connection c = getConnection();
             PreparedStatement stmt = c.prepareStatement(sql)) {
            stmt.setInt(1, s.kind);
            stmt.setBytes(2, s.idBytes());
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
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
