package peergos.server.storage.auth;

import peergos.server.sql.*;
import peergos.server.util.Logging;
import peergos.shared.cbor.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

public class JdbcBatCave implements BatCave, BatCache {

    private static final Logger LOG = Logging.LOG();

    private static final String CREATE = "INSERT INTO bats (username, id, bat) VALUES(?, ?, ?)";
    private static final String GET = "SELECT bat FROM bats WHERE id = ? LIMIT 1;";
    private static final String GET_USER = "SELECT * FROM bats WHERE username = ?;";

    private volatile boolean isClosed;
    private Supplier<Connection> conn;

    public JdbcBatCave(Supplier<Connection> conn, SqlSupplier commands) {
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
            commands.createTable(commands.createBatStoreTableCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<Bat> getBat(BatId id) {
        if (id.isInline())
            throw new IllegalStateException("Stored BATs cannot be inline.");

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET)) {
            stmt.setString(1, new String(Base64.getEncoder().encode(id.serialize())));
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Bat bat = Bat.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("bat"))));
                return Optional.of(bat);
            }

            return Optional.empty();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public CompletableFuture<List<BatWithId>> getUserBats(String username, byte[] auth) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_USER)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            List<BatWithId> res = new ArrayList<>();
            while (rs.next()) {
                res.add(new BatWithId(Bat.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("bat")))),
                        BatId.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("id")))).id));
            }
            return Futures.of(res);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            return Futures.errored(sqe);
        }
    }

    @Override
    public CompletableFuture<Boolean> addBat(String username, BatId id, Bat bat, byte[] auth) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(CREATE)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

            insert.setString(1, username);
            insert.setString(2, new String(Base64.getEncoder().encode(id.serialize())));
            insert.setString(3, new String(Base64.getEncoder().encode(bat.serialize())));
            int changed = insert.executeUpdate();
            return CompletableFuture.completedFuture(changed > 0);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override
    public CompletableFuture<List<BatWithId>> getUserBats(String username) {
        return getUserBats(username, (byte[])null);
    }

    @Override
    public CompletableFuture<Boolean> setUserBats(String username, List<BatWithId> bats) {
        List<BatWithId> existing = getUserBats(username).join();
        for (BatWithId bat : bats) {
            if (! existing.contains(bat))
                addBat(username, bat.id(), bat.bat, (byte[])null).join();
        }
        return Futures.of(true);
    }
}
