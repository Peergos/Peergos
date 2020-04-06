package peergos.server.space;

import peergos.server.sql.*;
import peergos.server.util.*;
import peergos.shared.storage.*;

import java.sql.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

public class JdbcSpaceRequests {
	private static final Logger LOG = Logging.LOG();

    private static final String SPACE_REQUEST_USER_NAME = "name";
    private static final String SPACE_REQUEST_DATA_NAME = "spacerequest";
    private static final String INSERT_SPACE_REQUEST = "INSERT INTO spacerequests (name, spacerequest) VALUES(?, ?);";
    private static final String SELECT_SPACE_REQUESTS = "SELECT name, spacerequest FROM spacerequests;";
    private static final String DELETE_SPACE_REQUEST = "DELETE FROM spacerequests WHERE name = ? AND spacerequest = ?;";

    private class SpaceRequestData {
        public final String name;
        public final byte[] data;
        public final String b64string;

        SpaceRequestData(String name, byte[] data) {
            this(name,data,(data == null ? null: new String(Base64.getEncoder().encode(data))));
        }

        SpaceRequestData(String name, String d) {
            this(name, Base64.getDecoder().decode(d), d);
        }

        SpaceRequestData(String name, byte[] data, String b64string) {
            this.name = name;
            this.data = data;
            this.b64string = b64string;
        }

        public boolean insert() {
            try (Connection conn = getConnection();
                 PreparedStatement insert = conn.prepareStatement(INSERT_SPACE_REQUEST)) {
                insert.setString(1,this.name);
                insert.setString(2,this.b64string);
                insert.executeUpdate();
                return true;
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return false;
            }
        }

        public SpaceRequestData[] select() {
            try (Connection conn = getConnection();
                 PreparedStatement select = conn.prepareStatement(SELECT_SPACE_REQUESTS)) {
                ResultSet rs = select.executeQuery();
                List<SpaceRequestData> list = new ArrayList<>();
                while (rs.next())
                {
                    String username = rs.getString(SPACE_REQUEST_USER_NAME);
                    String b64string = rs.getString(SPACE_REQUEST_DATA_NAME);
                    list.add(new SpaceRequestData(username, b64string));
                }
                return list.toArray(new SpaceRequestData[0]);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return null;
            }
        }

        public boolean delete() {
            try (Connection conn = getConnection();
                 PreparedStatement delete = conn.prepareStatement(DELETE_SPACE_REQUEST)) {
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

    public JdbcSpaceRequests(Supplier<Connection> conn, SqlSupplier commands) {
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
            commands.createTable(commands.createSpaceRequestsTableCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean addSpaceRequest(String username, byte[] signedRequest) {
        SpaceRequestData request = new SpaceRequestData(username, signedRequest);
        return request.insert();
    }

    public boolean removeSpaceRequest(String username, byte[] unsigned) {
        SpaceRequestData request = new SpaceRequestData(username, unsigned);
        return request.delete();
    }

    public List<SpaceUsage.LabelledSignedSpaceRequest> getSpaceRequests() {
        byte[] dummy = null;
        SpaceRequestData request = new SpaceRequestData(null, dummy);
        SpaceRequestData[] requests = request.select();
        if (requests == null)
            return Collections.emptyList();

        return Arrays.asList(requests).stream()
                .map(req -> new SpaceUsage.LabelledSignedSpaceRequest(req.name, req.data))
                .collect(Collectors.toList());
    }

    public synchronized void close() {
        if (isClosed)
            return;
        isClosed = true;
    }

    public static JdbcSpaceRequests build(Supplier<Connection> conn, SqlSupplier commands) {
        return new JdbcSpaceRequests(conn, commands);
    }
}
