package peergos.server.space;

import peergos.server.sql.*;
import peergos.server.util.Logging;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.util.*;

import java.sql.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;

public class JdbcUsageStore implements UsageStore {
	private static final Logger LOG = Logging.LOG();

    private Supplier<Connection> conn;
    private final SqlSupplier commands;
    private volatile boolean isClosed;

    public JdbcUsageStore(Supplier<Connection> conn, SqlSupplier commands) {
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
            return connection;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void init(SqlSupplier commands) {
        if (isClosed)
            return;

        try (Connection conn = getConnection()) {
            commands.createTable(commands.createUsageTablesCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void initialized() {
        // TODO can we remove this method?
    }

    @Override
    public void addUserIfAbsent(String username) {
        try (Connection conn = getConnection(true, false);
             PreparedStatement userInsert = conn.prepareStatement(commands.insertOrIgnoreCommand("INSERT ", "INTO users (name) VALUES(?)"));
             PreparedStatement select = conn.prepareStatement("SELECT id FROM users WHERE name = ?;");
             PreparedStatement usageInsert = conn.prepareStatement(commands.insertOrIgnoreCommand("INSERT ", "INTO userusage (user_id, total_bytes, errored) VALUES(?, ?, ?)"))) {
            userInsert.setString(1, username);
            userInsert.executeUpdate();

            select.setString(1, username);
            ResultSet resultSet = select.executeQuery();
            resultSet.next();
            int userId = resultSet.getInt(1);

            usageInsert.setInt(1, userId);
            usageInsert.setLong(2, 0);
            usageInsert.setBoolean(3, false);
            usageInsert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void confirmUsage(String username, PublicKeyHash writer, long usageDelta, boolean errored) {
        int userId = getUserId(username);
        try (Connection conn = getConnection(true, false);
             PreparedStatement insert = conn.prepareStatement(
                "UPDATE userusage SET total_bytes = total_bytes + ?, errored = ? " +
                        "WHERE user_id = ?;");
             PreparedStatement insertPending = conn.prepareStatement(
                     "UPDATE pendingusage SET pending_bytes = ? " +
                             "WHERE writer_id = (SELECT id FROM writers WHERE key_hash = ?);")) {
            insert.setLong(1, usageDelta);
            insert.setBoolean(2, errored);
            insert.setInt(3, userId);

            int count = insert.executeUpdate();
            if (count != 1)
                throw new IllegalStateException("Didn't update one record!");
            insertPending.setLong(1, 0);
            insertPending.setBytes(2, writer.toBytes());
            int count2 = insertPending.executeUpdate();
            if (count2 != 1)
                throw new IllegalStateException("Didn't update one record!");
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage() + ": " + username + "." + writer + "(" + usageDelta + ")", sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void addPendingUsage(String username, PublicKeyHash writer, int size) {
        int writerId = getWriterId(writer);
        try (Connection conn = getConnection(true, false);
             PreparedStatement insert = conn.prepareStatement("UPDATE pendingusage SET pending_bytes = pending_bytes + ? " +
                     "WHERE writer_id = ?;")) {
            insert.setLong(1, size);
            insert.setInt(2, writerId);
            int count = insert.executeUpdate();
            if (count != 1)
                throw new IllegalStateException("Didn't update one record!");
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public Map<String, Long> getAllUsage() {
        try (Connection conn = getConnection();
             PreparedStatement search = conn.prepareStatement("SELECT u.name, uu.total_bytes " +
                     "FROM users u, userusage uu WHERE u.id = uu.user_id;")) {
            Map<String, Long> usage = new HashMap<>();
            ResultSet resultSet = search.executeQuery();
            while (resultSet.next()) {
                String username = resultSet.getString(1);
                long total = resultSet.getLong(2);
                usage.put(username, total);
            }
            return usage;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public UserUsage getUsage(String username) {
        int userId = getUserId(username);
        try (Connection conn = getConnection();
             PreparedStatement search = conn.prepareStatement("SELECT pu.writer_id, pu.pending_bytes, uu.total_bytes, uu.errored " +
                     "FROM userusage uu, pendingusage pu WHERE uu.user_id = pu.user_id AND uu.user_id = ?;");
             PreparedStatement writerSearch = conn.prepareStatement("SELECT key_hash FROM writers WHERE id = ?;")) {
            search.setInt(1, userId);
            ResultSet resultSet = search.executeQuery();
            Map<PublicKeyHash, Long> pending = new HashMap<>();
            long totalBytes = -1;
            boolean errored = false;
            while (resultSet.next()) {
                writerSearch.setInt(1, resultSet.getInt(1));
                ResultSet writerRes = writerSearch.executeQuery();
                writerRes.next();
                PublicKeyHash writer = PublicKeyHash.decode(writerRes.getBytes(1));
                long pendingBytes = resultSet.getLong(2);
                if (pendingBytes > 0)
                    pending.put(writer, pendingBytes);
                if (totalBytes == -1) {
                    totalBytes = resultSet.getLong(3);
                    errored = resultSet.getBoolean(4);
                }
            }
            return new UserUsage(totalBytes, errored, pending);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private int getUserId(String username) {
        try (Connection conn = getConnection();
             PreparedStatement userSelect = conn.prepareStatement("SELECT id FROM users WHERE name = ?;")) {
            userSelect.setString(1, username);
            ResultSet resultSet = userSelect.executeQuery();
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private int getWriterId(PublicKeyHash writer) {
        try (Connection conn = getConnection();
             PreparedStatement writerSelect = conn.prepareStatement("SELECT id FROM writers WHERE key_hash = ?;")) {
            writerSelect.setBytes(1, writer.toBytes());
            ResultSet writerRes = writerSelect.executeQuery();
            if (writerRes.next())
                return writerRes.getInt(1);
            throw new IllegalStateException("Writer not present on this server: " + writer);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void addWriter(String owner, PublicKeyHash writer) {
        try (Connection conn = getConnection(true, false);
             PreparedStatement writerInsert = conn.prepareStatement(commands.insertOrIgnoreCommand("INSERT ", "INTO writers (key_hash) VALUES(?)"));
             PreparedStatement userSelect = conn.prepareStatement("SELECT id FROM users WHERE name = ?;");
             PreparedStatement writerSelect = conn.prepareStatement("SELECT id FROM writers WHERE key_hash = ?;");
             PreparedStatement defaultPendingInsert = conn.prepareStatement(commands.insertOrIgnoreCommand(
                     "INSERT ", "INTO pendingusage (user_id, writer_id, pending_bytes) VALUES(?, ?, ?)"));
             PreparedStatement usageInsert = conn.prepareStatement(commands.insertOrIgnoreCommand("INSERT ", "INTO writerusage (writer_id, user_id, direct_size) VALUES(?, ?, ?)"))) {
            writerInsert.setBytes(1, writer.toBytes());
            writerInsert.executeUpdate();

            userSelect.setString(1, owner);
            ResultSet resultSet = userSelect.executeQuery();
            resultSet.next();
            int userId = resultSet.getInt(1);

            writerSelect.setBytes(1, writer.toBytes());
            ResultSet writerRes = writerSelect.executeQuery();
            writerRes.next();
            int writerId = writerRes.getInt(1);

            defaultPendingInsert.setInt(1, userId);
            defaultPendingInsert.setInt(2, writerId);
            defaultPendingInsert.setInt(3, 0);
            defaultPendingInsert.executeUpdate();

            usageInsert.setInt(1, writerId);
            usageInsert.setInt(2, userId);
            usageInsert.setInt(3, 0);
            usageInsert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public Set<PublicKeyHash> getAllWriters() {
        try (Connection conn = getConnection();
             PreparedStatement query = conn.prepareStatement("SELECT key_hash FROM writers;")) {
            Set<PublicKeyHash> res = new HashSet<>();
            ResultSet resultSet = query.executeQuery();
            while (resultSet.next())
                res.add(PublicKeyHash.decode(resultSet.getBytes(1)));
            return res;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public Set<PublicKeyHash> getAllWriters(PublicKeyHash owner) {
        try (Connection conn = getConnection();
             PreparedStatement idQuery = conn.prepareStatement("SELECT id FROM writers WHERE key_hash=?;");
             PreparedStatement query = conn.prepareStatement("SELECT writers.key_hash FROM writers " +
                     "INNER JOIN ownedkeys ON writers.id=ownedkeys.owned_id WHERE ownedkeys.parent_id=?;")) {
            idQuery.setBytes(1, owner.toBytes());
            ResultSet idRes = idQuery.executeQuery();
            if (!idRes.next())
                return Collections.emptySet();
            int id = idRes.getInt(1);
            query.setInt(1, id);

            Set<PublicKeyHash> res = new HashSet<>();
            res.add(owner);
            ResultSet resultSet = query.executeQuery();
            while (resultSet.next())
                res.add(PublicKeyHash.decode(resultSet.getBytes(1)));
            return res;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public List<Pair<Multihash, String>> getAllTargets() {
        try (Connection conn = getConnection();
             PreparedStatement get = conn.prepareStatement("SELECT writerusage.target,users.name FROM writerusage " +
                     "INNER JOIN users ON writerusage.user_id=users.id;")) {
            List<Pair<Multihash, String>> res = new ArrayList<>();
            ResultSet resultSet = get.executeQuery();
            while (resultSet.next()) {
                MaybeMultihash target = Optional.ofNullable(resultSet.getBytes(1))
                    .map(x -> MaybeMultihash.of(Cid.cast(x)))
                    .orElse(MaybeMultihash.empty());
                String username = resultSet.getString(2);
                if (target.isPresent())
                    res.add(new Pair<>(target.get(), username));
            }
            return res;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private Map<PublicKeyHash, String> owners = new LRUCache<>(100);

    private String getOwner(PublicKeyHash writer) {
        String cached = owners.get(writer);
        if (cached != null)
            return cached;
        int writerId = getWriterId(writer);
        try (Connection conn = getConnection();
             PreparedStatement search = conn.prepareStatement("SELECT u.name FROM users u, writerusage wu WHERE u.id = wu.user_id AND wu.writer_id = ?;")) {
            search.setInt(1, writerId);
            ResultSet resultSet = search.executeQuery();
            if (! resultSet.next())
                throw new IllegalStateException("Unknown writer on this server!");
            String owner = resultSet.getString(1);
            owners.put(writer, owner);
            return owner;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private PublicKeyHash getWriter(int writerId) {
        try (Connection conn = getConnection();
             PreparedStatement search = conn.prepareStatement("SELECT key_hash FROM writers WHERE id = ?;")) {
            search.setInt(1, writerId);
            ResultSet resultSet = search.executeQuery();
            resultSet.next();
            return PublicKeyHash.decode(resultSet.getBytes(1));
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public WriterUsage getUsage(PublicKeyHash writer) {
        String owner = getOwner(writer);
        int writerId = getWriterId(writer);
        Set<PublicKeyHash> owned = new HashSet<>();
        try (Connection conn = getConnection();
             PreparedStatement ownedSearch = conn.prepareStatement("SELECT owned_id FROM ownedkeys WHERE parent_id = ?;");
             PreparedStatement usageSearch = conn.prepareStatement("SELECT target, direct_size FROM writerusage WHERE writer_id = ?;");
             PreparedStatement search = conn.prepareStatement("SELECT key_hash FROM writers WHERE id = ?;")) {
            ownedSearch.setInt(1, writerId);
            ResultSet ownedRes = ownedSearch.executeQuery();
            while (ownedRes.next()) {
                search.setInt(1, ownedRes.getInt(1));
                ResultSet resultSet = search.executeQuery();
                resultSet.next();
                PublicKeyHash ownedKey = PublicKeyHash.decode(resultSet.getBytes(1));
                owned.add(ownedKey);
            }
            usageSearch.setInt(1, writerId);
            ResultSet usageRes = usageSearch.executeQuery();
            usageRes.next();
            MaybeMultihash target = Optional.ofNullable(usageRes.getBytes(1))
                    .map(x -> MaybeMultihash.of(Cid.cast(x)))
                    .orElse(MaybeMultihash.empty());
            return new WriterUsage(owner, target, usageRes.getLong(2), owned);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }

    }

    @Override
    public void updateWriterUsage(PublicKeyHash writer,
                                  MaybeMultihash target,
                                  Set<PublicKeyHash> removedOwnedKeys,
                                  Set<PublicKeyHash> addedOwnedKeys,
                                  long retainedStorage) {
        int writerId = getWriterId(writer);
        try (Connection conn = getConnection(true, false);
             PreparedStatement insert = conn.prepareStatement("UPDATE writerusage SET target=?, direct_size=? WHERE writer_id = ?;");
             PreparedStatement writerSelect = conn.prepareStatement("SELECT id FROM writers WHERE key_hash = ?;");
             PreparedStatement deleteOwned = conn.prepareStatement("DELETE FROM ownedkeys WHERE owned_id = ?;");
             PreparedStatement insertOwned = conn.prepareStatement("INSERT INTO ownedkeys (parent_id, owned_id) VALUES(?, ?);")) {
            insert.setBytes(1, target.isPresent() ? target.get().toBytes() : null);
            insert.setLong(2, retainedStorage);
            insert.setInt(3, writerId);
            int count = insert.executeUpdate();
            if (count != 1)
                throw new IllegalStateException("Didn't update one record!");

            for (PublicKeyHash removed : removedOwnedKeys) {
                writerSelect.setBytes(1, removed.toBytes());
                ResultSet writerRes = writerSelect.executeQuery();
                writerRes.next();
                int ownedId = writerRes.getInt(1);
                deleteOwned.setInt(1, ownedId);
                deleteOwned.executeUpdate();
            }

            for (PublicKeyHash added : addedOwnedKeys) {
                writerSelect.setBytes(1, added.toBytes());
                ResultSet writerRes = writerSelect.executeQuery();
                writerRes.next();
                int ownedId = writerRes.getInt(1);
                insertOwned.setInt(1, writerId);
                insertOwned.setInt(2, ownedId);
                insertOwned.execute();
            }
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
