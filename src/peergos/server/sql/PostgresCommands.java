package peergos.server.sql;

import java.util.*;

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

    @Override
    public List<String> migrateBlockMetadataToOwnerKeyCommands() {
        return List.of(
                "DELETE FROM blockmetadata WHERE owner IS NULL;",
                "ALTER TABLE blockmetadata DROP CONSTRAINT blockmetadata_pkey;",
                "ALTER TABLE blockmetadata ALTER COLUMN owner SET NOT NULL;",
                "ALTER TABLE blockmetadata ADD PRIMARY KEY (owner, cid);");
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
