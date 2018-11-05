package peergos.shared.corenode;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public interface CoreNodeProxy extends CoreNode {

    /**
     *
     * @param username
     * @return the key chain proving the claim of the requested username
     */
    CompletableFuture<List<UserPublicKeyLink>> getChain(Multihash targetServerId, String username);

    /** Claim a username, or change the public key owning a username
     *
     * @param username
     * @param chain
     * @return
     */
    CompletableFuture<Boolean> updateChain(Multihash targetServerId, String username, List<UserPublicKeyLink> chain);

    /**
     *
     * @param key
     * @return the username claimed by a given public key
     */
    CompletableFuture<String> getUsername(Multihash targetServerId, PublicKeyHash key);

    /**
     *
     * @param prefix
     * @return All usernames starting with prefix
     */
    CompletableFuture<List<String>> getUsernames(Multihash targetServerId, String prefix);

    /**
     *
     * @param username
     * @return the public key for a username, if present
     */
    default CompletableFuture<Optional<PublicKeyHash>> getPublicKeyHash(Multihash targetServerId, String username) {
        return getChain(targetServerId, username).thenApply(chain -> {
            if (chain.size() == 0)
                return Optional.empty();
            else
                return Optional.of(chain.get(chain.size() - 1).owner);
        });
    }
}
