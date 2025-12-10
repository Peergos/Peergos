package peergos.server.storage;

import peergos.server.sql.SqlSupplier;
import peergos.server.util.Logging;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JdbcPartitionStatus implements PartitionStatus {

    private static final Logger LOG = Logging.LOG();
    private static final String GET = "SELECT * FROM partitioned;";
    private static final String SET_DONE = "INSERT INTO partitioned (done) VALUES(true);";

    private Supplier<Connection> conn;
    private final SqlSupplier commands;

    public JdbcPartitionStatus(Supplier<Connection> conn, SqlSupplier commands) {
        this.conn = conn;
        this.commands = commands;
        init(commands);
    }

    private Connection getConnection() {
        return getConnection(true, true);
    }

    private Connection getConnection(boolean autocommit, boolean serializable) {
        Connection connection = conn.get();
        try {
            if (autocommit)
                connection.setAutoCommit(true);
            if (serializable)
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            else
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            return connection;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void init(SqlSupplier commands) {
        try (Connection conn = getConnection()) {
            commands.createTable(commands.createPartitionStatusTableCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isDone() {
        try (Connection conn = getConnection(false, false);
             PreparedStatement stmt = conn.prepareStatement(GET)) {
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void complete() {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(SET_DONE)) {
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }
}
