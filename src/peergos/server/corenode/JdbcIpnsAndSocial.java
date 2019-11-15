package peergos.server.corenode;
import java.util.logging.*;

import org.sqlite.*;
import peergos.server.util.Logging;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.social.*;
import peergos.shared.util.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class JdbcIpnsAndSocial {
    public interface SqlSupplier {
        String listTablesCommand();

        String createFollowRequestsTableCommand();

        String createMutablePointersTableCommand();
    }

    public static class SqliteCommands implements SqlSupplier {

        @Override
        public String listTablesCommand() {
            return "SELECT NAME FROM sqlite_master WHERE type='table';";
        }

        @Override
        public String createFollowRequestsTableCommand() {
            return "CREATE TABLE followrequests (id integer primary key autoincrement, " +
                    "name text not null, followrequest text not null);";
        }

        @Override
        public String createMutablePointersTableCommand() {
            return "CREATE TABLE metadatablobs (writingkey text primary key not null, hash text not null); " +
                    "CREATE UNIQUE INDEX index_name ON metadatablobs (writingkey);";
        }
    }

	public static class PostgresCommands implements SqlSupplier {

        @Override
        public String listTablesCommand() {
            return "SELECT tablename FROM pg_catalog.pg_tables " +
                    "WHERE schemaname != 'pg_catalog' AND schemaname != 'information_schema';";
        }

        @Override
        public String createFollowRequestsTableCommand() {
            return "CREATE TABLE followrequests (id serial primary key, " +
                    "name text not null, followrequest text not null);";
        }

        @Override
        public String createMutablePointersTableCommand() {
            return "CREATE TABLE metadatablobs (writingkey text primary key not null, hash text not null); " +
                    "CREATE UNIQUE INDEX index_name ON metadatablobs (writingkey);";
        }
    }

	private static final Logger LOG = Logging.LOG();

    private static final String FOLLOW_REQUEST_USER_NAME = "name";
    private static final String FOLLOW_REQUEST_DATA_NAME = "followrequest";
    private static final String INSERT_FOLLOW_REQUEST = "INSERT INTO followrequests (name, followrequest) VALUES(?, ?);";
    private static final String SELECT_FOLLOW_REQUESTS = "SELECT name, followrequest FROM followrequests WHERE name = ?;";
    private static final String DELETE_FOLLOW_REQUEST = "DELETE FROM followrequests WHERE name = ? AND followrequest = ?;";

    private static final String IPNS_TARGET_NAME = "hash";
    private static final String IPNS_CREATE = "INSERT INTO metadatablobs (writingkey, hash) VALUES(?, ?)";
    private static final String IPNS_UPDATE = "UPDATE metadatablobs SET hash=? WHERE writingkey = ? AND hash = ?";
    private static final String IPNS_GET = "SELECT * FROM metadatablobs WHERE writingKey = ? LIMIT 1;";

    private Connection conn;

    private class FollowRequestData {
        public final String name;
        public final byte[] data;
        public final String b64string;

        FollowRequestData(PublicKeyHash owner, byte[] publicKey) {
            this(owner.toString(), publicKey);
        }

        FollowRequestData(String name, byte[] data) {
            this(name,data,(data == null ? null: new String(Base64.getEncoder().encode(data))));
        }

        FollowRequestData(String name, String d) {
            this(name, Base64.getDecoder().decode(d), d);
        }

        FollowRequestData(String name, byte[] data, String b64string) {
            this.name = name;
            this.data = data;
            this.b64string = b64string;
        }

        public boolean insert() {
            try (PreparedStatement insert = conn.prepareStatement(INSERT_FOLLOW_REQUEST)) {
                insert.setString(1,this.name);
                insert.setString(2,this.b64string);
                insert.executeUpdate();
                return true;
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return false;
            }
        }

        public FollowRequestData[] select() {
            try (PreparedStatement select = conn.prepareStatement(SELECT_FOLLOW_REQUESTS)) {
                select.setString(1, name);
                ResultSet rs = select.executeQuery();
                List<FollowRequestData> list = new ArrayList<>();
                while (rs.next())
                {
                    String username = rs.getString(FOLLOW_REQUEST_USER_NAME);
                    String b64string = rs.getString(FOLLOW_REQUEST_DATA_NAME);
                    list.add(new FollowRequestData(username, b64string));
                }
                return list.toArray(new FollowRequestData[0]);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return null;
            }
        }

        public boolean delete() {
            try (PreparedStatement delete = conn.prepareStatement(DELETE_FOLLOW_REQUEST)) {
                delete.setString(1, name);
                delete.setString(2, b64string);
                delete.executeUpdate();
                return true;
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return false;
            }
        }
    }

    private volatile boolean isClosed;

    public JdbcIpnsAndSocial(Connection conn, SqlSupplier commands) throws SQLException {
        this.conn = conn;
        init(commands);
    }

    private synchronized void init(SqlSupplier commands) throws SQLException {
        if (isClosed)
            return;

        //do tables exists?
        PreparedStatement list = conn.prepareStatement(commands.listTablesCommand());
        ResultSet rs = list.executeQuery();

        List<String> tables = new ArrayList<>();
        while (rs.next()) {
            String tableName = rs.getString(1);
            tables.add(tableName);
        }

        try {
            if (! tables.contains("followrequests")) {
                Statement createStmt = conn.createStatement();
                createStmt.executeUpdate(commands.createFollowRequestsTableCommand());
                createStmt.close();
            }
            if (! tables.contains("metadatablobs")) {
                Statement createStmt = conn.createStatement();
                createStmt.executeUpdate(commands.createMutablePointersTableCommand());
                createStmt.close();
            }
        } catch ( Exception e ) {
            LOG.severe( e.getClass().getName() + ": " + e.getMessage() );
        }
    }

    public CompletableFuture<Boolean> addFollowRequest(PublicKeyHash owner, byte[] encryptedPermission) {
        byte[] dummy = null;
        FollowRequestData selector = new FollowRequestData(owner, dummy);
        FollowRequestData[] requests = selector.select();
        if (requests != null && requests.length > SocialNetwork.MAX_PENDING_FOLLOWERS)
            return CompletableFuture.completedFuture(false);
        // ToDo add a crypto currency transaction to prevent spam

        FollowRequestData request = new FollowRequestData(owner, encryptedPermission);
        return CompletableFuture.completedFuture(request.insert());
    }

    public CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] unsigned) {
        FollowRequestData request = new FollowRequestData(owner, unsigned);
        return CompletableFuture.completedFuture(request.delete());
    }

    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner) {
        byte[] dummy = null;
        FollowRequestData request = new FollowRequestData(owner, dummy);
        FollowRequestData[] requests = request.select();
        if (requests == null)
            return CompletableFuture.completedFuture(new byte[4]);

        CborObject.CborList resp = new CborObject.CborList(Arrays.asList(requests).stream()
                .map(req -> CborObject.fromByteArray(req.data))
                .collect(Collectors.toList()));
        return CompletableFuture.completedFuture(resp.serialize());
    }

    public CompletableFuture<Boolean> setPointer(PublicKeyHash writingKey, Optional<byte[]> existingCas, byte[] newCas) {
        if (existingCas.isPresent()) {
            try (PreparedStatement insert = conn.prepareStatement(IPNS_UPDATE)) {
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                String key = new String(Base64.getEncoder().encode(writingKey.serialize()));

                insert.setString(1, new String(Base64.getEncoder().encode(newCas)));
                insert.setString(2, key);
                insert.setString(3, new String(Base64.getEncoder().encode(existingCas.get())));
                int changed = insert.executeUpdate();
                return CompletableFuture.completedFuture(changed > 0);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return CompletableFuture.completedFuture(false);
            }
        } else {
            try (PreparedStatement stmt = conn.prepareStatement(IPNS_CREATE)) {
                stmt.setString(1, new String(Base64.getEncoder().encode(writingKey.serialize())));
                stmt.setString(2, new String(Base64.getEncoder().encode(newCas)));
                stmt.executeUpdate();
                return CompletableFuture.completedFuture(true);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return CompletableFuture.completedFuture(false);
            }
        }
    }

    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash writingKey) {
        try (PreparedStatement stmt = conn.prepareStatement(IPNS_GET)) {
            stmt.setString(1, new String(Base64.getEncoder().encode(writingKey.serialize())));
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return CompletableFuture.completedFuture(Optional.of(Base64.getDecoder().decode(rs.getString(IPNS_TARGET_NAME))));
            }

            return CompletableFuture.completedFuture(Optional.empty());
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            return Futures.errored(sqe);
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
}
