package peergos.shared.user;

import java.io.*;
import java.util.logging.*;

import peergos.shared.fingerprint.*;
import peergos.shared.inode.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.login.mfa.*;
import peergos.shared.resolution.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.user.fs.transaction.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import jsinterop.annotations.*;

/**
 * The UserContext class represents a logged in user, or a retrieved secret link and the resulting view of the global
 * filesystem.
 */
public class UserContext {
    private static final Logger LOG = Logger.getGlobal();

    public static final String SHARED_DIR_NAME = "shared";
    public static final String POSTS_DIR_NAME = ".posts";
    public static final String GROUPS_FILENAME = ".groups.cbor"; // no clash possible with usernames possible because of dot
    public static final String FEED_DIR_NAME = ".feed";
    public static final String TRANSACTIONS_DIR_NAME = ".transactions";
    public static final String FRIEND_ANNOTATIONS_FILE_NAME = ".annotations";

    public static final String ENTRY_POINTS_FROM_FRIENDS_FILENAME = ".from-friends.cborstream";
    public static final String ENTRY_POINTS_FROM_FRIENDS_GROUPS_FILENAME = ".groups-from-friends.cborstream";
    public static final String SOCIAL_STATE_FILENAME = ".social-state.cbor";
    public static final String BLOCKED_USERNAMES_FILE = ".blocked-usernames.txt";

    @JsProperty
    public final String username;
    public final SigningPrivateKeyAndPublicHash signer;
    private final BoxingKeyPair boxer;
    @JsProperty
    private final SymmetricKey rootKey;

    private final WriteSynchronizer writeSynchronizer;
    private final TransactionService transactions;
    private final IncomingCapCache capCache;
    private final Optional<BatWithId> mirrorBat;
    public final SharedWithCache sharedWithCache;

    // The root of the global filesystem as viewed by this context
    @JsProperty
    public TrieNode entrie; // ba dum che!

    // Contact external world
    @JsProperty
    public final NetworkAccess network;

    // In process only
    @JsProperty
    public final Crypto crypto;

    public UserContext(String username,
                       SigningPrivateKeyAndPublicHash signer,
                       BoxingKeyPair boxer,
                       SymmetricKey rootKey,
                       NetworkAccess network,
                       Crypto crypto,
                       CommittedWriterData userData,
                       TrieNode entrie,
                       TransactionService transactions,
                       IncomingCapCache capCache,
                       SharedWithCache sharedWithCache,
                       Optional<BatWithId> mirrorBat) {
        this.username = username;
        this.signer = signer;
        this.boxer = boxer;
        this.rootKey = rootKey;
        this.network = network;
        this.crypto = crypto;
        this.entrie = entrie;
        this.writeSynchronizer = network.synchronizer;
        if (signer != null) {
            writeSynchronizer.put(signer.publicKeyHash, signer.publicKeyHash, userData);
        }
        this.transactions = transactions;
        this.capCache = capCache;
        this.sharedWithCache = sharedWithCache;
        this.mirrorBat = mirrorBat;
    }

    private static CompletableFuture<TransactionService> buildTransactionService(TrieNode root,
                                                                                 String username,
                                                                                 NetworkAccess network,
                                                                                 Crypto crypto) {
        return root.getByPath(username, crypto.hasher, network)
                .thenApply(Optional::get)
                .thenCompose(home -> home.getChild(TRANSACTIONS_DIR_NAME, crypto.hasher, network))
                .thenApply(Optional::get)
                .thenApply(txnDir -> new TransactionServiceImpl(network, crypto, txnDir));
    }

    private static CompletableFuture<IncomingCapCache> buildCapCache(TrieNode root,
                                                                     String username,
                                                                     Optional<BatId> mirrorBatId,
                                                                     NetworkAccess network,
                                                                     Crypto crypto) {
        return root.getByPath(username, crypto.hasher, network)
                .thenApply(Optional::get)
                .thenCompose(home -> home.getOrMkdirs(PathUtil.get(CapabilityStore.CAPABILITY_CACHE_DIR), network, true, mirrorBatId, crypto))
                .thenCompose(cacheRoot -> IncomingCapCache.build(cacheRoot, mirrorBatId, crypto, network));
    }

    @JsMethod
    public TransactionService getTransactionService() {
        return transactions;
    }

    public static CompletableFuture<UserContext> signIn(String username,
                                                        String password,
                                                        Function<MultiFactorAuthRequest, CompletableFuture<MultiFactorAuthResponse>> mfa,
                                                        NetworkAccess network,
                                                        Crypto crypto) {
        return signIn(username, password, mfa, false, network, crypto, t -> {});
    }

    @JsMethod
    public static CompletableFuture<UserContext> signIn(String username,
                                                        String password,
                                                        Function<MultiFactorAuthRequest, CompletableFuture<MultiFactorAuthResponse>> mfa,
                                                        boolean cacheMfaLoginData,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        Consumer<String> progressCallback) {
        return Futures.asyncExceptionally(() -> getWriterDataCbor(network, username),
                e ->  {
                    if (e.getMessage().contains("hash not present"))
                        return Futures.errored(new IllegalStateException("User has been deleted. Did you mean a different username?"));
                    else if (e.getMessage().contains("No public-key for user"))
                        return Futures.errored(new IllegalStateException("Unknown username. Did you enter it correctly?"));
                    else
                        return Futures.errored(e);
                }).thenCompose(pair -> {
            SecretGenerationAlgorithm algorithm = WriterData.fromCbor(pair.right).generationAlgorithm
                    .orElseThrow(() -> new IllegalStateException("No login algorithm specified in user data!"));
            progressCallback.accept("Generating keys");
            return UserUtil.generateUser(username, password, crypto.hasher, crypto.symmetricProvider,
                    crypto.random, crypto.signer, crypto.boxer, algorithm)
                    .thenCompose(userWithRoot -> {
                        progressCallback.accept("Logging in");
                        return login(username, userWithRoot, mfa, cacheMfaLoginData, pair, network, crypto, progressCallback);
                    });
        }).exceptionally(Futures::logAndThrow);
    }

    public static CompletableFuture<UserContext> signIn(String username,
                                                        UserWithRoot userWithRoot,
                                                        Function<MultiFactorAuthRequest, CompletableFuture<MultiFactorAuthResponse>> mfa,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        Consumer<String> progressCallback) {
        progressCallback.accept("Logging in");
        return getWriterDataCbor(network, username)
                .thenCompose(pair -> login(username, userWithRoot, mfa, false, pair, network, crypto, progressCallback))
                .exceptionally(Futures::logAndThrow);
    }

    @JsMethod
    public static CompletableFuture<UserContext> restoreContext(String username,
                                                                SymmetricKey loginRoot,
                                                                UserStaticData entryData,
                                                                NetworkAccess network,
                                                                Crypto crypto,
                                                                Consumer<String> progressCallback) {
        progressCallback.accept("Logging in");
        return getWriterDataCbor(network, username)
                .thenCompose(pair -> {
                    WriterData userData = WriterData.fromCbor(pair.right);
                    boolean legacyAccount = userData.staticData.isPresent();
                    if (legacyAccount)
                        throw new IllegalStateException("Legacy accounts can't stay logged in. Please change your password to upgrade your account.");

                    UserStaticData.EntryPoints staticData = entryData.getData(loginRoot);
                    SigningPrivateKeyAndPublicHash signer =
                            new SigningPrivateKeyAndPublicHash(userData.controller, staticData.identity.get().secretSigningKey);
                    BoxingKeyPair boxer = staticData.boxer.orElseThrow(() -> new IllegalStateException("No social keypair present in login data!"));
                    return login(username, userData, staticData, signer, boxer, loginRoot, pair, network, crypto, progressCallback);
                })
                .exceptionally(Futures::logAndThrow);
    }

    private static CompletableFuture<UserStaticData> getLoginData(String username,
                                                                  PublicSigningKey loginPub,
                                                                  SecretSigningKey loginSecret,
                                                                  Function<MultiFactorAuthRequest, CompletableFuture<MultiFactorAuthResponse>> mfa,
                                                                  boolean cacheMfaLoginData,
                                                                  NetworkAccess network) {
        return TimeLimitedClient.signNow(loginSecret)
                .thenCompose(signedTime -> network.account.getLoginData(username, loginPub, signedTime, Optional.empty(), cacheMfaLoginData))
                .thenCompose(res -> {
                    if (res.isA())
                        return Futures.of(res.a());
                    MultiFactorAuthRequest authReq = res.b();
                    return mfa.apply(authReq)
                            .thenCompose(authResp -> TimeLimitedClient.signNow(loginSecret)
                                    .thenCompose(signedTime -> network.account.getLoginData(username, loginPub, signedTime, Optional.of(authResp), cacheMfaLoginData)))
                            .thenApply(login -> {
                                if (login.isB())
                                    throw new IllegalStateException("Server rejected second factor auth");
                                return login.a();
                            });
                });
    }

    private static CompletableFuture<UserContext> login(String username,
                                                        UserWithRoot generatedCredentials,
                                                        Function<MultiFactorAuthRequest, CompletableFuture<MultiFactorAuthResponse>> mfa,
                                                        boolean cacheMfaLoginData,
                                                        Pair<PointerUpdate, CborObject> pair,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        Consumer<String> progressCallback) {
        try {
            WriterData userData = WriterData.fromCbor(pair.right);
            boolean legacyAccount = userData.staticData.isPresent();
            SymmetricKey loginRoot = generatedCredentials.getRoot();
            PublicSigningKey loginPub = generatedCredentials.getUser().publicSigningKey;
            SecretSigningKey loginSecret = generatedCredentials.getUser().secretSigningKey;
            return (legacyAccount ?
                    Futures.of(userData.staticData.get()) :
                    getLoginData(username, loginPub, loginSecret, mfa, cacheMfaLoginData, network)).thenCompose(entryData -> {
                UserStaticData.EntryPoints staticData;
                try {
                    staticData = entryData.getData(loginRoot);
                } catch (Exception e) {
                    if (legacyAccount)
                        throw new IllegalStateException("Incorrect password");
                    else throw new RuntimeException(e);
                }
                // Use generated signer for legacy logins, or get from UserStaticData for newer logins
                SigningPrivateKeyAndPublicHash signer =
                        new SigningPrivateKeyAndPublicHash(userData.controller,
                                legacyAccount ? loginSecret : staticData.identity.get().secretSigningKey);
                BoxingKeyPair boxer = staticData.boxer.orElse(generatedCredentials.getBoxingPair());
                return login(username, userData, staticData, signer, boxer, loginRoot, pair, network, crypto, progressCallback);
            });
        } catch (Throwable t) {
            throw new IllegalStateException("Incorrect password");
        }
    }

    private static CompletableFuture<UserContext> login(String username,
                                                        WriterData userData,
                                                        UserStaticData.EntryPoints staticData,
                                                        SigningPrivateKeyAndPublicHash signer,
                                                        BoxingKeyPair boxer,
                                                        SymmetricKey login,
                                                        Pair<PointerUpdate, CborObject> pair,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        Consumer<String> progressCallback) {
        try {
            return createOurFileTreeOnly(username, staticData, userData, network)
                    .thenCompose(root -> TofuCoreNode.load(username, root, network, crypto)
                            .thenCompose(tofuCorenode -> {
                                return buildTransactionService(root, username, network, crypto)
                                        .thenCompose(transactions -> getMirrorBat(username, signer, login, network)
                                                .thenCompose(mirrorBatId -> buildCapCache(root, username, mirrorBatId.map(BatWithId::id), network, crypto)
                                                        .thenCompose(capCache -> SharedWithCache.initOrBuild(root, username, network, crypto)
                                                                .thenCompose(sharedWith -> {
                                                                    UserContext result = new UserContext(username,
                                                                            signer,
                                                                            boxer,
                                                                            login,
                                                                            network.withCorenode(tofuCorenode),
                                                                            crypto,
                                                                            new CommittedWriterData(pair.left.updated, userData, pair.left.sequence),
                                                                            root,
                                                                            transactions,
                                                                            capCache,
                                                                            sharedWith,
                                                                            mirrorBatId);

                                                                    return result.init(progressCallback)
                                                                            .exceptionally(Futures::logAndThrow);
                                                                }))));
                            }));
        } catch (Throwable t) {
            throw new IllegalStateException("Incorrect password");
        }
    }

    @JsMethod
    public static CompletableFuture<UserContext> signUp(String username,
                                                        String password,
                                                        String token,
                                                        Optional<String> existingIdentity,
                                                        Consumer<String> identityStorer,
                                                        Optional<Function<PaymentProperties, CompletableFuture<Long>>> addCard,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        Consumer<String> progressCallback) {
        // set claim expiry to two months from now
        LocalDate expiry = LocalDate.now().plusMonths(2);
        SecretGenerationAlgorithm algorithm = SecretGenerationAlgorithm.getDefault(crypto.random);
        Optional<SigningKeyPair> existingKeyPair = existingIdentity.map(ArrayOps::hexToBytes)
                .map(CborObject::fromByteArray)
                .map(SigningKeyPair::fromCbor);
        return signUpGeneral(username, password, token, existingKeyPair, identityStorer,
                addCard.map(f -> (p, i) -> f.apply(p).thenCompose(s -> signSpaceRequest(username, i, s))),
                expiry, network, crypto, algorithm, progressCallback);
    }

    /** Ensure we have signed the current peerid for our home server, verifying any key rotations
     *
     * @return whether we updated anything
     */
    @JsMethod
    public CompletableFuture<Boolean> ensureCurrentHost() {
        return network.coreNode.getChain(username)
                .thenCompose(chain -> network.dhtClient.ids()
                        .thenCompose(ids -> {
                            Multihash pkiCurrent = chain.get(chain.size() - 1).claim.storageProviders.get(0).bareMultihash();
                            List<Multihash> peerIds = ids.stream().map(c -> c.bareMultihash()).collect(Collectors.toList());
                            boolean onHome = peerIds.contains(pkiCurrent);
                            Multihash latest = peerIds.get(peerIds.size() - 1);
                            if (! onHome || latest.equals(pkiCurrent))
                                return Futures.of(false);
                            // Need to check peerid chain and update our pki entry
                            return getAndVerifyServerIdChain(pkiCurrent, latest)
                                    .thenCompose(x -> updateHostInPki(username, signer, LocalDate.now().plusMonths(2), latest, crypto.hasher, network));
                        }));
    }

    public CompletableFuture<Boolean> getAndVerifyServerIdChain(Multihash from, Multihash to) {
        return network.dhtClient.getIpnsEntry(from)
                .thenCompose(res -> validateResolutionRecord(res, from))
                .thenCompose(record -> {
                    if (record.host.isEmpty() || ! record.moved)
                        return Futures.errored(new IllegalStateException("Invalid server id update!"));
                    if (record.host.get().equals(to))
                        return Futures.of(true);
                    return getAndVerifyServerIdChain(record.host.get(), to);
                });
    }

    public CompletableFuture<ResolutionRecord> validateResolutionRecord(IpnsEntry signedRecord, Multihash signer) {
        return signedRecord.getValue(signer, crypto);
    }

    private static CompletableFuture<byte[]> signSpaceRequest(String username, SigningPrivateKeyAndPublicHash identity, long desiredQuota) {
        SpaceUsage.SpaceRequest req = new SpaceUsage.SpaceRequest(username, desiredQuota, System.currentTimeMillis(), Optional.empty());
        return identity.secret.signMessage(req.serialize());
    }

    public static CompletableFuture<UserContext> signUp(String username,
                                                        String password,
                                                        String token,
                                                        NetworkAccess network,
                                                        Crypto crypto) {
        // set claim expiry to two months from now
        LocalDate expiry = LocalDate.now().plusMonths(2);
        SecretGenerationAlgorithm algorithm = SecretGenerationAlgorithm.getDefault(crypto.random);
        return signUpGeneral(username, password, token, Optional.empty(), id -> {}, Optional.empty(), expiry,
                network, crypto, algorithm, t -> {});
    }

    public static CompletableFuture<UserContext> signUpGeneral(String username,
                                                               String password,
                                                               String token,
                                                               Optional<SigningKeyPair> existingIdentity,
                                                               Consumer<String> tmpIdentityStore,
                                                               Optional<BiFunction<PaymentProperties, SigningPrivateKeyAndPublicHash, CompletableFuture<byte[]>>> addCard,
                                                               LocalDate expiry,
                                                               NetworkAccess initialNetwork,
                                                               Crypto crypto,
                                                               SecretGenerationAlgorithm algorithm,
                                                               Consumer<String> progressCallback) {
        // Using a local OpLog that doesn't commit anything allows us to group all the updates into a single atomic call
        OpLog opLog = new OpLog(new ArrayList<>(), null, Optional.empty());
        BufferedNetworkAccess network = NetworkAccess.nonCommittingForSignup(opLog, opLog, opLog, opLog, crypto.hasher);
        network.synchronizer.setFlusher((o, v, w) -> Futures.of(v)); // disable final commit
        progressCallback.accept("Generating keys");
        return initialNetwork.coreNode.getChain(username)
                .thenApply(existing -> {
                    if (existing.size() > 0)
                        throw new IllegalStateException("User already exists!");
                    return true;
                })
                .thenCompose(x -> UserUtil.generateUser(username, password, crypto.hasher, crypto.symmetricProvider,
                        crypto.random, crypto.signer, crypto.boxer, algorithm))
                .thenCompose(userWithRoot -> {
                    PublicSigningKey loginPublicKey = userWithRoot.getUser().publicSigningKey;

                    boolean isLegacy = algorithm.generateBoxerAndIdentity();
                    SigningKeyPair identityPair = isLegacy ?
                            userWithRoot.getUser() :
                            existingIdentity.orElseGet(() -> SigningKeyPair.random(crypto.random, crypto.signer));
                    if (addCard.isPresent())
                        tmpIdentityStore.accept(ArrayOps.bytesToHex(identityPair.serialize()));
                    PublicKeyHash identityHash = ContentAddressedStorage.hashKey(identityPair.publicSigningKey);
                    SigningPrivateKeyAndPublicHash identity = new SigningPrivateKeyAndPublicHash(identityHash, identityPair.secretSigningKey);

                    Optional<BoxingKeyPair> boxer = isLegacy ? Optional.empty() : Optional.of(userWithRoot.getBoxingPair());
                    Optional<SigningKeyPair> loginDataIdentity = isLegacy ? Optional.empty() : Optional.of(identityPair);
                    UserStaticData entryData = new UserStaticData(Collections.emptyList(), userWithRoot.getRoot(), loginDataIdentity, boxer);
                    progressCallback.accept("Registering username");
                    Bat mirror = Bat.random(crypto.random);
                    return BatId.sha256(mirror, crypto.hasher)
                            .thenCompose(batid -> opLog.addBat(username, batid, mirror, identity)
                                    .thenCompose(b -> initialNetwork.dhtClient.id()
                            .thenCompose(id -> UserPublicKeyLink.createInitial(identity, username, expiry, Arrays.asList(id)))
                            .thenCompose(chain -> IpfsTransaction.call(identityHash, tid -> identityPair.secretSigningKey.signMessage(identityPair.publicSigningKey.serialize())
                                    .thenCompose(signedIdentity -> network.dhtClient.putSigningKey(
                                    signedIdentity,
                                    identityHash,
                                    identityPair.publicSigningKey, tid)).thenCompose(returnedIdentityHash -> {
                                PublicBoxingKey publicBoxingKey = userWithRoot.getBoxingPair().publicBoxingKey;
                                return crypto.hasher.sha256(publicBoxingKey.serialize())
                                        .thenCompose(boxerHash -> identityPair.secretSigningKey.signMessage(boxerHash)
                                                .thenCompose(signedBoxerHash -> network.dhtClient.putBoxingKey(identityHash,
                                                        signedBoxerHash, publicBoxingKey, tid)));
                                            }).thenCompose(boxerHash -> {
                                progressCallback.accept("Creating filesystem");
                                return WriterData.createIdentity(identityHash,
                                        identity,
                                        Optional.of(new PublicKeyHash(boxerHash)),
                                        isLegacy ? Optional.of(entryData) : Optional.empty(),
                                        algorithm,
                                        network.dhtClient, network.hasher, tid).thenCompose(newUserData -> {

                                    CommittedWriterData notCommitted = new CommittedWriterData(MaybeMultihash.empty(), newUserData, Optional.empty());
                                    network.synchronizer.put(identity.publicKeyHash, identity.publicKeyHash, notCommitted);
                                    return network.synchronizer.applyComplexUpdate(identityHash, identity,
                                            (s, committer) -> committer.commit(identityHash, identity, newUserData, notCommitted, tid));
                                });
                            }), network.dhtClient)
                                    .thenCompose(snapshot -> {
                                        LOG.info("Creating user's root directory");
                                        long t1 = System.currentTimeMillis();
                                        return createEntryDirectory(identity, username, entryData, loginPublicKey, userWithRoot.getRoot(), Optional.of(batid), network, crypto)
                                                .thenCompose(globalRoot -> {
                                                    LOG.info("Creating root directory took " + (System.currentTimeMillis() - t1) + " mS");
                                                    return createSpecialDirectory(globalRoot, username, SHARED_DIR_NAME, Optional.of(batid), network, crypto);
                                                })
                                                .thenCompose(globalRoot -> createSpecialDirectory(globalRoot, username,
                                                        TRANSACTIONS_DIR_NAME, Optional.of(batid), network, crypto))
                                                .thenCompose(globalRoot -> createSpecialDirectory(globalRoot, username,
                                                        CapabilityStore.CAPABILITY_CACHE_DIR, Optional.of(batid), network, crypto))
                                                .thenCompose(x -> network.commit(identityHash))
                                                .thenCompose(c -> completeSignup(username, chain, identity, token, addCard, progressCallback, opLog, initialNetwork, crypto))
                                                .thenCompose(y -> signIn(username, userWithRoot, mfa -> null, initialNetwork, crypto, progressCallback));
                                    }))));
                }).exceptionally(Futures::logAndThrow);
    }

    private static CompletableFuture<Boolean> completeSignup(String username,
                                                             List<UserPublicKeyLink> chain,
                                                             SigningPrivateKeyAndPublicHash identity,
                                                             String token,
                                                             Optional<BiFunction<PaymentProperties, SigningPrivateKeyAndPublicHash, CompletableFuture<byte[]>>> addCard,
                                                             Consumer<String> progressCallback,
                                                             OpLog opLog,
                                                             NetworkAccess initialNetwork,
                                                             Crypto crypto) {
        return signupWithRetry(chain.get(0), addCard.isPresent() ?
                        proof -> initialNetwork.coreNode.startPaidSignup(username, chain.get(0), proof)
                                .thenCompose(toPayOrRetry -> toPayOrRetry.isB() ?
                                        Futures.of(Optional.of(toPayOrRetry.b())) :
                                        addCard.get().apply(toPayOrRetry.a(), identity)
                                                .thenCompose(signedSpaceReq -> initialNetwork.coreNode.completePaidSignup(username, chain.get(0), opLog, signedSpaceReq, proof)
                                                        .thenCompose(paid -> paid.error.isPresent() ?
                                                                Futures.<Optional<RequiredDifficulty>>errored(new RuntimeException(paid.error.get())) :
                                                                retryUntilPositiveQuota(initialNetwork, identity,
                                                                        () -> initialNetwork.coreNode.completePaidSignup(username, chain.get(0), opLog, signedSpaceReq, proof), 1_000, 5).thenApply(z -> Optional.<RequiredDifficulty>empty())))) :
                        proof -> initialNetwork.coreNode.signup(username, chain.get(0), opLog, proof, token),
                crypto.hasher, progressCallback);
    }

    private static CompletableFuture<Boolean> retryUntilPositiveQuota(NetworkAccess network,
                                                                          SigningPrivateKeyAndPublicHash identity,
                                                                          Supplier<CompletableFuture<PaymentProperties>> retry,
                                                                          long sleepMillis,
                                                                          int attemptsLeft) {
        return TimeLimitedClient.signNow(identity.secret)
                .thenCompose(signedTime -> network.spaceUsage.getQuota(identity.publicKeyHash, signedTime))
                .thenCompose(quota -> {
                    if (quota > 0)
                        return Futures.of(true);
                    if (attemptsLeft <= 0)
                        return Futures.errored(new IllegalStateException("Unable to complete paid signup. Did you add your payment card?"));
                    try {Thread.sleep(sleepMillis);} catch (InterruptedException e) {}
                    return retry.get()
                            .thenCompose(b -> retryUntilPositiveQuota(network, identity, retry, sleepMillis * 2, attemptsLeft - 1));
                });
    }

    @JsMethod
    public CompletableFuture<Boolean> ensureUsernameClaimRenewed() {
        return getUsernameClaimExpiry()
                .thenCompose(expiry -> expiry.isBefore(LocalDate.now().plusMonths(1)) ?
                        renewUsernameClaim(LocalDate.now().plusMonths(2)) :
                        CompletableFuture.completedFuture(true));
    }

    private static CompletableFuture<TrieNode> createSpecialDirectory(TrieNode globalRoot,
                                                                      String username,
                                                                      String dirName,
                                                                      Optional<BatId> mirrorBatId,
                                                                      NetworkAccess network,
                                                                      Crypto crypto) {
        return globalRoot.getByPath(username, crypto.hasher, network)
                .thenCompose(root -> root.get().mkdir(dirName, network, true, mirrorBatId, crypto))
                .thenApply(x -> globalRoot);
    }

    @JsType
    public static class EncryptedURL {
        public static final int PAD_TO_SIZE = 50;
        public final String base64Nonce, base64Ciphertext;

        public EncryptedURL(String base64Nonce, String base64Ciphertext) {
            this.base64Nonce = base64Nonce;
            this.base64Ciphertext = base64Ciphertext;
        }
    }

    @JsMethod
    public EncryptedURL encryptURL(String url) {
        // pad url to avoid leaking length
        while (url.length() % EncryptedURL.PAD_TO_SIZE != 0)
            url = url + " ";
        byte[] nonce = rootKey.createNonce();
        byte[] cipherText = rootKey.encrypt(url.getBytes(), nonce);
        Base64.Encoder encoder = Base64.getEncoder();
        return new EncryptedURL(encoder.encodeToString(nonce), encoder.encodeToString(cipherText));
    }

    @JsMethod
    public String decryptURL(String cipherTextBase64, String nonceBase64) {
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] nonce = decoder.decode(nonceBase64);
        byte[] ciphertext = decoder.decode(cipherTextBase64);
        return new String(rootKey.decrypt(ciphertext, nonce)).trim();
    }

    /**
     *
     * @param friendName
     * @return a pair of the friends keys used to generate the fingerprint, and the resulting fingerprint
     */
    @JsMethod
    public CompletableFuture<Pair<List<PublicKeyHash>, FingerPrint>> generateFingerPrint(String friendName) {
        return getPublicKeyHashes(username)
                .thenCompose(ourKeys -> getPublicKeyHashes(friendName)
                        .thenApply(friendKeysPair -> {
                            PublicKeyHash friendId = friendKeysPair.left;
                            PublicKeyHash friendBox = friendKeysPair.right;
                            List<PublicKeyHash> friendKeys = Arrays.asList(friendId, friendBox);
                            return new Pair<>(friendKeys, FingerPrint.generate(
                                    username,
                                    Arrays.asList(ourKeys.left, ourKeys.right),
                                    friendName,
                                    friendKeys,
                                    crypto.hasher));
                        }));
    }

    @JsMethod
    public boolean isSecretLink() {
        return username == null;
    }

    @JsMethod
    public static CompletableFuture<UserContext> fromSecretLinkV2(String linkString,
                                                                  Supplier<CompletableFuture<String>> userPassword,
                                                                  NetworkAccess network,
                                                                  Crypto crypto) {
        SecretLink link = SecretLink.fromLink(linkString);
        return network.getSecretLink(link)
                .thenCompose(retrieved -> (retrieved.hasUserPassword ? userPassword.get() : Futures.of(""))
                        .thenCompose(upass -> retrieved.decryptFromPassword(link.labelString(), link.linkPassword + upass, crypto)))
                .thenCompose(cap -> fromSecretLink(cap, network, crypto));
    }

    @JsMethod
    public static CompletableFuture<UserContext> fromSecretLink(String link, NetworkAccess network, Crypto crypto) {
        AbsoluteCapability cap;
        try {
            cap = AbsoluteCapability.fromLink(link);
        } catch (Exception e) { //link was invalid
            CompletableFuture<UserContext> invalidLink = new CompletableFuture<>();
            invalidLink.completeExceptionally(e);
            return invalidLink;
        }
        return fromSecretLink(cap, network, crypto);
    }

    private static CompletableFuture<UserContext> fromSecretLink(AbsoluteCapability cap, NetworkAccess network, Crypto crypto) {
        WriterData empty = new WriterData(cap.owner,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Collections.emptyMap(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        CommittedWriterData userData = new CommittedWriterData(MaybeMultihash.empty(), empty, Optional.empty());
        UserContext context = new UserContext(null, null, null, null, network,
                crypto, userData, TrieNodeImpl.empty(), null, null,
                new SharedWithCache(null, null, network, crypto), Optional.empty());
        return buildTrieFromCap(cap, context.entrie, network, crypto)
                .thenApply(trieNode -> {
                    context.entrie = trieNode;
                    return context;
                });
    }

    private static CompletableFuture<TrieNode> buildTrieFromCap(AbsoluteCapability cap,
                                                                TrieNode currentRoot,
                                                                NetworkAccess network,
                                                                Crypto crypto) {
        EntryPoint entry = new EntryPoint(cap, "");
        return NetworkAccess.retrieveEntryPoint(entry, network)
                .thenCompose(r -> entry.isValid(r.getPath(), network).thenApply(valid -> {
                    if (! valid)
                        throw new IllegalStateException("Invalid link!");
                    return currentRoot.put(r.getPath(), entry);
                }));
    }
    @JsMethod
    public String getLinkString(LinkProperties props) {
        return props.toLinkString(signer.publicKeyHash);
    }

    @JsMethod
    public CompletableFuture<LinkProperties> createSecretLink(String filePath,
                                                              boolean isWritable,
                                                              Optional<LocalDateTime> expiry,
                                                              String maxRetrievals,
                                                              String userPassword) {
        return createSecretLink(filePath, isWritable, expiry,
                maxRetrievals.isEmpty() ? Optional.empty() : Optional.of(Integer.parseInt(maxRetrievals)),
                userPassword);
    }

    public CompletableFuture<LinkProperties> createSecretLink(String filePath,
                                                              boolean isWritable,
                                                              Optional<LocalDateTime> expiry,
                                                              Optional<Integer> maxRetrievals,
                                                              String userPassword) {
        SecretLink res = SecretLink.create(signer.publicKeyHash, crypto.random);
        LinkProperties props = new LinkProperties(res.label, res.linkPassword, userPassword, isWritable, maxRetrievals, expiry, Optional.empty());
        Path toFile = PathUtil.get(filePath);
        if (! isWritable)
            return updateSecretLink(filePath, props);
        return getByPath(toFile.getParent())
                .thenCompose(parent -> parent.get().getChild(toFile.getFileName().toString(), crypto.hasher, network)
                        .thenCompose(fopt -> shareWriteAccessWith(toFile, Collections.emptySet())))
                .thenCompose(s -> writeSynchronizer.applyComplexComputation(signer.publicKeyHash, signer, (v, c) -> updateSecretLink(filePath, props, v.mergeAndOverwriteWith(s), c)))
                .thenApply(x -> x.right);
    }

    @JsMethod
    public CompletableFuture<LinkProperties> updateSecretLink(String filePath,
                                                              LinkProperties props) {
        return writeSynchronizer.applyComplexComputation(signer.publicKeyHash, signer, (v, c) -> updateSecretLink(filePath, props, v, c))
                .thenApply(p -> p.right);
    }

    private CompletableFuture<Pair<Snapshot, LinkProperties>> updateSecretLink(String filePath,
                                                                               LinkProperties props,
                                                                               Snapshot v1,
                                                                               Committer c) {
        // put encrypted secret link in champ on identity, champ root must have mirror bat to make it private
        PublicKeyHash id = signer.publicKeyHash;
        return getByPath(filePath, v1)
                .thenApply(Optional::get)
                .thenCompose(file -> {
                    boolean differentWriter = file.getPointer().getParentCap().writer.map(parentWriter -> ! parentWriter.equals(file.writer())).orElse(false);
                    if (props.isLinkWritable && ! differentWriter)
                        throw new IllegalStateException("To generate a writable secret link, the target must already be in a different writing space!");
                    AbsoluteCapability cap = props.isLinkWritable ? file.getPointer().capability : file.getPointer().capability.readOnly();
                    SecretLink res = new SecretLink(id, props.label, props.linkPassword);
                    String fullPassword = props.linkPassword + props.userPassword;
                    return EncryptedCapability.createFromPassword(cap, res.labelString(), fullPassword, !props.userPassword.isEmpty(), crypto)
                            .thenApply(payload -> new SecretLinkTarget(payload, props.expiry, props.maxRetrievals))
                            .thenCompose(value -> IpfsTransaction.call(id,
                                    tid -> v1.withWriter(id, id, network).thenCompose(v2 -> v2.get(id).props.get().addLink(signer, props.label, value,
                                                    props.existing.map(CborObject.CborMerkleLink::new), mirrorBat, tid, network.dhtClient, network.hasher)
                                            .thenCompose(p -> c.commit(id, signer, p.left, v2.get(id), tid)
                                                    .thenCompose(s -> sharedWithCache.addSecretLink(PathUtil.get(filePath),
                                                            props.withExisting(Optional.of(p.right)), v2.mergeAndOverwriteWith(s), c, network))
                                                    .thenApply(s -> new Pair<>(s, props.withExisting(Optional.of(p.right)))))), network.dhtClient));
                });
    }
    @JsMethod
    public CompletableFuture<Snapshot> deleteSecretLink(long label, Path toFile, boolean isWritable) {
        PublicKeyHash id = signer.publicKeyHash;
        return writeSynchronizer.applyComplexUpdate(id, signer,
                        (v, c) -> IpfsTransaction.call(id,
                                tid -> v.get(id).props.get().removeLink(signer, label, mirrorBat, tid, network.dhtClient, network.hasher)
                                        .thenCompose(wd -> c.commit(id, signer, wd, v.get(id), tid))
                                        .thenCompose(s -> sharedWithCache.removeSecretLink(toFile, label, s, c, network)), network.dhtClient));
    }

    public static CompletableFuture<AbsoluteCapability> getPublicCapability(Path originalPath, NetworkAccess network) {
        String ownerName = originalPath.getName(0).toString();

        return network.coreNode.getPublicKeyHash(ownerName).thenCompose(ownerOpt -> {
            if (!ownerOpt.isPresent())
                throw new IllegalStateException("Owner doesn't exist for path " + originalPath);
            PublicKeyHash owner = ownerOpt.get();
            return WriterData.getWriterData(owner, owner, network.mutable, network.dhtClient).thenCompose(userData -> {
                Optional<Multihash> publicData = userData.props.get().publicData;
                if (! publicData.isPresent())
                    throw new IllegalStateException("User " + ownerName + " has not made any files public.");

                return network.dhtClient.get(owner, (Cid)publicData.get(), Optional.empty())
                        .thenCompose(rootCbor -> InodeFileSystem.build(owner, rootCbor.get(), network.hasher, network.dhtClient))
                        .thenCompose(publicCaps -> publicCaps.getByPath(originalPath.toString()))
                        .thenApply(resOpt -> {
                            if (resOpt.isEmpty() || resOpt.get().left.cap.isEmpty())
                                throw new IllegalStateException("User " + ownerName + " has not published a file at " + originalPath);
                            return resOpt.get().left.cap.get();
                        });
            });
        });
    }

    @JsMethod
    public CompletableFuture<Optional<FileWrapper>> getPublicFile(Path file) {
        FileProperties.ensureValidParsedPath(file);
        return getPublicCapability(file, network)
                .thenCompose(cap -> buildTrieFromCap(cap, TrieNodeImpl.empty(), network, crypto)
                .thenCompose(t -> t.getByPath(file.toString(), crypto.hasher, network)))
                .exceptionally(e -> Optional.empty());
    }

    @JsMethod
    public CompletableFuture<String> getEntryPath() {
        if (username != null)
            return CompletableFuture.completedFuture("/");

        CompletableFuture<Optional<FileWrapper>> dir = getByPath("/");
        return dir.thenCompose(opt -> getLinkPath(opt.get()))
                .thenApply(path -> path.substring(1)); // strip off extra slash at root
    }

    private CompletableFuture<String> getLinkPath(FileWrapper file) {
        if (!file.isDirectory())
            return CompletableFuture.completedFuture("");
        return file.getChildren(crypto.hasher, network)
                .thenCompose(children -> {
                    if (children.size() != 1)
                        return CompletableFuture.completedFuture(file.getName());
                    FileWrapper child = children.stream().findAny().get();
                    if (child.isReadable()) // case where a directory was shared with exactly one direct child
                        return CompletableFuture.completedFuture(file.getName() + (child.isDirectory() ?
                                "/" + child.getName() : ""));
                    return getLinkPath(child)
                            .thenApply(p -> file.getName() + (p.length() > 0 ? "/" + p : ""));
                });
    }

    private CompletableFuture<UserContext> init(Consumer<String> progressCallback) {
        progressCallback.accept("Retrieving Friends");
        return writeSynchronizer.getValue(signer.publicKeyHash, signer.publicKeyHash)
                .thenCompose(wd -> time(() -> createFileTree(entrie, username, network, crypto), "Creating filetree")
                        .thenApply(root -> {
                            this.entrie = root;
                            return this;
                        })
                );
    }

    public static CompletableFuture<Optional<BatWithId>> getMirrorBat(String username,
                                                                      SigningPrivateKeyAndPublicHash identity,
                                                                      SymmetricKey loginRoot,
                                                                      NetworkAccess network) {
        return Futures.asyncExceptionally(
                        () -> network.batCave.getUserBats(username, identity).thenApply(res -> {
                            if (!res.isEmpty() && network.batCache.isPresent())
                                network.batCache.get().setUserBats(username, res, loginRoot);
                            return res;
                        }),
                        t -> {
                            if (network.batCache.isPresent() &&
                            (t.toString().contains("ConnectException") || t.toString().contains("RateLimitException")))
                                return network.batCache.get().getUserBats(username, loginRoot);
                            return Futures.errored(t);
                        })
                .thenApply(bats -> bats.isEmpty() ?
                        Optional.empty() :
                        Optional.of(bats.get(bats.size() - 1)));
    }

    public CompletableFuture<Optional<BatWithId>> getMirrorBat() {
        return getMirrorBat(username, signer, rootKey, network);
    }

    @JsMethod
    public CompletableFuture<Optional<BatId>> ensureMirrorId() {
        return getMirrorBat()
                .thenCompose(current -> {
                    if (current.isPresent())
                        return Futures.of(current.map(BatWithId::id));
                    // generate a mirror bat
                    Bat mirror = Bat.random(crypto.random);
                    return BatId.sha256(mirror, crypto.hasher)
                            .thenCompose(id -> network.batCave.addBat(username, id, mirror, signer)
                                    .thenApply(b -> Optional.of(id)));
                });
    }

    public Optional<BatId> mirrorBatId() {
        return mirrorBat.map(BatWithId::id);
    }

    public CompletableFuture<FileWrapper> getSharingFolder() {
        return getByPath("/" + username + "/shared").thenApply(opt -> opt.get());
    }

    /**
     *
     * @return The pending storage requests on the server we are talking to if we are an admin
     */
    @JsMethod
    public CompletableFuture<List<DecodedSpaceRequest>> getAndDecodePendingSpaceRequests() {
        return getPendingSpaceRequests().thenCompose(this::decodeSpaceRequests);
    }

    /**
     *
     * @return The pending storage requests on the server we are talking to if we are an admin
     */
    @JsMethod
    public CompletableFuture<List<SpaceUsage.LabelledSignedSpaceRequest>> getPendingSpaceRequests() {
        return TimeLimitedClient.signNow(signer.secret)
                .thenCompose(signedTime -> network.dhtClient.id()
                        .thenCompose(id -> network.instanceAdmin.getPendingSpaceRequests(signer.publicKeyHash, id, signedTime)));
    }

    /**
     *
     * @param in raw space requests
     * @return raw space requests paired with their decoded request
     */
    @JsMethod
    public CompletableFuture<List<DecodedSpaceRequest>> decodeSpaceRequests(List<QuotaControl.LabelledSignedSpaceRequest> in) {
        return DecodedSpaceRequest.decodeSpaceRequests(in, network.coreNode, network.dhtClient);
    }

    /**
     *
     * @param req
     * @return true when completed successfully
     */
    @JsMethod
    public CompletableFuture<Boolean> approveSpaceRequest(DecodedSpaceRequest req) {
        return signer.secret.signMessage(req.source.serialize())
                .thenCompose(adminSignedRequest -> network.dhtClient.id()
                        .thenCompose(instanceId -> network.instanceAdmin
                                .approveSpaceRequest(signer.publicKeyHash, instanceId, adminSignedRequest)));
    }

    /**
     *
     * @param req
     * @return true when completed successfully
     */
    @JsMethod
    public CompletableFuture<Boolean> rejectSpaceRequest(DecodedSpaceRequest req) {
        throw new IllegalStateException("Unimplemented!");
//        byte[] adminSignedRequest = signer.secret.signMessage(req.source.serialize());
//        return network.dhtClient.id()
//                .thenCompose(instanceId -> network.instanceAdmin
//                        .rejectSpaceRequest(signer.publicKeyHash, instanceId, adminSignedRequest));
    }

    @JsMethod
    public CompletableFuture<PaymentProperties> getPaymentProperties(boolean newClientSecret) {
        return TimeLimitedClient.signNow(signer.secret)
                .thenCompose(signedTime -> network.spaceUsage.getPaymentProperties(signer.publicKeyHash, newClientSecret, signedTime));
    }

    /**
     *
     * @return The maximum amount of space this user is allowed to use in bytes
     */
    @JsMethod
    public CompletableFuture<Long> getQuota() {
        return TimeLimitedClient.signNow(signer.secret)
                .thenCompose(signedTime -> network.spaceUsage.getQuota(signer.publicKeyHash, signedTime));
    }

    /**
     *
     * @return The total amount of space used by this account in bytes
     */
    @JsMethod
    public CompletableFuture<Long> getSpaceUsage() {
        return TimeLimitedClient.signNow(signer.secret)
                .thenCompose(signedTime -> network.spaceUsage.getUsage(signer.publicKeyHash, signedTime));
    }

    /**
     *
     * @return true when completed successfully
     */
    @JsMethod
    public CompletableFuture<PaymentProperties> requestSpace(long requestedQuota) {
        return network.spaceUsage.requestQuota(username, signer, requestedQuota);
    }

    @JsMethod
    public CompletableFuture<Boolean> isRegistered() {
        LOG.info("isRegistered");
        return network.coreNode.getUsername(signer.publicKeyHash).thenApply(registeredUsername -> {
            LOG.info("got username \"" + registeredUsername + "\"");
            return this.username.equals(registeredUsername);
        });
    }

    @JsMethod
    public CompletableFuture<Boolean> isAvailable() {
        return network.coreNode.getPublicKeyHash(username)
                .thenApply(publicKey -> !publicKey.isPresent());
    }

    public CompletableFuture<LocalDate> getUsernameClaimExpiry() {
        return network.coreNode.getChain(username)
                .thenApply(chain -> chain.get(chain.size() - 1).claim.expiry);
    }

    public CompletableFuture<Boolean> usernameIsExpired() {
        return network.coreNode.getChain(username)
                .thenApply(chain -> UserPublicKeyLink.isExpiredClaim(chain.get(chain.size() - 1)));
    }

    public CompletableFuture<Boolean> renewUsernameClaim(LocalDate expiry) {
        return renewUsernameClaim(username, signer, expiry, crypto.hasher, network);
    }

    private static CompletableFuture<Boolean> signupWithRetry(UserPublicKeyLink chain,
                                                              Function<ProofOfWork, CompletableFuture<Optional<RequiredDifficulty>>> signup,
                                                              Hasher hasher,
                                                              Consumer<String> progressCallback) {
        byte[] data = new CborObject.CborList(Arrays.asList(chain)).serialize();
        return time(() -> hasher.generateProofOfWork(ProofOfWork.MIN_DIFFICULTY, data), "Proof of work")
                .thenCompose(signup)
                .thenCompose(diff -> {
                    if (diff.isPresent()) {
                        progressCallback.accept("The server is currently under load, retrying...");
                        return time(() -> hasher.generateProofOfWork(diff.get().requiredDifficulty, data), "Proof of work")
                                .thenCompose(signup)
                                .thenApply(d -> {
                                    if (d.isPresent())
                                        throw new IllegalStateException("Server is under load please try again later");
                                    return true;
                                });
                    }
                    return Futures.of(true);
                });
    }

    private static CompletableFuture<Boolean> updateChainWithRetry(String username,
                                                                   List<UserPublicKeyLink> claimChain,
                                                                   String token,
                                                                   Hasher hasher,
                                                                   NetworkAccess network,
                                                                   Consumer<String> progressCallback) {
        byte[] data = new CborObject.CborList(claimChain).serialize();
        return time(() -> hasher.generateProofOfWork(ProofOfWork.MIN_DIFFICULTY, data), "Proof of work")
                .thenCompose(proof -> network.coreNode.updateChain(username, claimChain, proof, token))
                .thenCompose(diff -> {
                    if (diff.isPresent()) {
                        progressCallback.accept("The server is currently under load, retrying...");
                        return time(() -> hasher.generateProofOfWork(diff.get().requiredDifficulty, data), "Proof of work")
                                .thenCompose(proof -> network.coreNode.updateChain(username, claimChain, proof, token))
                                .thenApply(d -> {
                                    if (d.isPresent())
                                        throw new IllegalStateException("Server is under load please try again later");
                                    return true;
                                });
                    }
                    return Futures.of(true);
                });
    }

    public static CompletableFuture<Boolean> updateHostInPki(String username,
                                                             SigningPrivateKeyAndPublicHash signer,
                                                             LocalDate expiry,
                                                             Multihash newHost,
                                                             Hasher hasher,
                                                             NetworkAccess network) {
        LOG.info("updating host for username: " + username + " to " + newHost);
        return network.coreNode.getChain(username).thenCompose(existing -> {
            List<Multihash> storage = Arrays.asList(new Cid(1, Cid.Codec.LibP2pKey, newHost.type, newHost.getHash()));
            return UserPublicKeyLink.Claim.build(username, signer.secret, expiry, storage).thenCompose(newClaim -> {
                List<UserPublicKeyLink> updated = new ArrayList<>(existing.subList(0, existing.size() - 1));
                updated.add(new UserPublicKeyLink(signer.publicKeyHash, newClaim, Optional.empty()));
                return updateChainWithRetry(username, updated, "", hasher, network, x -> {
                });
            });
        });
    }

    public static CompletableFuture<Boolean> renewUsernameClaim(String username,
                                                                SigningPrivateKeyAndPublicHash signer,
                                                                LocalDate expiry,
                                                                Hasher hasher,
                                                                NetworkAccess network) {
        LOG.info("renewing username: " + username + " with expiry " + expiry);
        return network.coreNode.getChain(username).thenCompose(existing -> {
            UserPublicKeyLink last = existing.get(existing.size() - 1);
            List<Multihash> storage = last.claim.storageProviders;
            return UserPublicKeyLink.Claim.build(username, signer.secret, expiry, storage).thenCompose(newClaim -> {
                List<UserPublicKeyLink> updated = new ArrayList<>(existing.subList(0, existing.size() - 1));
                updated.add(new UserPublicKeyLink(signer.publicKeyHash, newClaim, Optional.empty()));
                return updateChainWithRetry(username, updated, "", hasher, network, x -> {
                });
            });
        });
    }

    public CompletableFuture<SecretGenerationAlgorithm> getKeyGenAlgorithm() {
        return getWriterData(network, signer.publicKeyHash, signer.publicKeyHash)
                .thenApply(wd -> wd.props.get().generationAlgorithm
                        .orElseThrow(() -> new IllegalStateException("No login algorithm specified in user data!")));
    }

    public CompletableFuture<Optional<PublicKeyHash>> getNamedKey(String name) {
        return getWriterData(network, signer.publicKeyHash, signer.publicKeyHash)
                .thenApply(wd -> wd.props.get().namedOwnedKeys.get(name))
                .thenApply(res -> Optional.ofNullable(res).map(p -> p.ownedKey));
    }

    @JsMethod
    public CompletableFuture<UserContext> changePassword(String oldPassword,
                                                         String newPassword,
                                                         Function<MultiFactorAuthRequest, CompletableFuture<MultiFactorAuthResponse>> mfa) {
        return getKeyGenAlgorithm().thenCompose(alg -> {
            if (oldPassword.equals(newPassword))
                throw new IllegalStateException("You must change to a different password.");
            // Use a new salt, and if this is a legacy account with generated boxer, remove it from generation and use
            // a new generated identity independent of the password
            SecretGenerationAlgorithm newAlgorithm = SecretGenerationAlgorithm.withNewSalt(alg, crypto.random).withoutBoxerOrIdentity();
            // set claim expiry to two months from now
            return changePassword(oldPassword, newPassword, alg, newAlgorithm, LocalDate.now().plusMonths(2), mfa);
        });
    }

    public CompletableFuture<UserContext> changePassword(String oldPassword,
                                                         String newPassword,
                                                         SecretGenerationAlgorithm existingAlgorithm,
                                                         SecretGenerationAlgorithm newAlgorithm,
                                                         LocalDate expiry,
                                                         Function<MultiFactorAuthRequest, CompletableFuture<MultiFactorAuthResponse>> mfa) {
        LOG.info("Changing password and setting expiry to: " + expiry);
        boolean isLegacy = existingAlgorithm.generateBoxerAndIdentity();
        if (! isLegacy && newAlgorithm.generateBoxerAndIdentity())
            throw new IllegalStateException("Cannot migrate from an upgraded style account to a legacy style account!");

        return UserUtil.generateUser(username, oldPassword, crypto.hasher, crypto.symmetricProvider, crypto.random, crypto.signer, crypto.boxer, existingAlgorithm)
                .thenCompose(existingLogin -> {
                    SecretSigningKey existingLoginSecret = existingLogin.getUser().secretSigningKey;
                    if (isLegacy && !existingLoginSecret.equals(this.signer.secret))
                        throw new IllegalArgumentException("Incorrect existing password during change password attempt!");

                    return UserUtil.generateUser(username, newPassword, crypto.hasher, crypto.symmetricProvider,
                            crypto.random, crypto.signer, crypto.boxer, newAlgorithm)
                            .thenCompose(updatedLogin -> {
                                PublicSigningKey newLoginPublicKey = updatedLogin.getUser().publicSigningKey;
                                PublicKeyHash existingOwner = ContentAddressedStorage.hashKey(existingLogin.getUser().publicSigningKey);
                                if (! isLegacy) {
                                    // identity doesn't change here, just need to update the secret UserStaticData
                                    return getLoginData(username, existingLogin.getUser().publicSigningKey, existingLoginSecret, mfa, false, network).thenCompose(usd -> {
                                        UserStaticData.EntryPoints entry = usd.getData(existingLogin.getRoot());
                                        UserStaticData updatedEntry = new UserStaticData(entry.entries, updatedLogin.getRoot(), entry.identity, entry.boxer);
                                        // need to commit new login algorithm too in the same call
                                        return WriterData.getWriterData(signer.publicKeyHash, signer.publicKeyHash, network.mutable, network.dhtClient).thenCompose(cwd -> {
                                            WriterData newIdBlock = cwd.props.get().withAlgorithm(newAlgorithm);
                                            byte[] rawBlock = newIdBlock.serialize();
                                            return crypto.hasher.sha256(rawBlock).thenCompose(blockHash -> {
                                                return signer.secret.signMessage(blockHash)
                                                        .thenCompose(signed -> {
                                                            OpLog.BlockWrite blockWrite = new OpLog.BlockWrite(signer.publicKeyHash, signed, rawBlock, false, Optional.empty());
                                                            MaybeMultihash newHash = MaybeMultihash.of(new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, blockHash));
                                                            PointerUpdate pointerCas = new PointerUpdate(cwd.hash, newHash, PointerUpdate.increment(cwd.sequence));
                                                            return signer.secret.signMessage(pointerCas.serialize())
                                                                    .thenCompose(signedCas -> {
                                                                        OpLog.PointerWrite pointerWrite = new OpLog.PointerWrite(signer.publicKeyHash, signedCas);
                                                                        LoginData updatedLoginData = new LoginData(username, updatedEntry, newLoginPublicKey, Optional.of(new Pair<>(blockWrite, pointerWrite)));
                                                                        return network.account.setLoginData(updatedLoginData, signer)
                                                                                .thenCompose(b -> UserContext.login(username, newIdBlock, entry,
                                                                                        signer, entry.boxer.get(), updatedLogin.getRoot(), new Pair<>(pointerCas, newIdBlock.toCbor()), network.clear(), crypto, p -> {}));
                                                                    });
                                                        });
                                            });
                                        });
                                    });
                                }
                                // upgrading a legacy account to new style
                                BoxingKeyPair newBoxingKeypair = newAlgorithm.generateBoxerAndIdentity() ? updatedLogin.getBoxingPair() : boxer;
                                SigningKeyPair newIdentityPair = SigningKeyPair.random(crypto.random, crypto.signer);
                                PublicSigningKey newIdentityPub = newIdentityPair.publicSigningKey;
                                return existingLoginSecret.signMessage(newIdentityPub.serialize())
                                        .thenCompose(signed -> IpfsTransaction.call(existingOwner,
                                                tid -> network.dhtClient.putSigningKey(
                                                        signed,
                                                        existingOwner,
                                                        newIdentityPub,
                                                        tid),
                                                network.dhtClient
                                        )).thenCompose(newIdentityHash -> {
                                            SigningPrivateKeyAndPublicHash newIdentity =
                                                    new SigningPrivateKeyAndPublicHash(newIdentityHash, newIdentityPair.secretSigningKey);
                                            Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> ownedKeys = new HashMap<>();
                                            return getUserRoot().thenCompose(homeDir -> {
                                                // If we ever implement plausibly deniable dual (N) login this will need to include all the other keys
                                                ownedKeys.put(homeDir.writer(), homeDir.signingPair());
                                                // Add any named owned key to lookup as well
                                                // TODO need to get the pki keypair here if we are the 'peergos' user

                                                // auth new key by adding to existing writer data first
                                                return OwnerProof.build(newIdentity, signer.publicKeyHash).thenCompose(proof -> {
                                                    return writeSynchronizer.applyUpdate(signer.publicKeyHash, signer, (wd, tid) ->
                                                                    wd.addOwnedKey(signer.publicKeyHash, signer, proof, network.dhtClient, network.hasher))
                                                            .thenCompose(version -> version.get(signer).props.get().changeKeys(username,
                                                                    signer,
                                                                    newIdentity,
                                                                    newIdentityPair,
                                                                    newLoginPublicKey,
                                                                    newBoxingKeypair,
                                                                    existingLogin.getRoot(),
                                                                    updatedLogin.getRoot(),
                                                                    newAlgorithm,
                                                                    ownedKeys,
                                                                    network)).thenCompose(writerData -> {
                                                                return network.coreNode.getChain(username).thenCompose(existing -> {
                                                                    List<Multihash> storage = existing.get(existing.size() - 1).claim.storageProviders;
                                                                    return UserPublicKeyLink.createChain(signer, newIdentity, username, expiry, storage).thenCompose(claimChain ->
                                                                                    updateChainWithRetry(username, claimChain, "", crypto.hasher, network, x -> {
                                                                                    }))
                                                                            .thenCompose(updatedChain -> {
                                                                                if (!updatedChain)
                                                                                    throw new IllegalStateException("Couldn't register new public keys during password change!");

                                                                                return UserContext.signIn(username, newPassword, mfas -> null, network, crypto);
                                                                            });
                                                                });
                                                            });
                                                });
                                            });
                                        });
                            });
                });
    }

    private static CompletableFuture<TrieNode> createEntryDirectory(SigningPrivateKeyAndPublicHash owner,
                                                                    String directoryName,
                                                                    UserStaticData current,
                                                                    PublicSigningKey loginPublic,
                                                                    SymmetricKey userRootKey,
                                                                    Optional<BatId> mirrorBatId,
                                                                    NetworkAccess network,
                                                                    Crypto crypto) {
        long t1 = System.currentTimeMillis();
        SigningKeyPair writer = SigningKeyPair.random(crypto.random, crypto.signer);
        LOG.info("Random User generation took " + (System.currentTimeMillis() - t1) + " mS");
        // Steps (All in 1 transaction):
        // 1. Add signing key authorised by owner
        // 2. Commit authorisation for writer to owner WriterData
        // 3. Add empty WriterData for writer
        // 4. Add root directory writer's WriterData
        // 5. Add entry point to root dir to owner WriterData's UserStaticData (legacy account) or LoginData

        byte[] rootMapKey = crypto.random.randomBytes(32); // root will be stored under this label
        Optional<Bat> rootBat = Optional.of(Bat.random(crypto.random));
        SymmetricKey rootRKey = SymmetricKey.random();
        SymmetricKey rootWKey = SymmetricKey.random();
        LOG.info("Random keys generation took " + (System.currentTimeMillis() - t1) + " mS");

        PublicKeyHash preHash = ContentAddressedStorage.hashKey(writer.publicSigningKey);
        SigningPrivateKeyAndPublicHash writerPair =
                new SigningPrivateKeyAndPublicHash(preHash, writer.secretSigningKey);
        WritableAbsoluteCapability rootPointer =
                new WritableAbsoluteCapability(owner.publicKeyHash, preHash, rootMapKey, rootBat, rootRKey, rootWKey);
        EntryPoint entry = new EntryPoint(rootPointer, directoryName);
        return owner.secret.signMessage(writer.publicSigningKey.serialize()).thenCompose(signed -> IpfsTransaction.call(owner.publicKeyHash, tid -> network.dhtClient.putSigningKey(
                signed,
                owner.publicKeyHash,
                writer.publicSigningKey,
                tid).thenCompose(writerHash -> {

            // and authorise the writer key
            return network.synchronizer.applyComplexUpdate(owner.publicKeyHash, owner,
                    (s, committer) -> OwnerProof.build(writerPair, owner.publicKeyHash)
                            .thenCompose(proof -> s.get(owner.publicKeyHash).props.get().addOwnedKeyAndCommit(owner.publicKeyHash, owner,
                                    proof,
                                    s.get(owner.publicKeyHash).hash, s.get(owner.publicKeyHash).sequence, network, committer, tid))
                            .thenCompose(s2 -> {
                                long t2 = System.currentTimeMillis();
                                RelativeCapability nextChunk =
                                        RelativeCapability.buildSubsequentChunk(crypto.random.randomBytes(32), Optional.of(Bat.random(crypto.random)), rootRKey);
                                LocalDateTime timestamp = LocalDateTime.now();
                                return CryptreeNode.createEmptyDir(MaybeMultihash.empty(), rootRKey, rootWKey, Optional.of(writerPair),
                                                new FileProperties(directoryName, true, false, "", 0, timestamp, timestamp,
                                                        false, Optional.empty(), Optional.empty()),
                                                Optional.empty(), SymmetricKey.random(), nextChunk, rootBat, mirrorBatId, crypto.random, crypto.hasher)
                                        .thenCompose(root -> {
                                            LOG.info("Uploading entry point directory");
                                            return WriterData.createEmpty(owner.publicKeyHash, writerPair,
                                                            network.dhtClient, network.hasher, tid)
                                                    .thenCompose(empty -> committer.commit(owner.publicKeyHash, writerPair, empty, new CommittedWriterData(MaybeMultihash.empty(), empty, Optional.empty()), tid))
                                                    .thenCompose(s3 -> root.commit(s3, committer, rootPointer, Optional.of(writerPair), network, tid)
                                                            .thenApply(finalSnapshot -> {
                                                                long t3 = System.currentTimeMillis();
                                                                LOG.info("Uploading root dir metadata took " + (t3 - t2) + " mS");
                                                                return finalSnapshot;
                                                            }))
                                                    .thenCompose(x -> addRootEntryPointAndCommit(x.merge(s2), entry, current, loginPublic, owner, userRootKey, committer, network, tid));
                                        });
                            }));
        }), network.dhtClient).thenApply(s -> TrieNodeImpl.empty().put("/" + directoryName, entry)));
    }

    public CompletableFuture<PublicSigningKey> getSigningKey(PublicKeyHash owner) {
        return network.dhtClient.get(owner, owner, Optional.empty()).thenApply(cborOpt -> cborOpt.map(PublicSigningKey::fromCbor).get());
    }

    public CompletableFuture<PublicBoxingKey> getBoxingKey(PublicKeyHash owner, PublicKeyHash keyhash) {
        return network.dhtClient.get(owner, keyhash, Optional.empty()).thenApply(cborOpt -> cborOpt.map(PublicBoxingKey::fromCbor).get());
    }

    /**
     *
     * @param username
     * @return A pair containing the identity key hash and the boxing key hash of the user
     */
    public CompletableFuture<Pair<PublicKeyHash, PublicKeyHash>> getPublicKeyHashes(String username) {
        return network.coreNode.getPublicKeyHash(username)
                .thenCompose(signerOpt -> {
                    if (! signerOpt.isPresent())
                        throw new IllegalStateException("Couldn't retrieve identity key for " + username);
                    return getSigningKey(signerOpt.get())
                            .thenCompose(signer2 -> getWriterData(network, signerOpt.get(), signerOpt.get())
                                    .thenApply(wd -> new Pair<>(signerOpt.get(), wd.props.get().followRequestReceiver.get())));
                });
    }

    @JsMethod
    public CompletableFuture<Optional<Pair<PublicKeyHash, PublicBoxingKey>>> getPublicKeys(String username) {
        return network.coreNode.getPublicKeyHash(username)
                .thenCompose(signerOpt ->
                        signerOpt.map(signer -> getSigningKey(signer)
                                .thenCompose(signer2 -> getWriterData(network, signerOpt.get(), signerOpt.get())
                                        .thenCompose(wd -> getBoxingKey(signer, wd.props.get().followRequestReceiver.get())
                                                .thenApply(boxer -> Optional.of(new Pair<>(signerOpt.get(), boxer))))))
                                .orElse(CompletableFuture.completedFuture(Optional.empty())));
    }

    public CompletableFuture<CommittedWriterData> addNamedOwnedKeyAndCommit(String keyName,
                                                                            SigningPrivateKeyAndPublicHash owned) {
        return OwnerProof.build(owned, signer.publicKeyHash).thenCompose(proof -> writeSynchronizer.applyUpdate(signer.publicKeyHash, signer,
                        (wd, tid) -> CompletableFuture.completedFuture(wd.addNamedKey(keyName, proof)))
                .thenApply(v -> v.get(signer)));
    }

    @JsMethod
    public CompletableFuture<Boolean> deleteAccount(String password,
                                                    Function<MultiFactorAuthRequest, CompletableFuture<MultiFactorAuthResponse>> mfa) {
        return signIn(username, password, mfa, network, crypto)
                .thenCompose(user -> {
                    // set mutable pointer of root dir writer and owner to EMPTY
                    SigningPrivateKeyAndPublicHash identity = user.signer;
                    PublicKeyHash owner = identity.publicKeyHash;
                    return user.getUserRoot().thenCompose(root -> {
                        SigningPrivateKeyAndPublicHash pair = root.signingPair();
                        CommittedWriterData current = root.getVersionRoot();
                        PointerUpdate cas = new PointerUpdate(current.hash, MaybeMultihash.empty(), PointerUpdate.increment(current.sequence));
                        return pair.secret.signMessage(cas.serialize())
                                .thenCompose(signed -> network.mutable.setPointer(this.signer.publicKeyHash, pair.publicKeyHash, signed));
                    }).thenCompose(x -> network.spaceUsage.requestQuota(username, identity, 1_000_000))
                            .thenCompose(x -> network.mutable.getPointerTarget(owner, owner, network.dhtClient)
                                    .thenCompose(current -> {
                                        PointerUpdate cas = new PointerUpdate(current.updated, MaybeMultihash.empty(), PointerUpdate.increment(current.sequence));
                                        return identity.secret.signMessage(cas.serialize())
                                                .thenCompose(signed -> network.mutable.setPointer(owner, owner, signed));
                                    })
                            );
                });
    }

    public CompletableFuture<Snapshot> unPublishFile(Path path) {
        return sharedWith(path).thenCompose(sharedWithState -> getByPath(path)
                .thenCompose(opt -> {
                    FileWrapper toUnshare = opt.get();
                    return getByPath(path.getParent())
                            .thenCompose(parentOpt -> {
                                FileWrapper parent = parentOpt.get();
                                return removePublicCap(path.toString())
                                        .thenCompose(x -> network.synchronizer.applyComplexUpdate(signer.publicKeyHash,
                                                parent.signingPair(),
                                                (s, c) -> rotateAllKeys(toUnshare, parent, false, s, c)
                                                        .thenCompose(markedDirty ->
                                                                sharedWithCache.removeSharedWith(SharedWithCache.Access.READ, path, sharedWithState.readAccess, markedDirty, c, network)
                                                                        .thenCompose(s2 ->
                                                                                sharedWithCache.removeSharedWith(SharedWithCache.Access.WRITE, path, sharedWithState.writeAccess, s2, c, network))
                                                        )
                                        ));
                            });
                }));
    }

    private CompletableFuture<CommittedWriterData> removePublicCap(String path) {
        return writeSynchronizer.applyUpdate(signer.publicKeyHash, signer, (wd, tid) -> {
            Optional<Multihash> publicData = wd.publicData;
            if (publicData.isEmpty())
                return Futures.of(wd);
            return network.dhtClient.get(signer.publicKeyHash, (Cid)publicData.get(), Optional.empty())
                    .thenCompose(rootCbor -> InodeFileSystem.build(signer.publicKeyHash, rootCbor.get(), crypto.hasher, network.dhtClient))
                    .thenCompose(pubCaps -> pubCaps.removeCap(signer.publicKeyHash, signer, path, tid))
                    .thenCompose(updated -> network.dhtClient.put(signer.publicKeyHash, signer, updated.serialize(), crypto.hasher, tid))
                    .thenApply(newRoot -> wd.withPublicRoot(newRoot));
        }).thenApply(v -> v.get(signer));
    }

    public CompletableFuture<CommittedWriterData> makePublic(FileWrapper file) {
        if (! file.getOwnerName().equals(username))
            return Futures.errored(new IllegalStateException("Only the owner of a file can make it public!"));
        if (file.isUserRoot())
            return Futures.errored(new IllegalStateException("You cannot publish your home directory!"));
        return writeSynchronizer.applyUpdate(signer.publicKeyHash, signer, (wd, tid) -> file.getPath(network).thenCompose(path -> {
            ensureAllowedToShare(file, username, false);
            Optional<Multihash> publicData = wd.publicData;

            CompletableFuture<InodeFileSystem> publicCaps = publicData.isPresent() ?
                    network.dhtClient.get(signer.publicKeyHash, (Cid)publicData.get(), Optional.empty())
                            .thenCompose(rootCbor -> InodeFileSystem.build(signer.publicKeyHash, rootCbor.get(), crypto.hasher, network.dhtClient)) :
                    InodeFileSystem.createEmpty(signer.publicKeyHash, signer, network.dhtClient, crypto.hasher, tid);

            AbsoluteCapability cap = file.getPointer().capability.readOnly();
            return publicCaps.thenCompose(pubCaps -> pubCaps.addCap(signer.publicKeyHash, signer, path, cap, tid))
                    .thenCompose(updated -> network.dhtClient.put(signer.publicKeyHash, signer, updated.serialize(), crypto.hasher, tid))
                    .thenApply(newRoot -> wd.withPublicRoot(newRoot));
        })).thenApply(v -> v.get(signer));
    }

    private static void ensureAllowedToShare(FileWrapper file, String ourname, boolean isWrite) {
        if (file.isUserRoot())
            throw new IllegalStateException("You cannot share your home directory public!");
        if (isWrite && ! file.getOwnerName().equals(ourname))
            throw new IllegalStateException("Only the owner of a file can grant write access!");
    }

    @JsMethod
    public CompletableFuture<Set<FileWrapper>> getFriendRoots() {
        return getChildren("/").thenApply(c -> c.stream()
                .filter(f -> ! f.getName().equals(username))
                .collect(Collectors.toSet()));
    }

    @JsMethod
    public CompletableFuture<Set<String>> getFollowing() {
        return getFriendRoots()
                .thenApply(set -> set.stream()
                        .map(FileWrapper::getOwnerName)
                        .collect(Collectors.toSet()));
    }

    @JsMethod
    public CompletableFuture<Set<String>> getFollowerNames() {
        return getFollowerRoots(true).thenApply(Map::keySet);
    }

    public CompletableFuture<Map<String, FileWrapper>> getFollowerRoots(boolean filterPending) {
        return (filterPending ?
                getPendingOutgoingFollowRequests()
                        .thenApply(p -> p.pendingOutgoingFollowRequests)
                : Futures.of(Collections.<String>emptySet()))
                .thenCompose(pendingOutgoing -> getFollowerRoots(pendingOutgoing));
    }

    private CompletableFuture<Map<String, FileWrapper>> getFollowerRoots(Set<String> pendingOutgoing) {
        return getSharingFolder()
                .thenCompose(sharing -> sharing.getChildren(crypto.hasher, network))
                .thenApply(children -> children.stream()
                        .filter(c -> ! c.getName().startsWith(".") &&
                                ! c.getName().startsWith(GROUPS_FILENAME) &&
                                ! pendingOutgoing.contains(c.getName()))
                        .collect(Collectors.toMap(e -> e.getFileProperties().name, e -> e)));
    }

    public CompletableFuture<Set<FriendSourcedTrieNode>> getFollowingNodes() {
        return Futures.of(entrie.getChildNodes()
                .stream()
                .filter(n -> n instanceof FriendSourcedTrieNode)
                .map(n -> (FriendSourcedTrieNode)n)
                .filter(n -> ! n.ownerName.equals(username))
                .collect(Collectors.toSet()));
    }

    public CompletableFuture<Map<String, FriendAnnotation>> getFriendAnnotations() {
        return getUserRoot()
                .thenCompose(home -> home.hasChild(FRIEND_ANNOTATIONS_FILE_NAME, crypto.hasher, network)
                        .thenCompose(exists ->  {
                            if (! exists)
                                return CompletableFuture.completedFuture(Collections.emptyMap());
                            Map<String, FriendAnnotation> res = new TreeMap<>();
                            return home.getChild(FRIEND_ANNOTATIONS_FILE_NAME, crypto.hasher, network)
                                    .thenCompose(fileOpt ->
                                            fileOpt.get().getInputStream(network, crypto, x -> {})
                                    .thenCompose(reader -> reader.parseStream(FriendAnnotation::fromCbor,
                                            anno -> res.put(anno.getUsername(), anno),
                                            fileOpt.get().getSize())))
                                    .thenApply(x -> res);
                        }));
    }

    @JsMethod
    public CompletableFuture<Boolean> addFriendAnnotation(FriendAnnotation annotation) {
        return getFriendAnnotations().thenCompose(existing -> {
            Map<String, FriendAnnotation> updated = new TreeMap<>(existing);
            updated.put(annotation.getUsername(), annotation);
            List<FriendAnnotation> values = updated.values()
                    .stream()
                    .collect(Collectors.toList());
            ByteArrayOutputStream serialized = new ByteArrayOutputStream();
            for (FriendAnnotation value : values) {
                try {
                    serialized.write(value.serialize());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return getUserRoot().thenCompose(home -> home.uploadFileSection(
                            FRIEND_ANNOTATIONS_FILE_NAME,
                            AsyncReader.build(serialized.toByteArray()),
                            true,
                            0,
                            serialized.size(),
                            Optional.empty(),
                            true,
                            network,
                            crypto,
                            x -> {},
                            crypto.random.randomBytes(32),
                            Optional.empty(),
                            Optional.of(Bat.random(crypto.random)),
                            mirrorBatId()))
                    .thenApply(x -> true);
        });
    }

    public CompletableFuture<Groups> getGroupNameMappings() {
        return FileUtils.getOrCreateObject(this,
                PathUtil.get(username, SHARED_DIR_NAME, GROUPS_FILENAME),
                () -> Groups.generate(crypto.random),
                this::initialiseGroups,
                Cborable.parser(Groups::fromCbor));
    }

    private CompletableFuture<Set<String>> getFriendNames() {
        return getFriendRoots()
                .thenApply(dirs -> dirs.stream()
                        .map(FileWrapper::getName)
                        .collect(Collectors.toSet()));
    }

    private CompletableFuture<Boolean> initialiseGroups(Groups g) {
        return getFollowerNames().thenCompose(followers -> getFriendNames()
                .thenCompose(friends -> {
                    return Futures.reduceAll(g.uidToGroupName.entrySet(), true,
                            (b, e) -> getUserRoot()
                                    .thenCompose(home -> home.getOrMkdirs(PathUtil.get(SHARED_DIR_NAME, e.getKey()), network, true, mirrorBatId(), crypto))
                                    .thenCompose(x -> getUserRoot()
                                            .thenCompose(home -> network.synchronizer.applyComplexUpdate(signer.publicKeyHash, home.signingPair(),
                                                    (s, c) -> shareReadAccessWith(PathUtil.get(username, SHARED_DIR_NAME, e.getKey()),
                                                            e.getValue().equals(SocialState.FOLLOWERS_GROUP_NAME) ? followers : friends, s, c))))
                                    .thenApply(x -> true),
                            (a, b) -> a && b);
                }));
    }

    private CompletableFuture<PendingSocialState> getPendingOutgoingFollowRequests() {
        return getUserRoot()
                .thenCompose(home -> home.hasChild(SOCIAL_STATE_FILENAME, crypto.hasher, network)
                        .thenCompose(exists ->  {
                            if (! exists)
                                return CompletableFuture.completedFuture(PendingSocialState.empty());
                            return home.getChild(SOCIAL_STATE_FILENAME, crypto.hasher, network)
                                    .thenCompose(fileOpt ->
                                            fileOpt.get().getInputStream(network, crypto, x -> {})
                                                    .thenCompose(reader -> Serialize.parse(reader, fileOpt.get().getSize(),
                                                            PendingSocialState::fromCbor)));
                        }));
    }

    private CompletableFuture<Boolean> removeFromPendingOutgoing(String usernameToRemove) {
        return getUserRoot()
                .thenCompose(home -> home.hasChild(SOCIAL_STATE_FILENAME, crypto.hasher, network)
                        .thenCompose(exists ->  {
                            if (! exists)
                                return CompletableFuture.completedFuture(true);
                            return home.getChild(SOCIAL_STATE_FILENAME, crypto.hasher, network)
                                    .thenCompose(fileOpt ->
                                            fileOpt.get().getInputStream(network, crypto, x -> {})
                                                    .thenCompose(reader -> Serialize.parse(reader, fileOpt.get().getSize(),
                                                            PendingSocialState::fromCbor))
                                                    .thenCompose(current -> {
                                                        PendingSocialState updated = current.withoutPending(usernameToRemove);
                                                        byte[] raw = updated.serialize();
                                                        return fileOpt.get().overwriteFile(AsyncReader.build(raw), raw.length,
                                                                network, crypto, x -> {})
                                                                .thenApply(x -> true);
                                                    }));
                        }));
    }

    @JsMethod
    public CompletableFuture<SocialState> getSocialState() {
        return processFollowRequests()
                .thenCompose(pendingIncoming -> getPendingOutgoingFollowRequests()
                        .thenCompose(pendingOutgoing -> getFollowerRoots(pendingOutgoing.pendingOutgoingFollowRequests)
                                .thenCompose(followerRoots -> getFriendRoots().thenCompose(
                                        followingRoots -> getFollowerNames().thenCompose(
                                                followers -> getBlocked().thenCompose(
                                                        blocked -> getFriendAnnotations().thenCompose(
                                                                annotations -> getGroupNameMappings().thenApply(
                                                                        groups -> new SocialState(pendingIncoming,
                                                                                pendingOutgoing.pendingOutgoingFollowRequests,
                                                                                followers, followerRoots, followingRoots,
                                                                                blocked, annotations, groups.uidToGroupName)))))))));
    }

    @JsMethod
    public CompletableFuture<Boolean> sendInitialFollowRequest(String targetUsername) {
        if (username.equals(targetUsername)) {
            return CompletableFuture.completedFuture(false);
        }
        return sendFollowRequest(targetUsername, SymmetricKey.random());
    }

    @JsMethod
    public CompletableFuture<Boolean> sendInitialFollowRequests(String[] targetUsernames) {
        Set<String> usernames = new HashSet<>(Arrays.asList(targetUsernames));
        return Futures.reduceAll(usernames,
                true,
                (b, targetUsername) -> sendFollowRequest(targetUsername, SymmetricKey.random()),
                (a, b) -> a);
    }

    @JsMethod
    public CompletableFuture<Boolean> sendReplyFollowRequest(FollowRequestWithCipherText initialRequestAndRaw, boolean accept, boolean reciprocate) {
        FollowRequest initialRequest = initialRequestAndRaw.req;
        String theirUsername = initialRequest.entry.get().ownerName;
        // if accept, create directory to share with them, note in entry points (they follow us)
        if (!accept && !reciprocate) {
            // send a null entry and absent key (full rejection)
            // write a null entry point and tell them we're not reciprocating with an absent key
            EntryPoint entry = new EntryPoint(AbsoluteCapability.createNull(), username);
            FollowRequest reply = new FollowRequest(Optional.of(entry), Optional.empty());

            return getPublicKeys(initialRequest.entry.get().ownerName).thenCompose(pair -> {
                PublicBoxingKey targetUser = pair.get().right;

                return blindAndSendFollowRequest(initialRequest.entry.get().pointer.owner, targetUser, reply)
                        .thenCompose(b ->
                                // remove pending follow request from them
                                signer.secret.signMessage(initialRequestAndRaw.cipher.serialize())
                                        .thenCompose(signed -> network.social.removeFollowRequest(signer.publicKeyHash, signed))
                        );
            });
        }

        return CompletableFuture.completedFuture(true).thenCompose(b -> {
            if (accept) {
                return getSharingFolder().thenCompose(sharing -> sharing.getChild(theirUsername, crypto.hasher, network)
                        .thenCompose(existingOpt -> {
                            if (existingOpt.isPresent())
                                return Futures.of(existingOpt);
                            return sharing.getChild(theirUsername, crypto.hasher, network)
                                    .thenCompose(existingFriendDir -> {
                                        if (existingFriendDir.isEmpty())
                                            return sharing.mkdir(theirUsername, network, initialRequest.key.get(), Optional.of(Bat.random(crypto.random)), true, mirrorBatId(), crypto)
                                                    .thenCompose(updatedSharing -> updatedSharing.getChild(theirUsername, crypto.hasher, network));
                                        // If we already have a sharing dir for them, don't rotate the keys
                                        return Futures.of(existingFriendDir);
                                    });
                        }).thenCompose(friendRootOpt -> {
                            FileWrapper friendRoot = friendRootOpt.get();
                            // add a note to our entry point store so we know who we sent the read access to
                            EntryPoint entry = new EntryPoint(friendRoot.getPointer().capability.readOnly(),
                                    username);
                            // add them to our followers group
                            return getGroupUid(SocialState.FOLLOWERS_GROUP_NAME)
                                    .thenCompose(followersUidOpt -> shareReadAccessWith(PathUtil.get(username,
                                            SHARED_DIR_NAME, followersUidOpt.get()), Collections.singleton(theirUsername)))
                                    .thenApply(x -> entry);
                        }));
            } else {
                EntryPoint entry = new EntryPoint(AbsoluteCapability.createNull(), username);
                return CompletableFuture.completedFuture(entry);
            }
        }).thenCompose(entry -> {

            Optional<SymmetricKey> baseKey;
            if (!reciprocate) {
                baseKey = Optional.empty(); // tell them we're not reciprocating
            } else {
                // if reciprocate, add entry point to their shared directory (we follow them) and then
                baseKey = Optional.of(initialRequest.entry.get().pointer.rBaseKey); // tell them we are reciprocating
            }
            FollowRequest reply = new FollowRequest(Optional.of(entry), baseKey);

            return (accept && reciprocate ?
                    getGroupUid(SocialState.FRIENDS_GROUP_NAME)
                            .thenCompose(friendsUidOpt -> shareReadAccessWith(PathUtil.get(username,
                                    SHARED_DIR_NAME, friendsUidOpt.get()), Collections.singleton(theirUsername))): // put them in our friends group
                    Futures.of(null))
                    .thenCompose(x -> getPublicKeys(initialRequest.entry.get().ownerName))
                    .thenCompose(pair -> {
                        PublicBoxingKey targetUser = pair.get().right;
                        return blindAndSendFollowRequest(initialRequest.entry.get().pointer.owner, targetUser, reply);
                    });
        }).thenCompose(b -> {
            if (reciprocate)
                return addExternalEntryPoint(initialRequest.entry.get())
                        .thenCompose(x -> retrieveAndAddEntryPointToTrie(entrie, initialRequest.entry.get()));
            return CompletableFuture.completedFuture(entrie);
        }).thenCompose(trie -> {
            // remove original request
            entrie = trie;
            return signer.secret.signMessage(initialRequestAndRaw.cipher.serialize())
                    .thenCompose(signed -> network.social.removeFollowRequest(signer.publicKeyHash, signed));
        });
    }

    /**
     * Send details to allow friend to follow us, and optionally let us follow them
     * create a tmp keypair whose public key we can prepend to the request without leaking information
     *
     * @param targetIdentity
     * @param targetBoxer
     * @param req
     * @return
     */
    private CompletableFuture<Boolean> blindAndSendFollowRequest(PublicKeyHash targetIdentity, PublicBoxingKey targetBoxer, FollowRequest req) {
        BlindFollowRequest blindRequest = BlindFollowRequest.build(targetBoxer, req, crypto.random, crypto.boxer);
        return network.social.sendFollowRequest(targetIdentity, blindRequest.serialize());
    }

    public CompletableFuture<Boolean> sendFollowRequest(String targetUsername, SymmetricKey requestedKey) {
        return getSharingFolder().thenCompose(sharing -> {
            // check for them not reciprocating
            return getFollowing().thenCompose(following -> {
                boolean alreadyFollowing = following.stream()
                        .filter(x -> x.equals(targetUsername))
                        .findAny()
                        .isPresent();
                if (alreadyFollowing) {
                    return Futures.errored(new Exception("User " + targetUsername +" is already a follower!"));
                }
                return getPublicKeys(targetUsername).thenCompose(targetUserOpt -> {
                    if (! targetUserOpt.isPresent()) {
                        return Futures.errored(new Exception("User " + targetUsername + " does not exist!"));
                    }
                    PublicBoxingKey targetUser = targetUserOpt.get().right;
                    return sharing.getOrMkdirs(PathUtil.get(targetUsername), network, true, mirrorBatId(), crypto)
                            .thenCompose(friendRoot -> {

                                EntryPoint entry = new EntryPoint(friendRoot.getPointer().capability.readOnly(), username);
                                FollowRequest followReq = new FollowRequest(Optional.of(entry), Optional.ofNullable(requestedKey));

                                PublicKeyHash targetSigner = targetUserOpt.get().left;
                                return getPendingOutgoingFollowRequests()
                                        .thenCompose(pending -> blindAndSendFollowRequest(targetSigner, targetUser, followReq)
                                                .thenCompose(b -> {
                                                    // note that we have a pending request sent to them
                                                    PendingSocialState updated = pending.withPending(targetUsername);
                                                    byte[] raw = updated.toCbor().serialize();
                                                    return getUserRoot().thenCompose(home -> home.uploadFileSection(
                                                                    SOCIAL_STATE_FILENAME, AsyncReader.build(raw), true, 0, raw.length, Optional.empty(),
                                                                    true, network, crypto, x -> {}, crypto.random.randomBytes(32),
                                                                    Optional.empty(), Optional.of(Bat.random(crypto.random)), mirrorBatId()))
                                                            .thenApply(x -> b);
                                                }));
                            });
                });
            });
        });
    }

    @JsMethod
    public CompletableFuture<SocialFeed> getSocialFeed() {
        return getByPath(PathUtil.get(username, FEED_DIR_NAME))
                .thenCompose(feedDirOpt -> {
                    if (feedDirOpt.isEmpty())
                        return SocialFeed.create(this);
                    return SocialFeed.load(feedDirOpt.get(), this);
                });
    }

    public CompletableFuture<Boolean> unShareReadAccess(Path path, String readerToRemove) {
        return unShareReadAccessWith(path, Collections.singleton(readerToRemove)).thenApply(x -> true);
    }

    public CompletableFuture<Boolean> unShareWriteAccess(Path path, String writerToRemove) {
        return unShareWriteAccessWith(path, Collections.singleton(writerToRemove)).thenApply(x -> true);
    }

    private CompletableFuture<Snapshot> rotateAllKeys(FileWrapper file,
                                                      FileWrapper parent,
                                                      boolean rotateSigners,
                                                      Snapshot initial,
                                                      Committer c) {
        // 1) rotate all the symmetric keys and optionally signers
        // 2) if parent signer is different, add a link node pointing to the new child
        // 2) update parent pointer to new child/link
        // 3) delete old subtree
        PublicKeyHash owner = parent.owner();
        SigningPrivateKeyAndPublicHash parentSigner = parent.signingPair();
        AbsoluteCapability parentCap = parent.getPointer().capability;
        AbsoluteCapability originalCap = file.getPointer().capability;
        return (rotateSigners ?
                CryptreeNode.initAndAuthoriseSigner(
                        owner,
                        parentSigner,
                        SigningKeyPair.random(crypto.random, crypto.signer), network, initial, c) :
                Futures.of(new Pair<>(initial, file.signingPair())))
                .thenCompose(p -> {
                    Optional<RelativeCapability> newParentLink = Optional.of(
                            rotateSigners ?
                                    new RelativeCapability(
                                            Optional.of(parent.writer()),
                                            crypto.random.randomBytes(RelativeCapability.MAP_KEY_LENGTH),
                                            Optional.of(Bat.random(crypto.random)),
                                            SymmetricKey.random(),
                                            Optional.empty()):
                                    new RelativeCapability(
                                            Optional.empty(),
                                            parent.getPointer().capability.getMapKey(),
                                            parent.getPointer().capability.bat,
                                            parent.getParentKey(),
                                            Optional.empty())
                    );
                    return file.getPointer().fileAccess.rotateAllKeys(
                            true,
                            new CryptreeNode.CapAndSigner((WritableAbsoluteCapability)
                                    originalCap, file.signingPair()),
                            new CryptreeNode.CapAndSigner(new WritableAbsoluteCapability(
                                    owner,
                                    p.right.publicKeyHash,
                                    crypto.random.randomBytes(RelativeCapability.MAP_KEY_LENGTH),
                                    Optional.of(Bat.random(crypto.random)),
                                    SymmetricKey.random(),
                                    SymmetricKey.random()),
                                    p.right),
                            new CryptreeNode.CapAndSigner((WritableAbsoluteCapability) parentCap, parent.signingPair()),
                            new CryptreeNode.CapAndSigner((WritableAbsoluteCapability) parentCap, parent.signingPair()),
                            newParentLink,
                            Optional.empty(),
                            mirrorBatId(),
                            rotateSigners,
                            network,
                            crypto,
                            p.left,
                            c)
                            .thenCompose(rotated -> {
                                // add a link in same writing space as parent to restrict rename access
                                if (rotateSigners) {
                                    SymmetricKey linkRBase = SymmetricKey.random();
                                    SymmetricKey linkParent = newParentLink.get().rBaseKey;
                                    SymmetricKey linkWBase = SymmetricKey.random();
                                    byte[] linkMapKey = newParentLink.get().getMapKey();
                                    Optional<Bat> linkBat = newParentLink.get().bat;
                                    WritableAbsoluteCapability linkCap = new WritableAbsoluteCapability(owner,
                                            parentCap.writer, linkMapKey, linkBat, linkRBase, linkWBase);
                                    return CryptreeNode.createAndCommitLink(parent, rotated.right,
                                            file.getFileProperties(), linkCap, linkParent,
                                            mirrorBatId(), crypto, network, rotated.left, c)
                                            .thenApply(newSnapshot -> new Pair<>(newSnapshot, linkCap));
                                } else
                                    return Futures.of(rotated);
                            });
                }).thenCompose(rotated ->
                                parent.getPointer().fileAccess.updateChildLink(
                                        rotated.left, c, (WritableAbsoluteCapability) parentCap,
                                        parentSigner, file.isLink() ? file.getLinkPointer().capability : originalCap,
                                        new NamedAbsoluteCapability(file.getName(), rotated.right),
                                        network, crypto.random, crypto.hasher)
                                        .thenCompose(s -> IpfsTransaction.call(owner,
                                                tid -> FileWrapper.deleteAllChunks(
                                                        file.writableFilePointer(),
                                                        file.signingPair(),
                                                        tid, crypto.hasher, network, s, c), network.dhtClient))
                                        .thenCompose(s -> rotateSigners ?
                                                CryptreeNode.deAuthoriseSigner(owner, parentSigner, file.writer(),
                                                        network, s, c) :
                                                Futures.of(s)));
    }

    /**
     * Remove read access to a file for the supplied readers.
     * The readers can include the inbuilt friend groupUid and followers groupUid
     * If the friend group is supplied - in addition to removing read access for the friend group,
     * all individual friends that currently have read access will lose read access
     * If the followers group is supplied - in addition to removing read access for the follower group AND friend
     * group all individual users that currently have read access will lose read access
     *
     * @param path - The path to the file/dir to revoke access to
     * @param initialReadersToRemove - The usernames or groupUids to revoke read access to
     * @return The resulting snapshot of the filesystem after rotating keys
     */
    @JsMethod
    public CompletableFuture<Snapshot> unShareReadAccessWith(Path path, Set<String> initialReadersToRemove) {
        //does list of readers include groups?
        boolean hasGroups = initialReadersToRemove.stream().anyMatch(i -> i.startsWith("."));
        return (hasGroups ?
                getSocialState().thenCompose(social -> sharedWith(path)
                        .thenApply(fileSharingState ->
                                gatherAllUsernamesToUnshare(social, fileSharingState.readAccess, initialReadersToRemove)
                        )) :
                Futures.of(initialReadersToRemove))
                .thenCompose(users -> getUserRoot()
                        .thenCompose(home -> network.synchronizer.applyComplexUpdate(signer.publicKeyHash, home.signingPair(),
                                (s, c) -> unShareReadAccessWith(path, users, s, c))));
    }

    public CompletableFuture<Snapshot> unShareReadAccessWith(Path path,
                                                             Set<String> readersToRemove,
                                                             Snapshot s,
                                                             Committer c) {
        String pathString = path.toString();
        String absolutePathString = pathString.startsWith("/") ? pathString : "/" + pathString;
        return getByPath(absolutePathString, s).thenCompose(opt -> {
            FileWrapper toUnshare = opt.orElseThrow(() -> new IllegalStateException("Specified un-shareWith path " + absolutePathString + " does not exist"));
            // now change to new base keys, clean some keys and mark others as dirty
            return getByPath(path.getParent().toString(), s)
                    .thenCompose(parent -> rotateAllKeys(toUnshare, parent.get(), false, toUnshare.version, c)
                            .thenCompose(markedDirty -> {
                                return sharedWithCache.removeSharedWith(SharedWithCache.Access.READ, path, readersToRemove, markedDirty, c, network)
                                        .thenCompose(s2 -> reSendAllSharesAndLinksRecursive(path, s2, c));
                            }));
        });
    }

    /**
     * Remove write access to a file for the supplied writers.
     * The readers can include the inbuilt friend groupUid and followers groupUid
     * If the friend group is supplied - in addition to removing write access for the friend group,
     * all individual friends that currently have write access will lose write access
     * If the followers group is supplied - in addition to removing write access for the follower group AND friend
     * group all individual users that currently have write access will lose write access
     *
     * @param path - The path to the file/dir to revoke access to
     * @param initialWritersToRemove - The usernames or groupUids to revoke write access to
     * @return
     */
    @JsMethod
    public CompletableFuture<Snapshot> unShareWriteAccessWith(Path path, Set<String> initialWritersToRemove) {
        //does list of readers include groups?
        boolean hasGroups = initialWritersToRemove.stream().anyMatch(i -> i.startsWith("."));
        return (hasGroups ?
                getSocialState().thenCompose(social -> sharedWith(path)
                        .thenApply(fileSharingState ->
                            gatherAllUsernamesToUnshare(social, fileSharingState.writeAccess, initialWritersToRemove)
                        )) :
                Futures.of(initialWritersToRemove))
                .thenCompose(writersToRemove -> {
                    // 1. Authorise new writer pair as an owned key to parent's writer
                    // 2. Rotate all keys (except data keys which are marked as dirty)
                    // 3. Update link from parent to point to new rotated child
                    // 4. Delete old file and subtree
                    // 5. Remove old writer from parent owned keys
                    String pathString = path.toString();
                    String absolutePathString = pathString.startsWith("/") ? pathString : "/" + pathString;
                    return getByPath(absolutePathString).thenCompose(opt -> {
                        FileWrapper toUnshare = opt.orElseThrow(() -> new IllegalStateException("Specified un-shareWith path " + absolutePathString + " does not exist"));
                        return getByPath(path.getParent().toString())
                                .thenCompose(parentOpt -> {
                                    FileWrapper parent = parentOpt.get();
                                    return network.synchronizer.applyComplexUpdate(signer.publicKeyHash,
                                            parent.signingPair(), (s, c) -> rotateAllKeys(toUnshare, parent, true, s, c)
                                            .thenCompose(s2 ->
                                                    sharedWithCache.removeSharedWith(SharedWithCache.Access.WRITE,
                                                            path, writersToRemove, s2, c, network))
                                                    .thenCompose(s3 -> reSendAllSharesAndLinksRecursive(path, s3, c)));
                                });
                    });
                });
    }

    @JsMethod
    public CompletableFuture<FileSharedWithState> sharedWith(Path p) {
        return getUserRoot().thenCompose(home -> sharedWithCache.getSharedWith(p, home.version));
    }

    @JsMethod
    public CompletableFuture<SharedWithState> getDirectorySharingState(Path dir) {
        // The global root and home folders cannot be shared
        if (dir.getNameCount() == 0 || username == null)
            return Futures.of(SharedWithState.empty());
        return getUserRoot().thenCompose(home -> sharedWithCache.getDirSharingState(dir, home.version));
    }

    @JsMethod
    public CompletableFuture<Snapshot> shareReadAccessWithFriends(Path path) {
        return getSocialState()
                .thenApply(s -> s.getFriendsGroupUid())
                .thenCompose(friendsGroupUid -> getByPath(path.toString())
                        .thenCompose(file -> getUserRoot()
                                .thenCompose(home -> network.synchronizer.applyComplexUpdate(signer.publicKeyHash, home.signingPair(),
                                        (s, c) -> shareReadAccessWith(file.orElseThrow(() ->
                                new IllegalStateException("Could not find path " + path)), path, Collections.singleton(friendsGroupUid), s, c)))));
    }

    @JsMethod
    public CompletableFuture<Snapshot> shareReadAccessWithFollowers(Path path) {
        return getSocialState()
                .thenApply(s -> s.getFollowersGroupUid())
                .thenCompose(followersGroupUid -> getByPath(path.toString())
                        .thenCompose(file -> getUserRoot()
                                .thenCompose(home -> network.synchronizer.applyComplexUpdate(signer.publicKeyHash, home.signingPair(),
                                        (s, c) -> shareReadAccessWith(file.orElseThrow(() ->
                                                new IllegalStateException("Could not find path " + path.toString())),
                                                path, Collections.singleton(followersGroupUid),s , c)))));
    }

    /*
        Taking into account currently shared users/groups and users/groups selected for unsharing, build a list that is group aware
        Note: Only inbuilt groups of friends and followers are currently handled
     */
    private Set<String> gatherAllUsernamesToUnshare(SocialState social,
                                                    Set<String> currentSharedWithUsernames,
                                                    Set<String> usernamesToUnshare) {

        Set<String> followers = social.getFollowers();
        Set<String> friends = social.getFriends();

        String friendGroupUid = social.getFriendsGroupUid();
        String followersGroupUid = social.getFollowersGroupUid();

        Set<String> usersToUnshare = new HashSet<>(usernamesToUnshare);
        if (usernamesToUnshare.contains(friendGroupUid)) {
            HashSet<String> toAdd = new HashSet<>(currentSharedWithUsernames);
            toAdd.retainAll(friends);
            usersToUnshare.addAll(toAdd);
        }
        if (usernamesToUnshare.contains(followersGroupUid)) {
            HashSet<String> toAdd = new HashSet<>(currentSharedWithUsernames);
            toAdd.retainAll(followers);
            usersToUnshare.addAll(toAdd);
            if (currentSharedWithUsernames.contains(friendGroupUid)) {
                usersToUnshare.add(friendGroupUid);
            }
        }
        return usersToUnshare;
    }

    @JsMethod
    public CompletableFuture<Snapshot> shareReadAccessWith(Path path, Set<String> readersToAdd) {
        return getUserRoot()
                .thenCompose(home -> network.synchronizer.applyComplexUpdate(signer.publicKeyHash, home.signingPair(),
                        (s, c) -> shareReadAccessWith(path, readersToAdd, s, c)));
    }

    public CompletableFuture<Snapshot> shareReadAccessWith(Path path, Set<String> readersToAdd, Snapshot s, Committer c) {
        if (readersToAdd.isEmpty())
            return Futures.of(s);

        return getByPath(path.toString(), s)
                .thenCompose(file -> shareReadAccessWith(file.orElseThrow(() ->
                        new IllegalStateException("Could not find path " + path)), path, readersToAdd, s, c));
    }

    public CompletableFuture<Snapshot> reSendAllSharesAndLinksRecursive(Path start, Snapshot in, Committer c) {
        return sharedWithCache.getAllDescendantShares(start, in)
                .thenCompose(toReshare -> Futures.reduceAll(toReshare.entrySet(),
                        in,
                        (s, e) -> reshareAndUpdateLinks(e.getKey(), e.getValue(), s, c),
                        (a, b) -> b));
    }

    private CompletableFuture<Snapshot> reshareAndUpdateLinks(Path start, SharedWithState file, Snapshot in, Committer c) {
        return Futures.reduceAll(file.readShares().entrySet(), in,
                        (s, e) -> shareReadAccessWith(start.resolve(e.getKey()), e.getValue(), s, c),
                        (a, b) -> b)
                .thenCompose(s2 -> Futures.reduceAll(file.writeShares().entrySet(),
                        s2,
                        (s, e) -> sendWriteCapToAll(start.resolve(e.getKey()), e.getValue(), s, c),
                        (a, b) -> b))
                .thenCompose(s3 -> Futures.reduceAll(file.links().entrySet(), s3,
                        (s, e) -> Futures.reduceAll(e.getValue(),
                                s,
                                (v, p) -> updateSecretLink(start.resolve(e.getKey()).toString(), p, v, c).thenApply(x -> x.left),
                                (a, b) -> b),
                        (a, b) -> b));
    }

    private CompletableFuture<Snapshot> shareReadAccessWith(FileWrapper file,
                                                            Path p,
                                                            Set<String> readersToAdd,
                                                            Snapshot in,
                                                            Committer c) {
        ensureAllowedToShare(file, username, false);
        BiFunction<FileWrapper, FileWrapper, CompletableFuture<Snapshot>> sharingFunction = (sharedDir, fileWrapper) ->
                CapabilityStore.addReadOnlySharingLinkTo(sharedDir, fileWrapper.getPointer().capability,
                        c, network, crypto);
        return Futures.reduceAll(readersToAdd,
                in,
                (s, username) -> shareAccessWith(file, username, sharingFunction, s),
                (a, b) -> a.mergeAndOverwriteWith(b))
                .thenCompose(result -> updatedSharedWithCache(file, p, readersToAdd, SharedWithCache.Access.READ, result, c));
    }

    @JsMethod
    public CompletableFuture<Snapshot> shareWriteAccessWith(Path fileToShare,
                                                            Set<String> writersToAdd) {
        return getByPath(fileToShare.getParent())
                .thenCompose(parentOpt -> ! parentOpt.isPresent() ?
                                Futures.errored(new IllegalStateException("Unable to read " + fileToShare.getParent())) :
                parentOpt.get().getChild(fileToShare.getFileName().toString(), crypto.hasher, network)
                        .thenCompose(fileOpt -> ! fileOpt.isPresent() ?
                                Futures.errored(new IllegalStateException("Unable to read " + fileToShare)) :
                                shareWriteAccessWith(fileOpt.get(), fileToShare, parentOpt.get(), writersToAdd)));
    }

    private CompletableFuture<Snapshot> shareWriteAccessWith(FileWrapper file,
                                                             Path pathToFile,
                                                             FileWrapper parent,
                                                             Set<String> writersToAdd) {
        // There are 2 situations:
        // a) file already has a different signing key than parent,
        //    in which case, just share a write cap
        //
        // b) file needs to be moved to a different signing key (along with its subtree)
        // To do this atomically,
        // 1) rotate all the keys as if we were revoking write access
        // 2) update parent pointer
        // 3) delete old subtree
        // 4) then reshare all sub sharees
        System.out.println("Sharing write: " + parent.writer() + " child " + file.writer());
        ensureAllowedToShare(file, username, true);
        SigningPrivateKeyAndPublicHash currentSigner = file.signingPair();
        boolean changeSigner = currentSigner.publicKeyHash.equals(parent.signingPair().publicKeyHash);

        if (! changeSigner) {
            return network.synchronizer.applyComplexUpdate(signer.publicKeyHash,
                    parent.signingPair(), (s, c) -> sharedWithCache.addSharedWith(SharedWithCache.Access.WRITE, pathToFile, writersToAdd, s, c, network)
                    .thenCompose(s2 -> sendWriteCapToAll(pathToFile, writersToAdd, s2, c)));
        }

        return network.synchronizer.applyComplexUpdate(signer.publicKeyHash,
                file.signingPair(), (s, c) -> rotateAllKeys(file, parent, true, s, c)
                        .thenCompose(s2 -> getByPath(pathToFile.toString(), s2)
                                .thenCompose(newFileOpt -> {
                                    System.out.println("New child writer: " + newFileOpt.get().writer());
                                    return sharedWithCache
                                            .addSharedWith(SharedWithCache.Access.WRITE, pathToFile, writersToAdd, s2, c, network);
                                })
                                .thenCompose(s3 -> reSendAllSharesAndLinksRecursive(pathToFile, s3, c))
                        ));
    }

    public CompletableFuture<Snapshot> sendWriteCapToAll(Path toFile, Set<String> writersToAdd, Snapshot s, Committer c) {
        if (writersToAdd.isEmpty())
            return Futures.of(s);

        System.out.println("Resharing WRITE cap to " + toFile + " with " + writersToAdd);
        return getByPath(toFile.getParent().toString(), s)
                .thenCompose(parent -> getByPath(toFile.toString(), s)
                        .thenCompose(fileOpt -> fileOpt.map(file -> sendWriteCapToAll(file, parent.get(), toFile, writersToAdd, s, c))
                                .orElseGet(() -> Futures.errored(
                                        new IllegalStateException("Couldn't retrieve file at " + toFile)))));
    }

    public CompletableFuture<Snapshot> sendWriteCapToAll(FileWrapper file,
                                                         FileWrapper parent,
                                                         Path pathToFile,
                                                         Set<String> writersToAdd,
                                                         Snapshot in,
                                                         Committer c) {
        if (parent.writer().equals(file.writer()))
            return Futures.errored(
                    new IllegalStateException("A file must have different writer than its parent to grant write access!"));
        BiFunction<FileWrapper, FileWrapper, CompletableFuture<Snapshot>> sharingFunction =
                (sharedDir, fileToShare) -> CapabilityStore.addEditSharingLinkTo(sharedDir,
                        file.writableFilePointer(), c, network, crypto);
        return Futures.reduceAll(writersToAdd,
                in,
                (s, username) -> shareAccessWith(file, username, sharingFunction, s),
                (a, b) -> a.mergeAndOverwriteWith(b))
                .thenCompose(result -> updatedSharedWithCache(file, pathToFile, writersToAdd, SharedWithCache.Access.WRITE, result, c));
    }

    private CompletableFuture<Snapshot> updatedSharedWithCache(FileWrapper file,
                                                               Path pathToFile,
                                                               Set<String> usersToAdd,
                                                               SharedWithCache.Access access,
                                                               Snapshot s,
                                                               Committer c) {
        return sharedWithCache.addSharedWith(access, pathToFile, usersToAdd, s, c, network);
    }

    public CompletableFuture<Snapshot> shareAccessWith(FileWrapper file,
                                                       String usernameToGrantAccess,
                                                       BiFunction<FileWrapper, FileWrapper, CompletableFuture<Snapshot>> sharingFunction,
                                                       Snapshot s) {
        return getByPath("/" + username + "/shared/" + usernameToGrantAccess, s)
                .thenCompose(shared -> {
                    if (!shared.isPresent())
                        return Futures.errored(new IllegalStateException("Unknown recipient for sharing: " + usernameToGrantAccess));
                    FileWrapper sharedDir = shared.get();
                    return sharingFunction.apply(sharedDir, file);
                });
    }

    private static CompletableFuture<Snapshot> addRootEntryPointAndCommit(Snapshot version,
                                                                          EntryPoint entry,
                                                                          UserStaticData current,
                                                                          PublicSigningKey loginPublic,
                                                                          SigningPrivateKeyAndPublicHash owner,
                                                                          SymmetricKey rootKey,
                                                                          Committer c,
                                                                          NetworkAccess network,
                                                                          TransactionId tid) {
        CommittedWriterData cwd = version.get(owner.publicKeyHash);
        WriterData wd = cwd.props.get();
        if (wd.staticData.isEmpty()) {
            UserStaticData updated = new UserStaticData(current.getData(rootKey).addEntryPoint(entry), rootKey);
            return network.account.setLoginData(new LoginData(entry.ownerName, updated, loginPublic, Optional.empty()), owner)
                    .thenApply(b -> version);
        } else {
            // legacy account
            Optional<UserStaticData> updated = wd.staticData.map(sd -> new UserStaticData(sd.getData(rootKey).addEntryPoint(entry), rootKey));
            return c.commit(owner.publicKeyHash, owner, wd.withStaticData(updated), cwd, tid);
        }
    }

    private synchronized CompletableFuture<FileWrapper> addExternalEntryPoint(EntryPoint entry) {
        boolean isOurs = username.equals(entry.ownerName);
        if (isOurs)
            throw new IllegalStateException("Cannot add an entry point to your own filesystem!");
        String filename = ENTRY_POINTS_FROM_FRIENDS_FILENAME;
        // verify owner before adding
        return entry.isValid("/" + entry.ownerName, network)
                .thenCompose(valid -> valid ?
                        getByPath(PathUtil.get(username, filename)) :
                        Futures.errored(new IllegalStateException("Incorrect claimed owner for entry point")))
                .thenCompose(existing -> {
                    long offset = existing.map(f -> f.getSize()).orElse(0L);
                    byte[] data = entry.serialize();
                    AsyncReader reader = AsyncReader.build(data);
                    Optional<SymmetricKey> base = existing.map(f -> f.getPointer().capability.rBaseKey);
                    return getUserRoot().thenCompose(home ->
                            home.uploadFileSection(filename, reader, true, offset,
                                    offset + data.length, base, true, network, crypto, x -> {},
                                    crypto.random.randomBytes(32), Optional.empty(), Optional.of(Bat.random(crypto.random)), mirrorBatId()));
                });
    }

    public CompletableFuture<List<BlindFollowRequest>> getFollowRequests() {
        return TimeLimitedClient.signNow(signer.secret)
                .thenCompose(auth -> network.social.getFollowRequests(signer.publicKeyHash, auth)).thenApply(reqs -> {
                    CborObject cbor = CborObject.fromByteArray(reqs);
                    if (!(cbor instanceof CborObject.CborList))
                        throw new IllegalStateException("Invalid cbor for list of follow requests: " + cbor);
                    return ((CborObject.CborList) cbor).value.stream()
                            .map(BlindFollowRequest::fromCbor)
                            .collect(Collectors.toList());
                });
    }

    /**
     * Process any responses to our follow requests.
     *
     * @return initial follow requests
     */
    public CompletableFuture<List<FollowRequestWithCipherText>> processFollowRequests() {
        return getFollowRequests().thenCompose(this::processFollowRequests);
    }

    private CompletableFuture<List<FollowRequestWithCipherText>> processFollowRequests(List<BlindFollowRequest> all) {
        return getSharingFolder().thenCompose(sharing ->
                getFollowerRoots(false).thenCompose(followerRoots -> getPendingOutgoingFollowRequests()
                        .thenCompose(pendingOut -> {
                    List<FollowRequestWithCipherText> withDecrypted = all.stream()
                            .map(b -> new FollowRequestWithCipherText(b.followRequest.decrypt(boxer.secretBoxingKey, b.dummySource, FollowRequest::fromCbor), b))
                            .collect(Collectors.toList());

                    List<FollowRequestWithCipherText> replies = withDecrypted.stream()
                            .filter(p -> pendingOut.pendingOutgoingFollowRequests.contains(p.req.entry.get().ownerName))
                            .collect(Collectors.toList());

                    BiFunction<TrieNode, FollowRequestWithCipherText, CompletableFuture<TrieNode>> addToStatic = (root, p) -> {
                        FollowRequest freq = p.req;
                        if (! Arrays.equals(freq.entry.get().pointer.rBaseKey.serialize(), SymmetricKey.createNull().serialize())) {
                            CompletableFuture<TrieNode> updatedRoot = freq.entry.get().ownerName.equals(username) ?
                                    CompletableFuture.completedFuture(root) : // ignore responses claiming to be owned by us
                                    addExternalEntryPoint(freq.entry.get())
                                    .thenCompose(x -> removeFromPendingOutgoing(freq.entry.get().ownerName))
                                    .thenCompose(x -> retrieveAndAddEntryPointToTrie(root, freq.entry.get()));
                            return updatedRoot.thenCompose(newRoot -> {
                                entrie = newRoot;
                                // clear their response follow req too
                                return signer.secret.signMessage(p.cipher.serialize())
                                        .thenCompose(signed -> network.social.removeFollowRequest(signer.publicKeyHash, signed))
                                        .thenApply(b -> newRoot);
                            });
                        }
                        return signer.secret.signMessage(p.cipher.serialize())
                                .thenCompose(signed -> network.social.removeFollowRequest(signer.publicKeyHash, signed))
                                .thenApply(b -> root);
                    };

                    BiFunction<TrieNode, FollowRequestWithCipherText, CompletableFuture<TrieNode>> mozart = (trie, p) -> {
                        FollowRequest freq = p.req;
                        // delete our folder if they didn't reciprocate
                        String theirName = freq.entry.get().ownerName;
                        FileWrapper ourDirForThem = followerRoots.get(theirName);
                        byte[] ourKeyForThem = ourDirForThem.getKey().serialize();
                        Optional<byte[]> keyFromResponse = freq.key.map(Cborable::serialize);
                        if (keyFromResponse.isEmpty()) {
                            // They didn't reciprocate (follow us)
                            CompletableFuture<FileWrapper> removeDir = ourDirForThem.remove(sharing,
                                    PathUtil.get(username, SHARED_DIR_NAME, theirName), this);

                            return removeDir.thenCompose(x -> removeFromPendingOutgoing(freq.entry.get().ownerName))
                                    .thenCompose(b -> addToStatic.apply(trie, p));
                        } else if (freq.entry.get().pointer.isNull()) {
                            // They reciprocated, but didn't accept (they follow us, but we can't follow them)
                            // add entry point to static data to signify their acceptance
                            // and finally remove the follow request
                            EntryPoint entryWeSentToThem = new EntryPoint(ourDirForThem.getPointer().capability.readOnly(),
                                    username);
                            // add them to followers group
                            return getGroupUid(SocialState.FOLLOWERS_GROUP_NAME)
                                    .thenCompose(followersUidOpt -> shareReadAccessWith(PathUtil.get(username,
                                            SHARED_DIR_NAME, followersUidOpt.get()), Collections.singleton(theirName)))
                                    .thenCompose(x -> signer.secret.signMessage(p.cipher.serialize())
                                            .thenCompose(signed -> network.social.removeFollowRequest(signer.publicKeyHash, signed)))
                                    .thenApply(x -> trie);
                        } else {
                            // they accepted and reciprocated
                            // add entry point to static data to signify their acceptance
                            EntryPoint entryWeSentToThem = new EntryPoint(ourDirForThem.getPointer().capability.readOnly(),
                                    username);

                            // add new entry point to tree root
                            EntryPoint entry = freq.entry.get();
                            if (entry.ownerName.equals(username))
                                throw new IllegalStateException("Received a follow request claiming to be owned by us!");
                            // add them to followers and friends group
                            return getGroupUid(SocialState.FOLLOWERS_GROUP_NAME)
                                    .thenCompose(followersUidOpt -> shareReadAccessWith(PathUtil.get(username,
                                            SHARED_DIR_NAME, followersUidOpt.get()), Collections.singleton(theirName)))
                                    .thenCompose(x -> getGroupUid(SocialState.FRIENDS_GROUP_NAME)
                                            .thenCompose(friendsUidOpt -> shareReadAccessWith(PathUtil.get(username,
                                                    SHARED_DIR_NAME, friendsUidOpt.get()), Collections.singleton(theirName))))
                                    .thenCompose(x -> addToStatic.apply(trie, p.withEntryPoint(entry)))
                                    .exceptionally(t -> trie);
                        }
                    };
                    List<FollowRequestWithCipherText> initialRequests = withDecrypted.stream()
                            .filter(p -> !followerRoots.containsKey(p.req.entry.get().ownerName))
                            .collect(Collectors.toList());
                    return Futures.reduceAll(replies, entrie, mozart, (a, b) -> a)
                            .thenApply(newRoot -> {
                                entrie = newRoot;
                                return initialRequests;
                            });
                }))
        );
    }

    @JsMethod
    public CompletableFuture<List<Pair<SharedItem, FileWrapper>>> getFiles(List<SharedItem> pointers) {
        return Futures.combineAllInOrder(pointers.stream()
                .map(s -> Futures.asyncExceptionally(() -> network.getFile(s.cap, s.owner)
                                .thenCompose(fopt -> fopt.map(f -> Futures.of(Optional.of(f)))
                                        .orElseGet(() -> getByPath(s.path))),
                        t -> getByPath(s.path))
                        .thenApply(opt -> opt.map(f -> new Pair<>(s, f))))
                .collect(Collectors.toList()))
                .thenApply(res -> res.stream()
                        .flatMap(Optional::stream)
                        .collect(Collectors.toList()));
    }

    public CompletableFuture<Set<FileWrapper>> getChildren(String path) {
        FileProperties.ensureValidPath(path);
        return entrie.getChildren(path, crypto.hasher, network);
    }

    public CompletableFuture<Optional<FileWrapper>> getByPath(Path path) {
        String pathString = IntStream.range(0, path.getNameCount())
                .mapToObj(path::getName)
                .map(Path::toString)
                .collect(Collectors.joining("/"));
        return getByPath(pathString);
    }

    @JsMethod
    public CompletableFuture<Optional<FileWrapper>> getByPath(String path) {
        if (path.equals("/"))
            return CompletableFuture.completedFuture(Optional.of(FileWrapper.createRoot(entrie)));
        FileProperties.ensureValidPath(path);
        String absolutePath = path.startsWith("/") ? path : "/" + path;
        return entrie.getByPath(absolutePath, crypto.hasher, network)
                .thenCompose(res -> {
                    if (res.isPresent())
                        return Futures.of(res);
                    return getPublicFile(PathUtil.get(path));
                });
    }

    public CompletableFuture<Optional<FileWrapper>> getByPath(String path, Snapshot version) {
        if (path.equals("/"))
            return CompletableFuture.completedFuture(Optional.of(FileWrapper.createRoot(entrie)));
        FileProperties.ensureValidPath(path);
        String absolutePath = path.startsWith("/") ? path : "/" + path;
        return entrie.getByPath(absolutePath, version, crypto.hasher, network);
    }

    public CompletableFuture<FileWrapper> getUserRoot() {
        return getByPath("/" + username).thenApply(opt -> opt.get());
    }

    /**
     * @return TrieNode for root of filesystem containing only our files
     */
    private static CompletableFuture<TrieNode> createOurFileTreeOnly(String ourName,
                                                                     UserStaticData.EntryPoints entry,
                                                                     WriterData userData,
                                                                     NetworkAccess network) {
        TrieNode root = TrieNodeImpl.empty();
        List<EntryPoint> ourFileSystemEntries = entry.entries
                .stream()
                .filter(e -> e.ownerName.equals(ourName))
                .map(e -> e.withOwner(userData.controller))
                .collect(Collectors.toList());
        return Futures.reduceAll(ourFileSystemEntries, root,
                (t, e) -> NetworkAccess.getLatestEntryPoint(e, network)
                        .thenApply(r -> t.put(r.getPath(), r.entry)),
                (a, b) -> a)
                .exceptionally(Futures::logAndThrow);
    }

    private CompletableFuture<TrieNode> buildFileTree(TrieNode ourRoot,
                                                      FileWrapper homeDir,
                                                      Predicate<String> includeUser,
                                                      NetworkAccess network,
                                                      Crypto crypto) {
        return time(() -> getFriendsEntryPoints(homeDir), "Get friend's entry points")
                .thenCompose(friendEntries -> homeDir.getChild(CapabilityStore.CAPABILITY_CACHE_DIR, crypto.hasher, network)
                        .thenCompose(copt -> {
                            List<EntryPoint> friendsOnly = friendEntries.stream()
                                    .filter(e -> includeUser.test(e.ownerName))
                                    .collect(Collectors.toList());

                            List<CompletableFuture<Optional<FriendSourcedTrieNode>>> friendNodes = friendsOnly.stream()
                                    .parallel()
                                    .map(e -> FriendSourcedTrieNode.build(capCache, e,
                                            (cap, o, n, s, c) -> addFriendGroupCap(cap, o, n, s, c), network, crypto))
                                    .collect(Collectors.toList());
                            return Futures.reduceAll(friendNodes, ourRoot,
                                    (t, e) -> e.thenApply(fromUser -> fromUser.map(userEntrie -> t.putNode(userEntrie.ownerName, userEntrie))
                                            .orElse(t)).exceptionally(ex -> t),
                                    (a, b) -> a);
                        })).thenCompose(root -> getFriendsGroupCaps(homeDir, homeDir.version, network)
                        .thenApply(groups -> { // now add the groups from each friend
                            Set<String> friendNames = root.getChildNames()
                                    .stream()
                                    .filter(includeUser)
                                    .collect(Collectors.toSet());
                            for (String friendName : friendNames) {
                                FriendSourcedTrieNode friend = (FriendSourcedTrieNode) root.getChildNode(friendName);
                                for (EntryPoint group : groups.left.getFriends(friendName)) {
                                    friend.addGroup(group);
                                }
                            }
                            return root;
                        }));
    }

    /**
     * @return TrieNode for root of filesystem
     */
    private CompletableFuture<TrieNode> createFileTree(TrieNode ourRoot,
                                                       String ourName,
                                                       NetworkAccess network,
                                                       Crypto crypto) {
        // need to retrieve all the entry points of our friends and any of their groups
        return ourRoot.getByPath(ourName, crypto.hasher, network)
                        .thenApply(Optional::get)
                .thenCompose(homeDir -> buildFileTree(ourRoot, homeDir, n -> ! n.equals(ourName), network, crypto))
                .exceptionally(Futures::logAndThrow);
    }

    private CompletableFuture<TrieNode> retrieveAndAddEntryPointToTrie(TrieNode root, EntryPoint e) {
        return NetworkAccess.retrieveEntryPoint(e, network)
                .thenCompose(r -> addRetrievedEntryPointToTrie(username, root, r.entry, r.getPath(), false,
                        capCache, this, network, crypto));
    }

    private CompletableFuture<List<EntryPoint>> getFriendsEntryPoints(FileWrapper homeDir) {
        return homeDir.getChild(ENTRY_POINTS_FROM_FRIENDS_FILENAME, crypto.hasher, network)
                .thenCompose(fopt -> {
                    return fopt.map(f -> {
                        List<EntryPoint> res = new ArrayList<>();
                        return f.getInputStream(network, crypto, x -> {})
                                .thenCompose(reader -> reader.parseStream(EntryPoint::fromCbor, res::add, f.getSize())
                                        .thenApply(x -> res));
                    }).orElse(CompletableFuture.completedFuture(Collections.emptyList()))
                            .thenCompose(fromFriends -> {
                                // filter out blocked friends
                                return homeDir.getChild(BLOCKED_USERNAMES_FILE, crypto.hasher, network)
                                        .thenCompose(bopt -> bopt.map(f -> f.getInputStream(network, crypto, x -> {})
                                                .thenCompose(in -> Serialize.readFully(in, f.getSize()))
                                                .thenApply(data -> new HashSet<>(Arrays.asList(new String(data).split("\n")))
                                                        .stream()
                                                        .collect(Collectors.toSet())))
                                                .orElse(CompletableFuture.completedFuture(Collections.emptySet()))
                                                .thenApply(toRemove -> fromFriends.stream()
                                                        .filter(e -> !toRemove.contains(e.ownerName))
                                                        .collect(Collectors.toList())));
                            });
                }).thenApply(entries -> {
                    // Only take the most recent version of each entry
                    Map<PublicKeyHash, EntryPoint> latest = new LinkedHashMap<>();
                    entries.forEach(e -> latest.put(e.pointer.writer, e));
                    return latest.values()
                            .stream()
                            .collect(Collectors.toList());
                });
    }

    private CompletableFuture<Pair<FriendsGroups, Optional<FileWrapper>>> getFriendsGroupCaps(FileWrapper homeDir,
                                                                                              Snapshot s,
                                                                                              NetworkAccess network) {
        return homeDir.getChild(ENTRY_POINTS_FROM_FRIENDS_GROUPS_FILENAME, crypto.hasher, network)
                .thenCompose(fopt -> fopt.map(f -> f.getInputStream(s.get(f.writer()).props.get(), network, crypto, x -> {})
                        .thenCompose(reader -> Serialize.parse(reader, f.getSize(), FriendsGroups::fromCbor))
                        .thenApply(g -> new Pair<>(g, fopt)))
                        .orElse(CompletableFuture.completedFuture(new Pair<>(FriendsGroups.empty(), Optional.empty()))));
    }

    public CompletableFuture<Snapshot> addFriendGroupCap(CapabilityWithPath group,
                                                         String owner,
                                                         NetworkAccess network,
                                                         Snapshot s,
                                                         Committer c) {
        return entrie.getByPath("/" + username, s, crypto.hasher, network).thenApply(Optional::get)
                .thenCompose(home -> getFriendsGroupCaps(home, s, network)
                        .thenCompose(p -> {
                            FriendsGroups updated = p.left.addGroup(group, owner);
                            byte[] raw = updated.serialize();
                            AsyncReader reader = AsyncReader.build(raw);
                            if (p.right.isPresent())
                                return p.right.get().overwriteFile(reader, raw.length, network, crypto, x -> {}, s, c);

                            return home.uploadOrReplaceFile(ENTRY_POINTS_FROM_FRIENDS_GROUPS_FILENAME, reader, raw.length, true, s, c, network, crypto, x -> {},
                                    crypto.random.randomBytes(RelativeCapability.MAP_KEY_LENGTH), Optional.of(Bat.random(crypto.random)), mirrorBatId());
                        }));
    }

    private static CompletableFuture<TrieNode> addRetrievedEntryPointToTrie(String ourName,
                                                                            TrieNode root,
                                                                            EntryPoint fileCap,
                                                                            String path,
                                                                            boolean checkOwner,
                                                                            IncomingCapCache capCache,
                                                                            UserContext context,
                                                                            NetworkAccess network,
                                                                            Crypto crypto) {
        // check entrypoint doesn't forge the owner
        return (fileCap.ownerName.equals(ourName) || ! checkOwner ? CompletableFuture.completedFuture(true) :
                fileCap.isValid(path, network)).thenCompose(valid -> {
            if (! valid)
                return Futures.errored(new IllegalStateException("Capability claims incorrect owner!"));
            String[] parts = path.split("/");
            if (parts.length < 3 || !parts[2].equals(SHARED_DIR_NAME))
                return CompletableFuture.completedFuture(root.put(path, fileCap));
            String username = parts[1];
            if (username.equals(ourName)) // This is a sharing directory of ours for a friend
                return CompletableFuture.completedFuture(root);
            // This is a friend's sharing directory, create a wrapper to read the capabilities lazily from it
            return root.getByPath(PathUtil.get(ourName, CapabilityStore.CAPABILITY_CACHE_DIR).toString(), crypto.hasher, network)
                    .thenApply(opt -> opt.get())
                    .thenCompose(cacheDir -> FriendSourcedTrieNode.build(capCache, fileCap, context::addFriendGroupCap, network, crypto))
                    .thenApply(fromUser -> fromUser.map(userEntrie -> root.putNode(username, userEntrie)).orElse(root));
        });
    }

    public static CompletableFuture<CommittedWriterData> getWriterData(NetworkAccess network,
                                                                       PublicKeyHash owner,
                                                                       PublicKeyHash writer) {
        return getWriterDataCbor(network.dhtClient, network.mutable, owner, writer)
                .thenApply(pair -> new CommittedWriterData(pair.left.updated, WriterData.fromCbor(pair.right), pair.left.sequence));
    }

    public static CompletableFuture<CommittedWriterData> getWriterData(ContentAddressedStorage ipfs,
                                                                       MutablePointers mutable,
                                                                       PublicKeyHash owner,
                                                                       PublicKeyHash writer) {
        return getWriterDataCbor(ipfs, mutable, owner, writer)
                .thenApply(pair -> new CommittedWriterData(pair.left.updated, WriterData.fromCbor(pair.right), pair.left.sequence));
    }

    public static CompletableFuture<Pair<PointerUpdate, CborObject>> getWriterDataCbor(NetworkAccess network, String username) {
        return network.coreNode.getPublicKeyHash(username)
                .thenCompose(signer -> {
                    PublicKeyHash owner = signer.orElseThrow(
                            () -> new IllegalStateException("No public-key for user " + username));
                    return getWriterDataCbor(network.dhtClient, network.mutable, owner, owner);
                });
    }

    private static CompletableFuture<Pair<PointerUpdate, CborObject>> getWriterDataCbor(ContentAddressedStorage ipfs,
                                                                                        MutablePointers mutable,
                                                                                        PublicKeyHash owner,
                                                                                        PublicKeyHash writer) {
        return mutable.getPointer(owner, writer)
                .thenCompose(casOpt -> ipfs.getSigningKey(owner, writer)
                        .thenCompose(signer -> casOpt.map(raw -> signer.get().unsignMessage(raw)
                                        .thenApply(unsigned -> PointerUpdate.fromCbor(CborObject.fromByteArray(unsigned))))
                                .orElse(Futures.of(PointerUpdate.empty()))))
                .thenCompose(pointer -> ipfs.get(owner, (Cid)pointer.updated.get(), Optional.empty())
                        .thenApply(Optional::get)
                        .thenApply(cbor -> new Pair<>(pointer, cbor))
                );
    }

    @JsMethod
    public CompletableFuture<Boolean> unfollow(String friendName) {
        LOG.info("Unfollowing: " + friendName);
        return getUserRoot()
                .thenCompose(home -> home.getChild(BLOCKED_USERNAMES_FILE, crypto.hasher, network)
                        .thenCompose(fopt -> home.appendToChild(BLOCKED_USERNAMES_FILE,
                                fopt.map(f -> f.getSize()).orElse(0L), (friendName + "\n").getBytes(), true,
                                mirrorBatId(), network, crypto, x -> {})))
                .thenApply(b -> {
                    entrie = entrie.removeEntry("/" + friendName + "/");
                    return true;
                });
    }

    public CompletableFuture<Set<String>> getBlocked() {
        return getUserRoot()
                .thenCompose(home -> home.getChild(BLOCKED_USERNAMES_FILE, crypto.hasher, network))
                .thenCompose(this::getBlocked);
    }

    private CompletableFuture<Set<String>> getBlocked(Optional<FileWrapper> blockedUsernamesFile) {
        return blockedUsernamesFile.isEmpty() ?
                Futures.of(Collections.emptySet()) :
                blockedUsernamesFile.get().getInputStream(network, crypto, x -> {})
                        .thenCompose(in -> Serialize.readFully(in, blockedUsernamesFile.get().getSize()))
                        .thenApply(data -> new HashSet<>(Arrays.asList(new String(data).split("\n"))));
    }

    @JsMethod
    public CompletableFuture<Boolean> unblock(String username) {
        return getUserRoot()
                .thenCompose(home -> home.getChild(BLOCKED_USERNAMES_FILE, crypto.hasher, network)
                        .thenCompose(bopt -> bopt.isEmpty() ?
                                Futures.of(true) :
                                getBlocked(bopt)
                                        .thenCompose(all -> {
                                            byte[] updated = all.stream()
                                                    .filter(u -> !u.equals(username))
                                                    .sorted()
                                                    .map(u -> u + "\n")
                                                    .collect(Collectors.joining())
                                                    .getBytes();

                                            return bopt.get().overwriteFile(AsyncReader.build(updated), updated.length, network, crypto, x -> {})
                                                    .thenApply(x -> true);
                                        })
                        )).thenCompose(x -> getUserRoot()
                        .thenCompose(home -> buildFileTree(entrie, home, n -> n.equals(username), network, crypto)).thenApply(updated -> {
                            this.entrie = updated;
                            return true;
                        }));
    }

    public CompletableFuture<Optional<String>> getGroupUid(String groupName) {
        return getGroupNameMappings()
                .thenApply(m -> m.uidToGroupName.entrySet().stream()
                        .filter(e -> e.getValue().equals(groupName))
                        .map(e -> e.getKey())
                        .findFirst());
    }

    @JsMethod
    public CompletableFuture<Boolean> removeFollower(String usernameToRemove) {
        LOG.info("Remove follower: " + usernameToRemove);
        // remove /$us/shared/$them
        Path sharingDir = PathUtil.get(this.username, SHARED_DIR_NAME, usernameToRemove);
        return removeFromFriendGroup(usernameToRemove)
                .thenCompose(x1 -> removeFromFollowersGroup(usernameToRemove))
                .thenCompose(x2 -> unshareItemsInSharingFolder(usernameToRemove, usernameToRemove)) // revoke access to everything ever shared with this user!
                .thenCompose(x3 -> getSharingFolder())
                .thenCompose(sharing -> getByPath(sharingDir)
                        .thenCompose(dir -> dir.get().remove(sharing, sharingDir, this)))
                .thenApply(x4 -> true);
    }

    private CompletableFuture<Boolean> removeFromFriendGroup(String usernameToRemove) {
        return getGroupUid(SocialState.FRIENDS_GROUP_NAME)
                .thenCompose(friendsUid -> friendsUid.isPresent() ?
                        removeFromGroup(friendsUid.get(), usernameToRemove) :
                        Futures.of(true));
    }

    private CompletableFuture<Boolean> removeFromFollowersGroup(String usernameToRemove) {
        return getGroupUid(SocialState.FOLLOWERS_GROUP_NAME)
                .thenCompose(followersUid -> followersUid.isPresent() ?
                        removeFromGroup(followersUid.get(), usernameToRemove) :
                        Futures.of(true));
    }

    /** Remove a user from a group. This involves rotating the keys to the group sharing dir,
     *  and then also rotating the keys for everything ever shared with the group.
     *
     * @param groupUid
     * @param username
     * @return
     */
    public CompletableFuture<Boolean> removeFromGroup(String groupUid, String username) {
        return unShareReadAccess(PathUtil.get(this.username, SHARED_DIR_NAME, groupUid), username)
                .thenCompose(x -> unshareItemsInSharingFolder(groupUid, username));
    }

    public CompletableFuture<Boolean> unshareItemsInSharingFolder(String folderName, String usernameToRevoke) {
        return getByPath(PathUtil.get(username, SHARED_DIR_NAME, folderName))
                .thenCompose(opt -> {
                    if (opt.isEmpty())
                        return Futures.of(true);
                    return CapabilityStore.loadReadAccessSharingLinksFromIndex(null, opt.get(), null,
                            network, crypto, 0, false, false)
                            .thenCompose(readCaps -> revokeAllReadCaps(readCaps.getRetrievedCapabilities(), usernameToRevoke))
                            .thenCompose(x -> CapabilityStore.loadWriteAccessSharingLinksFromIndex(null, opt.get(), null,
                                    network, crypto, 0, false, false)
                                    .thenCompose(writeCaps -> revokeAllWriteCaps(writeCaps.getRetrievedCapabilities(), usernameToRevoke)));
                });
    }

    private CompletableFuture<Boolean> revokeAllReadCaps(List<CapabilityWithPath> caps, String usernameToRevoke) {
        return Futures.reduceAll(caps, true,
                (b, c) -> unShareReadAccess(PathUtil.get(c.path), usernameToRevoke),
                (a, b) -> a && b);
    }

    private CompletableFuture<Boolean> revokeAllWriteCaps(List<CapabilityWithPath> caps, String usernameToRevoke) {
        return Futures.reduceAll(caps, true,
                (b, c) -> unShareWriteAccess(PathUtil.get(c.path), usernameToRevoke),
                (a, b) -> a && b);
    }

    @JsMethod
    public CompletableFuture<Snapshot> cleanPartialUploads() {
        // clear any partial upload started more than a day ago
        return cleanPartialUploads(t -> t.startTimeEpochMillis() < System.currentTimeMillis() - 24*3600_000L);
    }

    public CompletableFuture<Snapshot> cleanPartialUploads(Predicate<Transaction> filter) {
        TransactionService txns = getTransactionService();
        return getUserRoot().thenCompose(home -> network.synchronizer
                .applyComplexUpdate(home.owner(), txns.getSigner(),
                        (s, comitter) -> txns.clearAndClosePendingTransactions(s, comitter, filter)));
    }

    public void logout() {
        entrie = TrieNodeImpl.empty();
    }

    @JsMethod
    public CompletableFuture<List<ServerMessage>> getNewMessages() {
        //get all messages not dismissed
        return network.serverMessager.getMessages(username, signer.secret);
    }

    @JsMethod
    public CompletableFuture<List<ServerConversation>> getServerConversations() {
        if (this.username == null) {
            return Futures.of(Collections.emptyList());
        }
        return network.serverMessager.getMessages(username, signer.secret).thenApply(ServerConversation::combine);
    }

    @JsMethod
    public CompletableFuture<Boolean> dismissMessage(ServerMessage message) {
        ServerMessage dismiss = new ServerMessage(message.id, ServerMessage.Type.Dismiss, System.currentTimeMillis(), "", Optional.empty(), true);
        return network.serverMessager.sendMessage(username, dismiss, signer.secret);
    }

    @JsMethod
    public CompletableFuture<Boolean> sendReply(ServerMessage prior, String message) {
        ServerMessage msg = new ServerMessage(prior.id, ServerMessage.Type.FromUser, System.currentTimeMillis(), message, Optional.of(prior.id), true);
        return network.serverMessager.sendMessage(username, msg, signer.secret);
    }

    @JsMethod
    public CompletableFuture<Boolean> sendFeedback(String message) {
        ServerMessage msg = ServerMessage.buildUserMessage(message);
        return network.serverMessager.sendMessage(username, msg, signer.secret);
    }

    public static <V> CompletableFuture<V> time(Supplier<CompletableFuture<V>> f, String name) {
        long t0 = System.currentTimeMillis();
        return f.get().thenApply(x -> {
            long t1 = System.currentTimeMillis();
            LOG.log(Level.INFO, name + " took " + (t1 - t0) + "ms");
            return x;
        });
    }
}
