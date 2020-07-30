package peergos.server.sql;

public class SqliteCommands implements SqlSupplier {

    @Override
    public String listTablesCommand() {
        return "SELECT NAME FROM sqlite_master WHERE type='table';";
    }

    @Override
    public String createFollowRequestsTableCommand() {
        return "CREATE TABLE IF NOT EXISTS followrequests (id integer primary key autoincrement, " +
                "name text not null, followrequest text not null);";
    }

    @Override
    public String insertTransactionCommand() {
        return "INSERT OR IGNORE INTO transactions (tid, owner, hash) VALUES (?, ?, ?);";
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
