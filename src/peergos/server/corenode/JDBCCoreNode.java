package peergos.server.corenode;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.util.*;

import java.io.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class JDBCCoreNode implements CoreNode, MutablePointers {
    public static final boolean LOGGING = false;

    public static final long MIN_USERNAME_SET_REFRESH_PERIOD = 60*1000000000L;

    private static final String TABLE_NAMES_SELECT_STMT = "SELECT * FROM sqlite_master WHERE type='table';";
    private static final String CREATE_USERNAMES_TABLE =
            "create table usernames (id integer primary key autoincrement, name text not null unique);";
    private static final String CREATE_LINKS_TABLE =
            "create table links (id integer primary key autoincrement, publickey text not null unique, link text not null);";
    private static final String CREATE_CHAINS_TABLE =
            "create table chains (userID int references usernames(id), linkID int references links(id), lindex int, primary key (userID, lindex)); create unique index uniq1 on chains(userID, linkID)";
    private static final String CREATE_FOLLOW_REQUESTS_TABLE = "create table followrequests (id integer primary key autoincrement, name text not null, followrequest text not null);";
    private static final String CREATE_METADATA_BLOBS_TABLE = "create table metadatablobs (writingkey text primary key not null, hash text not null); " +
            "CREATE UNIQUE INDEX index_name on metadatablobs (writingkey);";

    private static final Map<String,String> TABLES = new HashMap<>();
    static
    {
        TABLES.put("usernames", CREATE_USERNAMES_TABLE);
        TABLES.put("links", CREATE_LINKS_TABLE);
        TABLES.put("chains", CREATE_CHAINS_TABLE);
        TABLES.put("followrequests", CREATE_FOLLOW_REQUESTS_TABLE);
        TABLES.put("metadatablobs", CREATE_METADATA_BLOBS_TABLE);
    }

    private Connection conn;
    private final UserSetCache userSet = new UserSetCache();

    private static class UserSetCache {
        private volatile List<String> userSet = null;
        private volatile LocalDateTime nextExpiry = LocalDateTime.MIN;

        public Optional<List<String>> getMostRecent() {
            if (LocalDateTime.now().isBefore(nextExpiry))
                return Optional.of(userSet);
            return Optional.empty();
        }

        public void setUserSet(List<String> set) {
            userSet = set;
            nextExpiry = LocalDateTime.now().plusNanos(MIN_USERNAME_SET_REFRESH_PERIOD);
        }
    }

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

    private class FollowRequestData extends RowData
    {
        FollowRequestData(PublicSigningKey owner, byte[] publicKey)
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
    public CompletableFuture<String> getUsername(PublicSigningKey encodedKey)
    {
        String b64key = Base64.getEncoder().encodeToString(encodedKey.serialize());
        try {
            try (PreparedStatement preparedStatement = conn.prepareStatement("select name from usernames u inner join chains ch on u.id=ch.userid inner join links ln on ch.linkid=ln.id and ln.publickey = ? limit 1")) {
                preparedStatement.setString(1, b64key);
                ResultSet resultSet = preparedStatement.executeQuery();
                boolean next = resultSet.next();
                if (! next)
                    return CompletableFuture.completedFuture("");
                String username = resultSet.getString(1);
                return getChain(username).thenApply(chain -> {
                    if (!chain.get(chain.size() - 1).owner.equals(encodedKey))
                        return "";
                    return username;
                });
            }
        } catch (SQLException sqle) {
            throw new IllegalStateException(sqle);
        }
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        try {
            try (PreparedStatement preparedStatement = conn.prepareStatement("select chains.lindex, links.publickey, links.link from links inner join chains on links.id=chains.linkid \n" +
                    "inner join usernames on chains.userid=usernames.id where usernames.name=? order by chains.lindex;")) {
                preparedStatement.setString(1, username);
                ResultSet resultSet = preparedStatement.executeQuery();
                Map<Integer, UserPublicKeyLink> serializedChain = new HashMap<>();
                while (resultSet.next()) {
                    serializedChain.put(resultSet.getInt(1), UserPublicKeyLink.fromByteArray(PublicSigningKey.fromString(resultSet.getString(2)),
                            Base64.getDecoder().decode(resultSet.getString(3))));
                }
                ArrayList<UserPublicKeyLink> result = new ArrayList<>();
                for (int i=0; i < serializedChain.size(); i++) {
                    if (!serializedChain.containsKey(i))
                        throw new IllegalStateException("Missing UserPublicKeyLink at index: "+i);
                    result.add(serializedChain.get(i));
                }
                return CompletableFuture.completedFuture(result);
            }
        } catch (SQLException sqle) {
            throw new IllegalStateException(sqle);
        }
    }

    @Override
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> tail) {
        if (! UserPublicKeyLink.validChain(tail, username))
            return CompletableFuture.completedFuture(false);

        if (UserPublicKeyLink.isExpiredClaim(tail.get(tail.size() - 1)))
            return CompletableFuture.completedFuture(false);

        if (tail.size() > 2)
            return CompletableFuture.completedFuture(false);

        return getChain(username).thenApply(existing -> {
            List<String> existingStrings = existing.stream().map(x -> new String(Base64.getEncoder().encode(x.toByteArray()))).collect(Collectors.toList());
            List<UserPublicKeyLink> merged = UserPublicKeyLink.merge(existing, tail);
            List<String> toWrite = merged.stream().map(x -> new String(Base64.getEncoder().encode(x.toByteArray()))).collect(Collectors.toList());
            Optional<PublicSigningKey> oldKey = existing.size() == 0 ? Optional.empty() : Optional.of(existing.get(existing.size() - 1).owner);
            PublicSigningKey newKey = tail.get(tail.size() - 1).owner;

            // Conceptually this should be a CAS of the new chain in for the old one under the username
            // The last one or two elements will have changed
            // Ensure usernamesandkeys table is uptodate as well
            Optional<String> existingKeyb64 = oldKey.map(x -> new String(Base64.getEncoder().encode(x.serialize())));
            String newKeyb64 = new String(Base64.getEncoder().encode(newKey.serialize()));
            if (existingStrings.size() == 0 && toWrite.size() == 1) {
                // single link to claim a new username
                try {
                    PreparedStatement user = null, link = null, chain = null;
                    try {
                        conn.setAutoCommit(false);
                        user = conn.prepareStatement("insert into usernames (name) VALUES(?);");
                        user.setString(1, username);
                        user.execute();
                        link = conn.prepareStatement("insert into links (publickey, link) VALUES(?, ?);");
                        link.setString(1, newKeyb64);
                        link.setString(2, toWrite.get(0));
                        link.execute();
                        chain = conn.prepareStatement("insert into chains (userid, linkid, lindex) select usernames.id, links.id, 0 "
                                + "from usernames join links where links.publickey=? and usernames.name=?;");
                        chain.setString(1, newKeyb64);
                        chain.setString(2, username);
                        chain.execute();
                        conn.commit();
                        // updated cached list of usernames
                        List<String> updatedUsernames = Stream.concat(
                                Stream.of(username),
                                userSet.getMostRecent().orElse(Collections.emptyList()).stream()
                        ).sorted().collect(Collectors.toList());
                        userSet.setUserSet(updatedUsernames);
                        return true;
                    } catch (SQLException sqe) {
                        throw new IllegalStateException(sqe);
                    } finally {
                        if (user != null) {
                            user.close();
                        }
                        if (link != null) {
                            link.close();
                        }
                        if (chain != null) {
                            chain.close();
                        }
                        conn.setAutoCommit(true);
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            } else if (toWrite.size() == existingStrings.size() + 1) {
                // two link update ( a key change to an existing username)
                try {
                    PreparedStatement update = null, link = null, chain = null;
                    try {
                        conn.setAutoCommit(false);
                        update = conn.prepareStatement("update links set link=? where links.publickey=?;");
                        update.setString(1, toWrite.get(toWrite.size() - 2));
                        update.setString(2, existingKeyb64.get());
                        update.execute();
                        link = conn.prepareStatement("insert into links (publickey, link) VALUES(?, ?);");
                        link.setString(1, newKeyb64);
                        link.setString(2, toWrite.get(toWrite.size() - 1));
                        link.execute();
                        chain = conn.prepareStatement("insert into chains (userid, linkid, lindex) " +
                                "select usernames.id, links.id, " +
                                "((select max(lindex) from chains inner join usernames where " +
                                "chains.userid=usernames.id and usernames.name=?)+1) " +
                                "from usernames join links where links.publickey=? and usernames.name=?;");
                        chain.setString(1, username);
                        chain.setString(2, newKeyb64);
                        chain.setString(3, username);
                        chain.execute();
                        conn.commit();
                        return true;
                    } catch (SQLException sqe) {
                        throw new IllegalStateException(sqe);
                    } finally {
                        if (update != null) {
                            update.close();
                        }
                        if (link != null) {
                            link.close();
                        }
                        if (chain != null) {
                            chain.close();
                        }
                        conn.setAutoCommit(true);
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            } else if (toWrite.size() == existingStrings.size()) {
                // single link update to existing username and key (changing expiry date)
                try (PreparedStatement stmt = conn.prepareStatement("update links set link=? where links.publickey=?;")) {
                    stmt.setString(1, toWrite.get(toWrite.size() - 1));
                    stmt.setString(2, existingKeyb64.get());
                    stmt.execute();

                    return true;
                } catch (SQLException sqe) {
                    throw new IllegalStateException(sqe);
                }
            } else
                throw new IllegalStateException("Tried to shorten key chain for username: " + username + "!");
        });
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        Optional<List<String>> cached = userSet.getMostRecent();
        if (cached.isPresent())
            return CompletableFuture.completedFuture(cached.get());
        try (PreparedStatement stmt = conn.prepareStatement("select name from usernames where name like ?"))
        {
            stmt.setString(1, prefix + "%");
            ResultSet rs = stmt.executeQuery();
            List<String> list = new ArrayList<>();
            while (rs.next())
            {
                String username = rs.getString("name");
                list.add(username);
            }

            userSet.setUserSet(list);
            return CompletableFuture.completedFuture(list);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Boolean> followRequest(PublicSigningKey owner, byte[] encryptedPermission)
    {
        byte[] dummy = null;
        FollowRequestData selector = new FollowRequestData(owner, dummy);
        RowData[] requests = selector.select();
        if (requests != null && requests.length > CoreNode.MAX_PENDING_FOLLOWERS)
            return CompletableFuture.completedFuture(false);
        // ToDo add a crypto currency transaction to prevent spam

        FollowRequestData request = new FollowRequestData(owner, encryptedPermission);
        return CompletableFuture.completedFuture(request.insert());
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(PublicSigningKey owner, byte[] req)
    {
        try {
            byte[] unsigned = owner.unsignMessage(req);

            FollowRequestData request = new FollowRequestData(owner, unsigned);
            return CompletableFuture.completedFuture(request.delete());
        } catch (TweetNaCl.InvalidSignatureException e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicSigningKey owner) {
        byte[] dummy = null;
        FollowRequestData request = new FollowRequestData(owner, dummy);
        RowData[] requests = request.select();
        if (requests == null)
            return CompletableFuture.completedFuture(new byte[4]);

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutput dout = new DataOutputStream(bout);
        try {
            dout.writeInt(requests.length);
            for (RowData req : requests)
                Serialize.serialize(req.data, dout);
            return CompletableFuture.completedFuture(bout.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicSigningKey owner, PublicSigningKey writer, byte[] writingKeySignedHash) {
        try {
            return getPointer(writer).thenApply(current -> {
                byte[] bothHashes = writer.unsignMessage(writingKeySignedHash);
                // check CAS [current hash, new hash]
                HashCasPair cas = HashCasPair.fromCbor(CborObject.fromByteArray(bothHashes));
                MaybeMultihash claimedCurrentHash = cas.original;
                Multihash newHash = cas.updated.get();
                if (!current.equals(claimedCurrentHash))
                    return false;
                if (LOGGING)
                    System.out.println("Core::setMetadata for " + writer + " from " + current + " to " + newHash);
                MetadataBlob blob = new MetadataBlob(writer.serialize(), bothHashes);
                return blob.insert();
            });
        } catch (TweetNaCl.InvalidSignatureException e) {
            System.err.println("Invalid signature during setMetadataBlob for sharer: " + writer);
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override
    public CompletableFuture<MaybeMultihash> getPointer(PublicSigningKey writingKey) {
        byte[] dummy = null;
        MetadataBlob blob = new MetadataBlob(writingKey.serialize(), dummy);
        MetadataBlob users = blob.selectOne();
        if (users == null)
            return CompletableFuture.completedFuture(MaybeMultihash.EMPTY());
        HashCasPair cas = HashCasPair.fromCbor(CborObject.fromByteArray(users.hash));
        return CompletableFuture.completedFuture(cas.updated);
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
