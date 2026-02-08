package peergos.server.tests;

import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.OpLog;
import peergos.shared.corenode.UserPublicKeyLink;
import peergos.shared.crypto.ProofOfWork;
import peergos.shared.crypto.RequiredDifficulty;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.PaymentProperties;
import peergos.shared.storage.auth.BatWithId;
import peergos.shared.user.UserSnapshot;
import peergos.shared.util.Either;
import peergos.shared.util.Futures;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

class RamPki implements CoreNode {
    final Map<PublicKeyHash, String> reverseLookup = new HashMap<>();

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> signup(String username, UserPublicKeyLink chain, OpLog setupOperations, ProofOfWork proof, String token) {
        return null;
    }

    @Override
    public CompletableFuture<Either<PaymentProperties, RequiredDifficulty>> startPaidSignup(String username, UserPublicKeyLink chain, ProofOfWork proof) {
        return null;
    }

    @Override
    public CompletableFuture<PaymentProperties> completePaidSignup(String username, UserPublicKeyLink chain, OpLog setupOperations, byte[] signedSpaceRequest, ProofOfWork proof) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> startMirror(String username, BatWithId mirrorBat, byte[] auth, ProofOfWork proof) {
        return null;
    }

    @Override
    public CompletableFuture<Either<PaymentProperties, RequiredDifficulty>> startPaidMirror(String username, byte[] auth, ProofOfWork proof) {
        return null;
    }

    @Override
    public CompletableFuture<List<UserSnapshot>> getSnapshots(String prefix, BatWithId instanceBat, LocalDateTime lastLinkCountsUpdate) {
        return null;
    }

    @Override
    public CompletableFuture<PaymentProperties> completePaidMirror(String username, BatWithId mirrorBat, byte[] signedSpaceRequest, ProofOfWork proof) {
        return null;
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return null;
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> updateChain(String username, List<UserPublicKeyLink> chain, ProofOfWork proof, String token) {
        return null;
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash owner) {
        return Futures.of(reverseLookup.get(owner));
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return null;
    }

    @Override
    public CompletableFuture<UserSnapshot> migrateUser(String username, List<UserPublicKeyLink> newChain, Multihash currentStorageId, Optional<BatWithId> mirrorBat, LocalDateTime latestLinkCountUpdate, long usage, boolean commitToPki) {
        return null;
    }

    @Override
    public CompletableFuture<Optional<Multihash>> getNextServerId(Multihash serverId) {
        return null;
    }

    @Override
    public void close() throws IOException {

    }
}
