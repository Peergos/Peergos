package peergos.shared.user;

import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.login.*;
import peergos.shared.login.mfa.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class ProxyingAccount implements Account {

    private final List<Cid> serverIds;
    private final CoreNode core;
    private final Account local;
    private final AccountProxy p2p;
    private final Function<PublicKeyHash, Boolean> weMirror;

    public ProxyingAccount(List<Cid> serverIds,
                           CoreNode core,
                           Account local,
                           AccountProxy p2p,
                           Function<PublicKeyHash, Boolean> weMirror) {
        this.serverIds = serverIds;
        this.core = core;
        this.local = local;
        this.p2p = p2p;
        this.weMirror = weMirror;
    }

    @Override
    public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth, boolean forceLocal) {
        return setLoginData(login.username, login, auth, forceLocal);
    }

    @Override
    public CompletableFuture<Boolean> setLoginData(String username, LoginData login, byte[] auth, boolean forceLocal) {
        if (forceLocal)
            return local.setLoginData(login, auth, true);
        return Proxy.redirectByName(core, serverIds, username,
                user -> local.setLoginData(login, auth, false),
                (target, user) -> p2p.setLoginData(target, login, auth));
    }

    @Override
    public CompletableFuture<LoginResponse> getLoginData(String username,
                                                         PublicSigningKey authorisedReader,
                                                         byte[] auth,
                                                         Optional<MultiFactorAuthResponse> mfa,
                                                         Optional<String> deviceToken,
                                                         boolean cacheMfaLoginData,
                                                         boolean forceProxy,
                                                         boolean forceNoCache) {
        LoginName name = LoginName.parse(username);
        if (name.server.isPresent())
            return Proxy.toNamedServer(core, serverIds, name.server.get(),
                    () -> local.getLoginData(name.username, authorisedReader, auth, mfa, deviceToken, false, forceProxy, forceNoCache),
                    target -> p2p.getLoginData(target, name.username, authorisedReader, auth, mfa, deviceToken));
        // a mirror of the login data is only used when the home server can't be reached, and only holds
        // an entry at all for a user without 2FA, whose login data we were able to mirror
        return core.getPublicKeyHash(username).thenCompose(idOpt -> Proxy.redirectCallWithMirrorFallback(core,
                serverIds,
                idOpt.get(),
                // a device token is only any use from the home server, so don't let a mirror replace it
                () -> local.getLoginData(username, authorisedReader, auth, mfa, deviceToken, false, forceProxy, forceNoCache)
                        .thenCompose(res -> Proxy.isHomeServer(core, serverIds, idOpt.get())
                                .thenApply(home -> home ? res : res.withoutDeviceToken())),
                target -> p2p.getLoginData(target, username, authorisedReader, auth, mfa, deviceToken),
                weMirror));
    }

    @Override
    public CompletableFuture<TotpKey> addTotpFactor(String username, byte[] auth) {
        return Proxy.redirectByName(core, serverIds, username,
                user -> local.addTotpFactor(user, auth),
                (target, user) -> p2p.addTotpFactor(target, user, auth));
    }

    @Override
    public CompletableFuture<TotpKey> addMountFactor(String username, String name, byte[] auth) {
        return Proxy.redirectByName(core, serverIds, username,
                user -> local.addMountFactor(user, name, auth),
                (target, user) -> p2p.addMountFactor(target, user, name, auth));
    }

    @Override
    public CompletableFuture<Boolean> enableMountFactor(String username, byte[] credentialId, String code, byte[] auth) {
        return Proxy.redirectByName(core, serverIds, username,
                user -> local.enableMountFactor(user, credentialId, code, auth),
                (target, user) -> p2p.enableMountFactor(target, user, credentialId, code, auth));
    }

    @Override
    public CompletableFuture<BackupCodes> generateBackupCodes(String username, byte[] auth) {
        return Proxy.redirectByName(core, serverIds, username,
                user -> local.generateBackupCodes(user, auth),
                (target, user) -> p2p.generateBackupCodes(target, user, auth));
    }

    @Override
    public CompletableFuture<byte[]> registerSecurityKeyStart(String username, byte[] auth) {
        return Proxy.redirectByName(core, serverIds, username,
                user -> local.registerSecurityKeyStart(user, auth),
                (target, user) -> p2p.registerSecurityKeyStart(target, user, auth));
    }



    @Override
    public CompletableFuture<Boolean> registerSecurityKeyComplete(String username, String keyName, MultiFactorAuthResponse resp, byte[] auth) {
        return Proxy.redirectByName(core, serverIds, username,
                user -> local.registerSecurityKeyComplete(user, keyName, resp, auth),
                (target, user) -> p2p.registerSecurityKeyComplete(target, user, keyName, resp, auth));
    }

    @Override
    public CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(String username, byte[] auth) {
        return Proxy.redirectByName(core, serverIds, username,
                user -> local.getSecondAuthMethods(user, auth),
                (target, user) -> p2p.getSecondAuthMethods(target, user, auth));
    }

    @Override
    public CompletableFuture<Boolean> enableTotpFactor(String username, byte[] credentialId, String code, byte[] auth) {
        return Proxy.redirectByName(core, serverIds, username,
                user -> local.enableTotpFactor(user, credentialId, code, auth),
                (target, user) -> p2p.enableTotpFactor(target, user, credentialId, code, auth));
    }

    @Override
    public CompletableFuture<Boolean> deleteSecondFactor(String username, byte[] credentialId, byte[] auth) {
        return Proxy.redirectByName(core, serverIds, username,
                user -> local.deleteSecondFactor(user, credentialId, auth),
                (target, user) -> p2p.deleteSecondFactor(target, user, credentialId, auth));
    }
}
