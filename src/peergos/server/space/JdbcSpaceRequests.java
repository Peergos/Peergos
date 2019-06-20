package peergos.server.space;

import peergos.server.util.Logging;
import peergos.shared.storage.*;

import java.sql.*;
import java.util.*;
import java.util.logging.*;
import java.util.stream.*;

public class JdbcSpaceRequests {
	private static final Logger LOG = Logging.LOG();

    private static final String TABLE_NAMES_SELECT_STMT = "SELECT * FROM sqlite_master WHERE type='table';";

    private static final String SPACE_REQUEST_USER_NAME = "name";
    private static final String SPACE_REQUEST_DATA_NAME = "spacerequest";
    private static final String CREATE_SPACE_REQUESTS_TABLE =
            "CREATE TABLE spacerequests (name text primary key not null, spacerequest text not null);";
    private static final String INSERT_SPACE_REQUEST = "INSERT INTO spacerequests (name, spacerequest) VALUES(?, ?);";
    private static final String SELECT_SPACE_REQUESTS = "SELECT name, spacerequest FROM spacerequests;";
    private static final String DELETE_SPACE_REQUEST = "DELETE FROM spacerequests WHERE name = ? AND spacerequest = ?;";

    private static final Map<String,String> TABLES = new HashMap<>();
    static {
        TABLES.put("spacerequests", CREATE_SPACE_REQUESTS_TABLE);
    }

    private Connection conn;

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
            try (PreparedStatement insert = conn.prepareStatement(INSERT_SPACE_REQUEST)) {
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
            try (PreparedStatement select = conn.prepareStatement(SELECT_SPACE_REQUESTS)) {
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
            try (PreparedStatement delete = conn.prepareStatement(DELETE_SPACE_REQUEST)) {
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

    public JdbcSpaceRequests(Connection conn) throws SQLException {
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

    public Boolean addSpaceRequest(String username, byte[] signedRequest) {
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
        try {
            if (conn != null)
                conn.close();
            isClosed = true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }

    public static JdbcSpaceRequests buildSqlLite(String dbPath) throws SQLException {
        try
        {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException cnfe) {
            throw new SQLException(cnfe);
        }

        String url = "jdbc:sqlite:"+dbPath;
        Connection conn = DriverManager.getConnection(url);
        conn.setAutoCommit(true);
        return new JdbcSpaceRequests(conn);
    }
}
