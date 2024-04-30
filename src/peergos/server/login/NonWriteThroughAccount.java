package peergos.server.login;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.login.mfa.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class NonWriteThroughAccount implements Account {

    private final Account source;
    private final Map<String, LoginData> modifications;

    public NonWriteThroughAccount(Account source) {
        this.source = source;
        this.modifications = new HashMap<>();
    }

    @Override
    public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth) {
        modifications.put(login.username, login);
        return Futures.of(true);
    }

    @Override
    public CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getLoginData(String username,
                                                                                          PublicSigningKey authorisedReader,
                                                                                          byte[] auth,
                                                                                          Optional<MultiFactorAuthResponse>  mfa,
                                                                                          boolean cacheMfaLoginData) {
        LoginData updated = modifications.get(username);
        if (updated == null)
            return source.getLoginData(username, authorisedReader, auth, mfa, false);
        return Futures.of(Either.a(updated.entryPoints));
    }

    @Override
    public CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(String username, byte[] auth) {
        return source.getSecondAuthMethods(username, auth);
    }

    @Override
    public CompletableFuture<Boolean> enableTotpFactor(String username, byte[] credentialId, String code, byte[] auth) {
        throw new IllegalStateException("TODO");
    }

    @Override
    public CompletableFuture<byte[]> registerSecurityKeyStart(String username, byte[] auth) {
        throw new IllegalStateException("TODO");
    }

    @Override
    public CompletableFuture<Boolean> registerSecurityKeyComplete(String username, String keyName, MultiFactorAuthResponse resp, byte[] auth) {
        throw new IllegalStateException("TODO");
    }

    @Override
    public CompletableFuture<Boolean> deleteSecondFactor(String username, byte[] credentialId, byte[] auth) {
        throw new IllegalStateException("TODO");
    }

    @Override
    public CompletableFuture<TotpKey> addTotpFactor(String username, byte[] auth) {
        throw new IllegalStateException("TODO");
    }
}
