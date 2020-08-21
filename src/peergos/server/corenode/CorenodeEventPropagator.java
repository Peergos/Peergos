package peergos.server.corenode;

import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** This class propagates core node writes to
 *
 */
public class CorenodeEventPropagator implements CoreNode {

    private final CoreNode target;
    private final List<Consumer<? super CorenodeEvent>> listeners = new ArrayList<>();

    public CorenodeEventPropagator(CoreNode target) {
        this.target = target;
    }

    public void addListener(Consumer<? super CorenodeEvent> listener) {
        listeners.add(listener);
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> updateChain(String username, List<UserPublicKeyLink> chain, ProofOfWork proof) {
        return target.updateChain(username, chain, proof)
                .thenApply(res -> {
                    if (res.isEmpty()) {
                        CorenodeEvent event = new CorenodeEvent(username, chain.get(chain.size() - 1).owner);
                        for (Consumer<? super CorenodeEvent> listener : listeners) {
                            listener.accept(event);
                        }
                    }
                    return res;
                });
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
    public void close() throws IOException {

    }
}
