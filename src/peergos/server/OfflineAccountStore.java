package peergos.server;

import peergos.server.login.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.user.*;

import java.util.*;
import java.util.concurrent.*;

public class OfflineAccountStore implements Account {

    private final Account target;
    private final JdbcAccount local;

    public OfflineAccountStore(Account target, JdbcAccount local) {
        this.target = target;
        this.local = local;
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
        return target.getLoginData(username, authorisedReader, auth)
                .thenApply(entryPoints -> {
                    local.setLoginData(new LoginData(username, entryPoints, authorisedReader, Optional.empty()));
                    return entryPoints;
                }).exceptionally(t -> local.getEntryData(username, authorisedReader).join());
    }
}
