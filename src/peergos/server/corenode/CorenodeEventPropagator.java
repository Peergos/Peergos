package peergos.server.corenode;

import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** This class propagates core node writes to
 *
 */
public class CorenodeEventPropagator implements CoreNode {

    private final CoreNode target;
    private final List<Function<? super CorenodeEvent, CompletableFuture<Boolean>>> listeners = new ArrayList<>();

    public CorenodeEventPropagator(CoreNode target) {
        this.target = target;
    }

    public void addListener(Function<? super CorenodeEvent, CompletableFuture<Boolean>> listener) {
        listeners.add(listener);
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> signup(String username,
                                                                  UserPublicKeyLink chain,
                                                                  OpLog setupOperations,
                                                                  ProofOfWork proof,
                                                                  String token) {
        return target.signup(username, chain, setupOperations, proof, token)
                .thenApply(res -> {
                    if (res.isEmpty()) {
                        processEvent(Arrays.asList(chain));
                    }
                    return res;
                });
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> updateChain(String username,
                                                                       List<UserPublicKeyLink> chain,
                                                                       ProofOfWork proof,
                                                                       String token) {
        return target.updateChain(username, chain, proof, token)
                .thenApply(res -> {
                    if (res.isEmpty()) {
                        processEvent(chain);
                    }
                    return res;
                });
    }

    private CompletableFuture<Boolean> processEvent(List<UserPublicKeyLink> chain) {
        UserPublicKeyLink last = chain.get(chain.size() - 1);
        CorenodeEvent event = new CorenodeEvent(last.claim.username, last.owner);
        List<CompletableFuture<Boolean>> all = new ArrayList<>();
        for (Function<? super CorenodeEvent, CompletableFuture<Boolean>> listener : listeners) {
            all.add(listener.apply(event));
        }
        return Futures.combineAll(all).thenApply(x -> true);
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        return target.getUsername(key);
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return target.getChain(username);
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return target.getUsernames(prefix);
    }

    @Override
    public CompletableFuture<UserSnapshot> migrateUser(String username,
                                                       List<UserPublicKeyLink> newChain,
                                                       Multihash currentStorageId) {
        return target.migrateUser(username, newChain, currentStorageId).thenApply(res -> {
            processEvent(newChain);
            return res;
        });
    }

    @Override
    public void close() throws IOException {

    }
}
