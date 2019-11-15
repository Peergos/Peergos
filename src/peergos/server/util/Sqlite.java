package peergos.server.util;

import org.sqlite.*;

import java.sql.*;

public class Sqlite {

    public static Connection build(String dbPath) throws SQLException {
        String url = "jdbc:sqlite:"+dbPath;
        SQLiteDataSource dc = new SQLiteDataSource();
        dc.setUrl(url);

        Connection conn = dc.getConnection();
        conn.setAutoCommit(true);
        return conn;
    }

    public static String getDbPath(Args a, String type) {
        String sqlFile = a.getArg(type);
        return sqlFile.equals(":memory:") ? sqlFile : a.fromPeergosDir(type).toString();
    }
}
