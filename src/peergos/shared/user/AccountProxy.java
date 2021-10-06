package peergos.shared.user;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.multihash.*;

import java.util.concurrent.*;

/** A Mutable Pointers extension that proxies all calls over a p2p stream
 *
 */
public interface AccountProxy extends Account {

    CompletableFuture<Boolean> setLoginData(Multihash targetServerId, LoginData login, byte[] auth);

    CompletableFuture<UserStaticData> getLoginData(Multihash targetServerId, String username, PublicSigningKey authorisedReader, byte[] auth);

}
