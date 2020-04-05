package peergos.server.storage;

import peergos.server.sql.*;
import peergos.server.util.Logging;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;

import java.sql.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;

public class JdbcTransactionStore implements TransactionStore {
	private static final Logger LOG = Logging.LOG();

    private static final String SELECT_TRANSACTIONS_BLOCKS = "SELECT tid, owner, hash FROM transactions;";
    private static final String DELETE_TRANSACTION = "DELETE FROM transactions WHERE tid = ? AND owner = ?;";

    private Supplier<Connection> conn;
    private final SqlSupplier commands;
    private volatile boolean isClosed;

    public JdbcTransactionStore(Supplier<Connection> conn, SqlSupplier commands) {
        this.conn = conn;
        this.commands = commands;
        init(commands);
    }

    private Connection getConnection() {
        Connection connection = conn.get();
        try {
            connection.setAutoCommit(true);
            return connection;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void init(SqlSupplier commands) {
        if (isClosed)
            return;

        try (Connection conn = getConnection()) {
            commands.createTable(commands.createTransactionsTableCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public TransactionId startTransaction(PublicKeyHash owner) {
        return new TransactionId(UUID.randomUUID().toString());
    }

    @Override
    public void addBlock(Multihash hash, TransactionId tid, PublicKeyHash owner) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(commands.insertTransactionCommand())) {
            insert.clearParameters();
            insert.setString(1, tid.toString());
            insert.setString(2, owner.toString());
            insert.setString(3, hash.toString());
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    @Override
    public void closeTransaction(PublicKeyHash owner, TransactionId tid) {
        try (Connection conn = getConnection();
             PreparedStatement delete = conn.prepareStatement(DELETE_TRANSACTION)) {
            delete.setString(1, tid.toString());
            delete.setString(2, owner.toString());
            delete.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    @Override
    public List<Multihash> getOpenTransactionBlocks() {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(SELECT_TRANSACTIONS_BLOCKS)) {
            ResultSet rs = select.executeQuery();
            List<Multihash> results = new ArrayList<>();
            while (rs.next())
            {
                String tid = rs.getString("tid");
                String owner = rs.getString("owner");
                String hash = rs.getString("hash");
                results.add(Cid.decode(hash));
            }
            return results;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public synchronized void close() {
        if (isClosed)
            return;
        isClosed = true;
    }

    public static JdbcTransactionStore build(Supplier<Connection> conn, SqlSupplier commands) {
        return new JdbcTransactionStore(conn, commands);
    }
}
