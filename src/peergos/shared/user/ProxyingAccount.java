package peergos.shared.user;

import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;

import java.util.*;
import java.util.concurrent.*;

public class ProxyingAccount implements Account {

    private final Multihash serverId;
    private final CoreNode core;
    private final Account local;
    private final AccountProxy p2p;

    public ProxyingAccount(Multihash serverId, CoreNode core, Account local, AccountProxy p2p) {
        this.serverId = serverId;
        this.core = core;
        this.local = local;
        this.p2p = p2p;
    }

    @Override
    public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth) {
        return core.getPublicKeyHash(login.username).thenCompose(idOpt -> Proxy.redirectCall(core,
                serverId,
                idOpt.get(),
                () -> local.setLoginData(login, auth),
                target -> p2p.setLoginData(target, login, auth)));
    }

    @Override
    public CompletableFuture<UserStaticData> getLoginData(String username, PublicSigningKey authorisedReader, byte[] auth) {
        return core.getPublicKeyHash(username).thenCompose(idOpt -> Proxy.redirectCall(core,
                serverId,
                idOpt.get(),
                () -> local.getLoginData(username, authorisedReader, auth),
                target -> p2p.getLoginData(target, username, authorisedReader, auth)));
    }
}
