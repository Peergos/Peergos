package peergos.corenode;

import peergos.crypto.*;
import peergos.util.*;

import java.sql.*;
import java.util.*;

public class JDBCCoreNode implements CoreNode {

    private static final String TABLE_NAMES_SELECT_STMT = "SELECT * FROM sqlite_master WHERE type='table';";
    private static final String CREATE_USERS_TABLE = "create table users (id integer primary key autoincrement, name text not null, publickey text not null);";
    private static final String CREATE_STATIC_DATA_TABLE = "create table staticdata (id integer primary key autoincrement, name text not null, staticdata text not null);";
    private static final String CREATE_FOLLOW_REQUESTS_TABLE = "create table followrequests (id integer primary key autoincrement, name text not null, followrequests text not null);";
    private static final String CREATE_METADATA_BLOBS_TABLE = "create table metadatablobs (id integer primary key autoincrement, writingkey text not null, mapkey text not null, blobdata text not null);";

    private static final Map<String,String> TABLES = new HashMap<>();
    static
    {
        TABLES.put("users", CREATE_USERS_TABLE);
        TABLES.put("staticdata", CREATE_STATIC_DATA_TABLE);
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
                sqe.printStackTrace();
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
                sqe.printStackTrace();
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
                System.err.println(deleteStatement());
                sqe.printStackTrace();
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

    private class StaticData extends RowData
    {
        StaticData(byte[] publicKey, byte[] staticdata)
        {
            super(new String(Base64.getEncoder().encode(publicKey)), staticdata);
        }
        StaticData(String publickey, String data)
        {
            super(publickey, data);
        }

        public String b64DataName(){return DATA_NAME;}
        public String insertStatement(){return "insert into staticdata (name, staticdata) VALUES(?, ?);";}
        public String selectStatement(){return "select name, "+b64DataName()+" from staticdata where name = '"+name+"';";}
        public String deleteStatement(){return "delete from staticdata where name = \""+ name +"\" and "+ b64DataName()+ " = \""+ b64string + "\";";}
        static final String DATA_NAME = "staticdata";
    }

    private class FollowRequestData extends RowData
    {
        FollowRequestData(UserPublicKey owner, byte[] publicKey)
        {
            super(owner.toString(), publicKey);
        }
        FollowRequestData(String name, String d)
        {
            super(name, d);
        }

        public String b64DataName(){return DATA_NAME;}
        public String insertStatement(){return "insert into followrequests (name, followrequests) VALUES(?, ?);";}
        public String selectStatement(){return "select name, "+b64DataName()+" from followrequests where name = "+name+";";}
        public String deleteStatement(){return "delete from followrequests where name = \""+ name +"\" and "+ b64DataName()+ " = \""+ b64string + "\";";}
        static final String DATA_NAME = "followrequests";
    }

    private class MetadataBlob
    {
        final byte[] writingKey, mapkey, blobdata;
        final String b64WritingKey, b64mapkey, b64blobdata;

        MetadataBlob(byte[] writingKey, byte[] mapkey, byte[] blobdata)
        {
            this(writingKey, new String(Base64.getEncoder().encode(writingKey)), mapkey, new String(Base64.getEncoder().encode(mapkey)), blobdata, blobdata == null ? null : new String(Base64.getEncoder().encode(blobdata)));

        }

        MetadataBlob(String b64WritingKey, String b64mapkey, String b64blobdata)
        {
            this(Base64.getDecoder().decode(b64WritingKey), b64WritingKey, Base64.getDecoder().decode(b64mapkey), b64mapkey, Base64.getDecoder().decode(b64blobdata), b64blobdata);
        }

        MetadataBlob(byte[] writingKey, String b64WritingKey, byte[] mapkey, String b64mapkey, byte[] blobdata, String b64blobdata)
        {
            this.writingKey = writingKey;
            this.b64WritingKey = b64WritingKey;
            this.mapkey=  mapkey;
            this.b64mapkey = b64mapkey;
            this.blobdata = blobdata;
            this.b64blobdata = b64blobdata;
        }

        public String selectStatement(){return "select writingkey, mapkey, blobdata from metadatablobs where writingkey = "+ b64WritingKey +";";}
        public String deleteStatement(){return "delete from metadatablobs where writingkey = "+ b64WritingKey +";";}

        public boolean insert()
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement("insert into metadatablobs (writingkey, mapkey, blobdata) VALUES(?, ?, ?);");

                stmt.setString(1,this.b64WritingKey);
                stmt.setString(2,this.b64mapkey);
                stmt.setString(3,this.b64blobdata);
                stmt.executeUpdate();
                return true;
            } catch (SQLException sqe) {
                sqe.printStackTrace();
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
            MetadataBlob[] fd = select("where writingKey = '"+ b64WritingKey +"' and mapkey = '"+ b64mapkey +"'");
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
                stmt = conn.prepareStatement("select writingKey, mapkey, blobdata from metadatablobs "+ selectString + ";");
                ResultSet rs = stmt.executeQuery();
                List<MetadataBlob> list = new ArrayList<MetadataBlob>();
                while (rs.next())
                {
                    MetadataBlob f = new MetadataBlob(rs.getString("writingkey"), rs.getString("mapkey"), rs.getString("blobdata"));
                    list.add(f);
                }

                return list.toArray(new MetadataBlob[0]);
            } catch (SQLException sqe) {
                System.err.println("Error selecting: "+selectString);
                sqe.printStackTrace();
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

    public JDBCCoreNode(Connection conn) throws SQLException
    {
        this.conn =  conn;
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
                //System.out.println("Adding table "+ missingTable);
                createStmt.executeUpdate(TABLES.get(missingTable));
                createStmt.close();

            } catch ( Exception e ) {
                System.err.println( e.getClass().getName() + ": " + e.getMessage() );
            }
        }
    }

    @Override
    public String getUsername(byte[] encodedKey)
    {
        String b64key = Base64.getEncoder().encodeToString(encodedKey);
        try {
            try (PreparedStatement preparedStatement = conn.prepareStatement("select name from users where publickey = ? limit 1")) {
                preparedStatement.setString(1, b64key);
                ResultSet resultSet = preparedStatement.executeQuery();
                boolean next = resultSet.next();
                if (! next)
                    return "";
                return resultSet.getString(1);
            }
        } catch (SQLException sqle) {
            throw new IllegalStateException(sqle);
        }
    }

    @Override
    public UserPublicKey getPublicKey(String username)
    {
        byte[] dummy = null;
        UserData user = new UserData(username, dummy);
        RowData[] users = user.select();
        if (users == null || users.length != 1)
            return null;
        return new UserPublicKey(users[0].data);
    }

    @Override
    public boolean addUsername(String username, byte[] encodedUserKey, byte[] signed, byte[] staticData) {
        UserPublicKey key = new UserPublicKey(encodedUserKey);

        if (! key.isValidSignature(signed, ArrayOps.concat(username.getBytes(), encodedUserKey, staticData)))
            return false;

        UserPublicKey existingKey = getPublicKey(username);
        if (existingKey != null)
            return false;
        String existingUsername = getUsername(key.getPublicKeys());
        if (existingUsername.length() > 0)
            return false;

        UserData user = new UserData(username, key.getPublicKeys());
        return user.insert();
    }

    @Override
    public byte[] getStaticData(UserPublicKey owner) {
        byte[] dummy = null;
        StaticData staticData = new StaticData(owner.getPublicKeys(), dummy);
        RowData[] users = staticData.select();
        if (users == null || users.length != 1)
            return null;
        return users[0].data;
    }

    @Override
    public boolean setStaticData(UserPublicKey owner, byte[] signedStaticData) {
        try {
            StaticData userData = new StaticData(owner.getPublicKeys(), owner.unsignMessage(signedStaticData));
            return userData.insert();
        } catch (TweetNaCl.InvalidSignatureException e) {
            System.err.println("Invalid signature setting static data for: "+owner);
            return false;
        }
    }

    @Override
    public boolean followRequest(UserPublicKey target, byte[] encryptedPermission)
    {
        // TODO check < max pending follow requests

        FollowRequestData request = new FollowRequestData(target, encryptedPermission);
        return request.insert();
    }

    @Override
    public boolean removeFollowRequest(UserPublicKey target, byte[] req)
    {
        // TODO check signature

        FollowRequestData request = new FollowRequestData(target, req);
        return request.delete();
    }

    @Override
    public byte[] getFollowRequests(UserPublicKey owner) {
        byte[] dummy = null;
        FollowRequestData request = new FollowRequestData(owner, dummy);
        RowData[] users = request.select();
        if (users == null || users.length != 1)
            return null;
        return users[0].data;
    }

    @Override
    public boolean setMetadataBlob(UserPublicKey owner, byte[] encodedWritingPublicKey, byte[] writingKeySignedMapKeyPlusBlob) {
        UserPublicKey writingKey = new UserPublicKey(encodedWritingPublicKey);

        try {
            byte[] payload = writingKey.unsignMessage(writingKeySignedMapKeyPlusBlob);
            byte[] mapKey = Arrays.copyOfRange(payload, 0, 32);
            byte[] metaDataBlob = Arrays.copyOfRange(payload, 32, payload.length);
            return addMetadataBlob(owner, writingKey, mapKey, metaDataBlob);
        } catch (TweetNaCl.InvalidSignatureException e) {
            System.err.println("Invalid signature for owner: "+owner + " and sharer: "+writingKey);
            return false;
        }
    }

    protected synchronized boolean addMetadataBlob(UserPublicKey owner, UserPublicKey writingKey, byte[] mapKey, byte[] metadataBlob)
    {
        // TODO verify

        MetadataBlob blob = new MetadataBlob(writingKey.getPublicKeys(), mapKey, metadataBlob);
        return blob.insert();
    }

    @Override
    public byte[] getMetadataBlob(UserPublicKey owner, byte[] writingKey, byte[] mapKey) {
        byte[] dummy = null;
        MetadataBlob blob = new MetadataBlob(writingKey, mapKey, dummy);
        MetadataBlob users = blob.selectOne();
        if (users == null)
            return null;
        return users.blobdata;
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
            e.printStackTrace();
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
            sqe.printStackTrace();
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
