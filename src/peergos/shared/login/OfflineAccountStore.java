package peergos.shared.login;

import peergos.shared.*;
import peergos.shared.crypto.asymmetric.*;
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
    public CompletableFuture<UserStaticData> getLoginData(String username, PublicSigningKey authorisedReader, byte[] auth) {
        return Futures.asyncExceptionally(() -> {
                    if (online.isOnline())
                        return target.getLoginData(username, authorisedReader, auth)
                                .thenApply(entryPoints -> {
                                    local.setLoginData(new LoginData(username, entryPoints, authorisedReader, Optional.empty()));
                                    return entryPoints;
                                });
                    online.updateAsync();
                    return local.getEntryData(username, authorisedReader);
                },
                t -> {
                    if (t.getMessage().contains("Incorrect+password"))
                        return Futures.errored(new IllegalStateException("Incorrect password!"));
                    online.handleRequestException(t);
                    return local.getEntryData(username, authorisedReader);
                });
    }
}
