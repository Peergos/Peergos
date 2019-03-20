package peergos.server.corenode;

import peergos.server.storage.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class SignUpFilter implements CoreNode {

    private final CoreNode target;
    private final UserQuotas judge;
    private final Multihash ourNodeId;

    public SignUpFilter(CoreNode target, UserQuotas judge, Multihash ourNodeId) {
        this.target = target;
        this.judge = judge;
        this.ourNodeId = ourNodeId;
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return target.getChain(username);
    }

    @Override
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain) {
        boolean forUs = chain.get(chain.size() - 1).claim.storageProviders.contains(ourNodeId);
        if (! forUs)
            return target.updateChain(username, chain);
        return CompletableFuture.completedFuture(true)
                .thenCompose(x -> {
                    if (judge.allowSignupOrUpdate(username))
                        return target.updateChain(username, chain);
                    throw new IllegalStateException("This server is not currently accepting new sign ups. Please try again later");
                });
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        return target.getUsername(key);
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return target.getUsernames(prefix);
    }

    @Override
    public void close() throws IOException {
        target.close();
    }
}
