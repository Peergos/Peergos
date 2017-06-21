package peergos.server.corenode;

import peergos.shared.storage.*;

import java.sql.*;

public class SQLiteCoreNode extends JDBCCoreNode
{
    public final String dbPath;

    private SQLiteCoreNode(Connection connection, String dbPath, ContentAddressedStorage ipfs) throws SQLException {
        super(connection, ipfs);
        this.dbPath = dbPath;
    }

    public static SQLiteCoreNode build(String dbPath, ContentAddressedStorage ipfs) throws SQLException
    {
        try
        {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException cnfe) {
            throw new SQLException(cnfe);
        }

        String url = "jdbc:sqlite:"+dbPath;
        Connection conn= DriverManager.getConnection(url);
        conn.setAutoCommit(true);
        return new SQLiteCoreNode(conn, dbPath, ipfs);
    }
}
