package peergos.shared.user;

import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.login.mfa.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class ProxyingAccount implements Account {

    private final List<Cid> serverIds;
    private final CoreNode core;
    private final Account local;
    private final AccountProxy p2p;

    public ProxyingAccount(List<Cid> serverIds, CoreNode core, Account local, AccountProxy p2p) {
        this.serverIds = serverIds;
        this.core = core;
        this.local = local;
        this.p2p = p2p;
    }

    @Override
    public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth) {
        return core.getPublicKeyHash(login.username).thenCompose(idOpt -> Proxy.redirectCall(core,
                serverIds,
                idOpt.get(),
                () -> local.setLoginData(login, auth),
                target -> p2p.setLoginData(target, login, auth)));
    }

    @Override
    public CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getLoginData(String username,
                                                                                          PublicSigningKey authorisedReader,
                                                                                          byte[] auth,
                                                                                          Optional<MultiFactorAuthResponse>  mfa) {
        return core.getPublicKeyHash(username).thenCompose(idOpt -> Proxy.redirectCall(core,
                serverIds,
                idOpt.get(),
                () -> local.getLoginData(username, authorisedReader, auth, mfa),
                target -> p2p.getLoginData(target, username, authorisedReader, auth, mfa)));
    }

    @Override
    public CompletableFuture<TotpKey> addTotpFactor(String username, byte[] auth) {
        return core.getPublicKeyHash(username).thenCompose(idOpt -> Proxy.redirectCall(core,
                serverIds,
                idOpt.get(),
                () -> local.addTotpFactor(username, auth),
                target -> p2p.addTotpFactor(target, username, auth)));
    }

    @Override
    public CompletableFuture<byte[]> registerSecurityKeyStart(String username, byte[] auth) {
        return core.getPublicKeyHash(username).thenCompose(idOpt -> Proxy.redirectCall(core,
                serverIds,
                idOpt.get(),
                () -> local.registerSecurityKeyStart(username, auth),
                target -> p2p.registerSecurityKeyStart(target, username, auth)));
    }



    @Override
    public CompletableFuture<Boolean> registerSecurityKeyComplete(String username, String keyName, MultiFactorAuthResponse resp, byte[] auth) {
        return core.getPublicKeyHash(username).thenCompose(idOpt -> Proxy.redirectCall(core,
                serverIds,
                idOpt.get(),
                () -> local.registerSecurityKeyComplete(username, keyName, resp, auth),
                target -> p2p.registerSecurityKeyComplete(target, username, keyName, resp, auth)));
    }

    @Override
    public CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(String username, byte[] auth) {
        return core.getPublicKeyHash(username).thenCompose(idOpt -> Proxy.redirectCall(core,
                serverIds,
                idOpt.get(),
                () -> local.getSecondAuthMethods(username, auth),
                target -> p2p.getSecondAuthMethods(target, username, auth)));
    }

    @Override
    public CompletableFuture<Boolean> enableTotpFactor(String username, byte[] credentialId, String code, byte[] auth) {
        return core.getPublicKeyHash(username).thenCompose(idOpt -> Proxy.redirectCall(core,
                serverIds,
                idOpt.get(),
                () -> local.enableTotpFactor(username, credentialId, code, auth),
                target -> p2p.enableTotpFactor(target, username, credentialId, code, auth)));
    }

    @Override
    public CompletableFuture<Boolean> deleteSecondFactor(String username, byte[] credentialId, byte[] auth) {
        return core.getPublicKeyHash(username).thenCompose(idOpt -> Proxy.redirectCall(core,
                serverIds,
                idOpt.get(),
                () -> local.deleteSecondFactor(username, credentialId, auth),
                target -> p2p.deleteSecondFactor(username, credentialId, auth)));
    }
}
