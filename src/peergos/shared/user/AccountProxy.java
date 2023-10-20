package peergos.shared.user;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.login.mfa.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** A Mutable Pointers extension that proxies all calls over a p2p stream
 *
 */
public interface AccountProxy extends Account {

    CompletableFuture<Boolean> setLoginData(Multihash targetServerId, LoginData login, byte[] auth);

    CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getLoginData(Multihash targetServerId,
                                                                                   String username,
                                                                                   PublicSigningKey authorisedReader,
                                                                                   byte[] auth,
                                                                                   Optional<MultiFactorAuthResponse>  mfa);

    CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(Multihash targetServerId, String username, byte[] auth);

    CompletableFuture<TotpKey> addTotpFactor(Multihash targetServerId, String username, byte[] auth);

    CompletableFuture<Boolean> enableTotpFactor(Multihash targetServerId,
                                                String username,
                                                byte[] credentialId,
                                                String code,
                                                byte[] auth);

    CompletableFuture<byte[]> registerSecurityKeyStart(Multihash targetServerId, String username, byte[] auth);

    CompletableFuture<Boolean> registerSecurityKeyComplete(Multihash targetServerId, String username, String keyName, MultiFactorAuthResponse resp, byte[] auth);

    CompletableFuture<Boolean> deleteSecondFactor(Multihash targetServerId, String username, byte[] credentialId, byte[] auth);
}
