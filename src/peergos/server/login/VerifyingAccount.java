package peergos.server.login;

import peergos.server.util.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.concurrent.*;

public class VerifyingAccount implements Account {

    private final Account target;
    private final CoreNode core;
    private final ContentAddressedStorage storage;

    public VerifyingAccount(Account target, CoreNode core, ContentAddressedStorage storage) {
        this.target = target;
        this.core = core;
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth) {
        PublicKeyHash identityHash = core.getPublicKeyHash(login.username).join().get();
        PublicSigningKey identity = storage.getSigningKey(identityHash).join().get();
        identity.unsignMessage(ArrayOps.concat(auth, login.serialize()));
        return target.setLoginData(login, auth);
    }

    @Override
    public CompletableFuture<UserStaticData> getLoginData(String username, PublicSigningKey authorisedReader, byte[] auth) {
        return target.getLoginData(username, authorisedReader, auth).thenApply(res -> {
            TimeLimited.isAllowedTime(auth, 24*3600, authorisedReader);
            return res;
        });
    }
}
