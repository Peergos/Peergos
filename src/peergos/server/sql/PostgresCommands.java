package peergos.server.sql;

public class PostgresCommands implements SqlSupplier {

    @Override
    public String listTablesCommand() {
        return "SELECT tablename FROM pg_catalog.pg_tables " +
                "WHERE schemaname != 'pg_catalog' AND schemaname != 'information_schema';";
    }

    @Override
    public String createFollowRequestsTableCommand() {
        return "CREATE TABLE IF NOT EXISTS followrequests (id serial primary key, " +
                "name text not null, followrequest text not null);";
    }
}
