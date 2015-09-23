package peergos.corenode;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;

import java.util.*;
import java.net.*;
import java.sql.*;

import org.bouncycastle.util.encoders.Base64;

public class SQLiteCoreNode extends JDBCCoreNode
{
    public final String dbPath;

    private SQLiteCoreNode(Connection connection, String dbPath) throws SQLException {
        super(connection);
        this.dbPath = dbPath;
    }

    public  static SQLiteCoreNode  build(String dbPath) throws SQLException
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
        return new SQLiteCoreNode(conn, dbPath);
    }
}
