package peergos.server.tests;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.OnlineState;
import peergos.shared.crypto.SigningKeyPair;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.login.LoginCache;
import peergos.shared.login.OfflineAccountStore;
import peergos.shared.login.mfa.BackupCodes;
import peergos.shared.login.mfa.MultiFactorAuthMethod;
import peergos.shared.login.mfa.MultiFactorAuthRequest;
import peergos.shared.login.mfa.MultiFactorAuthResponse;
import peergos.shared.login.mfa.TotpKey;
import peergos.shared.user.Account;
import peergos.shared.user.LoginData;
import peergos.shared.user.UserStaticData;
import peergos.shared.util.Either;
import peergos.shared.util.Futures;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** A device that has logged in before holds the login data locally, and that data plus the password
 *  is a whole login: the second factor only guards the copy on the server. So the cache must never
 *  be able to answer in place of a server that would have asked for a second factor.
 */
public class OfflineAccountStoreTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final String USERNAME = "bob";

    private final PublicSigningKey reader = SigningKeyPair.random(crypto.random, crypto.signer).publicSigningKey;
    private final byte[] auth = new byte[0];
    private FakeServer server;
    private MemoryLoginCache cache;
    private OfflineAccountStore store;

    @Before
    public void setup() {
        server = new FakeServer(entryData());
        cache = new MemoryLoginCache();
        store = new OfflineAccountStore(server, cache, new OnlineState(() -> Futures.of(true)));
    }

    private static UserStaticData entryData() {
        return new UserStaticData(Collections.emptyList(), SymmetricKey.random(),
                Optional.of(SigningKeyPair.random(crypto.random, crypto.signer)), Optional.empty());
    }

    /** The login this device did before a second factor was added. */
    private void cacheAPreviousLogin() {
        cache.setLoginData(new LoginData(USERNAME, entryData(), reader, Optional.empty())).join();
        Assert.assertTrue(cache.has(USERNAME));
    }

    private Either<UserStaticData, MultiFactorAuthRequest> login() {
        return store.getLoginData(USERNAME, reader, auth, Optional.empty(), false, false, false).join();
    }

    /** The server is held mid call, which is where the cache used to win and answer for it. */
    @Test
    public void cachedLoginDataDoesNotSkipASecondFactor() {
        cacheAPreviousLogin();
        server.mfaEnabled = true;
        server.holdReplies = true;

        CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> login =
                store.getLoginData(USERNAME, reader, auth, Optional.empty(), false, false, false);
        Assert.assertFalse("answered from the cache before the server had a say", login.isDone());

        server.answer();
        Assert.assertTrue("the server asked for a second factor", login.join().isB());
        Assert.assertFalse("and offline login is off while a factor is enabled", cache.has(USERNAME));
    }

    /** With no second factor the cache is still refreshed from the server on every login. */
    @Test
    public void loginWithoutASecondFactorCachesTheData() {
        Assert.assertTrue(login().isA());
        Assert.assertTrue(cache.has(USERNAME));
    }

    /** The point of the cache: a server we cannot reach at all. */
    @Test
    public void anUnreachableServerFallsBackToTheCache() {
        cacheAPreviousLogin();
        server.offline = true;

        Assert.assertTrue(login().isA());
        Assert.assertTrue("a failed call is not a reason to forget the login", cache.has(USERNAME));
    }

    @Test
    public void enablingTotpDropsTheCachedLogin() {
        cacheAPreviousLogin();

        Assert.assertTrue(store.enableTotpFactor(USERNAME, new byte[]{1}, "123456", auth).join());
        Assert.assertFalse(cache.has(USERNAME));
    }

    /** A security key is enabled the moment it is registered, unlike totp. */
    @Test
    public void registeringASecurityKeyDropsTheCachedLogin() {
        cacheAPreviousLogin();

        Assert.assertTrue(store.registerSecurityKeyComplete(USERNAME, "yubikey", null, auth).join());
        Assert.assertFalse(cache.has(USERNAME));
    }

    /** A factor that the server refused to enable must not cost us offline login. */
    @Test
    public void aRejectedFactorLeavesTheCachedLogin() {
        cacheAPreviousLogin();
        server.acceptFactors = false;

        Assert.assertFalse(store.enableTotpFactor(USERNAME, new byte[]{1}, "000000", auth).join());
        Assert.assertTrue(cache.has(USERNAME));
    }

    private static class MemoryLoginCache implements LoginCache {
        private final Map<String, UserStaticData> entries = new HashMap<>();

        public boolean has(String username) {
            return entries.containsKey(username);
        }

        @Override
        public CompletableFuture<Boolean> setLoginData(LoginData login) {
            entries.put(login.username, login.entryPoints);
            return Futures.of(true);
        }

        @Override
        public CompletableFuture<Boolean> removeLoginData(String username) {
            entries.remove(username);
            return Futures.of(true);
        }

        @Override
        public CompletableFuture<UserStaticData> getEntryData(String username, PublicSigningKey authorisedReader) {
            UserStaticData entry = entries.get(username);
            if (entry == null)
                return Futures.errored(new IllegalStateException("Client Offline!"));
            return Futures.of(entry);
        }
    }

    private static class FakeServer implements Account {
        private final UserStaticData entry;
        public boolean mfaEnabled = false;
        public boolean acceptFactors = true;
        public boolean offline = false;
        public boolean holdReplies = false;
        private final List<Runnable> held = new ArrayList<>();

        public FakeServer(UserStaticData entry) {
            this.entry = entry;
        }

        private <T> CompletableFuture<T> whenReachable(T result) {
            if (offline)
                return Futures.errored(new RuntimeException("java.net.ConnectException: Connection refused"));
            if (! holdReplies)
                return Futures.of(result);
            CompletableFuture<T> reply = new CompletableFuture<>();
            held.add(() -> reply.complete(result));
            return reply;
        }

        public void answer() {
            List<Runnable> replies = new ArrayList<>(held);
            held.clear();
            replies.forEach(Runnable::run);
        }

        private MultiFactorAuthMethod totp() {
            return new MultiFactorAuthMethod("phone", new byte[]{1}, LocalDate.now(),
                    MultiFactorAuthMethod.Type.TOTP, true);
        }

        @Override
        public CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getLoginData(String username,
                                                                                              PublicSigningKey authorisedReader,
                                                                                              byte[] auth,
                                                                                              Optional<MultiFactorAuthResponse> mfa,
                                                                                              boolean cacheMfaLoginData,
                                                                                              boolean forceProxy,
                                                                                              boolean forceNoCache) {
            if (mfaEnabled && mfa.isEmpty())
                return whenReachable(Either.b(new MultiFactorAuthRequest(List.of(totp()), new byte[32])));
            return whenReachable(Either.a(entry));
        }

        @Override
        public CompletableFuture<Boolean> enableTotpFactor(String username, byte[] credentialId, String code, byte[] auth) {
            mfaEnabled = mfaEnabled || acceptFactors;
            return whenReachable(acceptFactors);
        }

        @Override
        public CompletableFuture<Boolean> registerSecurityKeyComplete(String username, String keyName, MultiFactorAuthResponse resp, byte[] auth) {
            mfaEnabled = mfaEnabled || acceptFactors;
            return whenReachable(acceptFactors);
        }

        @Override
        public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth, boolean forceLocal) {
            return whenReachable(true);
        }

        @Override
        public CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(String username, byte[] auth) {
            return whenReachable(mfaEnabled ? List.of(totp()) : Collections.emptyList());
        }

        @Override
        public CompletableFuture<TotpKey> addTotpFactor(String username, byte[] auth) {
            throw new IllegalStateException("Not used by these tests");
        }

        @Override
        public CompletableFuture<TotpKey> addMountFactor(String username, String name, byte[] auth) {
            throw new IllegalStateException("Not used by these tests");
        }

        @Override
        public CompletableFuture<Boolean> enableMountFactor(String username, byte[] credentialId, String code, byte[] auth) {
            mfaEnabled = mfaEnabled || acceptFactors;
            return whenReachable(acceptFactors);
        }

        @Override
        public CompletableFuture<BackupCodes> generateBackupCodes(String username, byte[] auth) {
            throw new IllegalStateException("Not used by these tests");
        }

        @Override
        public CompletableFuture<byte[]> registerSecurityKeyStart(String username, byte[] auth) {
            throw new IllegalStateException("Not used by these tests");
        }

        @Override
        public CompletableFuture<Boolean> deleteSecondFactor(String username, byte[] credentialId, byte[] auth) {
            mfaEnabled = false;
            return whenReachable(true);
        }
    }
}
