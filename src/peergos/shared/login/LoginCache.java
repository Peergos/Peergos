package peergos.shared.login;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.user.*;

import java.util.concurrent.*;

public interface LoginCache {

    CompletableFuture<Boolean> setLoginData(LoginData login);

    CompletableFuture<Boolean> removeLoginData(String username);

    CompletableFuture<UserStaticData> getEntryData(String username, PublicSigningKey authorisedReader);
}
