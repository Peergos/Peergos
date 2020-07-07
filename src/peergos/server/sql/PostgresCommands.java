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

    @Override
    public String insertTransactionCommand() {
        return "INSERT INTO transactions (tid, owner, hash) VALUES(?, ?, ?) ON CONFLICT DO NOTHING;";
    }

    @Override
    public String insertOrIgnoreCommand(String prefix, String suffix) {
        return prefix + suffix + " ON CONFLICT DO NOTHING;";
    }

    @Override
    public String getByteArrayType() {
        return "BYTEA";
    }

    @Override
    public String getSerialIdType() {
        return "SERIAL";
    }

    @Override
    public String sqlInteger() {
        return "BIGINT";
    }
}
