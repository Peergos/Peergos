package peergos.server.sql;

public class SqliteCommands implements SqlSupplier {

    @Override
    public String vacuumCommand() {
        return "VACUUM;";
    }

    @Override
    public String listTablesCommand() {
        return "SELECT NAME FROM sqlite_master WHERE type='table';";
    }

    @Override
    public String tableExistsCommand() {
        return "SELECT name FROM sqlite_master WHERE type='table' AND name=?;";
    }

    @Override
    public String addMetadataCommand() {
        return "INSERT OR IGNORE INTO blockmetadata (cid, version, size, links, batids) VALUES(?, ?, ?, ?, ?);";
    }

    @Override
    public String createFollowRequestsTableCommand() {
        return "CREATE TABLE IF NOT EXISTS followrequests (id integer primary key autoincrement, " +
                "name text not null, followrequest text not null);";
    }

    @Override
    public String ensureColumnExistsCommand(String table, String column, String type) {
        return "ALTER TABLE " + table + " ADD COLUMN " + column + " " + type + ";";
    }

    @Override
    public String insertTransactionCommand() {
        return "INSERT OR IGNORE INTO transactions (tid, owner, hash, time) VALUES (?, ?, ?, ?);";
    }

    @Override
    public String insertServerIdCommand() {
        return "INSERT OR IGNORE INTO serverids (peerid, record) VALUES (?, ?);";
    }

    @Override
    public String insertOrIgnoreCommand(String prefix, String suffix) {
        return prefix + "OR IGNORE " + suffix + ";";
    }

    @Override
    public String getByteArrayType() {
        return "blob";
    }

    @Override
    public String getSerialIdType() {
        return "INTEGER";
    }

    @Override
    public String sqlInteger() {
        return "INTEGER";
    }
}
