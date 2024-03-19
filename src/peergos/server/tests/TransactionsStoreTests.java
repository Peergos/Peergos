package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;

import java.security.*;
import java.util.*;

@RunWith(Parameterized.class)
public class TransactionsStoreTests {

    private final TransactionStore store;

    public TransactionsStoreTests(TransactionStore store) {
        this.store = store;
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() throws Exception {
        TransactionStore ram = JdbcTransactionStore.build(Main.buildEphemeralSqlite(), new SqliteCommands());
        return Arrays.asList(new Object[][] {
                {ram}
        });
    }

    public static byte[] hash(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(input);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("couldn't find hash algorithm");
        }
    }

    public static Cid hashToCid(byte[] input, boolean isRaw) {
        byte[] hash = hash(input);
        return new Cid(1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
    }

    @Test
    public void singleTransaction() {
        Cid multihash = hashToCid(new byte[2], true);
        PublicKeyHash owner = new PublicKeyHash(multihash);
        TransactionId tid = store.startTransaction(owner);
        List<Multihash> pending = new ArrayList<>();
        for (int i=0; i < 20; i++) {
            Cid block = hashToCid(new byte[]{(byte) i}, false);
            pending.add(block);
            store.addBlock(block, tid, owner);
        }
        List<Cid> uncommitted = store.getOpenTransactionBlocks();
        Assert.assertTrue("All blocks present", uncommitted.containsAll(pending));

        store.closeTransaction(owner, tid);
        List<Cid> empty = store.getOpenTransactionBlocks();
        Assert.assertTrue("All blocks removed", empty.isEmpty());
    }
}
