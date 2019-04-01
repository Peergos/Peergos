package peergos.server.corenode;
import java.util.logging.*;

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
	private static final Logger LOG = Logging.LOG();

    private static final String TABLE_NAMES_SELECT_STMT = "SELECT * FROM sqlite_master WHERE type='table';";

    private static final String FOLLOW_REQUEST_USER_NAME = "name";
    private static final String FOLLOW_REQUEST_DATA_NAME = "followrequest";
    private static final String CREATE_FOLLOW_REQUESTS_TABLE =
            "CREATE TABLE followrequests (id integer primary key autoincrement, " +
                    "name text not null, followrequest text not null);";
    private static final String INSERT_FOLLOW_REQUEST = "INSERT INTO followrequests (name, followrequest) VALUES(?, ?);";
    private static final String SELECT_FOLLOW_REQUESTS = "SELECT name, followrequest FROM followrequests WHERE name = ?;";
    private static final String DELETE_FOLLOW_REQUEST = "DELETE FROM followrequests WHERE name = ? AND followrequest = ?;";

    private static final String CREATE_IPNS_TABLE =
            "CREATE TABLE metadatablobs (writingkey text primary key not null, hash text not null); " +
            "CREATE UNIQUE INDEX index_name ON metadatablobs (writingkey);";
    private static final String IPNS_TARGET_NAME = "hash";
    private static final String IPNS_CREATE = "INSERT INTO metadatablobs (writingkey, hash) VALUES(?, ?)";
    private static final String IPNS_UPDATE = "UPDATE metadatablobs SET hash=? WHERE writingkey = ? AND hash = ?";
    private static final String IPNS_GET = "SELECT * FROM metadatablobs WHERE writingKey = ? LIMIT 1;";

    private static final Map<String,String> TABLES = new HashMap<>();
    static {
        TABLES.put("followrequests", CREATE_FOLLOW_REQUESTS_TABLE);
        TABLES.put("metadatablobs", CREATE_IPNS_TABLE);
    }

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

    public JdbcIpnsAndSocial(Connection conn) throws SQLException {
        this.conn = conn;
        init();
    }

    private synchronized void init() throws SQLException {
        if (isClosed)
            return;

        //do tables exists?
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(TABLE_NAMES_SELECT_STMT);

        ArrayList<String> missingTables = new ArrayList<>(TABLES.keySet());
        while (rs.next()) {
            String tableName = rs.getString("name");
            missingTables.remove(tableName);
        }

        for (String missingTable: missingTables) {
            try {
                Statement createStmt = conn.createStatement();
                //LOG.info("Adding table "+ missingTable);
                createStmt.executeUpdate(TABLES.get(missingTable));
                createStmt.close();

            } catch ( Exception e ) {
                LOG.severe( e.getClass().getName() + ": " + e.getMessage() );
            }
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

    public static Connection buildSqlLite(String dbPath) throws SQLException {
        try
        {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException cnfe) {
            throw new SQLException(cnfe);
        }

        String url = "jdbc:sqlite:"+dbPath;
        Connection conn = DriverManager.getConnection(url);
        conn.setAutoCommit(true);
        return conn;
    }
}
