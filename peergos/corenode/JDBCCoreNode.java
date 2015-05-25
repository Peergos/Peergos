package peergos.corenode;

import peergos.crypto.UserPublicKey;
import peergos.util.ByteArrayWrapper;

import java.net.InetSocketAddress;
import java.sql.*;
import java.util.*;

public abstract class JDBCCoreNode extends AbstractCoreNode {

    private static final String TABLE_NAMES_SELECT_STMT = "SELECT * FROM sqlite_master WHERE type='table';";
    private static final String CREATE_USERS_TABLE = "create table users (id integer primary key autoincrement, name text not null, publickey text not null);";
    private static final String CREATE_FOLLOW_REQUESTS_TABLE = "create table followrequests (id integer primary key autoincrement, name text not null, publickey text not null);";
    private static final String CREATE_SHARING_KEYS_TABLE = "create table sharingkeys (id integer primary key autoincrement, name text not null, publickey text not null);";
    private static final String CREATE_FRAGMENTS_TABLE = "create table fragments (id integer primary key autoincrement, sharingkeyid not null, mapkey text not null, fragmentdata text not null);";
    private static final String CREATE_STORAGE_TABLE = "create table storage (id integer primary key autoincrement, address text not null, port integer not null, owner text not null, fraction double not null);";
    private static final String CREATE_FRAGMENTHASHES_TABLE = "create table fragmenthashes (id integer primary key autoincrement, storageid integer not null, hash text not null);";

    private static final Map<String,String> TABLES = new HashMap<>();
    static
    {
        TABLES.put("users", CREATE_USERS_TABLE);
        TABLES.put("followrequests", CREATE_FOLLOW_REQUESTS_TABLE);
        TABLES.put("sharingkeys", CREATE_SHARING_KEYS_TABLE);
        TABLES.put("fragments", CREATE_FRAGMENTS_TABLE);
        TABLES.put("storage", CREATE_STORAGE_TABLE);
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
                conn.commit();
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
                List<RowData> list = new ArrayList<RowData>();
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
                conn.commit();
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
        public String deleteStatement(){return "delete from users where name = "+ name +" and "+ b64DataName()+ " = "+ b64string + ";";}
        static final String DATA_NAME = "publickey";
    }

    private int getUserDataID(String name)
    {
        PreparedStatement stmt = null;
        try
        {
            stmt = conn.prepareStatement("select id from users where name = '" + name + "';");
            ResultSet rs = stmt.executeQuery();
            int id = -1;
            while(rs.next())
                id = rs.getInt("id");
            return id;
        } catch (SQLException sqe) {
            sqe.printStackTrace();
            return -1;
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

    private class FollowRequestData extends RowData
    {
        FollowRequestData(String name, byte[] publicKey)
        {
            super(name, publicKey);
        }
        FollowRequestData(String name, String d)
        {
            super(name, d);
        }

        public String b64DataName(){return DATA_NAME;}
        public String insertStatement(){return "insert into followrequests (name, publickey) VALUES(?, ?);";}
        public String selectStatement(){return "select name, "+b64DataName()+" from followrequests where name = "+name+";";}
        public String deleteStatement(){return "delete from followrequests where name = "+ name +" and "+ b64DataName()+ " = "+ b64string + ";";}
        static final String DATA_NAME = "publickey";
    }

    private class SharingKeyData extends RowData
    {
        SharingKeyData(String name, byte[] publicKey)
        {
            super(name, publicKey);
        }
        SharingKeyData(String name, String d)
        {
            super(name, d);
        }
        SharingKeyData(String name, byte[] publicKey, String b64publicKey)
        {
            super(name, publicKey, b64publicKey);
        }

        public String b64DataName(){return DATA_NAME;}
        public String insertStatement(){return "insert into sharingkeys (name, publickey) VALUES(?, ?);";}
        public String selectStatement(){return "select name, "+b64DataName()+" from sharingkeys where name = "+name+";";}
        public String deleteStatement(){return "delete from sharingkeys where name = "+ name +" and "+ b64DataName()+ " = "+ b64string + ";";}
        static final String DATA_NAME = "publickey";
    }

    private int getSharingKeyId(String name, byte[] sharingKey)
    {
        String b64sharingKey= new String(Base64.getEncoder().encode(sharingKey));

        PreparedStatement stmt = null;
        try
        {
            String s = "select id from sharingkeys where name = '"+ name + "' and publickey = '"+ b64sharingKey+"';";
            //System.out.println(s);
            stmt = conn.prepareStatement(s);
            ResultSet rs = stmt.executeQuery();
            int id = -1;
            while(rs.next())
                id = rs.getInt("id");
            return id;
        } catch (SQLException sqe) {
            sqe.printStackTrace();
            return -1;
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

    class FragmentHashData
    {
        final int storageID;
        final byte[] hash;
        final String b64hash;

        FragmentHashData(int storageID, byte[] hash)
        {
            this(storageID, hash, new String(Base64.getEncoder().encode(hash)));
        }
        FragmentHashData(int storageID, String b64hash)
        {
            this(storageID, Base64.getDecoder().decode(b64hash), b64hash);
        }
        FragmentHashData(int storageID, byte[] hash, String b64hash)
        {
            this.storageID = storageID;
            this.hash = hash;
            this.b64hash = b64hash;
        }

        public boolean insert()
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement("insert into fragmenthashes (storageid, hash) VALUES(?,?);");
                stmt.setInt(1,this.storageID);
                stmt.setString(2,this.b64hash);
                stmt.executeUpdate();
                conn.commit();
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

        public boolean delete(byte[] hash)
        {
            return JDBCCoreNode.this.delete("fragmenthashes", "hash = '"+new String(Base64.getEncoder().encode(hash))+"'");
        }
    }

    private class StorageNodeData
    {
        final String address, owner;
        final int port;
        final double fraction;
        StorageNodeData(String address, int port, String owner, double fraction)
        {
            this.address = address;
            this.port = port;
            this.owner = owner;
            this.fraction = fraction;
        }

        public boolean insert()
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement("insert into storage(address, port, owner, fraction) VALUES(?, ?, ?, ?);");
                stmt.setString(1,address);
                stmt.setInt(2,port);
                stmt.setString(3,owner);
                stmt.setDouble(4, fraction);
                stmt.executeUpdate();
                conn.commit();
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

        public StorageNodeData[] selectByAddress(InetSocketAddress address)
        {
            return select("address = '"+ address.getAddress() +"' and port = "+ address.getPort()+";");
        }
        public StorageNodeData[] selectByOwner(String owner)
        {
            return select("owner = '"+ owner+"';");
        }
        public StorageNodeData[] select(String criteria)
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement("select * from storage where "+criteria+";");
                ResultSet rs = stmt.executeQuery();
                List<StorageNodeData> list = new ArrayList<StorageNodeData>();
                while (rs.next())
                {
                    String address = rs.getString("address");
                    int port = rs.getInt("port");
                    String owner = rs.getString("owner");
                    double fraction = rs.getDouble("fraction");

                    list.add(new StorageNodeData(address, port, owner, fraction));
                }
                return list.toArray(new StorageNodeData[0]);
            } catch (SQLException sqe) {
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

    private class FragmentData
    {
        final byte[] mapkey, fragmentdata;
        final String b64mapkey, b64fragmentdata;
        final int sharingKeyID;

        FragmentData(int sharingKeyID, byte[] mapkey, byte[] fragmentdata)
        {
            this(sharingKeyID, mapkey, new String(Base64.getEncoder().encode(mapkey)), fragmentdata, new String(Base64.getEncoder().encode(fragmentdata)));

        }

        FragmentData(int sharingKeyID, String b64mapkey, String b64fragmentData)
        {
            this(sharingKeyID, Base64.getDecoder().decode(b64mapkey), b64mapkey, Base64.getDecoder().decode(b64fragmentData), b64fragmentData);
        }

        FragmentData(int sharingKeyID, byte[] mapkey, String b64mapkey, byte[] fragmentdata, String b64fragmentdata)
        {
            this.sharingKeyID = sharingKeyID;
            this.mapkey=  mapkey;
            this.b64mapkey = b64mapkey;
            this.fragmentdata = fragmentdata;
            this.b64fragmentdata = b64fragmentdata;
        }

        public String selectStatement(){return "select sharingkeyid, mapkey fragmentdata from fragments where sharingkeyid = "+sharingKeyID +";";}
        public String deleteStatement(){return "delete from fragments where sharingkeyid = "+ sharingKeyID +";";}

        public boolean insert()
        {
            //int id = new SharingKeyData(name, publickey, b64string).getUserDataID();
            //if (id <0)
            //    return false;
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement("insert into fragments (sharingkeyid, mapkey, fragmentdata) VALUES(?, ?, ?);");

                stmt.setInt(1,this.sharingKeyID);
                stmt.setString(2,this.b64mapkey);
                stmt.setString(3,this.b64fragmentdata);
                stmt.executeUpdate();
                conn.commit();
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

        public FragmentData selectOne()
        {
            FragmentData[] fd = select("where sharingKeyID = "+ sharingKeyID +" and mapkey = '"+ b64mapkey +"' and fragmentdata = '"+ b64fragmentdata+"'");
            if (fd == null || fd.length != 1)
                return null;
            return fd[0];
        }

        public FragmentData[] selectAllByName(String username)
        {
            return select("where name = "+ username);
        }

        public FragmentData[] select(String selectString)
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement("select sharingKeyID, mapkey, fragmentdata from fragments "+ selectString + ";");
                ResultSet rs = stmt.executeQuery();
                List<FragmentData> list = new ArrayList<FragmentData>();
                while (rs.next())
                {
                    FragmentData f = new FragmentData(rs.getInt("sharingkeyid"), rs.getString("mapkey"), rs.getString("fragmentdata"));
                    list.add(f);
                }

                return list.toArray(new FragmentData[0]);
            } catch (SQLException sqe) {
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

    public boolean deleteOneFragmentData(String name, int sharingKeyID, String b64mapkey)
    {
        return JDBCCoreNode.this.delete("fragenthashes", "name = " + name + " and sharingKeyID = " + sharingKeyID + " and mapkey = " + b64mapkey);
    }

    public boolean deleteFragmentData(String deleteString)
    {
        Statement stmt = null;
        try
        {
            stmt = conn.createStatement();
            stmt.executeUpdate("delete from fragments where "+ deleteString +";");
            conn.commit();
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

    public boolean deleteAllByName(String username)
    {
        return delete("fragmenthashes", "name = " + username);
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
                conn.commit();
            } catch ( Exception e ) {
                System.err.println( e.getClass().getName() + ": " + e.getMessage() );
            }
        }
    }

    public UserPublicKey getPublicKey(String username)
    {
        byte[] dummy = null;
        UserData user = new UserData(username, dummy);
        RowData[] users = user.select();
        if (users == null || users.length != 1)
            return null;
        return new UserPublicKey(users[0].data);
    }

    protected synchronized boolean addUsername(String username, UserPublicKey key, ByteArrayWrapper clearanceData)
    {
        if (! super.addUsername(username, key, clearanceData))
            return false;

        UserData user = new UserData(username, key.getPublicKeys());
        return user.insert();
    }

    protected synchronized boolean removeUsername(String username, UserPublicKey key)
    {
        if (! super.removeUsername(username, key))
            return false;

        UserData user = new UserData(username, key.getPublicKeys());
        RowData[] rs = user.select();
        if (rs == null || rs.length ==0)
            return false;

        return user.delete();

    }

    public synchronized boolean followRequest(String target, byte[] encryptedPermission)
    {
        if (! super.followRequest(target, encryptedPermission))
            return false;

        FollowRequestData request = new FollowRequestData(target, encryptedPermission);
        return request.insert();
    }

    protected synchronized boolean removeFollowRequest(String target, ByteArrayWrapper baw)
    {
        if (! super.removeFollowRequest(target, baw))
            return false;

        FollowRequestData request = new FollowRequestData(target, baw.data);
        return request.delete();
    }

    protected synchronized boolean allowSharingKey(String username, UserPublicKey sharingPublicKey)
    {
        if (! super.allowSharingKey(username, sharingPublicKey))
            return false;

        SharingKeyData request = new SharingKeyData(username, sharingPublicKey.getPublicKeys());
        return request.insert();
    }



    protected synchronized boolean banSharingKey(String username, UserPublicKey sharingPublicKey)
    {
        if (! super.banSharingKey(username, sharingPublicKey))
            return false;

        SharingKeyData request = new SharingKeyData(username, sharingPublicKey.getPublicKeys());
        return request.delete();
    }

    protected synchronized boolean addMetadataBlob(String username, UserPublicKey sharingKey, byte[] mapKey, byte[] metadataBlob)
    {
        if (! super.addMetadataBlob(username, sharingKey, mapKey, metadataBlob))
            return false;

        int sharingKeyID = getSharingKeyId(username, sharingKey.getPublicKeys());
        if (sharingKeyID <0)
            return false;

        FragmentData fragment = new FragmentData(sharingKeyID, mapKey, metadataBlob);
        return fragment.insert();
    }

    protected synchronized boolean removeMetadataBlob(String username, UserPublicKey sharingKey, byte[] mapKey)
    {
        if (! super.removeMetadataBlob(username, sharingKey, mapKey))
            return false;

        int sharingKeyID = getSharingKeyId(username, sharingKey.getPublicKeys());
        if (sharingKeyID <0)
            return false;
        return deleteOneFragmentData(username, sharingKeyID, new String(Base64.getEncoder().encode(mapKey)));
    }

    public Iterator<UserPublicKey> getSharingKeys(String username)
    {
        return super.getSharingKeys(username);
    }

    public MetadataBlob getMetadataBlob(String username, byte[] encodedSharingKey, byte[] mapkey)
    {
        return super.getMetadataBlob(username, encodedSharingKey, mapkey);
    }

    public boolean registerFragmentStorage(String spaceDonor, InetSocketAddress node, String owner, byte[] signedKeyPlusHash)
    {
        if (! super.registerFragmentStorage(spaceDonor, node, owner, signedKeyPlusHash))
            return false;
        return true;

        //int userID = UserData.getUserDataID(recipient);
        //if (userID <0)
        //    return false;
    }

    protected synchronized boolean addStorageNodeState(StorageNodeState state)
    {
        if (! super.addStorageNodeState(state))
            return false;
        Map<String,Float> map = state.fractions();
        for (Map.Entry<String,Float> entry: map.entrySet())
        {
            StorageNodeData s = new StorageNodeData(state.address().getAddress().toString(), state.address().getPort(), entry.getKey(), (double) entry.getValue());
            if (! s.insert())
                return false;
        }
        return true;
    }

    protected synchronized boolean addFragmentHashes(String username, UserPublicKey sharingKey, byte[] mapKey, byte[] allHashes)
    {
        if (! super.addFragmentHashes(username, sharingKey, mapKey, allHashes))
            return false;
        //TODO
        return false;
    }
    public long getQuota(String user)
    {
        return super.getQuota(user);
    }

    public long getUsage(String username)
    {
        return super.getUsage(username);
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
            conn.commit();
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
