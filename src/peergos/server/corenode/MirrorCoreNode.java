package peergos.server.corenode;

import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.util.*;
import java.util.concurrent.*;

public class MirrorCoreNode implements CoreNode {

    private final CoreNode writeTarget;
    private final ContentAddressedStorage ipfs;
    private final MutablePointers mutable;
    private final PublicKeyHash pkiOwnerIdentity;

    private final Map<String, List<UserPublicKeyLink>> chains = new ConcurrentHashMap<>();
    private final Map<PublicKeyHash, String> reverseLookup = new ConcurrentHashMap<>();
    private final List<String> usernames = new ArrayList<>();

    private MaybeMultihash currentRoot = MaybeMultihash.empty();
    private volatile boolean running = true;

    public MirrorCoreNode(CoreNode writeTarget,
                          ContentAddressedStorage ipfs,
                          MutablePointers mutable,
                          PublicKeyHash pkiOwnerIdentity) {
        this.writeTarget = writeTarget;
        this.ipfs = ipfs;
        this.mutable = mutable;
        this.pkiOwnerIdentity = pkiOwnerIdentity;
    }

    public void start() {
        running = true;
        new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException i) {}
                update();
            }
        }, "Mirroring PKI node").start();
    }

    private PublicKeyHash getPkiKey() throws Exception {
        CommittedWriterData current = WriterData.getWriterData(pkiOwnerIdentity, pkiOwnerIdentity, mutable, ipfs).get();
        PublicKeyHash pki = current.props.namedOwnedKeys.get("pki");
        if (pki == null)
            throw new IllegalStateException("No pki key on owner: " + pkiOwnerIdentity);
        return pki;
    }

    private synchronized boolean update() {
        try {
            PublicKeyHash pkiKey = getPkiKey();
            MaybeMultihash newRoot = mutable.getPointerTarget(pkiOwnerIdentity, pkiKey, ipfs).get();
            IpfsCoreNode.updateAllMappings(pkiKey, currentRoot, newRoot, ipfs, chains, reverseLookup, usernames);
            currentRoot = newRoot;
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        List<UserPublicKeyLink> chain = chains.get(username);
        if (chain != null)
            return CompletableFuture.completedFuture(chain);

        update();
        return CompletableFuture.completedFuture(chains.getOrDefault(username, Collections.emptyList()));
    }

    @Override
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain) {
        return writeTarget.updateChain(username, chain).thenApply(x -> this.update());
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        String username = reverseLookup.get(key);
        if (username != null)
            return CompletableFuture.completedFuture(username);
        update();
        return CompletableFuture.completedFuture(reverseLookup.get(key));
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return CompletableFuture.completedFuture(usernames);
    }

    @Override
    public void close() {
        running = false;
    }
}
