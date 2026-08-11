package peergos.server.tests;

import com.webauthn4j.data.client.*;
import org.junit.*;
import peergos.server.*;
import peergos.server.login.*;
import peergos.server.sql.*;
import peergos.server.util.Sqlite;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;

import java.sql.*;
import java.util.*;

/** The login table has to stay usable through a password change that is interrupted at any point,
 *  because the new key generation algorithm and the new login data live in different stores.
 */
public class JdbcAccountTests {

    private static final Crypto crypto = Main.initCrypto();
    private JdbcAccount db;

    @Before
    public void setup() throws Exception {
        SqlSupplier commands = new SqliteCommands();
        Connection conn = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        db = new JdbcAccount(() -> conn, commands, new Origin("https://localhost"), "localhost");
    }

    /** The login data a given password would produce - a distinct reader key per password. */
    private static LoginData loginFor(String username, PublicSigningKey reader) {
        UserStaticData entry = new UserStaticData(Collections.emptyList(), SymmetricKey.random(),
                Optional.of(SigningKeyPair.random(crypto.random, crypto.signer)), Optional.empty());
        return new LoginData(username, entry, reader, Optional.empty());
    }

    /** Stage a password change. Without a pointer store wired in, nothing here is ever treated as
     *  published, so these tests only ever see staged data served directly.
     */
    private void stage(String username, PublicSigningKey reader) {
        db.setPendingLoginData(loginFor(username, reader),
                new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36))),
                Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.sha2_256, crypto.random.randomBytes(32))).join();
    }

    private static PublicSigningKey reader() {
        return SigningKeyPair.random(crypto.random, crypto.signer).publicSigningKey;
    }

    private boolean canLogin(String username, PublicSigningKey reader) {
        try {
            return db.getEntryData(username, reader).join() != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void stagingDoesntInvalidateTheCurrentPassword() {
        String username = "alice";
        PublicSigningKey oldReader = reader(), newReader = reader();
        db.setLoginData(loginFor(username, oldReader)).join();

        // a password change that dies before publishing its new algorithm
        stage(username, newReader);

        Assert.assertTrue("old password still works", canLogin(username, oldReader));
    }

    @Test
    public void stagedLoginDataIsUsableBeforeItIsPromoted() {
        String username = "alice";
        PublicSigningKey oldReader = reader(), newReader = reader();
        db.setLoginData(loginFor(username, oldReader)).join();

        // a password change that published its new algorithm, then died before updating the login table
        stage(username, newReader);

        Assert.assertTrue("new password works from the staged data", canLogin(username, newReader));
    }

    @Test
    public void promotingClearsStagedData() {
        String username = "alice";
        PublicSigningKey oldReader = reader(), newReader = reader();
        db.setLoginData(loginFor(username, oldReader)).join();
        stage(username, newReader);

        db.setLoginData(loginFor(username, newReader)).join();

        Assert.assertTrue(canLogin(username, newReader));
        Assert.assertFalse("old password is revoked", canLogin(username, oldReader));
        Assert.assertEquals(newReader, db.getLoginData(username).get().authorisedReader);
    }

    /** Without a row per reader, a second interrupted change would overwrite the login data that the
     *  currently published algorithm needs, and lock the user out.
     */
    @Test
    public void secondStagedChangeDoesntInvalidateTheFirst() {
        String username = "alice";
        PublicSigningKey oldReader = reader(), reader1 = reader(), reader2 = reader();
        db.setLoginData(loginFor(username, oldReader)).join();

        stage(username, reader1);
        stage(username, reader2);

        Assert.assertTrue(canLogin(username, oldReader));
        Assert.assertTrue(canLogin(username, reader1));
        Assert.assertTrue(canLogin(username, reader2));
    }

    @Test
    public void retryingTheSameChangeIsIdempotent() {
        String username = "alice";
        PublicSigningKey oldReader = reader(), newReader = reader();
        db.setLoginData(loginFor(username, oldReader)).join();

        for (int i = 0; i < JdbcAccount.MAX_PENDING_LOGINS + 5; i++)
            stage(username, newReader);

        Assert.assertTrue(canLogin(username, newReader));
    }

    @Test
    public void stagedLoginDataIsPerUser() {
        PublicSigningKey aliceOld = reader(), aliceNew = reader(), bobOld = reader();
        db.setLoginData(loginFor("alice", aliceOld)).join();
        db.setLoginData(loginFor("bob", bobOld)).join();
        stage("alice", aliceNew);

        Assert.assertFalse("bob can't use alice's staged login data", canLogin("bob", aliceNew));
        db.setLoginData(loginFor("bob", reader())).join();
        Assert.assertTrue("bob's write doesn't clear alice's staged data", canLogin("alice", aliceNew));
    }

    @Test
    public void deletingAnAccountRemovesStagedData() {
        String username = "alice";
        PublicSigningKey oldReader = reader(), newReader = reader();
        db.setLoginData(loginFor(username, oldReader)).join();
        stage(username, newReader);

        db.removeLoginData(username).join();

        Assert.assertFalse(canLogin(username, oldReader));
        Assert.assertFalse(canLogin(username, newReader));
    }

    @Test
    public void unknownReaderIsRejected() {
        String username = "alice";
        db.setLoginData(loginFor(username, reader())).join();
        stage(username, reader());

        Assert.assertFalse(canLogin(username, reader()));
    }
}
