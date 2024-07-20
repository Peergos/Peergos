package peergos.server.sql;

import java.sql.*;

public interface SqlSupplier {

    String listTablesCommand();

    String tableExistsCommand();

    String createFollowRequestsTableCommand();

    String insertTransactionCommand();

    String insertServerIdCommand();

    String getByteArrayType();

    String getSerialIdType();

    String sqlInteger();

    String ensureColumnExistsCommand(String table, String column, String type);

    String addMetadataCommand();

    String vacuumCommand();

    default String createMutablePointersTableCommand() {
        return "CREATE TABLE IF NOT EXISTS metadatablobs (writingkey text primary key not null, hash text not null); " +
                "CREATE UNIQUE INDEX IF NOT EXISTS index_name ON metadatablobs (writingkey);";
    }

    default String createAccountTableCommand() {
        return "CREATE TABLE IF NOT EXISTS login (username text primary key not null, entry text not null, reader text not null); " +
                "CREATE UNIQUE INDEX IF NOT EXISTS login_index ON login (username);";
    }

    // credid is <= 1023 bytes
    default String createMfaTableCommand() {
        return "CREATE TABLE IF NOT EXISTS mfa (username text not null, name text not null, credid " + getByteArrayType() + " not null, " +
                "type " + sqlInteger() + " not null, " +
                "enabled boolean not null, " +
                "created " + sqlInteger() + " not null, " +
                "value " + getByteArrayType() + " not null); " +
                "CREATE INDEX IF NOT EXISTS mfa_index ON mfa (username);";
    }

    default String createMfaChallengeTableCommand() {
        return "CREATE TABLE IF NOT EXISTS mfa_challenge (username text primary key not null, challenge " + getByteArrayType() + " not null); " +
                "CREATE UNIQUE INDEX IF NOT EXISTS mfa_challenge_index ON mfa_challenge (username);";
    }

    default String createBatStoreTableCommand() {
        return "CREATE TABLE IF NOT EXISTS bats (username text not null, id text primary key not null, bat text not null); " +
                "CREATE UNIQUE INDEX IF NOT EXISTS bat_index ON bats (id);";
    }

    default String createLinkCountTableCommand() {
        return "CREATE TABLE IF NOT EXISTS linkcounts (username text not null, label "+sqlInteger()+" not null, count "+
                sqlInteger()+" not null, modified "+sqlInteger()+" not null, PRIMARY KEY (username, label)); ";
    }

    default String createSpaceRequestsTableCommand() {
        return "CREATE TABLE IF NOT EXISTS spacerequests (name text primary key not null, spacerequest text not null);";
    }

    default String createQuotasTableCommand() {
        return "CREATE TABLE IF NOT EXISTS freequotas (name text primary key not null, quota BIGINT not null);" +
                "CREATE TABLE IF NOT EXISTS signuptokens (token varchar(64) primary key not null);";
    }

    default String createTransactionsTableCommand() {
        return "CREATE TABLE IF NOT EXISTS transactions (" +
                "tid varchar(64) not null, owner varchar(64) not null, hash varchar(64) not null, time " + sqlInteger()+");";
    }

    default String createServerIdentitiesTableCommand() {
        return "CREATE TABLE IF NOT EXISTS serverids (" +
                "id " + getSerialIdType() + " primary key not null," +
                "peerid " + getByteArrayType() + " not null, " +
                "private " + getByteArrayType() + ", " +
                "record " + getByteArrayType() + " not null);";
    }

    default String createBlockMetadataStoreTableCommand() {
        return "CREATE TABLE IF NOT EXISTS blockmetadata (cid " + getByteArrayType() + " primary key not null, " +
                "version varchar(160)," +
                "size " + sqlInteger() + " not null, " +
                "links " + getByteArrayType() + " not null, " +
                "batids " + getByteArrayType() + " not null);";
    }

    default String createServerMessageTableCommand() {
        return "CREATE TABLE IF NOT EXISTS messages (" +
                "id " + getSerialIdType() + " PRIMARY KEY NOT NULL," +
                "username VARCHAR(32) NOT NULL," +
                "type " + sqlInteger() + " NOT NULL," +
                "sent " + sqlInteger() + " NOT NULL," +
                "body text NOT NULL," +
                "priorid " + sqlInteger() + "," +
                "dismissed boolean" +
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
