package peergos.shared.user;

import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.login.*;
import peergos.shared.login.mfa.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** A session logged in as username@peerid goes on calling itself that to the server, which may give the bare
 *  username to someone else, or to no one (remove-pki §12.1).
 */
public class NamedLogin {
    private final LoginName name;

    public NamedLogin(LoginName name) {
        this.name = name;
    }

    private String as(String username) {
        return username.equals(name.username) ? name.toString() : username;
    }

    public static class Accounts implements Account {
        private final Account target;
        private final NamedLogin named;

        public Accounts(Account target, NamedLogin named) {
            this.target = target;
            this.named = named;
        }

        @Override
        public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth, boolean forceLocal) {
            return target.setLoginData(named.as(login.username), login, auth, forceLocal);
        }

        @Override
        public CompletableFuture<Boolean> setLoginData(String username, LoginData login, byte[] auth, boolean forceLocal) {
            return target.setLoginData(named.as(username), login, auth, forceLocal);
        }

        @Override
        public CompletableFuture<LoginResponse> getLoginData(String username, PublicSigningKey authorisedReader, byte[] auth,
                                                             Optional<MultiFactorAuthResponse> mfa, Optional<String> deviceToken,
                                                             boolean cacheMfaLoginData, boolean forceProxy, boolean forceNoCache) {
            return target.getLoginData(named.as(username), authorisedReader, auth, mfa, deviceToken, cacheMfaLoginData, forceProxy, forceNoCache);
        }

        @Override
        public CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(String username, byte[] auth) {
            return target.getSecondAuthMethods(named.as(username), auth);
        }

        @Override
        public CompletableFuture<Boolean> enableTotpFactor(String username, byte[] credentialId, String code, byte[] auth) {
            return target.enableTotpFactor(named.as(username), credentialId, code, auth);
        }

        @Override
        public CompletableFuture<TotpKey> addTotpFactor(String username, byte[] auth) {
            return target.addTotpFactor(named.as(username), auth);
        }

        @Override
        public CompletableFuture<TotpKey> addMountFactor(String username, String mount, byte[] auth) {
            return target.addMountFactor(named.as(username), mount, auth);
        }

        @Override
        public CompletableFuture<Boolean> enableMountFactor(String username, byte[] credentialId, String code, byte[] auth) {
            return target.enableMountFactor(named.as(username), credentialId, code, auth);
        }

        @Override
        public CompletableFuture<BackupCodes> generateBackupCodes(String username, byte[] auth) {
            return target.generateBackupCodes(named.as(username), auth);
        }

        @Override
        public CompletableFuture<byte[]> registerSecurityKeyStart(String username, byte[] auth) {
            return target.registerSecurityKeyStart(named.as(username), auth);
        }

        @Override
        public CompletableFuture<Boolean> registerSecurityKeyComplete(String username, String keyName, MultiFactorAuthResponse resp, byte[] auth) {
            return target.registerSecurityKeyComplete(named.as(username), keyName, resp, auth);
        }

        @Override
        public CompletableFuture<Boolean> deleteSecondFactor(String username, byte[] credentialId, byte[] auth) {
            return target.deleteSecondFactor(named.as(username), credentialId, auth);
        }
    }

    public static class Bats implements BatCave {
        private final BatCave target;
        private final NamedLogin named;

        public Bats(BatCave target, NamedLogin named) {
            this.target = target;
            this.named = named;
        }

        @Override
        public Optional<Bat> getBat(BatId id) {
            return target.getBat(id);
        }

        @Override
        public CompletableFuture<List<BatWithId>> getUserBats(String username, byte[] auth) {
            return target.getUserBats(named.as(username), auth);
        }

        @Override
        public CompletableFuture<Boolean> addBat(String username, BatId id, Bat bat, byte[] auth) {
            return target.addBat(named.as(username), id, bat, auth);
        }
    }

    public static class Messages implements ServerMessager {
        private final ServerMessager target;
        private final NamedLogin named;

        public Messages(ServerMessager target, NamedLogin named) {
            this.target = target;
            this.named = named;
        }

        @Override
        public CompletableFuture<List<ServerMessage>> getMessages(String username, byte[] auth) {
            return target.getMessages(named.as(username), auth);
        }

        @Override
        public CompletableFuture<Boolean> sendMessage(String username, byte[] signedBody) {
            return target.sendMessage(named.as(username), signedBody);
        }
    }

    /** Our own username is the identity we logged in to, whatever the server's PKI mirror holds under it. */
    public static class Core implements CoreNode {
        private final CoreNode target;
        private final NamedLogin named;
        private final PublicKeyHash owner;

        public Core(CoreNode target, NamedLogin named, PublicKeyHash owner) {
            this.target = target;
            this.named = named;
            this.owner = owner;
        }

        private boolean us(String username) {
            return username.equals(named.name.username) || username.equals(named.name.toString());
        }

        @Override
        public CompletableFuture<Optional<PublicKeyHash>> getPublicKeyHash(String username) {
            return us(username) ? Futures.of(Optional.of(owner)) : target.getPublicKeyHash(username);
        }

        @Override
        public CompletableFuture<Optional<Multihash>> getHomeServer(String username) {
            return us(username) ?
                    target.getStorageProviders(owner).thenApply(ids -> ids.stream().findFirst()) :
                    target.getHomeServer(username);
        }

        @Override
        public CompletableFuture<String> getUsername(PublicKeyHash key) {
            return key.equals(owner) ? Futures.of(named.name.username) : target.getUsername(key);
        }

        @Override
        public CompletableFuture<List<Multihash>> getStorageProviders(PublicKeyHash owner) {
            return target.getStorageProviders(owner);
        }

        @Override
        public CompletableFuture<Optional<RequiredDifficulty>> signup(String username, UserPublicKeyLink chain, OpLog setupOperations, ProofOfWork proof, String token) {
            return target.signup(username, chain, setupOperations, proof, token);
        }

        @Override
        public CompletableFuture<Either<PaymentProperties, RequiredDifficulty>> startPaidSignup(String username, UserPublicKeyLink chain, ProofOfWork proof) {
            return target.startPaidSignup(username, chain, proof);
        }

        @Override
        public CompletableFuture<PaymentProperties> completePaidSignup(String username, UserPublicKeyLink chain, OpLog setupOperations, byte[] signedSpaceRequest, ProofOfWork proof) {
            return target.completePaidSignup(username, chain, setupOperations, signedSpaceRequest, proof);
        }

        @Override
        public CompletableFuture<Boolean> startMirror(String username, BatWithId mirrorBat, byte[] auth, ProofOfWork proof) {
            return target.startMirror(username, mirrorBat, auth, proof);
        }

        @Override
        public CompletableFuture<Either<PaymentProperties, RequiredDifficulty>> startPaidMirror(String username, byte[] auth, ProofOfWork proof) {
            return target.startPaidMirror(username, auth, proof);
        }

        @Override
        public CompletableFuture<List<UserSnapshot>> getSnapshots(String prefix, BatWithId instanceBat, LocalDateTime lastLinkCountsUpdate) {
            return target.getSnapshots(prefix, instanceBat, lastLinkCountsUpdate);
        }

        @Override
        public CompletableFuture<PaymentProperties> completePaidMirror(String username, BatWithId mirrorBat, byte[] signedSpaceRequest, ProofOfWork proof) {
            return target.completePaidMirror(username, mirrorBat, signedSpaceRequest, proof);
        }

        @Override
        public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
            return target.getChain(username);
        }

        @Override
        public CompletableFuture<Optional<RequiredDifficulty>> updateChain(String username, List<UserPublicKeyLink> chain, ProofOfWork proof, String token) {
            return target.updateChain(username, chain, proof, token);
        }

        @Override
        public CompletableFuture<List<String>> getUsernames(String prefix) {
            return target.getUsernames(prefix);
        }

        @Override
        public CompletableFuture<UserSnapshot> migrateUser(String username, List<UserPublicKeyLink> newChain, Multihash currentStorageId,
                                                           Optional<BatWithId> mirrorBat, LocalDateTime latestLinkCountUpdate,
                                                           long usage, boolean commitToPki) {
            return target.migrateUser(username, newChain, currentStorageId, mirrorBat, latestLinkCountUpdate, usage, commitToPki);
        }

        @Override
        public CompletableFuture<Optional<Multihash>> getNextServerId(Multihash serverId) {
            return target.getNextServerId(serverId);
        }

        @Override
        public CompletableFuture<Boolean> updateUser(String username) {
            return target.updateUser(username);
        }

        @Override
        public void close() throws IOException {
            target.close();
        }
    }
}
