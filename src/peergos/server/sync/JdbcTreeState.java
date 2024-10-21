package peergos.server.sync;

import peergos.server.sql.SqlSupplier;
import peergos.server.sql.SqliteCommands;
import peergos.server.util.Sqlite;
import peergos.shared.user.fs.Blake3state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class JdbcTreeState implements SyncState {
    private static final String INSERT = "INSERT INTO syncstate (path, b3, modtime, size) VALUES(?, ?, ?, ?);";
    private static final String UPDATE = "UPDATE syncstate SET b3=?, modtime=?, size=? WHERE path=?;";
    private static final String DELETE = "DELETE from syncstate WHERE path = ?;";
    private static final String GET_BY_PATH = "SELECT path, b3, modtime, size FROM syncstate WHERE path = ?;";
    private static final String GET_BY_HASH = "SELECT path, b3, modtime, size FROM syncstate WHERE b3 = ?;";

    private final Supplier<Connection> conn;
    private final SqlSupplier cmds = new SqliteCommands();

    public JdbcTreeState(String sqlFile) {
        try {
            Connection memory = Sqlite.build(sqlFile);
            // We need a connection that ignores close
            Connection instance = new Sqlite.UncloseableConnection(memory);
            this.conn = () -> instance;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        init();
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

    private synchronized void init() {
        try (Connection conn = getConnection()) {
            cmds.createTable("CREATE TABLE IF NOT EXISTS syncstate (path text primary key not null, b3 blob, modtime bigint not null, size bigint not null); " +
                    "CREATE INDEX IF NOT EXISTS sync_hash_index ON syncstate (b3);", conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public FileState byPath(String path) {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(GET_BY_PATH)) {
            select.setString(1, path);
            ResultSet rs = select.executeQuery();
            if (rs.next())
                return new FileState(rs.getString(1), rs.getLong(3), rs.getLong(4), new Blake3state(rs.getBytes(2)));

            return null;
        } catch (SQLException sqe) {
            throw new IllegalStateException(sqe);
        }
    }

    @Override
    public void add(FileState fs) {
        FileState existing = byPath(fs.relPath);
        if (existing != null) {
            try (Connection conn = getConnection();
                 PreparedStatement update = conn.prepareStatement(UPDATE)) {
                update.setBytes(1, fs.hash.hash);
                update.setLong(2, fs.modificationTime);
                update.setLong(3, fs.size);
                update.setString(4, fs.relPath);
                update.executeUpdate();
            } catch (SQLException sqe) {
                throw new IllegalStateException(sqe);
            }
        } else
            try (Connection conn = getConnection();
                 PreparedStatement insert = conn.prepareStatement(INSERT)) {
                insert.setString(1, fs.relPath);
                insert.setBytes(2, fs.hash.hash);
                insert.setLong(3, fs.modificationTime);
                insert.setLong(4, fs.size);
                insert.executeUpdate();
            } catch (SQLException sqe) {
                throw new IllegalStateException(sqe);
            }
    }

    @Override
    public void remove(String path) {
        try (Connection conn = getConnection();
             PreparedStatement remove = conn.prepareStatement(DELETE)) {
            remove.setString(1, path);
            remove.executeUpdate();
        } catch (SQLException sqe) {
            throw new IllegalStateException(sqe);
        }
    }

    @Override
    public List<FileState> byHash(Blake3state b3) {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(GET_BY_HASH)) {
            select.setBytes(1, b3.serialize());
            ResultSet rs = select.executeQuery();
            List<FileState> res = new ArrayList<>();
            while (rs.next())
                res.add(new FileState(rs.getString(1), rs.getLong(3), rs.getLong(4), new Blake3state(rs.getBytes(2))));

            return res;
        } catch (SQLException sqe) {
            throw new IllegalStateException(sqe);
        }
    }
}
