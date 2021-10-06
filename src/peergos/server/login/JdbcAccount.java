package peergos.server.login;

import peergos.server.sql.*;
import peergos.server.util.Logging;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

public class JdbcAccount {
    private static final Logger LOG = Logging.LOG();

    private static final String CREATE = "INSERT INTO login (username, entry, reader) VALUES(?, ?, ?)";
    private static final String UPDATE = "UPDATE login SET entry=?, reader=? WHERE username = ?";
    private static final String GET_AUTH = "SELECT * FROM login WHERE username = ? AND reader = ? LIMIT 1;";
    private static final String GET = "SELECT * FROM login WHERE username = ? LIMIT 1;";

    private volatile boolean isClosed;
    private Supplier<Connection> conn;

    public JdbcAccount(Supplier<Connection> conn, SqlSupplier commands) {
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
            commands.createTable(commands.createAccountTableCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean hasEntry(String username) {
        try (Connection conn = getConnection();
             PreparedStatement present = conn.prepareStatement(GET)) {
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

    public CompletableFuture<Boolean> setLoginData(LoginData login) {
        if (hasEntry(login.username)) {
            try (Connection conn = getConnection();
                 PreparedStatement insert = conn.prepareStatement(UPDATE)) {
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

                insert.setString(1, new String(Base64.getEncoder().encode(login.entryPoints.serialize())));
                insert.setString(2, new String(Base64.getEncoder().encode(login.authorisedReader.serialize())));
                insert.setString(3, login.username);
                int changed = insert.executeUpdate();
                return CompletableFuture.completedFuture(changed > 0);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return CompletableFuture.completedFuture(false);
            }
        } else {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(CREATE)) {
                stmt.setString(1, login.username);
                stmt.setString(2, new String(Base64.getEncoder().encode(login.entryPoints.serialize())));
                stmt.setString(3, new String(Base64.getEncoder().encode(login.authorisedReader.serialize())));
                stmt.executeUpdate();
                return CompletableFuture.completedFuture(true);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return CompletableFuture.completedFuture(false);
            }
        }
    }

    public CompletableFuture<UserStaticData> getEntryData(String username, PublicSigningKey authorisedReader) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_AUTH)) {
            stmt.setString(1, username);
            stmt.setString(2, new String(Base64.getEncoder().encode(authorisedReader.serialize())));
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return CompletableFuture.completedFuture(UserStaticData.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("entry")))));
            }

            if (hasEntry(username))
                return Futures.errored(new IllegalStateException("Incorrect password"));
            return Futures.errored(new IllegalStateException("Unknown username. Did you enter it correctly?"));
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            return Futures.errored(sqe);
        }
    }

    public Optional<LoginData> getLoginData(String username) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                UserStaticData entry = UserStaticData.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("entry"))));
                PublicSigningKey authorisedReader = PublicSigningKey.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("reader"))));
                return Optional.of(new LoginData(username, entry, authorisedReader, Optional.empty()));
            }

            return Optional.empty();
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
