package peergos.server.corenode;

import peergos.server.*;
import peergos.server.storage.admin.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

public class SignUpFilter implements CoreNode {
    private static final Logger LOG = Logging.LOG();

    private final CoreNode target;
    private final QuotaAdmin quotaStore;
    private final Multihash ourNodeId;
    private final HttpSpaceUsage space;
    private final Hasher hasher;
    private final DifficultyGenerator startPaidRateLimiter;
    private final int maxPaidSignupsPerDay;
    private final boolean isPki;

    public SignUpFilter(CoreNode target,
                        QuotaAdmin quotaStore,
                        Multihash ourNodeId,
                        HttpSpaceUsage space,
                        Hasher hasher,
                        int maxPaidSignupsPerDay,
                        boolean isPki) {
        this.target = target;
        this.quotaStore = quotaStore;
        this.ourNodeId = ourNodeId;
        this.space = space;
        this.hasher = hasher;
        this.maxPaidSignupsPerDay = maxPaidSignupsPerDay;
        startPaidRateLimiter = new DifficultyGenerator(System.currentTimeMillis(), maxPaidSignupsPerDay);
        this.isPki = isPki;
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> signup(String username,
                                                                  UserPublicKeyLink chain,
                                                                  OpLog setupOperations,
                                                                  ProofOfWork proof,
                                                                  String token) {
        if (! forUs(Arrays.asList(chain)))
            return target.signup(username, chain, setupOperations, proof, token);

        if (quotaStore.allowSignupOrUpdate(username, token)) {
            return target.signup(username, chain, setupOperations, proof, token).thenApply(res -> {
                if (res.isEmpty())
                    quotaStore.consumeToken(username, token);
                return res;
            });
        }
        if (! token.isEmpty())
            return Futures.errored(new IllegalStateException("Invalid signup token."));

        return Futures.errored(new IllegalStateException("This server is not currently accepting new sign ups. Please try again later"));
    }

    @Override
    public CompletableFuture<Either<PaymentProperties, RequiredDifficulty>> startPaidSignup(String username,
                                                                                            UserPublicKeyLink chain,
                                                                                            ProofOfWork proof) {
        if (isPki)
            return target.startPaidSignup(username, chain, proof);
        if (maxPaidSignupsPerDay == 0)
            return Futures.of(Either.b(new RequiredDifficulty(ProofOfWork.MAX_DIFFICULTY)));
        // Apply rate limiting based on IP, failures, etc.
        // Check proof of work is sufficient, unless it is a password change
        byte[] hash = hasher.sha256(ArrayOps.concat(proof.prefix, new CborObject.CborList(Arrays.asList(chain)).serialize())).join();
        startPaidRateLimiter.updateTime(System.currentTimeMillis());
        int requiredDifficulty = startPaidRateLimiter.currentDifficulty();
        if (! ProofOfWork.satisfiesDifficulty(requiredDifficulty, hash)) {
            LOG.log(Level.INFO, "Rejected start paid signup request with insufficient proof of work for difficulty: " +
                    requiredDifficulty + " and username " + username);
            return Futures.of(Either.b(new RequiredDifficulty(requiredDifficulty)));
        }

        //  reserve username, then create user and get payment url
        return target.startPaidSignup(username, chain, proof)
                .thenApply(res -> {
                    if (res.isA()) {
                        return Either.a(quotaStore.createPaidUser(username));
                    }
                    return res;
                });
    }

    @Override
    public CompletableFuture<PaymentProperties> completePaidSignup(String username,
                                                                   UserPublicKeyLink chain,
                                                                   OpLog setupOperations,
                                                                   byte[] signedSpaceRequest,
                                                                   ProofOfWork proof) {
        if (isPki)
            return target.completePaidSignup(username, chain, setupOperations, signedSpaceRequest, proof);
        // take payment, and if successful, finalise account creation
        quotaStore.getQuota(username); // This will throw if the user doesn't exist in quota store
        PaymentProperties result = quotaStore.requestQuota(chain.owner, signedSpaceRequest).join();
        long quota = quotaStore.getQuota(username);
        if (quota == 0 && ! result.hasError()) {// try again if no card showed up yet
            for (int i = 0; i < 3; i++) {
                LOG.info("Paid signup: no cards available, sleeping for 3s...");
                try {Thread.sleep(3_000);} catch (InterruptedException e) {}
                result = quotaStore.requestQuota(chain.owner, signedSpaceRequest).join();
                quota = quotaStore.getQuota(username);
                if (quota > 0 || result.hasError())
                    break;
            }
        }
        LOG.info("Paid signup: quota="+quota + ", username=" + username);
        if (quota >= result.desiredQuota && quota > 1024*1024) {// 1 MiB is the deletion quota
            LOG.info("Successful Paid signup!");
            AggregatedMetrics.PAID_SIGNUP_SUCCESS.inc();
            return target.completePaidSignup(username, chain, setupOperations, signedSpaceRequest, proof);
        } else if (result.hasError()) {
            LOG.info("Payment error during Paid signup! username=" + username);
            // payment failed, set desired quota to 0 to prevent future payment attempts
            quotaStore.removeDesiredQuota(username);
        }
        return Futures.of(result);
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
        return target.updateChain(username, chain, proof, token);
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
                                                       Multihash currentStorageId,
                                                       Optional<BatWithId> mirrorBat,
                                                       LocalDateTime linkCountsAfter,
                                                       long currentUsage) {
        if (forUs(newChain)) {
            if (! quotaStore.allowSignupOrUpdate(username, ""))
                throw new IllegalStateException("This server is not currently accepting new user migrations.");
            PublicKeyHash owner = newChain.get(newChain.size() - 1).owner;

            // check we have enough local quota to mirror all user's data
            long localQuota = quotaStore.getQuota(username);
            if (localQuota < currentUsage)
                throw new IllegalStateException("Not enough space for user to migrate user to this server!");
        }

        return target.migrateUser(username, newChain, currentStorageId, mirrorBat, linkCountsAfter, currentUsage);
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
