package peergos.server.login;

import peergos.shared.crypto.asymmetric.*;
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
    public CompletableFuture<UserStaticData> getLoginData(String username, PublicSigningKey authorisedReader, byte[] auth) {
        LoginData updated = modifications.get(username);
        if (updated == null)
            return source.getLoginData(username, authorisedReader, auth);
        return Futures.of(updated.entryPoints);
    }
}
