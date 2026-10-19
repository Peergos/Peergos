package peergos.server.routing;

import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** Where an identity is stored, and a server's current peerid, from the routing log (remove-pki §5), falling back
 *  to the PKI for identities that haven't committed, servers not in the log, and until the log starts.
 */
public class RoutedCoreNode implements CoreNode {
    public final CoreNode pki;
    private volatile Optional<RoutingLookup> lookup = Optional.empty();

    public RoutedCoreNode(CoreNode pki) {
        this.pki = pki;
    }

    public void start(RoutingLookup lookup) {
        this.lookup = Optional.of(lookup);
    }

    /** A legacy-only identity's providers are pinned at the PKI's final state, which the PKI itself is newer than. */
    public static Optional<List<Multihash>> providers(RoutingLookup lookup, PublicKeyHash owner) {
        return lookup.identity(owner)
                .filter(log -> log.inception().isPresent())
                .map(log -> log.providers().stream()
                        .map(p -> currentPeerId(lookup, p).orElse(asPeerId(p)))
                        .collect(Collectors.toList()))
                .filter(ps -> ! ps.isEmpty());
    }

    /** The peerid to dial a server on: that of its current endpoint, or failing that its current key. */
    public static Optional<Multihash> currentPeerId(RoutingLookup lookup, Multihash server) {
        return lookup.server(server)
                .flatMap(log -> log.addresses().map(a -> a.endpoint)
                        .or(() -> log.currentKey().filter(k -> k.type() == PublicSigningKey.Type.Ed25519))
                        .map(ServerKeys::peerId)
                        .or(() -> log.legacy.map(LegacyServer::current)))
                .map(RoutedCoreNode::asPeerId);
    }

    /** The form the PKI's claims take, which callers compare against and cast to. */
    private static Multihash asPeerId(Multihash m) {
        Multihash bare = m.bareMultihash();
        return new Cid(1, Cid.Codec.LibP2pKey, bare.type, bare.getHash());
    }

    @Override
    public List<Multihash> getStorageProviders(PublicKeyHash owner) {
        return lookup.flatMap(r -> providers(r, owner))
                .orElseGet(() -> pki.getStorageProviders(owner));
    }

    @Override
    public CompletableFuture<Optional<Multihash>> getHomeServer(String username) {
        return getPublicKeyHash(username)
                .thenApply(owner -> owner.flatMap(o -> getStorageProviders(o).stream().findFirst()));
    }

    @Override
    public CompletableFuture<Optional<Multihash>> getNextServerId(Multihash serverId) {
        Optional<Multihash> logged = lookup.flatMap(r -> currentPeerId(r, serverId))
                .filter(current -> ! current.bareMultihash().equals(serverId.bareMultihash()));
        if (logged.isPresent())
            return Futures.of(logged);
        return pki.getNextServerId(serverId);
    }

    @Override
    public void initialize(boolean mirrorUsers) {
        pki.initialize(mirrorUsers);
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> signup(String username, UserPublicKeyLink chain, OpLog setupOperations, ProofOfWork proof, String token) {
        return pki.signup(username, chain, setupOperations, proof, token);
    }

    @Override
    public CompletableFuture<Either<PaymentProperties, RequiredDifficulty>> startPaidSignup(String username, UserPublicKeyLink chain, ProofOfWork proof) {
        return pki.startPaidSignup(username, chain, proof);
    }

    @Override
    public CompletableFuture<PaymentProperties> completePaidSignup(String username, UserPublicKeyLink chain, OpLog setupOperations, byte[] signedSpaceRequest, ProofOfWork proof) {
        return pki.completePaidSignup(username, chain, setupOperations, signedSpaceRequest, proof);
    }

    @Override
    public CompletableFuture<Boolean> startMirror(String username, BatWithId mirrorBat, byte[] auth, ProofOfWork proof) {
        return pki.startMirror(username, mirrorBat, auth, proof);
    }

    @Override
    public CompletableFuture<Either<PaymentProperties, RequiredDifficulty>> startPaidMirror(String username, byte[] auth, ProofOfWork proof) {
        return pki.startPaidMirror(username, auth, proof);
    }

    @Override
    public CompletableFuture<List<UserSnapshot>> getSnapshots(String prefix, BatWithId instanceBat, LocalDateTime lastLinkCountsUpdate) {
        return pki.getSnapshots(prefix, instanceBat, lastLinkCountsUpdate);
    }

    @Override
    public CompletableFuture<PaymentProperties> completePaidMirror(String username, BatWithId mirrorBat, byte[] signedSpaceRequest, ProofOfWork proof) {
        return pki.completePaidMirror(username, mirrorBat, signedSpaceRequest, proof);
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return pki.getChain(username);
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> updateChain(String username, List<UserPublicKeyLink> chain, ProofOfWork proof, String token) {
        return pki.updateChain(username, chain, proof, token);
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        return pki.getUsername(key);
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return pki.getUsernames(prefix);
    }

    @Override
    public CompletableFuture<UserSnapshot> migrateUser(String username, List<UserPublicKeyLink> newChain, Multihash currentStorageId,
                                                       Optional<BatWithId> mirrorBat, LocalDateTime latestLinkCountUpdate,
                                                       long usage, boolean commitToPki) {
        return pki.migrateUser(username, newChain, currentStorageId, mirrorBat, latestLinkCountUpdate, usage, commitToPki);
    }

    @Override
    public CompletableFuture<Boolean> updateUser(String username) {
        return pki.updateUser(username);
    }

    @Override
    public CompletableFuture<Optional<PublicKeyHash>> getPublicKeyHash(String username) {
        return pki.getPublicKeyHash(username);
    }

    @Override
    public void close() throws IOException {
        pki.close();
    }
}
