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

public class JDBCCoreNode {
	private static final Logger LOG = Logging.LOG();

    private static final String TABLE_NAMES_SELECT_STMT = "SELECT * FROM sqlite_master WHERE type='table';";
    private static final String CREATE_FOLLOW_REQUESTS_TABLE = "create table followrequests (id integer primary key autoincrement, name text not null, followrequest text not null);";
    private static final String CREATE_METADATA_BLOBS_TABLE = "create table metadatablobs (writingkey text primary key not null, hash text not null); " +
            "CREATE UNIQUE INDEX index_name on metadatablobs (writingkey);";

    private static final Map<String,String> TABLES = new HashMap<>();
    static {
        TABLES.put("followrequests", CREATE_FOLLOW_REQUESTS_TABLE);
        TABLES.put("metadatablobs", CREATE_METADATA_BLOBS_TABLE);
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

        public String b64DataName(){return DATA_NAME;}
        public String insertStatement(){return "insert into followrequests (name, followrequest) VALUES(?, ?);";}
        public String selectStatement(){return "select name, "+b64DataName()+" from followrequests where name = \""+name+"\";";}
        public String deleteStatement(){return "delete from followrequests where name = \""+ name +"\" and "+ b64DataName()+ " = \""+ b64string + "\";";}
        static final String DATA_NAME = "followrequest";

        public boolean insert() {
            try (PreparedStatement stmt = conn.prepareStatement(insertStatement())) {
                stmt.setString(1,this.name);
                stmt.setString(2,this.b64string);
                stmt.executeUpdate();
                return true;
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return false;
            }
        }

        public FollowRequestData[] select() {
            try (PreparedStatement stmt = conn.prepareStatement(selectStatement())) {
                ResultSet rs = stmt.executeQuery();
                List<FollowRequestData> list = new ArrayList<>();
                while (rs.next())
                {
                    String username = rs.getString("name");
                    String b64string = rs.getString(b64DataName());
                    list.add(new FollowRequestData(username, b64string));
                }
                return list.toArray(new FollowRequestData[0]);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return null;
            }
        }

        public boolean delete() {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(deleteStatement());
                return true;
            } catch (SQLException sqe) {
                LOG.severe(deleteStatement());
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return false;
            }
        }
    }

    private volatile boolean isClosed;

    public JDBCCoreNode(Connection conn) throws SQLException {
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
            try (PreparedStatement insert = conn.prepareStatement("UPDATE metadatablobs SET hash=? WHERE writingkey = ? AND hash = ?")) {
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
            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO metadatablobs (writingkey, hash) VALUES(?, ?)")) {
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
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM metadatablobs WHERE writingKey = ? LIMIT 1;")) {
            stmt.setString(1, new String(Base64.getEncoder().encode(writingKey.serialize())));
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return CompletableFuture.completedFuture(Optional.of(Base64.getDecoder().decode(rs.getString("hash"))));
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
