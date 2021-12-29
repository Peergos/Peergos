package peergos.server.storage.auth;

import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.Logging;
import peergos.shared.io.ipfs.cid.*;

import java.sql.*;
import java.util.function.*;
import java.util.logging.*;

public class JdbcLegacyRawBlockStore {

    private static final Logger LOG = Logging.LOG();

    private static final String ADD = "INSERT INTO legacyraw (cid) VALUES(?)";
    private static final String CONTAINS = "SELECT * FROM legacyraw WHERE cid = ?;";

    private Supplier<Connection> conn;

    public JdbcLegacyRawBlockStore(Supplier<Connection> conn) {
        this.conn = conn;
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

    public synchronized void init(SqlSupplier commands, DeletableContentAddressedStorage store) {
        try (Connection conn = getConnection();
             PreparedStatement exists = conn.prepareStatement(commands.tableExistsCommand())) {
            exists.setString(1, "legacyraw");
            ResultSet resultSet = exists.executeQuery();
            if (resultSet.next())
                return;
            commands.createTable(commands.createLegacyRawBlocksTableCommand(), conn);
            System.out.println("Upgrading to permissioned blocks...");
            store.getAllBlockHashes().forEach(this::addBlock);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean addBlock(Cid hash) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(ADD)) {
            stmt.setString(1, hash.toString());
            int updated = stmt.executeUpdate();
            return updated == 1;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public boolean hasBlock(Cid hash) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(CONTAINS)) {
            stmt.setString(1, hash.toString());
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }
}
