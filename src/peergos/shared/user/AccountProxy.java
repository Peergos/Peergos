package peergos.shared.user;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.login.mfa.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** A Mutable Pointers extension that proxies all calls over a p2p stream
 *
 */
public interface AccountProxy extends Account {

    CompletableFuture<Boolean> setLoginData(Multihash targetServerId, LoginData login, byte[] auth);

    CompletableFuture<Either<UserStaticData, List<MultiFactorAuthMethod>>> getLoginData(Multihash targetServerId,
                                                                                        String username,
                                                                                        PublicSigningKey authorisedReader,
                                                                                        byte[] auth,
                                                                                        Optional<MultiFactorAuthResponse>  mfa);

    CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(Multihash targetServerId, String username, byte[] auth);

    CompletableFuture<TotpKey> addTotpFactor(Multihash targetServerId, String username, byte[] auth);

    CompletableFuture<Boolean> enableTotpFactor(Multihash targetServerId, String username, String uid, String code);

}
