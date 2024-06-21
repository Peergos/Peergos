package peergos.server.corenode;

import peergos.shared.corenode.*;
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
import java.util.stream.*;

public class NonWriteThroughCoreNode implements CoreNode {

    private final CoreNode source;
    private final ContentAddressedStorage ipfs;
    private final Map<String, List<UserPublicKeyLink>> tempChains;
    private final Map<PublicKeyHash, String> tempOwnerToUsername;

    public NonWriteThroughCoreNode(CoreNode source, ContentAddressedStorage ipfs) {
        this.source = source;
        this.ipfs = ipfs;
        this.tempChains = new ConcurrentHashMap<>();
        this.tempOwnerToUsername = new ConcurrentHashMap<>();
    }

    @Override
    public CompletableFuture<Optional<PublicKeyHash>> getPublicKeyHash(String username) {
        try {
            List<UserPublicKeyLink> chain = tempChains.get(username);
            if (chain != null) {
                PublicKeyHash modified = chain.get(chain.size() - 1).owner;
                return CompletableFuture.completedFuture(Optional.of(modified));
            }
            return source.getPublicKeyHash(username);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        try {
            String modified = tempOwnerToUsername.get(key);
            if (modified != null)
                return CompletableFuture.completedFuture(modified);
            return source.getUsername(key);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        try {
            List<UserPublicKeyLink> modified = tempChains.get(username);
            if (modified != null)
                return CompletableFuture.completedFuture(modified);
            return source.getChain(username);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> signup(String username,
                                                                  UserPublicKeyLink chain,
                                                                  OpLog setupOperations,
                                                                  ProofOfWork proof,
                                                                  String token) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Either<PaymentProperties, RequiredDifficulty>> startPaidSignup(String username, UserPublicKeyLink chain, ProofOfWork proof) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<PaymentProperties> completePaidSignup(String username, UserPublicKeyLink chain, OpLog setupOperations, byte[] signedSpaceRequest, ProofOfWork proof) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> updateChain(String username,
                                                                       List<UserPublicKeyLink> updated,
                                                                       ProofOfWork proof,
                                                                       String token) {
        try {
            List<UserPublicKeyLink> modified = tempChains.get(username);
            if (modified != null)
                modified = source.getChain(username).get();
            List<UserPublicKeyLink> mergedChain = UserPublicKeyLink.merge(modified, updated, ipfs).get();
            tempChains.put(username, mergedChain);
            UserPublicKeyLink last = mergedChain.get(mergedChain.size() - 1);
            tempOwnerToUsername.put(last.owner, username);
            return CompletableFuture.completedFuture(Optional.empty());
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        try {
            return CompletableFuture.completedFuture(
                    Stream.concat(
                            source.getUsernames(prefix).get().stream(),
                            tempChains.keySet().stream().filter(u -> u.startsWith(prefix)))
                            .distinct()
                            .collect(Collectors.toList()));
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<UserSnapshot> migrateUser(String username,
                                                       List<UserPublicKeyLink> newChain,
                                                       Multihash currentStorageId,
                                                       Optional<BatWithId> mirrorBat,
                                                       LocalDateTime latestLinkCountUpdate,
                                                       long currentUsage) {
        throw new IllegalStateException("Unimplemented method!");
    }

    @Override
    public CompletableFuture<Optional<Multihash>> getNextServerId(Multihash serverId) {
        return source.getNextServerId(serverId);
    }

    @Override
    public void close() throws IOException {}
}
