package peergos.shared.corenode;

import peergos.shared.*;
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

public class OfflineCorenode implements CoreNode {

    private final CoreNode target;
    private final PkiCache pkiCache;
    private final OnlineState online;

    public OfflineCorenode(CoreNode target, PkiCache pkiCache, OnlineState online) {
        this.target = target;
        this.pkiCache = pkiCache;
        this.online = online;
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> signup(String username,
                                                                  UserPublicKeyLink userPublicKeyLink,
                                                                  OpLog opLog,
                                                                  ProofOfWork proofOfWork,
                                                                  String token) {
        return target.signup(username, userPublicKeyLink, opLog, proofOfWork, token);
    }

    @Override
    public CompletableFuture<Either<PaymentProperties, RequiredDifficulty>> startPaidSignup(String username,
                                                                                            UserPublicKeyLink chain,
                                                                                            ProofOfWork proof) {
        return target.startPaidSignup(username, chain, proof);
    }

    @Override
    public CompletableFuture<PaymentProperties> completePaidSignup(String username,
                                                                   UserPublicKeyLink chain,
                                                                   OpLog setupOperations,
                                                                   byte[] signedSpaceRequest,
                                                                   ProofOfWork proof) {
        return target.completePaidSignup(username, chain, setupOperations, signedSpaceRequest, proof);
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return Futures.asyncExceptionally(
                () -> {
                    if (online.isOnline())
                        return target.getChain(username).thenApply(chain -> {
                            if (!chain.isEmpty())
                                pkiCache.setChain(username, chain);
                            return chain;
                        });
                    online.updateAsync();
                    return pkiCache.getChain(username);
                },
                t -> {
                    if (online.isOfflineException(t))
                        return pkiCache.getChain(username);
                    return Futures.errored(t);
                });
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> updateChain(String username,
                                                                       List<UserPublicKeyLink> chain,
                                                                       ProofOfWork proofOfWork,
                                                                       String token) {
        return target.updateChain(username, chain, proofOfWork, token)
                .thenApply(work -> {
                    if (work.isEmpty())
                        target.getChain(username)
                                .thenCompose(updated -> pkiCache.setChain(username, updated));
                    return work;
        });
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash identity) {
        return Futures.asyncExceptionally(
                () -> target.getUsername(identity),
                t -> pkiCache.getUsername(identity));
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return target.getUsernames(prefix)
                .exceptionally(t -> Collections.emptyList());
    }

    @Override
    public CompletableFuture<UserSnapshot> migrateUser(String username,
                                                       List<UserPublicKeyLink> newChain,
                                                       Multihash currentStorageId,
                                                       Optional<BatWithId> mirrorBat,
                                                       LocalDateTime latestLinkCountUpdate,
                                                       long currentUsage) {
        return target.migrateUser(username, newChain, currentStorageId, mirrorBat, latestLinkCountUpdate, currentUsage);
    }

    @Override
    public CompletableFuture<Optional<Multihash>> getNextServerId(Multihash serverId) {
        return target.getNextServerId(serverId);
    }

    @Override
    public void close() throws IOException {
        target.close();
    }
}
