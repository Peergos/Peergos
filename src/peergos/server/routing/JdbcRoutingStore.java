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
    private static final String STATES = "SELECT subject, statehash FROM routingrecords WHERE kind = ? AND subject > ? " +
            "ORDER BY subject LIMIT ?;";
    private static final String COUNT = "SELECT COUNT(*) FROM routingrecords WHERE kind = ?;";
    private static final String ADD_PENDING = "INSERT INTO routingpending (kind, subject) VALUES (?, ?) ON CONFLICT DO NOTHING;";
    private static final String REMOVE_PENDING = "DELETE FROM routingpending WHERE kind = ? AND subject = ?;";
    private static final String PENDING = "SELECT kind, subject FROM routingpending;";
    private static final String GET_NEXT_KEY = "SELECT private FROM routingnextkeys WHERE peerid = ?;";
    private static final String SET_NEXT_KEY = "INSERT INTO routingnextkeys (peerid, private) VALUES (?, ?) ON CONFLICT DO NOTHING;";

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

    public List<SignedRecord> get(Subject s) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(GET)) {
            select.setInt(1, s.kind);
            select.setBytes(2, s.idBytes());
            ResultSet rs = select.executeQuery();
            if (! rs.next())
                return Collections.emptyList();
            return ((CborObject.CborList) CborObject.fromByteArray(rs.getBytes(1))).value.stream()
                    .map(RecordType::untag)
                    .collect(Collectors.toList());
        } catch (SQLException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void put(Subject s, List<? extends SignedRecord> records, byte[] stateHash) {
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

    public static byte[] encode(List<? extends SignedRecord> records) {
        return new CborObject.CborList(records.stream()
                .map(RecordType::tag)
                .collect(Collectors.toList())).toByteArray();
    }

    /** States in subject order after the given subject id, for reconciliation and snapshots. */
    public List<State> states(int kind, byte[] after, int limit) {
        try (Connection c = getConnection();
             PreparedStatement select = c.prepareStatement(STATES)) {
            select.setInt(1, kind);
            select.setBytes(2, after);
            select.setInt(3, limit);
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
}
