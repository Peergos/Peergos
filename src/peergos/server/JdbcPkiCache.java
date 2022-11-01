package peergos.server;

import peergos.server.sql.*;
import peergos.server.util.Logging;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

public class JdbcPkiCache implements PkiCache {
    private static final Logger LOG = Logging.LOG();

    private static final String CREATE = "INSERT INTO pkistate (username, chain, pubkey) VALUES(?, ?, ?)";
    private static final String UPDATE = "UPDATE pkistate SET chain=?, pubkey=? WHERE username = ?";
    private static final String GET_BY_USERNAME = "SELECT * FROM pkistate WHERE username = ? LIMIT 1;";
    private static final String GET_BY_KEY = "SELECT * FROM pkistate WHERE pubkey = ? LIMIT 1;";

    private volatile boolean isClosed;
    private Supplier<Connection> conn;

    public JdbcPkiCache(Supplier<Connection> conn, SqlSupplier commands) {
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
            commands.createTable("CREATE TABLE IF NOT EXISTS pkistate " +
                    "(username text primary key not null, chain text not null, pubkey text not null); " +
                    "CREATE UNIQUE INDEX IF NOT EXISTS pkistate_index ON pkistate (username);", conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean hasUser(String username) {
        try (Connection conn = getConnection();
             PreparedStatement present = conn.prepareStatement(GET_BY_USERNAME)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            present.setString(1, username);
            ResultSet rs = present.executeQuery();
            if (rs.next()) {
                return true;
            }
            return false;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        try (Connection conn = getConnection();
             PreparedStatement present = conn.prepareStatement(GET_BY_USERNAME)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            present.setString(1, username);
            ResultSet rs = present.executeQuery();
            if (rs.next()) {
                return Futures.of(((CborObject.CborList)CborObject.fromByteArray(rs.getBytes("chain"))).map(UserPublicKeyLink::fromCbor));
            }
            throw new IllegalStateException("Unknown user " + username);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public CompletableFuture<Boolean> setChain(String username, List<UserPublicKeyLink> chain) {
        PublicKeyHash owner = chain.get(chain.size() - 1).owner;
        if (hasUser(username)) {
            try (Connection conn = getConnection();
                 PreparedStatement insert = conn.prepareStatement(UPDATE)) {
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

                insert.setBytes(1, new CborObject.CborList(chain).serialize());
                insert.setString(2, new String(Base64.getEncoder().encode(owner.serialize())));
                insert.setString(3, username);
                int changed = insert.executeUpdate();
                return Futures.of(changed > 0);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return Futures.of(false);
            }
        } else {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(CREATE)) {
                stmt.setString(1, username);
                stmt.setBytes(2, new CborObject.CborList(chain).serialize());
                stmt.setString(3, new String(Base64.getEncoder().encode(owner.serialize())));
                stmt.executeUpdate();
                return Futures.of(true);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return Futures.of(false);
            }
        }
    }

    public CompletableFuture<String> getUsername(PublicKeyHash identity) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_BY_KEY)) {
            stmt.setString(1, new String(Base64.getEncoder().encode(identity.serialize())));
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return Futures.of(rs.getString("username"));
            }

            throw new IllegalStateException("Unknown user identity key.");
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public synchronized void close() {
        if (isClosed)
            return;

        isClosed = true;
    }
}
