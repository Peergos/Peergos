package peergos.shared.corenode;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public interface CoreNode {
    int MAX_USERNAME_SIZE = 64;

    default void initialize() {}

    CompletableFuture<Optional<RequiredDifficulty>> signup(String username,
                                                           UserPublicKeyLink chain,
                                                           OpLog setupOperations,
                                                           ProofOfWork proof,
                                                           String token);

    CompletableFuture<Either<PaymentProperties, RequiredDifficulty>> startPaidSignup(String username,
                                                                                     UserPublicKeyLink chain,
                                                                                     ProofOfWork proof);

    CompletableFuture<PaymentProperties> completePaidSignup(String username,
                                                            UserPublicKeyLink chain,
                                                            OpLog setupOperations,
                                                            byte[] signedSpaceRequest,
                                                            ProofOfWork proof);

    /**
     *
     * @param username
     * @return the key chain proving the claim of the requested username and the ipfs node id of their storage
     */
    CompletableFuture<List<UserPublicKeyLink>> getChain(String username);

    /** Claim a username, or change the public key owning a username
     *
     * @param username
     * @param chain The changed links of the chain
     * @param proof Any required proof of work
     * @param token Any required token to authorise signup on this server
     * @return Optional.empty() if successfully updated, otherwise the required difficulty
     */
    CompletableFuture<Optional<RequiredDifficulty>> updateChain(String username,
                                                                List<UserPublicKeyLink> chain,
                                                                ProofOfWork proof,
                                                                String token);

    /**
     *
     * @param key the hash of the public identity key of a user
     * @return the username claimed by a given public key
     */
    CompletableFuture<String> getUsername(PublicKeyHash key);

    /**
     *
     * @param prefix
     * @return All usernames starting with prefix
     */
    CompletableFuture<List<String>> getUsernames(String prefix);

    CompletableFuture<UserSnapshot> migrateUser(String username,
                                                List<UserPublicKeyLink> newChain,
                                                Multihash currentStorageId,
                                                Optional<BatWithId> mirrorBat,
                                                LocalDateTime latestLinkCountUpdate,
                                                long usage);

    CompletableFuture<Optional<Multihash>> getNextServerId(Multihash serverId);

    /** This is only implemented by caching corenodes
     *
     * @param username
     * @return
     */
    default CompletableFuture<Boolean> updateUser(String username) {return CompletableFuture.completedFuture(true);}

    /**
     *
     * @param username
     * @return the public key for a username, if present
     */
    default CompletableFuture<Optional<PublicKeyHash>> getPublicKeyHash(String username) {
        return getChain(username).thenApply(chain -> {
            if (chain.size() == 0)
                return Optional.empty();
            else
                return Optional.of(chain.get(chain.size() - 1).owner);
        });
    }

    default CompletableFuture<Optional<Multihash>> getHomeServer(String username) {
        return getChain(username).thenApply(chain -> {
            if (chain.size() == 0)
                return Optional.empty();
            else
                return Optional.of(chain.get(chain.size() - 1).claim.storageProviders.get(0));
        });
    }

    default List<Multihash> getStorageProviders(PublicKeyHash owner) {
        String username = getUsername(owner).join();
        List<UserPublicKeyLink> chain = getChain(username).join();
        if (chain.isEmpty())
            return Collections.emptyList();
        return chain.get(chain.size() - 1).claim.storageProviders;
    }

    void close() throws IOException;
}
