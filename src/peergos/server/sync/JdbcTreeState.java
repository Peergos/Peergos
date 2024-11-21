package peergos.server.sync;

import peergos.server.sql.SqlSupplier;
import peergos.server.sql.SqliteCommands;
import peergos.server.util.Sqlite;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.user.Snapshot;
import peergos.shared.user.fs.HashTree;
import peergos.shared.user.fs.RootHash;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

public class JdbcTreeState implements SyncState {
    private static final String INSERT_SNAPSHOT = "INSERT INTO snapshots (path, snapshot) VALUES(?, ?);";
    private static final String UPDATE_SNAPSHOT = "UPDATE snapshots SET snapshot=? WHERE path=?;";
    private static final String INSERT = "INSERT INTO syncstate (path, roothash, modtime, size, hashtree) VALUES(?, ?, ?, ?, ?);";
    private static final String INSERT_DIR_SUFFIX = "INTO syncdirs (path) VALUES(?);";
    private static final String UPDATE = "UPDATE syncstate SET roothash=?, hashtree=?, modtime=?, size=? WHERE path=?;";
    private static final String DELETE = "DELETE from syncstate WHERE path = ?;";
    private static final String DELETE_DIR = "DELETE from syncdirs WHERE path = ?;";
    private static final String GET_BY_PATH = "SELECT path, modtime, size, hashtree FROM syncstate WHERE path = ?;";
    private static final String GET_SNAPSHOT = "SELECT snapshot FROM snapshots WHERE path = ?;";
    private static final String COUNT_FILES = "SELECT COUNT(*) FROM syncstate;";
    private static final String ALL_FILE_PATHS = "SELECT path FROM syncstate;";
    private static final String GET_BY_HASH = "SELECT path, modtime, size, hashtree FROM syncstate WHERE roothash = ?;";
    private static final String GET_DIRS = "SELECT path FROM syncdirs;";
    private static final String HAS_DIR = "SELECT path FROM syncdirs WHERE path=?;";
    private static final String INSERT_COPY_OP = "INSERT INTO copyops (islocal, source, target, start, end, sourcestate, targetstate) VALUES(?, ?, ?, ?, ?, ?, ?);";
    private static final String REMOVE_COPY_OP = "DELETE FROM copyops WHERE source=? AND target=? AND start=? AND end=?";
    private static final String LIST_COPY_OPS = "SELECT islocal, source, target, start, end, sourcestate, targetstate FROM copyops;";

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
            cmds.createTable("CREATE TABLE IF NOT EXISTS syncstate (path text primary key not null, roothash blob, modtime bigint not null, size bigint not null, hashtree blob); " +
                    "CREATE INDEX IF NOT EXISTS sync_hash_index ON syncstate (roothash);", conn);
            cmds.createTable("CREATE TABLE IF NOT EXISTS syncdirs (path text primary key not null);", conn);
            cmds.createTable("CREATE TABLE IF NOT EXISTS snapshots (path text primary key not null, snapshot blob);", conn);
            cmds.createTable("CREATE TABLE IF NOT EXISTS copyops (islocal bool not null, source text not null, target text not null, " +
                    "start "+cmds.sqlInteger()+" not null, end " + cmds.sqlInteger() + " not null, sourcestate blob, targetstate blob);", conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long filesCount() {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(COUNT_FILES)) {
            ResultSet rs = select.executeQuery();
            rs.next();
            return rs.getLong(1);
        } catch (SQLException sqe) {
            throw new IllegalStateException(sqe);
        }
    }

    @Override
    public Set<String> allFilePaths() {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(ALL_FILE_PATHS)) {
            ResultSet rs = select.executeQuery();
            Set<String> res = new HashSet<>();
            while (rs.next())
                res.add(rs.getString(1));
            return res;
        } catch (SQLException sqe) {
            throw new IllegalStateException(sqe);
        }
    }

    @Override
    public void setSnapshot(String basePath, Snapshot s) {
        if (s.versions.isEmpty())
            return;
        Snapshot existing = getSnapshot(basePath);
        if (existing != null && ! existing.versions.isEmpty()) {
            try (Connection conn = getConnection();
                 PreparedStatement update = conn.prepareStatement(UPDATE_SNAPSHOT)) {
                update.setBytes(1, s.serialize());
                update.setString(2, basePath);
                update.executeUpdate();
            } catch (SQLException sqe) {
                throw new IllegalStateException(sqe);
            }
        } else
            try (Connection conn = getConnection();
                 PreparedStatement insert = conn.prepareStatement(INSERT_SNAPSHOT)) {
                insert.setString(1, basePath);
                insert.setBytes(2, s.serialize());
                insert.executeUpdate();
            } catch (SQLException sqe) {
                throw new IllegalStateException(sqe);
            }
    }

    @Override
    public Snapshot getSnapshot(String basePath) {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(GET_SNAPSHOT)) {
            ResultSet rs = select.executeQuery();
            if (rs.next())
                return Snapshot.fromCbor(CborObject.fromByteArray(rs.getBytes(1)));
            return new Snapshot(new HashMap<>());
        } catch (SQLException sqe) {
            throw new IllegalStateException(sqe);
        }
    }

    @Override
    public void addDir(String relPath) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(cmds.insertOrIgnoreCommand("INSERT ", INSERT_DIR_SUFFIX))) {
            insert.setString(1, relPath);
            insert.executeUpdate();
        } catch (SQLException sqe) {
            throw new IllegalStateException(sqe);
        }
    }

    @Override
    public void removeDir(String path) {
        try (Connection conn = getConnection();
             PreparedStatement remove = conn.prepareStatement(DELETE_DIR)) {
            remove.setString(1, path);
            remove.executeUpdate();
        } catch (SQLException sqe) {
            throw new IllegalStateException(sqe);
        }
    }

    @Override
    public boolean hasDir(String path) {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(HAS_DIR)) {
            select.setString(1, path);
            ResultSet rs = select.executeQuery();
            return (rs.next());
        } catch (SQLException sqe) {
            throw new IllegalStateException(sqe);
        }
    }

    @Override
    public Set<String> getDirs() {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(GET_DIRS)) {
            ResultSet rs = select.executeQuery();
            Set<String> res = new HashSet<>();
            while (rs.next())
                res.add(rs.getString(1));

            return res;
        } catch (SQLException sqe) {
            throw new IllegalStateException(sqe);
        }
    }

    @Override
    public FileState byPath(String path) {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(GET_BY_PATH)) {
            select.setString(1, path);
            ResultSet rs = select.executeQuery();
            if (rs.next())
                return new FileState(rs.getString(1), rs.getLong(2), rs.getLong(3), HashTree.fromCbor(CborObject.fromByteArray(rs.getBytes(4))));

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
                update.setBytes(1, fs.hashTree.rootHash.serialize());
                update.setBytes(2, fs.hashTree.serialize());
                update.setLong(3, fs.modificationTime);
                update.setLong(4, fs.size);
                update.setString(5, fs.relPath);
                update.executeUpdate();
            } catch (SQLException sqe) {
                throw new IllegalStateException(sqe);
            }
        } else
            try (Connection conn = getConnection();
                 PreparedStatement insert = conn.prepareStatement(INSERT)) {
                insert.setString(1, fs.relPath);
                insert.setBytes(2, fs.hashTree.rootHash.serialize());
                insert.setLong(3, fs.modificationTime);
                insert.setLong(4, fs.size);
                insert.setBytes(5, fs.hashTree.serialize());
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
    public List<FileState> byHash(RootHash hash) {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(GET_BY_HASH)) {
            select.setBytes(1, hash.serialize());
            ResultSet rs = select.executeQuery();
            List<FileState> res = new ArrayList<>();
            while (rs.next())
                res.add(new FileState(rs.getString(1), rs.getLong(2), rs.getLong(3), HashTree.fromCbor(CborObject.fromByteArray(rs.getBytes(4)))));

            return res;
        } catch (SQLException sqe) {
            throw new IllegalStateException(sqe);
        }
    }

    @Override
    public void startCopies(List<CopyOp> ops) {
        for (CopyOp op : ops) {
            try (Connection conn = getConnection();
                 PreparedStatement insert = conn.prepareStatement(INSERT_COPY_OP)) {
                insert.setBoolean(1, op.isLocalTarget);
                insert.setString(2, op.source.toString());
                insert.setString(3, op.target.toString());
                insert.setLong(4, op.diffStart);
                insert.setLong(5, op.diffEnd);
                insert.setBytes(6, Optional.ofNullable(op.sourceState).map(Cborable::serialize).orElse(null));
                insert.setBytes(7, Optional.ofNullable(op.targetState).map(Cborable::serialize).orElse(null));
                insert.executeUpdate();
            } catch (SQLException sqe) {
                throw new IllegalStateException(sqe);
            }
        }
    }

    @Override
    public void finishCopies(List<CopyOp> ops) {
        for (CopyOp op : ops) {
            try (Connection conn = getConnection();
                 PreparedStatement remove = conn.prepareStatement(REMOVE_COPY_OP)) {
                remove.setString(1, op.source.toString());
                remove.setString(2, op.target.toString());
                remove.setLong(3, op.diffStart);
                remove.setLong(4, op.diffEnd);
                remove.executeUpdate();
            } catch (SQLException sqe) {
                throw new IllegalStateException(sqe);
            }
        }
    }

    @Override
    public List<CopyOp> getInProgressCopies() {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(LIST_COPY_OPS)) {
            ResultSet rs = select.executeQuery();
            List<CopyOp> res = new ArrayList<>();
            while (rs.next())
                res.add(new CopyOp(rs.getBoolean(1), Paths.get(rs.getString(2)), Paths.get(rs.getString(3)),
                        Optional.ofNullable(rs.getBytes(6)).map(b -> FileState.fromCbor(CborObject.fromByteArray(b))).orElse(null),
                        Optional.ofNullable(rs.getBytes(7)).map(b -> FileState.fromCbor(CborObject.fromByteArray(b))).orElse(null),
                        rs.getLong(4),
                        rs.getLong(5)
                ));

            return res;
        } catch (SQLException sqe) {
            throw new IllegalStateException(sqe);
        }
    }
}
