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
}
