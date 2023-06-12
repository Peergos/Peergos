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
    public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth) {
        return target.setLoginData(login, auth).thenApply(r -> {
            if (r)
                local.setLoginData(login);
            return r;
        });
    }

    @Override
    public CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getLoginData(String username,
                                                                                          PublicSigningKey authorisedReader,
                                                                                          byte[] auth,
                                                                                          Optional<MultiFactorAuthResponse>  mfa) {
        return Futures.asyncExceptionally(() -> {
                    if (online.isOnline())
                        return target.getLoginData(username, authorisedReader, auth, mfa)
                                .thenApply(res -> {
                                    if (res.isA())
                                        local.setLoginData(new LoginData(username, res.a(), authorisedReader, Optional.empty()));
                                    return res;
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
    public CompletableFuture<Boolean> enableTotpFactor(String username, byte[] credentialId, String code) {
        return target.enableTotpFactor(username, credentialId, code);
    }

    @Override
    public CompletableFuture<byte[]> registerSecurityKeyStart(String username, byte[] auth) {
        return target.registerSecurityKeyStart(username, auth);
    }

    @Override
    public CompletableFuture<Boolean> registerSecurityKeyComplete(String username, MultiFactorAuthResponse resp, byte[] auth) {
        return registerSecurityKeyComplete(username, resp, auth);
    }

    @Override
    public CompletableFuture<Boolean> deleteSecondFactor(String username, byte[] credentialId, byte[] auth) {
        return target.deleteSecondFactor(username, credentialId, auth);
    }

    @Override
    public CompletableFuture<TotpKey> addTotpFactor(String username, byte[] auth) {
        return target.addTotpFactor(username, auth);
    }
}
