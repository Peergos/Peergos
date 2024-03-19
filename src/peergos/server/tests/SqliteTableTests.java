package peergos.server.tests;

import org.junit.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;

import java.sql.*;
import java.util.*;

public class SqliteTableTests {

    @Test
    public void modifyTransactionsTable() throws Exception {
        long start = System.currentTimeMillis();
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        String legacyTableCreate = "CREATE TABLE IF NOT EXISTS transactions (" +
                "tid varchar(64) not null, owner varchar(64) not null, hash varchar(64) not null);";
        db.createStatement().executeUpdate(legacyTableCreate);

        PublicKeyHash owner = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, new byte[36]));
        SqliteCommands cmds = new SqliteCommands();
        {
            PreparedStatement insert = db.prepareStatement("INSERT OR IGNORE INTO transactions (tid, owner, hash) VALUES (?, ?, ?);");
            insert.clearParameters();
            insert.setString(1, "tid0");
            insert.setString(2, owner.toString());
            insert.setString(3, new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, new byte[32]).toString());
            insert.executeUpdate();
        }

        // now add a column
        JdbcTransactionStore txns = new JdbcTransactionStore(() -> db, cmds);

        Multihash hash1 = new Cid(1, Cid.Codec.Raw, Multihash.Type.sha2_256, new byte[32]);
        txns.addBlock(hash1, TransactionId.build("tid1"), owner);

        // check both entries are correct
        List<Cid> open = txns.getOpenTransactionBlocks();
        Assert.assertTrue(open.size() == 2);

        // check an immediate GC doesn't clear the new block
        txns.clearOldTransactions(start);
        Assert.assertTrue(txns.getOpenTransactionBlocks().size() == 1);

        // clear both entries
        txns.clearOldTransactions(System.currentTimeMillis() + 1000);

        // check there are no entries left
        List<Cid> empty = txns.getOpenTransactionBlocks();
        Assert.assertTrue(empty.isEmpty());
    }
}
