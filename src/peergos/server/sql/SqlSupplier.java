package peergos.server.sql;

import java.sql.*;

public interface SqlSupplier {

    String listTablesCommand();

    String createFollowRequestsTableCommand();

    String insertTransactionCommand();

    default String createMutablePointersTableCommand() {
        return "CREATE TABLE IF NOT EXISTS metadatablobs (writingkey text primary key not null, hash text not null); " +
                "CREATE UNIQUE INDEX IF NOT EXISTS index_name ON metadatablobs (writingkey);";
    }

    default String createSpaceRequestsTableCommand() {
        return "CREATE TABLE IF NOT EXISTS spacerequests (name text primary key not null, spacerequest text not null);";
    }

    default String createTransactionsTableCommand() {
        return "CREATE TABLE IF NOT EXISTS transactions (" +
                "tid varchar(64) not null, owner varchar(64) not null, hash varchar(64) not null);";
    }

    default String createUsageTablesCommand() {
        return "CREATE TABLE IF NOT EXISTS users (" +
                "id INTEGER PRIMARY KEY NOT NULL," +
                "name VARCHAR(32) NOT NULL," +
                "CONSTRAINT uniq UNIQUE (name)" +
                ");" +
                "CREATE TABLE IF NOT EXISTS userusage (" +
                "user_id INTEGER REFERENCES users(id) PRIMARY KEY," +
                "total_bytes INTEGER NOT NULL," +
                "errored BOOLEAN NOT NULL," +
                "CONSTRAINT uniq UNIQUE (user_id)" +
                ");" +
                "CREATE TABLE IF NOT EXISTS writers (" +
                "id INTEGER PRIMARY KEY NOT NULL," +
                "key_hash blob NOT NULL," +
                "CONSTRAINT uniq UNIQUE (key_hash)" +
                ");" +
                "CREATE TABLE IF NOT EXISTS pendingusage (" +
                "user_id INTEGER REFERENCES users(id)," +
                "writer_id INTEGER REFERENCES writers(id) PRIMARY KEY," +
                "pending_bytes INTEGER NOT NULL" +
                ");" +
                "CREATE TABLE IF NOT EXISTS writerusage (" +
                "writer_id INTEGER REFERENCES writers(id)," +
                "user_id INTEGER REFERENCES users(id)," +
                "target blob," +
                "direct_size INTEGER NOT NULL," +
                "CONSTRAINT uniq UNIQUE (writer_id)" +
                ");"+
                "CREATE TABLE IF NOT EXISTS ownedkeys (" +
                "parent_id INTEGER REFERENCES writers(id)," +
                "owned_id INTEGER REFERENCES writers(id)" +
                ");";
    }

    default void createTable(String sqlTableCreate, Connection conn) throws SQLException {
        Statement createStmt = conn.createStatement();
        createStmt.executeUpdate(sqlTableCreate);
        createStmt.close();
    }
}
