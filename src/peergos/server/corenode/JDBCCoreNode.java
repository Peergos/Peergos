package peergos.server.corenode;
import java.util.logging.*;

import peergos.server.util.Logging;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.social.*;

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
    static
    {
        TABLES.put("followrequests", CREATE_FOLLOW_REQUESTS_TABLE);
        TABLES.put("metadatablobs", CREATE_METADATA_BLOBS_TABLE);
    }

    private Connection conn;

    private abstract class RowData
    {
        public final String name;
        public final byte[] data;
        public final String b64string;
        RowData(String name, byte[] data)
        {
            this(name,data,(data == null ? null: new String(Base64.getEncoder().encode(data))));
        }

        RowData(String name, String d)
        {
            this(name, Base64.getDecoder().decode(d), d);
        }

        RowData(String name, byte[] data, String b64string)
        {
            this.name = name;
            this.data = data;
            this.b64string = b64string;
        }


        abstract String b64DataName();
        abstract String insertStatement();
        abstract String selectStatement();
        abstract String deleteStatement();

        public boolean insert()
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement(insertStatement());
                stmt.setString(1,this.name);
                stmt.setString(2,this.b64string);
                stmt.executeUpdate();
                return true;
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return false;
            } finally {
                if (stmt != null)
                    try
                    {
                        stmt.close();
                    } catch (SQLException sqe2) {
                        sqe2.printStackTrace();
                    }
            }
        }

        public RowData[] select()
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement(selectStatement());
                ResultSet rs = stmt.executeQuery();
                List<RowData> list = new ArrayList<>();
                while (rs.next())
                {
                    String username = rs.getString("name");
                    String b64string = rs.getString(b64DataName());
                    list.add(new UserData(username, b64string));
                }
                return list.toArray(new RowData[0]);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return null;
            }finally {
                if (stmt != null)
                    try
                    {
                        stmt.close();
                    } catch (SQLException sqe2) {
                        sqe2.printStackTrace();
                    }
            }
        }


        public boolean delete()
        {
            Statement stmt = null;
            try
            {
                stmt = conn.createStatement();
                stmt.executeUpdate(deleteStatement());
                return true;
            } catch (SQLException sqe) {
                LOG.severe(deleteStatement());
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return false;
            } finally {
                if (stmt != null)
                    try
                    {
                        stmt.close();
                    } catch (SQLException sqe2) {
                        sqe2.printStackTrace();
                    }
            }
        }

    }

    private class UserData extends RowData
    {
        UserData(String name, byte[] publicKey)
        {
            super(name, publicKey);
        }
        UserData(String name, String d)
        {
            super(name, d);
        }

        public String b64DataName(){return DATA_NAME;}
        public String insertStatement(){return "insert into users (name, publickey) VALUES(?, ?);";}
        public String selectStatement(){return "select name, "+b64DataName()+" from users where name = '"+name+"';";}
        public String deleteStatement(){return "delete from users where name = \""+ name +"\" and "+ b64DataName()+ " = \""+ b64string + "\";";}
        static final String DATA_NAME = "publickey";
    }

    private class FollowRequestData extends RowData
    {
        FollowRequestData(PublicKeyHash owner, byte[] publicKey)
        {
            super(owner.toString(), publicKey);
        }
        FollowRequestData(String name, String d)
        {
            super(name, d);
        }

        public String b64DataName(){return DATA_NAME;}
        public String insertStatement(){return "insert into followrequests (name, followrequest) VALUES(?, ?);";}
        public String selectStatement(){return "select name, "+b64DataName()+" from followrequests where name = \""+name+"\";";}
        public String deleteStatement(){return "delete from followrequests where name = \""+ name +"\" and "+ b64DataName()+ " = \""+ b64string + "\";";}
        static final String DATA_NAME = "followrequest";
    }

    private class MetadataBlob
    {
        final byte[] writingKey, hash;
        final String b64WritingKey, b64hash;

        MetadataBlob(byte[] writingKey, byte[] hash)
        {
            this(writingKey, new String(Base64.getEncoder().encode(writingKey)), hash, hash == null ? null : new String(Base64.getEncoder().encode(hash)));

        }

        MetadataBlob(String b64WritingKey, String b64hash)
        {
            this(Base64.getDecoder().decode(b64WritingKey), b64WritingKey, Base64.getDecoder().decode(b64hash), b64hash);
        }

        MetadataBlob(byte[] writingKey, String b64WritingKey, byte[] hash, String b64hash)
        {
            this.writingKey = writingKey;
            this.b64WritingKey = b64WritingKey;
            this.hash = hash;
            this.b64hash = b64hash;
        }

        public String selectStatement(){return "select writingkey, hash from metadatablobs where writingkey = "+ b64WritingKey +";";}
        public String deleteStatement(){return "delete from metadatablobs where writingkey = "+ b64WritingKey +";";}

        public boolean insert()
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement("INSERT OR REPLACE INTO metadatablobs (writingkey, hash) VALUES(?, ?)");

                stmt.setString(1,this.b64WritingKey);
                stmt.setString(2,this.b64hash);
                stmt.executeUpdate();
                return true;
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return false;
            } finally {
                if (stmt != null)
                    try
                    {
                        stmt.close();
                    } catch (SQLException sqe2) {
                        sqe2.printStackTrace();
                    }
            }
        }

        public boolean delete()
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement("DELETE from metadatablobs where writingkey=? AND hash=?");

                stmt.setString(1,this.b64WritingKey);
                stmt.setString(2,this.b64hash);
                stmt.executeUpdate();
                return true;
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return false;
            } finally {
                if (stmt != null)
                    try
                    {
                        stmt.close();
                    } catch (SQLException sqe2) {
                        sqe2.printStackTrace();
                    }
            }
        }

        public MetadataBlob selectOne()
        {
            MetadataBlob[] fd = select("where writingKey = '"+ b64WritingKey +"'");
            if (fd == null || fd.length != 1)
                return null;
            return fd[0];
        }

        public MetadataBlob[] selectAllByName(String username)
        {
            return select("where name = "+ username);
        }

        public MetadataBlob[] select(String selectString)
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement("select writingKey, hash from metadatablobs "+ selectString + ";");
                ResultSet rs = stmt.executeQuery();
                List<MetadataBlob> list = new ArrayList<MetadataBlob>();
                while (rs.next())
                {
                    MetadataBlob f = new MetadataBlob(rs.getString("writingkey"), rs.getString("hash"));
                    list.add(f);
                }

                return list.toArray(new MetadataBlob[0]);
            } catch (SQLException sqe) {
                LOG.severe("Error selecting: "+selectString);
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return null;
            } finally {
                if (stmt != null)
                    try
                    {
                        stmt.close();
                    } catch (SQLException sqe2) {
                        sqe2.printStackTrace();
                    }
            }
        }
    }

    private volatile boolean isClosed;
    private final int maxUsernameCount;

    public JDBCCoreNode(Connection conn) throws SQLException
    {
        this(conn, CoreNode.MAX_USERNAME_COUNT);
    }

    public JDBCCoreNode(Connection conn, int maxUsernameCount) throws SQLException
    {
        this.conn = conn;
        this.maxUsernameCount = maxUsernameCount;
        init();
    }

    private synchronized void init() throws SQLException
    {
        if (isClosed)
            return;

        //do tables exists?
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(TABLE_NAMES_SELECT_STMT);

        ArrayList<String> missingTables = new ArrayList(TABLES.keySet());
        while (rs.next())
        {
            String tableName = rs.getString("name");
            missingTables.remove(tableName);
        }

        for (String missingTable: missingTables)
        {
            try
            {
                Statement createStmt = conn.createStatement();
                //LOG.info("Adding table "+ missingTable);
                createStmt.executeUpdate(TABLES.get(missingTable));
                createStmt.close();

            } catch ( Exception e ) {
                LOG.severe( e.getClass().getName() + ": " + e.getMessage() );
            }
        }
    }

    public CompletableFuture<Boolean> addFollowRequest(PublicKeyHash owner, byte[] encryptedPermission)
    {
        byte[] dummy = null;
        FollowRequestData selector = new FollowRequestData(owner, dummy);
        RowData[] requests = selector.select();
        if (requests != null && requests.length > SocialNetwork.MAX_PENDING_FOLLOWERS)
            return CompletableFuture.completedFuture(false);
        // ToDo add a crypto currency transaction to prevent spam

        FollowRequestData request = new FollowRequestData(owner, encryptedPermission);
        return CompletableFuture.completedFuture(request.insert());
    }

    public CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] unsigned)
    {
        FollowRequestData request = new FollowRequestData(owner, unsigned);
        return CompletableFuture.completedFuture(request.delete());
    }

    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner) {
        byte[] dummy = null;
        FollowRequestData request = new FollowRequestData(owner, dummy);
        RowData[] requests = request.select();
        if (requests == null)
            return CompletableFuture.completedFuture(new byte[4]);

        CborObject.CborList resp = new CborObject.CborList(Arrays.asList(requests).stream()
                .map(req -> CborObject.fromByteArray(req.data))
                .collect(Collectors.toList()));
        return CompletableFuture.completedFuture(resp.serialize());
    }

    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writerHash, byte[] writingKeySignedHash) {
        MetadataBlob blob = new MetadataBlob(writerHash.serialize(), writingKeySignedHash);
        return CompletableFuture.completedFuture(blob.insert());
    }

    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash writingKey) {
        byte[] dummy = null;
        MetadataBlob blob = new MetadataBlob(writingKey.serialize(), dummy);
        MetadataBlob users = blob.selectOne();
        if (users == null)
            return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.completedFuture(Optional.of(users.hash));
    }

    public synchronized void close()
    {
        if (isClosed)
            return;
        try
        {
            if (conn != null)
                conn.close();
            isClosed = true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }

    public boolean delete(String table, String deleteString)
    {
        Statement stmt = null;
        try
        {
            stmt = conn.createStatement();
            stmt.executeUpdate("delete from "+table+" where "+ deleteString +";");
            return true;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            return false;
        } finally {
            if (stmt != null)
                try
                {
                    stmt.close();
                } catch (SQLException sqe2) {
                    sqe2.printStackTrace();
                }
        }
    }

    public static Connection buildSqlLite(String dbPath) throws SQLException
    {
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
