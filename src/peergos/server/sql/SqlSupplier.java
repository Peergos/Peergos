package peergos.server.sql;

import java.sql.*;

public interface SqlSupplier {

    String listTablesCommand();

    String createFollowRequestsTableCommand();

    String insertTransactionCommand();

    String getByteArrayType();

    String getSerialIdType();

    String sqlInteger();

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

    default String createServerMessageTableCommand() {
        return "CREATE TABLE IF NOT EXISTS messages (" +
                "id " + getSerialIdType() + " PRIMARY KEY NOT NULL," +
                "type " + sqlInteger() + " NOT NULL," +
                "sent " + sqlInteger() + " NOT NULL," +
                "body text NOT NULL," +
                ");";
    }

    String insertOrIgnoreCommand(String prefix, String suffix);

    default String createUsageTablesCommand() {
        return "CREATE TABLE IF NOT EXISTS users (" +
                "id " + getSerialIdType() + " PRIMARY KEY NOT NULL," +
                "name VARCHAR(32) NOT NULL," +
                "CONSTRAINT uniq_users UNIQUE (name)" +
                ");" +
                "CREATE TABLE IF NOT EXISTS userusage (" +
                "user_id INTEGER REFERENCES users(id) PRIMARY KEY," +
                "total_bytes BIGINT NOT NULL," +
                "errored BOOLEAN NOT NULL," +
                "CONSTRAINT uniq_usage UNIQUE (user_id)" +
                ");" +
                "CREATE TABLE IF NOT EXISTS writers (" +
                "id " + getSerialIdType() + " PRIMARY KEY NOT NULL," +
                "key_hash " + getByteArrayType() + " NOT NULL," +
                "CONSTRAINT uniq_writers UNIQUE (key_hash)" +
                ");" +
                "CREATE TABLE IF NOT EXISTS pendingusage (" +
                "user_id INTEGER REFERENCES users(id)," +
                "writer_id INTEGER REFERENCES writers(id) PRIMARY KEY," +
                "pending_bytes INTEGER NOT NULL" +
                ");" +
                "CREATE TABLE IF NOT EXISTS writerusage (" +
                "writer_id INTEGER REFERENCES writers(id)," +
                "user_id INTEGER REFERENCES users(id)," +
                "target " + getByteArrayType() + "," +
                "direct_size BIGINT NOT NULL," +
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
