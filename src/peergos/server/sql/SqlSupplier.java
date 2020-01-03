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
                "tid text not null, owner text not null, hash text not null, primary key (tid, owner, hash));";
    }

    default void createTable(String sqlTableCreate, Connection conn) throws SQLException {
        Statement createStmt = conn.createStatement();
        createStmt.executeUpdate(sqlTableCreate);
        createStmt.close();
    }
}
