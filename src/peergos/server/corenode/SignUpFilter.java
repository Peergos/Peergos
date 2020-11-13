package peergos.server.corenode;

import peergos.server.storage.admin.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;
import peergos.shared.user.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class SignUpFilter implements CoreNode {

    private final CoreNode target;
    private final QuotaAdmin judge;
    private final Multihash ourNodeId;

    public SignUpFilter(CoreNode target, QuotaAdmin judge, Multihash ourNodeId) {
        this.target = target;
        this.judge = judge;
        this.ourNodeId = ourNodeId;
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return target.getChain(username);
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> updateChain(String username,
                                                                       List<UserPublicKeyLink> chain,
                                                                       ProofOfWork proof,
                                                                       String token) {
        if (! forUs(chain))
            return target.updateChain(username, chain, proof, token);

        if (judge.allowSignupOrUpdate(username, token)) {
            return target.updateChain(username, chain, proof, token).thenApply(res -> {
                if (res.isEmpty())
                    judge.consumeToken(username, token);
                return res;
            });
        }
        if (! token.isEmpty())
            return Futures.errored(new IllegalStateException("Invalid signup token."));

        return Futures.errored(new IllegalStateException("This server is not currently accepting new sign ups. Please try again later"));
    }

    private boolean forUs(List<UserPublicKeyLink> chain) {
        return chain.get(chain.size() - 1).claim.storageProviders.contains(ourNodeId);
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
    public CompletableFuture<UserSnapshot> migrateUser(String username,
                                                       List<UserPublicKeyLink> newChain,
                                                       Multihash currentStorageId) {
        if (forUs(newChain)) {
            if (! judge.allowSignupOrUpdate(username, ""))
                throw new IllegalStateException("This server is not currently accepting new user migrations.");
        }

        return target.migrateUser(username, newChain, currentStorageId);
    }

    @Override
    public void close() throws IOException {
        target.close();
    }
}
