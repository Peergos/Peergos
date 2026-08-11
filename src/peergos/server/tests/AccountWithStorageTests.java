package peergos.server.tests;

import com.webauthn4j.data.client.*;
import org.junit.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.login.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.Sqlite;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** A password change writes the new key generation algorithm to the pointer store and the new login data
 *  to SQL. Those can't be written atomically, so the pointer update is used as the commit point, and the
 *  account has to be loggable in to on either side of it.
 */
public class AccountWithStorageTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final String username = "alice";

    private DeletableContentAddressedStorage dht;
    private MutablePointers mutable;
    private FlakyAccount raw;
    private AccountWithStorage account;
    private SigningPrivateKeyAndPublicHash signer;

    /** Lets us simulate dying after the pointer update, but before the login table is updated. */
    private static class FlakyAccount extends JdbcAccount {
        public volatile boolean failLoginDataWrite = false;

        public FlakyAccount(java.util.function.Supplier<Connection> conn, SqlSupplier commands, Origin origin, String rpId) {
            super(conn, commands, origin, rpId);
        }

        @Override
        public CompletableFuture<Boolean> setLoginData(LoginData login) {
            if (failLoginDataWrite)
                throw new RuntimeException("Simulated database failure");
            return super.setLoginData(login);
        }
    }

    @Before
    public void setup() throws Exception {
        SqlSupplier commands = new SqliteCommands();
        Connection conn = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        dht = new RAMStorage(crypto.hasher);
        mutable = UserRepository.build(dht, new JdbcIpnsAndSocial(Main.buildEphemeralSqlite(), commands), crypto.hasher);
        raw = new FlakyAccount(() -> conn, commands, new Origin("https://localhost"), "localhost");
        account = new AccountWithStorage(dht, mutable, raw);

        SigningKeyPair identity = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash owner = ContentAddressedStorage.hashKey(identity.publicSigningKey);
        signer = new SigningPrivateKeyAndPublicHash(owner, identity.secretSigningKey);
        TransactionId tid = dht.startTransaction(owner).join();
        WriterData wd = WriterData.createEmpty(owner, signer, dht, crypto.hasher, tid).join()
                .withAlgorithm(SecretGenerationAlgorithm.getDefault(crypto.random));
        wd.commit(owner, signer, MaybeMultihash.empty(), Optional.empty(), mutable, dht, crypto.hasher, tid).join();
        dht.closeTransaction(owner, tid).join();
    }

    private static PublicSigningKey reader() {
        return SigningKeyPair.random(crypto.random, crypto.signer).publicSigningKey;
    }

    private static LoginData loginFor(PublicSigningKey reader,
                                      Optional<Pair<OpLog.BlockWrite, OpLog.PointerWrite>> identityUpdate) {
        UserStaticData entry = new UserStaticData(Collections.emptyList(), SymmetricKey.random(),
                Optional.of(SigningKeyPair.random(crypto.random, crypto.signer)), Optional.empty());
        return new LoginData(username, entry, reader, identityUpdate);
    }

    /** Build the same bundle that UserContext.changePassword sends: the WriterData carrying the new
     *  key generation algorithm, the pointer update publishing it, and the new login data.
     */
    private LoginData passwordChange(PublicSigningKey newReader, MaybeMultihash expectedCurrent) {
        CommittedWriterData cwd = WriterData.getWriterData(signer.publicKeyHash, signer.publicKeyHash, mutable, dht).join();
        WriterData newIdBlock = cwd.props.get().withAlgorithm(
                SecretGenerationAlgorithm.withNewSalt(cwd.props.get().generationAlgorithm.get(), crypto.random));
        byte[] rawBlock = newIdBlock.serialize();
        byte[] blockHash = crypto.hasher.sha256(rawBlock).join();
        byte[] signedBlock = signer.secret.signMessage(blockHash).join();
        OpLog.BlockWrite blockWrite = new OpLog.BlockWrite(signer.publicKeyHash, signedBlock, rawBlock, false, Optional.empty());
        MaybeMultihash newHash = MaybeMultihash.of(new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, blockHash));
        PointerUpdate cas = new PointerUpdate(expectedCurrent, newHash, PointerUpdate.increment(cwd.sequence));
        byte[] signedCas = signer.secret.signMessage(cas.serialize()).join();
        OpLog.PointerWrite pointerWrite = new OpLog.PointerWrite(signer.publicKeyHash, signedCas);
        return loginFor(newReader, Optional.of(new Pair<>(blockWrite, pointerWrite)));
    }

    private MaybeMultihash currentPointerTarget() {
        return WriterData.getWriterData(signer.publicKeyHash, signer.publicKeyHash, mutable, dht).join().hash;
    }

    private SecretGenerationAlgorithm publishedAlgorithm() {
        return WriterData.getWriterData(signer.publicKeyHash, signer.publicKeyHash, mutable, dht).join()
                .props.get().generationAlgorithm.get();
    }

    private boolean canLogin(PublicSigningKey reader) {
        try {
            return raw.getEntryData(username, reader).join() != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void passwordChangePublishesTheNewAlgorithmAndLoginData() {
        PublicSigningKey oldReader = reader(), newReader = reader();
        account.setLoginData(loginFor(oldReader, Optional.empty()), new byte[0], false).join();
        SecretGenerationAlgorithm before = publishedAlgorithm();

        boolean result = account.setLoginData(passwordChange(newReader, currentPointerTarget()), new byte[0], false).join();

        Assert.assertTrue(result);
        Assert.assertNotEquals("new salt is published", before.getExtraSalt(), publishedAlgorithm().getExtraSalt());
        Assert.assertTrue(canLogin(newReader));
        Assert.assertFalse("old password is revoked", canLogin(oldReader));
        Assert.assertEquals(newReader, raw.getLoginData(username).get().authorisedReader);
    }

    /** Dying between the pointer update and the login table write used to leave an account that neither
     *  password could log in to.
     */
    @Test
    public void newPasswordWorksWhenTheLoginTableWriteFails() {
        PublicSigningKey oldReader = reader(), newReader = reader();
        account.setLoginData(loginFor(oldReader, Optional.empty()), new byte[0], false).join();
        SecretGenerationAlgorithm before = publishedAlgorithm();

        raw.failLoginDataWrite = true;
        boolean result = account.setLoginData(passwordChange(newReader, currentPointerTarget()), new byte[0], false).join();
        raw.failLoginDataWrite = false;

        Assert.assertTrue("the password change happened at the pointer update", result);
        Assert.assertNotEquals(before.getExtraSalt(), publishedAlgorithm().getExtraSalt());
        Assert.assertTrue("new password works", canLogin(newReader));
    }

    /** ... and the login table catches up on the next login, rather than staying stale until the next
     *  password change - which may never come.
     */
    @Test
    public void loggingInCompletesAnInterruptedPasswordChange() {
        PublicSigningKey oldReader = reader(), newReader = reader();
        account.setLoginData(loginFor(oldReader, Optional.empty()), new byte[0], false).join();
        raw.failLoginDataWrite = true;
        account.setLoginData(passwordChange(newReader, currentPointerTarget()), new byte[0], false).join();
        raw.failLoginDataWrite = false;
        // the login table still holds the old row, so it still answers for the old reader
        Assert.assertTrue("login table is stale", canLogin(oldReader));

        Assert.assertTrue(canLogin(newReader));

        Assert.assertFalse("logging in completed the change", canLogin(oldReader));
        Assert.assertEquals(newReader, raw.getLoginData(username).get().authorisedReader);
    }

    /** A migration reads the login table by username, so it has to see the completed change even if the
     *  user hasn't logged in since.
     */
    @Test
    public void migrationSeesAnInterruptedPasswordChange() {
        PublicSigningKey oldReader = reader(), newReader = reader();
        account.setLoginData(loginFor(oldReader, Optional.empty()), new byte[0], false).join();
        raw.failLoginDataWrite = true;
        account.setLoginData(passwordChange(newReader, currentPointerTarget()), new byte[0], false).join();
        raw.failLoginDataWrite = false;

        Assert.assertEquals(newReader, raw.getLoginData(username).get().authorisedReader);
    }

    /** Login data staged by a password change that never published its new algorithm must not be
     *  promoted - that would revoke the password that still works.
     */
    @Test
    public void unpublishedLoginDataIsNeverPromoted() {
        PublicSigningKey oldReader = reader(), newReader = reader();
        account.setLoginData(loginFor(oldReader, Optional.empty()), new byte[0], false).join();

        LoginData stale = passwordChange(newReader, MaybeMultihash.empty());
        try {
            account.setLoginData(stale, new byte[0], false).join();
        } catch (Exception e) {
            // the pointer update is rejected, but the staged login data is already written
        }

        // even asking for the staged reader directly mustn't promote it
        canLogin(newReader);

        Assert.assertEquals(oldReader, raw.getLoginData(username).get().authorisedReader);
        Assert.assertTrue("old password still works", canLogin(oldReader));
    }

    /** If the new algorithm never gets published then the old password is still the live one. */
    @Test
    public void oldPasswordSurvivesAFailedPointerUpdate() {
        PublicSigningKey oldReader = reader(), newReader = reader();
        account.setLoginData(loginFor(oldReader, Optional.empty()), new byte[0], false).join();
        SecretGenerationAlgorithm before = publishedAlgorithm();

        // a concurrent write moved the pointer on, so our compare and swap is stale
        LoginData stale = passwordChange(newReader, MaybeMultihash.empty());
        try {
            Assert.assertFalse(account.setLoginData(stale, new byte[0], false).join());
        } catch (Exception e) {
            // rejected outright, which is also fine
        }

        Assert.assertEquals("algorithm unchanged", before.getExtraSalt(), publishedAlgorithm().getExtraSalt());
        Assert.assertTrue("old password still works", canLogin(oldReader));
        Assert.assertEquals(oldReader, raw.getLoginData(username).get().authorisedReader);
    }

    /** The login data must not be published before the algorithm that makes it reachable. */
    @Test
    public void loginDataIsntPublishedIfItCantBeStaged() {
        PublicSigningKey oldReader = reader(), newReader = reader();
        account.setLoginData(loginFor(oldReader, Optional.empty()), new byte[0], false).join();
        SecretGenerationAlgorithm before = publishedAlgorithm();

        for (int i = 0; i < JdbcAccount.MAX_PENDING_LOGINS; i++)
            raw.setPendingLoginData(loginFor(reader(), Optional.empty()), signer.publicKeyHash,
                    Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.sha2_256, crypto.random.randomBytes(32))).join();

        try {
            account.setLoginData(passwordChange(newReader, currentPointerTarget()), new byte[0], false).join();
            Assert.fail("should have refused to stage the login data");
        } catch (Exception e) {
            // expected
        }

        Assert.assertEquals("algorithm unchanged", before.getExtraSalt(), publishedAlgorithm().getExtraSalt());
        Assert.assertTrue("old password still works", canLogin(oldReader));
    }
}
