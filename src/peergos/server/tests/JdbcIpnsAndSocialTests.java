package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.sql.*;
import peergos.server.util.Sqlite;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;

import java.sql.*;
import java.util.*;

public class JdbcIpnsAndSocialTests {

    private JdbcIpnsAndSocial db;

    @Before
    public void setup() throws Exception {
        SqlSupplier commands = new SqliteCommands();
        Connection conn = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        db = new JdbcIpnsAndSocial(() -> conn, commands);
    }

    private static PublicKeyHash key(Crypto crypto) {
        return new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
    }

    @Test
    public void setNewPointerRetrievable() throws Exception {
        Crypto crypto = Main.initCrypto();
        PublicKeyHash writer = key(crypto);
        byte[] value = {1, 2, 3};

        boolean result = db.setPointers(List.of(Optional.empty()),
                List.of(new SignedPointerUpdate(writer, value))).join();

        Assert.assertTrue(result);
        Optional<byte[]> retrieved = db.getPointer(writer).join();
        Assert.assertTrue(retrieved.isPresent());
        Assert.assertArrayEquals(value, retrieved.get());
    }

    @Test
    public void updatePointerWithCorrectExpectedSucceeds() throws Exception {
        Crypto crypto = Main.initCrypto();
        PublicKeyHash writer = key(crypto);
        byte[] v1 = {1, 2, 3};
        byte[] v2 = {4, 5, 6};
        db.setPointers(List.of(Optional.empty()),
                List.of(new SignedPointerUpdate(writer, v1))).join();

        boolean result = db.setPointers(List.of(Optional.of(v1)),
                List.of(new SignedPointerUpdate(writer, v2))).join();

        Assert.assertTrue(result);
        Assert.assertArrayEquals(v2, db.getPointer(writer).join().get());
    }

    @Test
    public void updatePointerWithWrongExpectedFails() throws Exception {
        Crypto crypto = Main.initCrypto();
        PublicKeyHash writer = key(crypto);
        byte[] v1 = {1, 2, 3};
        byte[] v2 = {4, 5, 6};
        byte[] vWrong = {9, 9, 9};
        db.setPointers(List.of(Optional.empty()),
                List.of(new SignedPointerUpdate(writer, v1))).join();

        boolean result = db.setPointers(List.of(Optional.of(vWrong)),
                List.of(new SignedPointerUpdate(writer, v2))).join();

        Assert.assertFalse(result);
        Assert.assertArrayEquals(v1, db.getPointer(writer).join().get());
    }

    @Test
    public void setNewPointerFailsWhenKeyAlreadyExists() throws Exception {
        Crypto crypto = Main.initCrypto();
        PublicKeyHash writer = key(crypto);
        byte[] v1 = {1, 2, 3};
        byte[] v2 = {4, 5, 6};
        db.setPointers(List.of(Optional.empty()),
                List.of(new SignedPointerUpdate(writer, v1))).join();

        // expected=empty means "new key", but it already exists — should fail
        boolean result = db.setPointers(List.of(Optional.empty()),
                List.of(new SignedPointerUpdate(writer, v2))).join();

        Assert.assertFalse(result);
        Assert.assertArrayEquals(v1, db.getPointer(writer).join().get());
    }

    @Test
    public void atomicMultiPointerAllSucceed() throws Exception {
        Crypto crypto = Main.initCrypto();
        PublicKeyHash w1 = key(crypto), w2 = key(crypto);
        byte[] v1 = {1}, v2 = {2};

        boolean result = db.setPointers(
                List.of(Optional.empty(), Optional.empty()),
                List.of(new SignedPointerUpdate(w1, v1), new SignedPointerUpdate(w2, v2))).join();

        Assert.assertTrue(result);
        Assert.assertArrayEquals(v1, db.getPointer(w1).join().get());
        Assert.assertArrayEquals(v2, db.getPointer(w2).join().get());
    }

    @Test
    public void atomicMultiPointerOneWrongCasLeavesNeitherUpdated() throws Exception {
        Crypto crypto = Main.initCrypto();
        PublicKeyHash w1 = key(crypto), w2 = key(crypto);
        byte[] v1init = {0};
        byte[] v1new = {1};
        byte[] v2 = {2};
        db.setPointers(List.of(Optional.empty()),
                List.of(new SignedPointerUpdate(w1, v1init))).join();

        // w1: wrong expected, w2: new insert — both should be rolled back
        boolean result = db.setPointers(
                List.of(Optional.of(new byte[]{9}), Optional.empty()),
                List.of(new SignedPointerUpdate(w1, v1new), new SignedPointerUpdate(w2, v2))).join();

        Assert.assertFalse(result);
        Assert.assertArrayEquals(v1init, db.getPointer(w1).join().get());
        Assert.assertFalse("w2 should not have been inserted", db.getPointer(w2).join().isPresent());
    }

    @Test
    public void batchUpdateFailsAtomicallyWhenOnePointerConcurrentlyModified() throws Exception {
        Crypto crypto = Main.initCrypto();
        PublicKeyHash w1 = key(crypto), w2 = key(crypto);
        byte[] v1 = {1}, v2 = {2};
        byte[] v1Concurrent = {99};   // written by the concurrent updater
        byte[] v1Batch = {10}, v2Batch = {20}; // what the batch intended to write

        // Establish initial state for both writers
        db.setPointers(List.of(Optional.empty(), Optional.empty()),
                List.of(new SignedPointerUpdate(w1, v1), new SignedPointerUpdate(w2, v2))).join();

        // Concurrent writer changes w1 while our batch has already read v1 as the expected value
        boolean concurrentOk = db.setPointers(List.of(Optional.of(v1)),
                List.of(new SignedPointerUpdate(w1, v1Concurrent))).join();
        Assert.assertTrue(concurrentOk);

        // The batch arrives with a stale expected value for w1 — CAS must reject the whole batch
        boolean batchResult = db.setPointers(
                List.of(Optional.of(v1), Optional.of(v2)),
                List.of(new SignedPointerUpdate(w1, v1Batch), new SignedPointerUpdate(w2, v2Batch))).join();

        Assert.assertFalse("Batch must fail when one pointer was concurrently modified", batchResult);
        Assert.assertArrayEquals("w1 should retain the concurrent value", v1Concurrent, db.getPointer(w1).join().get());
        Assert.assertArrayEquals("w2 must not be updated when the batch fails atomically", v2, db.getPointer(w2).join().get());
    }

    @Test
    public void getPointerReturnsEmptyForUnknownKey() throws Exception {
        Crypto crypto = Main.initCrypto();
        PublicKeyHash unknown = key(crypto);
        Assert.assertFalse(db.getPointer(unknown).join().isPresent());
    }

    @Test
    public void emptyUpdateListSucceeds() {
        boolean result = db.setPointers(List.of(), List.of()).join();
        Assert.assertTrue(result);
    }
}
