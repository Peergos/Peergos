package peergos.shared.login;

import peergos.shared.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.login.mfa.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class OfflineAccountStore implements Account {

    private final Account target;
    private final LoginCache local;
    private final OnlineState online;

    public OfflineAccountStore(Account target, LoginCache local, OnlineState online) {
        this.target = target;
        this.local = local;
        this.online = online;
    }

    @Override
    public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth, boolean forceLocal) {
        return target.setLoginData(login, auth, forceLocal).thenCompose(r -> {
            if (r)
                return local.setLoginData(login);
            return Futures.of(r);
        });
    }

    @Override
    public CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getLoginData(String username,
                                                                                          PublicSigningKey authorisedReader,
                                                                                          byte[] auth,
                                                                                          Optional<MultiFactorAuthResponse>  mfa,
                                                                                          boolean cacheMfaLoginData,
                                                                                          boolean forceProxy,
                                                                                          boolean forceNoCache) {
        return Futures.asyncExceptionally(() -> {
                    if (online.isOnline())
                        return target.getLoginData(username, authorisedReader, auth, mfa, cacheMfaLoginData, forceProxy, forceNoCache)
                                .thenApply(login -> {
                                    if (login.isA() && (mfa.isEmpty() || cacheMfaLoginData))
                                        local.setLoginData(new LoginData(username, login.a(), authorisedReader, Optional.empty()));
                                    else // disable offline login if MFA is enabled
                                        local.removeLoginData(username);
                                    return login;
                                });
                    online.updateAsync();
                    return local.getEntryData(username, authorisedReader).thenApply(Either::a);
                },
                t -> {
                    if (t.getMessage().contains("Incorrect+password"))
                        return Futures.errored(new IllegalStateException("Incorrect password!"));
                    if (online.isOfflineException(t))
                        return local.getEntryData(username, authorisedReader).thenApply(Either::a);
                    return Futures.errored(t);
                });
    }

    @Override
    public CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(String username, byte[] auth) {
        return target.getSecondAuthMethods(username, auth);
    }

    @Override
    public CompletableFuture<Boolean> enableTotpFactor(String username, byte[] credentialId, String code, byte[] auth) {
        return target.enableTotpFactor(username, credentialId, code, auth)
                .thenCompose(enabled -> disableOfflineLogin(username, enabled));
    }

    private CompletableFuture<Boolean> disableOfflineLogin(String username, boolean enabled) {
        if (! enabled)
            return Futures.of(false);
        return local.removeLoginData(username).thenApply(x -> true);
    }

    @Override
    public CompletableFuture<byte[]> registerSecurityKeyStart(String username, byte[] auth) {
        return target.registerSecurityKeyStart(username, auth);
    }

    @Override
    public CompletableFuture<Boolean> registerSecurityKeyComplete(String username, String keyName, MultiFactorAuthResponse resp, byte[] auth) {
        return target.registerSecurityKeyComplete(username, keyName, resp, auth)
                .thenCompose(registered -> disableOfflineLogin(username, registered));
    }

    @Override
    public CompletableFuture<Boolean> deleteSecondFactor(String username, byte[] credentialId, byte[] auth) {
        return target.deleteSecondFactor(username, credentialId, auth);
    }

    @Override
    public CompletableFuture<TotpKey> addTotpFactor(String username, byte[] auth) {
        return target.addTotpFactor(username, auth);
    }

    @Override
    public CompletableFuture<BackupCodes> generateBackupCodes(String username, byte[] auth) {
        return target.generateBackupCodes(username, auth);
    }
}
