package peergos.server.storage;

import peergos.server.sql.*;
import peergos.server.util.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;

public class JdbcLinkRetrievalcounter implements LinkRetrievalCounter {

    private static final Logger LOG = Logging.LOG();

    private static final String GET = "SELECT count FROM linkcounts WHERE username = ? AND label = ?;";
    private static final String LATEST = "SELECT MAX(modified) FROM linkcounts WHERE username = ?;";
    private static final String AFTER = "SELECT label, count, modified FROM linkcounts WHERE username = ? AND modified > ?;";

    private Supplier<Connection> conn;
    private final SqlSupplier commands;

    public JdbcLinkRetrievalcounter(Supplier<Connection> conn, SqlSupplier commands) {
        this.conn = conn;
        this.commands = commands;
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
        try (Connection conn = getConnection()) {
            commands.createTable(commands.createLinkCountTableCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    @Override
    public void increment(String owner, long label) {
        try (Connection conn = getConnection();
             PreparedStatement linkInsert = conn.prepareStatement(commands.insertOrIgnoreCommand("INSERT ", "INTO linkcounts (username, label, count, modified) VALUES(?, ?, ?, ?)"));
             PreparedStatement increment = conn.prepareStatement("UPDATE linkcounts SET count = count + 1, modified=? where username=? AND label=?;")
             ) {
            linkInsert.setString(1, owner);
            linkInsert.setLong(2, label);
            linkInsert.setLong(3, 0);
            long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
            linkInsert.setLong(4, now);
            linkInsert.executeUpdate();

            increment.setLong(1, now);
            increment.setString(2, owner);
            increment.setLong(3, label);
            int modified = increment.executeUpdate();
            if (modified != 1)
                throw new IllegalStateException("No rows modified!");
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public long getCount(String owner, long label) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET)) {
            stmt.setString(1, owner);
            stmt.setLong(2, label);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public Optional<LocalDateTime> getLatestModificationTime(String owner) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(LATEST)) {
            stmt.setString(1, owner);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(LocalDateTime.ofEpochSecond(rs.getLong(1), 0, ZoneOffset.UTC));
            }
            return Optional.empty();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void setCounts(String owner, LinkCounts counts) {
        counts.counts.forEach((k, v) -> {
            try (Connection conn = getConnection();
                 PreparedStatement linkInsert = conn.prepareStatement(commands.insertOrIgnoreCommand("INSERT ", "INTO linkcounts (username, label, count, modified) VALUES(?, ?, ?, ?)"));
                 PreparedStatement increment = conn.prepareStatement("UPDATE linkcounts SET count = ? where username=? AND label=?;")
            ) {
                linkInsert.setString(1, owner);
                linkInsert.setLong(2, k);
                linkInsert.setLong(3, 0);
                linkInsert.setLong(4, v.right.toEpochSecond(ZoneOffset.UTC));
                linkInsert.executeUpdate();

                increment.setLong(1, v.left);
                increment.setString(2, owner);
                increment.setLong(3, k);
                increment.executeUpdate();
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                throw new RuntimeException(sqe);
            }
        });
    }

    @Override
    public LinkCounts getUpdatedCounts(String owner, LocalDateTime after) {
        long seconds = after.toEpochSecond(ZoneOffset.UTC);
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(AFTER)) {
            stmt.setString(1, owner);
            stmt.setLong(2, seconds);
            ResultSet rs = stmt.executeQuery();
            Map<Long, Pair<Long, LocalDateTime>> res = new HashMap<>();
            while (rs.next()) {
                res.put(rs.getLong(1), new Pair<>(rs.getLong(2), LocalDateTime.ofEpochSecond(rs.getLong(3), 0, ZoneOffset.UTC)));
            }

            return new LinkCounts(res);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }
}
