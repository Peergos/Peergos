package peergos.server.corenode;

import peergos.shared.corenode.*;
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
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain) {
        return target.updateChain(username, chain)
                .thenApply(res -> {
                    if (res) {
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
    public CompletableFuture<Boolean> sendFollowRequest(PublicKeyHash target, byte[] encryptedPermission) {
        return this.target.sendFollowRequest(target, encryptedPermission);
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner) {
        return target.getFollowRequests(owner);
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] data) {
        return target.removeFollowRequest(owner, data);
    }

    @Override
    public void close() throws IOException {

    }
}
