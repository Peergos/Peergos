package peergos.server.storage;

import peergos.server.sql.*;
import peergos.server.util.Logging;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.*;
import java.util.stream.*;

public class JdbcTransactionStore implements TransactionStore {
	private static final Logger LOG = Logging.LOG();

    public static final String INSERT_TRANSACTIONS_BLOCK = "INSERT OR IGNORE INTO transactions (tid, owner, hash) VALUES (?, ?, ?);";
    private static final String SELECT_TRANSACTIONS_BLOCKS = "SELECT tid, owner, hash FROM transactions;";
    private static final String DELETE_TRANSACTION = "DELETE FROM transactions WHERE tid = ? AND owner = ?;";

    private Connection conn;
    private final SqlSupplier commands;
    private volatile boolean isClosed;
    private AtomicLong counter =  new AtomicLong(0);
    private PreparedStatement insert;
    public JdbcTransactionStore(Connection conn, SqlSupplier commands) {
        this.conn = conn;

        this.commands = commands;
        init(commands);
        try {
            this.conn.setAutoCommit(false);
            this.insert = conn.prepareStatement(commands.insertTransactionCommand());
        } catch (SQLException sqle) {
            LOG.log(Level.WARNING, "", sqle);
        }
    }

    private synchronized void init(SqlSupplier commands) {
        if (isClosed)
            return;

        try {
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
        long l = counter.incrementAndGet();
        try {
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
        try (PreparedStatement delete = conn.prepareStatement(DELETE_TRANSACTION)) {
            delete.setString(1, tid.toString());
            delete.setString(2, owner.toString());
            delete.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    @Override
    public List<Multihash> getOpenTransactionBlocks() {
        try (PreparedStatement select = conn.prepareStatement(SELECT_TRANSACTIONS_BLOCKS)) {
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
        try {
            if (conn != null)
                conn.close();
            isClosed = true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }

    public static JdbcTransactionStore build(Connection conn, SqlSupplier commands) {
        return new JdbcTransactionStore(conn, commands);
    }
}
