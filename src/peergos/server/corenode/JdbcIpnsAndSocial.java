package peergos.server.corenode;
import java.util.function.*;
import java.util.logging.*;

import peergos.server.sql.*;
import peergos.server.util.Logging;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class JdbcIpnsAndSocial {

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
            try (Connection conn = getConnection();
                 PreparedStatement insert = conn.prepareStatement(INSERT_FOLLOW_REQUEST)) {
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
            try (Connection conn = getConnection();
                 PreparedStatement select = conn.prepareStatement(SELECT_FOLLOW_REQUESTS)) {
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
            try (Connection conn = getConnection();
                 PreparedStatement delete = conn.prepareStatement(DELETE_FOLLOW_REQUEST)) {
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
    private Supplier<Connection> conn;

    public JdbcIpnsAndSocial(Supplier<Connection> conn, SqlSupplier commands) {
        this.conn = conn;
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
        if (isClosed)
            return;

        try (Connection conn = getConnection()) {
            commands.createTable(commands.createFollowRequestsTableCommand(), conn);
            commands.createTable(commands.createMutablePointersTableCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
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

    public List<BlindFollowRequest> getAndParseFollowRequests(PublicKeyHash owner) {
        byte[] reqs = getFollowRequests(owner).join();
        CborObject cbor = CborObject.fromByteArray(reqs);
        if (!(cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for list of follow requests: " + cbor);
        return ((CborObject.CborList) cbor).value.stream()
                .map(BlindFollowRequest::fromCbor)
                .collect(Collectors.toList());
    }

    public CompletableFuture<Boolean> setPointer(PublicKeyHash writingKey, Optional<byte[]> existingCas, byte[] newCas) {
        if (existingCas.isPresent()) {
            try (Connection conn = getConnection();
                 PreparedStatement insert = conn.prepareStatement(IPNS_UPDATE)) {
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
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(IPNS_CREATE)) {
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
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(IPNS_GET)) {
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

    public Map<PublicKeyHash, byte[]> getAllEntries() {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM metadatablobs")) {
            ResultSet rs = stmt.executeQuery();
            Map<PublicKeyHash, byte[]> results = new HashMap<>();
            while (rs.next()) {
                PublicKeyHash writerHash = PublicKeyHash.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("writingKey"))));
                byte[] signedRawCas = Base64.getDecoder().decode(rs.getString(IPNS_TARGET_NAME));
                results.put(writerHash, signedRawCas);
            }

            return results;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            return Collections.emptyMap();
        }
    }

    public synchronized void close() {
        if (isClosed)
            return;

        isClosed = true;
    }
}
