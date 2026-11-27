package peergos.server.sql;

public class PostgresCommands implements SqlSupplier {

    @Override
    public String vacuumCommand() {
        return "";
    }

    @Override
    public String listTablesCommand() {
        return "SELECT tablename FROM pg_catalog.pg_tables " +
                "WHERE schemaname != 'pg_catalog' AND schemaname != 'information_schema';";
    }

    @Override
    public String tableExistsCommand() {
        return "SELECT table_name FROM information_schema.tables WHERE table_schema LIKE 'public' AND table_type LIKE 'BASE TABLE' AND table_name = ?;";
    }

    @Override
    public String addMetadataCommand(int rows) {
        return "INSERT INTO blockmetadata (owner, cid, version, size, links, batids) VALUES " +
                SqlSupplier.valuePlaceholders(6, rows) + " ON CONFLICT DO NOTHING;";
    }

    @Override
    public String blockMetadataPrimaryKeySizeCommand() {
        return "SELECT COUNT(*) FROM information_schema.key_column_usage k " +
                "JOIN information_schema.table_constraints t ON k.constraint_name = t.constraint_name AND k.table_name = t.table_name " +
                "WHERE t.table_name = 'blockmetadata' AND t.constraint_type = 'PRIMARY KEY';";
    }

    /** Older cockroachdb tables call it "primary" */
    public String blockMetadataPrimaryKeyNameCommand() {
        return "SELECT constraint_name FROM information_schema.table_constraints " +
                "WHERE table_name = 'blockmetadata' AND constraint_type = 'PRIMARY KEY';";
    }

    public String deleteNullOwnerBlockMetadataCommand(int batchSize) {
        return "DELETE FROM blockmetadata WHERE cid IN (SELECT cid FROM blockmetadata WHERE owner IS NULL LIMIT " + batchSize + ");";
    }

    /** One statement, so cockroachdb swaps the key without keeping the old one as a unique index */
    public String rekeyBlockMetadataCommand(String oldPrimaryKeyName) {
        return "ALTER TABLE blockmetadata DROP CONSTRAINT \"" + oldPrimaryKeyName + "\", " +
                "ADD CONSTRAINT blockmetadata_pkey PRIMARY KEY (owner, cid);";
    }

    @Override
    public String createFollowRequestsTableCommand() {
        return "CREATE TABLE IF NOT EXISTS followrequests (id serial primary key, " +
                "name text not null, followrequest text not null);";
    }

    @Override
    public String ensureColumnExistsCommand(String table, String column, String type) {
        return "ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column + " " + type + ";";
    }

    @Override
    public String insertTransactionCommand() {
        return "INSERT INTO transactions (tid, owner, hash, time) VALUES(?, ?, ?, ?) ON CONFLICT DO NOTHING;";
    }

    @Override
    public String insertServerIdCommand() {
        return "INSERT INTO serverids (peerid, record) VALUES(?, ?) ON CONFLICT DO NOTHING;";
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
