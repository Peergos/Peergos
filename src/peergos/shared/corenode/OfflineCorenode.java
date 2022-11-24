package peergos.shared.corenode;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
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
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return Futures.asyncExceptionally(
                () -> {
                    if (online.isOnline())
                        return target.getChain(username).thenApply(chain -> {
                            if (!chain.isEmpty())
                                pkiCache.setChain(username, chain);
                            return chain;
                        });
                    return pkiCache.getChain(username);
                },
                t -> pkiCache.getChain(username));
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
                                                       Optional<BatWithId> mirrorBat) {
        return target.migrateUser(username, newChain, currentStorageId, mirrorBat);
    }

    @Override
    public void close() throws IOException {
        target.close();
    }
}
