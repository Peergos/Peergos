package peergos.server.login;

import peergos.server.storage.admin.QuotaAdmin;
import peergos.server.util.TimeLimited;
import peergos.shared.corenode.CoreNode;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.login.mfa.MultiFactorAuthMethod;
import peergos.shared.login.mfa.MultiFactorAuthRequest;
import peergos.shared.login.mfa.MultiFactorAuthResponse;
import peergos.shared.login.mfa.TotpKey;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.Account;
import peergos.shared.user.LoginData;
import peergos.shared.user.UserStaticData;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.Constants;
import peergos.shared.util.Either;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class LocalOnlyAccount implements Account {

    public static final String EXPIRED_ERROR = "Your subscription has expired, please contact us to renew";
    public static final String EXTERNAL_ERROR = "Please login on your home server";

    private final Account target;
    private final QuotaAdmin quotas;
    private final CoreNode core;
    private final List<Cid> serverIds;
    private final boolean allowExternalLogin;

    public LocalOnlyAccount(Account target, QuotaAdmin quotas, CoreNode core, List<Cid> serverIds, boolean allowExternalLogin) {
        this.target = target;
        this.quotas = quotas;
        this.core = core;
        this.serverIds = serverIds;
        this.allowExternalLogin = allowExternalLogin;
    }

    @Override
    public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth, boolean forceLocal) {
        return target.setLoginData(login, auth, forceLocal);
    }

    private boolean hasQuota(String username) {
        try {
            return quotas.getQuota(username) > 0 || quotas.hadQuota(username, LocalDateTime.now().minusMonths(1));
        } catch (Exception e) {
            return false;
        }
    }

    /** Is this server the user's home server, according to the pki?
     */
    private boolean isHomeServer(String username) {
        try {
            Optional<Multihash> home = core.getHomeServer(username).join();
            return home.map(h -> serverIds.stream()
                            .map(Cid::bareMultihash)
                            .anyMatch(id -> id.equals(h.bareMultihash())))
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getLoginData(String username,
                                                                                          PublicSigningKey authorisedReader,
                                                                                          byte[] auth,
                                                                                          Optional<MultiFactorAuthResponse>  mfa,
                                                                                          boolean cacheMfaLoginData,
                                                                                          boolean forceProxy,
                                                                                          boolean forceNoCache) {
        if (! allowExternalLogin && ! hasQuota(username) && !forceProxy)
            throw new IllegalStateException(isHomeServer(username) ? EXPIRED_ERROR : EXTERNAL_ERROR);
        return target.getLoginData(username, authorisedReader, auth, mfa, cacheMfaLoginData, forceProxy, forceNoCache).thenApply(res -> {
            TimeLimited.isAllowedTime(auth, 24*3600, authorisedReader);
            return res;
        });
    }

    @Override
    public CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(String username, byte[] auth) {
        return target.getSecondAuthMethods(username, auth);
    }

    @Override
    public CompletableFuture<TotpKey> addTotpFactor(String username, byte[] auth) {
        return target.addTotpFactor(username, auth);
    }

    @Override
    public CompletableFuture<Boolean> enableTotpFactor(String username, byte[] credentialId, String code, byte[] auth) {
        return target.enableTotpFactor(username, credentialId, code, auth);
    }

    @Override
    public CompletableFuture<byte[]> registerSecurityKeyStart(String username, byte[] auth) {
        return target.registerSecurityKeyStart(username, auth);
    }

    @Override
    public CompletableFuture<Boolean> registerSecurityKeyComplete(String username, String keyName, MultiFactorAuthResponse resp, byte[] auth) {
        return target.registerSecurityKeyComplete(username, keyName, resp, auth);
    }

    @Override
    public CompletableFuture<Boolean> deleteSecondFactor(String username, byte[] credentialId, byte[] auth) {
        return target.deleteSecondFactor(username, credentialId, auth);
    }
}
