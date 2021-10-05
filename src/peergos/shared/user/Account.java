package peergos.shared.user;

import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;

import java.util.concurrent.*;

public interface Account {

    CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth);

    default CompletableFuture<Boolean> setLoginData(LoginData login, SigningPrivateKeyAndPublicHash identity) {
        byte[] auth = identity.secret.signatureOnly(login.serialize());
        return setLoginData(login, auth);
    }

    CompletableFuture<UserStaticData> getLoginData(String username, PublicSigningKey authorisedReader, byte[] auth);
}
