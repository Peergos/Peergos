package peergos.shared.user;

import java.util.logging.*;

import peergos.shared.user.fs.cryptree.*;
import peergos.shared.user.fs.transaction.TransactionService;
import peergos.shared.user.fs.transaction.TransactionServiceImpl;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.SafeRandom;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.Multihash;
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
 * The UserContext class represents a logged in user, or a retrieved public link and the resulting view of the global
 * filesystem.
 */
public class UserContext {
    private static final Logger LOG = Logger.getGlobal();

    public static final String PEERGOS_USERNAME = "peergos";
    public static final String SHARED_DIR_NAME = "shared";
    public static final String TRANSACTIONS_DIR_NAME = ".transactions";
    public static final String FEEDBACK_DIR_NAME = "feedback";

    public static final String ENTRY_POINTS_FROM_FRIENDS_FILENAME = ".from-friends.cborstream";
    public static final String ENTRY_POINTS_FROM_US_FILENAME = ".from-us.cborstream";
    public static final String BLOCKED_USERNAMES_FILE = ".blocked-usernames.txt";

    @JsProperty
    public final String username;
    public final SigningPrivateKeyAndPublicHash signer;
    private final BoxingKeyPair boxer;
    private final SymmetricKey rootKey;

    private final WriteSynchronizer writeSynchronizer;
    private final TransactionService transactionService;

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
                       TrieNode entrie) {
        this.username = username;
        this.signer = signer;
        this.boxer = boxer;
        this.rootKey = rootKey;
        this.network = network;
        this.crypto = crypto;
        this.entrie = entrie;
        this.transactionService = buildTransactionService();
        this.writeSynchronizer = network.synchronizer;
        if(signer != null) {
            writeSynchronizer.put(signer.publicKeyHash, userData);
        }
    }

    private TransactionService buildTransactionService() {
        Supplier<CompletableFuture<FileWrapper>> getTransactionsDir =
                () -> getByPath(Paths.get(username, TRANSACTIONS_DIR_NAME).toString())
                        .thenApply(Optional::get);
        return new TransactionServiceImpl(network, crypto.random, crypto.hasher, getTransactionsDir::get);
    }

    @JsMethod
    public TransactionService getTransactionService() {
        return transactionService;
    }

    public boolean isJavascript() {
        return this.network.isJavascript();
    }

    @JsMethod
    public CompletableFuture<Boolean> unShareReadAccess(FileWrapper file, String readerToRemove) {

        return file.getPath(network).thenCompose(pathString ->
                unShareReadAccess(Paths.get(pathString), Collections.singleton(readerToRemove))
        );
    }

    @JsMethod
    public CompletableFuture<Boolean> unShareWriteAccess(FileWrapper file, String writerToRemove) {

        return file.getPath(network).thenCompose(pathString ->
                unShareWriteAccess(Paths.get(pathString), Collections.singleton(writerToRemove))
        );
    }

    public static CompletableFuture<UserContext> signIn(String username, String password, NetworkAccess network
            , Crypto crypto) {
        return signIn(username, password, network, crypto, t -> {});
    }

    @JsMethod
    public static CompletableFuture<UserContext> signIn(String username, String password, NetworkAccess network,
                                                        Crypto crypto, Consumer<String> progressCallback) {
        return getWriterDataCbor(network, username)
                .thenCompose(pair -> {
                    SecretGenerationAlgorithm algorithm = WriterData.fromCbor(pair.right).generationAlgorithm
                            .orElseThrow(() -> new IllegalStateException("No login algorithm specified in user data!"));
                    progressCallback.accept("Generating keys");
                    return UserUtil.generateUser(username, password, crypto.hasher, crypto.symmetricProvider,
                            crypto.random, crypto.signer, crypto.boxer, algorithm)
                            .thenCompose(userWithRoot ->
                                    login(username, userWithRoot, pair, network, crypto, progressCallback));
                }).exceptionally(Futures::logAndThrow);
    }

    public static CompletableFuture<UserContext> signIn(String username, UserWithRoot userWithRoot, NetworkAccess network
            , Crypto crypto, Consumer<String> progressCallback) {
        return getWriterDataCbor(network, username)
                .thenCompose(pair -> login(username, userWithRoot, pair, network, crypto, progressCallback))
                .exceptionally(Futures::logAndThrow);
    }

    private static CompletableFuture<UserContext> login(String username,
                                                        UserWithRoot userWithRoot,
                                                        Pair<Multihash, CborObject> pair,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        Consumer<String> progressCallback) {
        try {
            progressCallback.accept("Logging in");
            WriterData userData = WriterData.fromCbor(pair.right);
            return createOurFileTreeOnly(username, userWithRoot.getRoot(), userData, network, crypto.random, crypto.hasher, Fragmenter.getInstance())
                    .thenCompose(root -> TofuCoreNode.load(username, root, network, crypto.random)
                            .thenCompose(keystore -> {
                                TofuCoreNode tofu = new TofuCoreNode(network.coreNode, keystore);
                                SigningPrivateKeyAndPublicHash signer = new SigningPrivateKeyAndPublicHash(userData.controller, userWithRoot.getUser().secretSigningKey);
                                UserContext result = new UserContext(username,
                                        signer,
                                        userWithRoot.getBoxingPair(),
                                        userWithRoot.getRoot(),
                                        network.withCorenode(tofu),
                                        crypto,
                                        new CommittedWriterData(MaybeMultihash.of(pair.left), userData),
                                        root);
                                tofu.setContext(result);
                                return result.getUsernameClaimExpiry()
                                        .thenCompose(expiry -> expiry.isBefore(LocalDate.now().plusMonths(1)) ?
                                                result.renewUsernameClaim(LocalDate.now().plusMonths(2)) :
                                                CompletableFuture.completedFuture(true))
                                        .thenCompose(x -> {
                                            System.out.println("Initializing context..");
                                            return result.init(progressCallback);
                                        }).exceptionally(Futures::logAndThrow);
                            }));
        } catch (Throwable t) {
            throw new IllegalStateException("Incorrect password");
        }
    }

    @JsMethod
    public static CompletableFuture<UserContext> signUp(String username,
                                                        String password,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        Consumer<String> progressCallback) {
        return signUpGeneral(username, password, network, crypto, SecretGenerationAlgorithm.getDefault(), progressCallback);
    }

    public static CompletableFuture<UserContext> signUp(String username,
                                                        String password,
                                                        NetworkAccess network,
                                                        Crypto crypto) {
        return signUpGeneral(username, password, network, crypto, SecretGenerationAlgorithm.getDefault(), t -> {});
    }

    public static CompletableFuture<UserContext> signUpGeneral(String username,
                                                               String password,
                                                               NetworkAccess network,
                                                               Crypto crypto,
                                                               SecretGenerationAlgorithm algorithm,
                                                               Consumer<String> progressCallback) {
        progressCallback.accept("Generating keys");
        return UserUtil.generateUser(username, password, crypto.hasher, crypto.symmetricProvider, crypto.random, crypto.signer, crypto.boxer, algorithm)
                .thenCompose(userWithRoot -> {
                    PublicSigningKey publicSigningKey = userWithRoot.getUser().publicSigningKey;
                    SecretSigningKey secretSigningKey = userWithRoot.getUser().secretSigningKey;
                    PublicKeyHash signerHash = ContentAddressedStorage.hashKey(publicSigningKey);
                    SigningPrivateKeyAndPublicHash signer = new SigningPrivateKeyAndPublicHash(signerHash, secretSigningKey);

                    LOG.info("Registering username " + username);
                    progressCallback.accept("Registering username");
                    return UserContext.register(username, signer, network).thenCompose(registered -> {
                        if (!registered) {
                            LOG.info("Couldn't register username");
                            throw new IllegalStateException("Couldn't register username: " + username);
                        }
                        return IpfsTransaction.call(signerHash, tid -> network.dhtClient.putSigningKey(
                                secretSigningKey.signatureOnly(publicSigningKey.serialize()),
                                signerHash,
                                publicSigningKey, tid).thenCompose(returnedSignerHash -> {
                                    PublicBoxingKey publicBoxingKey = userWithRoot.getBoxingPair().publicBoxingKey;
                                    return network.dhtClient.putBoxingKey(signerHash,
                                            secretSigningKey.signatureOnly(publicBoxingKey.serialize()), publicBoxingKey, tid);
                                }),
                                network.dhtClient
                        );
                    }).thenCompose(boxerHash -> {
                        progressCallback.accept("Creating filesystem");
                        return WriterData.createEmptyWithStaticData(signerHash,
                                signer,
                                Optional.of(new PublicKeyHash(boxerHash)),
                                userWithRoot.getRoot(),
                                network.dhtClient).thenCompose(newUserData -> {

                            CommittedWriterData notCommitted = new CommittedWriterData(MaybeMultihash.empty(), newUserData);
                            UserContext context = new UserContext(username,
                                    signer,
                                    userWithRoot.getBoxingPair(),
                                    userWithRoot.getRoot(),
                                    network,
                                    crypto,
                                    notCommitted,
                                    TrieNodeImpl.empty());

                            LOG.info("Creating user's root directory");
                            long t1 = System.currentTimeMillis();
                            return context.createEntryDirectory(signer, username).thenCompose(userRoot -> {
                                LOG.info("Creating root directory took " + (System.currentTimeMillis() - t1) + " mS");
                                return context.createSpecialDirectory(SHARED_DIR_NAME)
                                        .thenCompose(x -> signIn(username, userWithRoot, network, crypto, progressCallback))
                                        .thenCompose(c -> c.createSpecialDirectory(TRANSACTIONS_DIR_NAME));
                            });
                        });
                    });
                }).thenCompose(context -> network.coreNode.getUsernames(PEERGOS_USERNAME)
                        .thenCompose(usernames -> usernames.contains(PEERGOS_USERNAME) && ! username.equals(PEERGOS_USERNAME) ?
                                context.sendInitialFollowRequest(PEERGOS_USERNAME) :
                                CompletableFuture.completedFuture(true))
                        .thenApply(b -> context))
                .exceptionally(Futures::logAndThrow);
    }

    private CompletableFuture<UserContext> createSpecialDirectory(String dirName) {
        return getUserRoot()
                .thenCompose(root -> root.mkdir(dirName, network, true, crypto.random, crypto.hasher))
                .thenApply(x -> this);
    }

    @JsMethod
    public static CompletableFuture<UserContext> fromPublicLink(String link, NetworkAccess network, Crypto crypto) {
        AbsoluteCapability cap;
        try {
            cap = AbsoluteCapability.fromLink(link);
        } catch (Exception e) { //link was invalid
            CompletableFuture<UserContext> invalidLink = new CompletableFuture<>();
            invalidLink.completeExceptionally(e);
            return invalidLink;
        }
        EntryPoint entry = new EntryPoint(cap, "");
        WriterData empty = new WriterData(cap.owner,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Collections.emptyMap(),
                        Optional.empty(),
                        Optional.empty());
        CommittedWriterData userData = new CommittedWriterData(MaybeMultihash.empty(), empty);
        UserContext context = new UserContext(null, null, null, null, network, crypto, userData, TrieNodeImpl.empty());
        return context.addEntryPoint(null, context.entrie, entry, network, crypto.random, crypto.hasher).thenApply(trieNode -> {
            context.entrie = trieNode;
            return context;
        });
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
        return file.getChildren(network)
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

    public static CompletableFuture<UserContext> ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) {

        return network.isUsernameRegistered(username).thenCompose(isRegistered -> {
            if (isRegistered)
                return signIn(username, password, network, crypto);
            return signUp(username, password, network, crypto);
        });
    }

    private CompletableFuture<UserContext> init(Consumer<String> progressCallback) {
        progressCallback.accept("Retrieving Friends");
        return writeSynchronizer.applyUpdate(signer.publicKeyHash, signer.publicKeyHash,
                wd -> createFileTree(entrie, username, network, crypto.random, crypto.hasher)
                        .thenCompose(root -> {
                            this.entrie = root;
                            return getByPath("/" + username + "/" + "shared")
                                    .thenCompose(sharedOpt -> {
                                        if (!sharedOpt.isPresent())
                                            throw new IllegalStateException("Couldn't find shared folder!");
                                        return buildSharedWithCache(sharedOpt.get(), this::getUserRoot);
                                    });
                        }).thenApply(b -> wd)
        ).thenApply(res -> this);
    }

    public CompletableFuture<Boolean> buildSharedWithCache(FileWrapper sharedFolder, Supplier<CompletableFuture<FileWrapper>> homeDirSupplier) {
        return sharedFolder.getChildren(network)
                .thenCompose(children ->
                        Futures.reduceAll(children,
                                true,
                                (x, friendDirectory) -> {
                                    return CapabilityStore.loadReadAccessSharingLinks(homeDirSupplier, friendDirectory,
                                            this.username, network, crypto.random, crypto.hasher, false)
                                            .thenCompose(readCaps -> {
                                                readCaps.getRetrievedCapabilities().stream().forEach(rc -> {
                                                    network.sharedWithCache.addSharedWith(SharedWithCache.Access.READ,
                                                            rc.cap, friendDirectory.getName());
                                                });
                                                return CapabilityStore.loadWriteAccessSharingLinks(homeDirSupplier, friendDirectory,
                                                        this.username, network, crypto.random, crypto.hasher, false)
                                                        .thenApply(writeCaps -> {
                                                            writeCaps.getRetrievedCapabilities().stream().forEach(rc -> {
                                                                network.sharedWithCache.addSharedWith(SharedWithCache.Access.WRITE,
                                                                        rc.cap, friendDirectory.getName());
                                                            });
                                                            return true;
                                                        });
                                            });
                                }, (a, b) -> a && b).thenApply(done -> done));
    }

    public CompletableFuture<FileWrapper> getSharingFolder() {
        return getByPath("/" + username + "/shared").thenApply(opt -> opt.get());
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

    @JsMethod
    public CompletableFuture<Boolean> register() {
        return isRegistered().thenCompose(exists -> {
            if (exists)
                throw new IllegalStateException("Account already exists with username: " + username);
            return register(this.username, signer, network);
        });
    }

    public static CompletableFuture<Boolean> register(String username, SigningPrivateKeyAndPublicHash signer, NetworkAccess network) {
        LocalDate now = LocalDate.now();
        // set claim expiry to two months from now
        LocalDate expiry = now.plusMonths(2);
        LOG.info("claiming username: " + username + " with expiry " + expiry);
        return network.dhtClient.id()
                .thenCompose(id -> {
                    List<UserPublicKeyLink> claimChain = UserPublicKeyLink.createInitial(signer, username, expiry, Arrays.asList(id));
                    return network.coreNode.getChain(username).thenCompose(existing -> {
                        if (existing.size() > 0)
                            throw new IllegalStateException("User already exists!");
                        return network.coreNode.updateChain(username, claimChain);
                    });
                });
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
        return renewUsernameClaim(username, signer, expiry, network);
    }

    public static CompletableFuture<Boolean> renewUsernameClaim(String username,
                                                                SigningPrivateKeyAndPublicHash signer,
                                                                LocalDate expiry,
                                                                NetworkAccess network) {
        LOG.info("renewing username: " + username + " with expiry " + expiry);
        return network.coreNode.getChain(username).thenCompose(existing -> {
            List<Multihash> storage = existing.get(existing.size() - 1).claim.storageProviders;
            List<UserPublicKeyLink> claimChain = UserPublicKeyLink.createInitial(signer, username, expiry, storage);
            return network.coreNode.updateChain(username, claimChain);
        });
    }

    @JsMethod
    public CompletableFuture<Pair<Integer, Integer>> getTotalSpaceUsedJS(PublicKeyHash owner) {
        return getTotalSpaceUsed(owner, owner)
                .thenApply(size -> new Pair<>((int) (size >> 32), size.intValue()));
    }

    public CompletableFuture<Long> getTotalSpaceUsed(PublicKeyHash ownerHash, PublicKeyHash writerHash) {
        // assume no cycles in owned keys
        return WriterData.getOwnedKeysRecursive(ownerHash, writerHash, network.mutable, network.dhtClient)
                .thenCompose(allOwned -> Futures.reduceAll(allOwned.stream()
                                .map(w -> network.mutable.getPointerTarget(ownerHash, w, network.dhtClient)
                                        .thenCompose(root -> root.isPresent() ?
                                                network.dhtClient.getRecursiveBlockSize(root.get()) :
                                                CompletableFuture.completedFuture(0L)))
                                .collect(Collectors.toList()),
                        0L, (t, fut) -> fut.thenApply(x -> x + t), (a, b) -> a + b));
    }

    public CompletableFuture<SecretGenerationAlgorithm> getKeyGenAlgorithm() {
        return getWriterData(network, signer.publicKeyHash, signer.publicKeyHash)
                .thenApply(wd -> wd.props.generationAlgorithm
                        .orElseThrow(() -> new IllegalStateException("No login algorithm specified in user data!")));
    }

    public CompletableFuture<Optional<PublicKeyHash>> getNamedKey(String name) {
        return getWriterData(network, signer.publicKeyHash, signer.publicKeyHash)
                .thenApply(wd -> wd.props.namedOwnedKeys.get(name))
                .thenApply(res -> Optional.ofNullable(res).map(p -> p.ownedKey));
    }

    @JsMethod
    public CompletableFuture<UserContext> changePassword(String oldPassword, String newPassword) {

        return getKeyGenAlgorithm().thenCompose(alg -> changePassword(oldPassword, newPassword, alg, alg));
    }

    public CompletableFuture<UserContext> changePassword(String oldPassword,
                                                         String newPassword,
                                                         SecretGenerationAlgorithm existingAlgorithm,
                                                         SecretGenerationAlgorithm newAlgorithm) {
        // set claim expiry to two months from now
        LocalDate expiry = LocalDate.now().plusMonths(2);
        LOG.info("Changing password and setting expiry to: " + expiry);

        return UserUtil.generateUser(username, oldPassword, crypto.hasher, crypto.symmetricProvider, crypto.random, crypto.signer, crypto.boxer, existingAlgorithm)
                .thenCompose(existingUser -> {
                    if (!existingUser.getUser().secretSigningKey.equals(this.signer.secret))
                        throw new IllegalArgumentException("Incorrect existing password during change password attempt!");
                    return UserUtil.generateUser(username, newPassword, crypto.hasher, crypto.symmetricProvider,
                            crypto.random, crypto.signer, crypto.boxer, newAlgorithm)
                            .thenCompose(updatedUser -> {
                                PublicSigningKey newPublicSigningKey = updatedUser.getUser().publicSigningKey;
                                PublicKeyHash existingOwner = ContentAddressedStorage.hashKey(existingUser.getUser().publicSigningKey);
                                return IpfsTransaction.call(existingOwner,
                                        tid -> network.dhtClient.putSigningKey(
                                                existingUser.getUser().secretSigningKey.signatureOnly(newPublicSigningKey.serialize()),
                                                existingOwner,
                                                newPublicSigningKey,
                                                tid),
                                        network.dhtClient
                                ).thenCompose(newSignerHash ->
                                        writeSynchronizer.applyUpdate(signer.publicKeyHash, signer.publicKeyHash, wd ->
                                                wd.props.changeKeys(
                                                        signer,
                                                        new SigningPrivateKeyAndPublicHash(newSignerHash, updatedUser.getUser().secretSigningKey),
                                                        wd.hash,
                                                        updatedUser.getBoxingPair().publicBoxingKey,
                                                        existingUser.getRoot(),
                                                        updatedUser.getRoot(),
                                                        newAlgorithm,
                                                        network)
                                        ).thenCompose(writerData -> {
                                            SigningPrivateKeyAndPublicHash newUser =
                                                    new SigningPrivateKeyAndPublicHash(newSignerHash, updatedUser.getUser().secretSigningKey);
                                            return network.coreNode.getChain(username).thenCompose(existing -> {
                                                List<Multihash> storage = existing.get(existing.size() - 1).claim.storageProviders;
                                                List<UserPublicKeyLink> claimChain = UserPublicKeyLink.createChain(signer, newUser, username, expiry, storage);
                                                return network.coreNode.updateChain(username, claimChain)
                                                        .thenCompose(updatedChain -> {
                                                            if (!updatedChain)
                                                                throw new IllegalStateException("Couldn't register new public keys during password change!");

                                                            return UserContext.ensureSignedUp(username, newPassword, network, crypto);
                                                        });
                                            });
                                        })
                                );
                            });
                });
    }

    public CompletableFuture<RetrievedCapability> createEntryDirectory(SigningPrivateKeyAndPublicHash owner, String directoryName) {
        long t1 = System.currentTimeMillis();
        SigningKeyPair writer = SigningKeyPair.random(crypto.random, crypto.signer);
        LOG.info("Random User generation took " + (System.currentTimeMillis() - t1) + " mS");
        return IpfsTransaction.call(owner.publicKeyHash, tid -> network.dhtClient.putSigningKey(
                owner.secret.signatureOnly(writer.publicSigningKey.serialize()),
                owner.publicKeyHash,
                writer.publicSigningKey,
                tid).thenCompose(writerHash -> {
            byte[] rootMapKey = crypto.random.randomBytes(32); // root will be stored under this in the core node
            SymmetricKey rootRKey = SymmetricKey.random();
            SymmetricKey rootWKey = SymmetricKey.random();
            LOG.info("Random keys generation took " + (System.currentTimeMillis() - t1) + " mS");

            // and authorise the writer key
            SigningPrivateKeyAndPublicHash writerWithHash =
                    new SigningPrivateKeyAndPublicHash(writerHash, writer.secretSigningKey);
            WritableAbsoluteCapability rootPointer =
                    new WritableAbsoluteCapability(owner.publicKeyHash, writerHash, rootMapKey, rootRKey, rootWKey);
            EntryPoint entry = new EntryPoint(rootPointer, this.username);
            return addOwnedKeyAndCommit(writerWithHash, tid)
                    .thenCompose(x -> {
                        long t2 = System.currentTimeMillis();
                        RelativeCapability nextChunk =
                                RelativeCapability.buildSubsequentChunk(crypto.random.randomBytes(32), rootRKey);
                        CryptreeNode.DirAndChildren root =
                                CryptreeNode.createDir(MaybeMultihash.empty(), rootRKey, rootWKey, Optional.of(writerWithHash),
                                new FileProperties(directoryName, true, "", 0, LocalDateTime.now(),
                                        false, Optional.empty()), Optional.empty(), SymmetricKey.random(), nextChunk, crypto.hasher);

                        LOG.info("Uploading entry point directory");
                        return WriterData.createEmpty(owner.publicKeyHash, writerWithHash, network.dhtClient)
                                .thenCompose(empty -> empty.commit(owner.publicKeyHash, writerWithHash, MaybeMultihash.empty(), network, tid))
                                .thenCompose(empty -> root.commit(rootPointer, Optional.of(writerWithHash), network, tid)
                                        .thenApply(rootWithHash -> {
                                            long t3 = System.currentTimeMillis();
                                            LOG.info("Uploading root dir metadata took " + (t3 - t2) + " mS");
                                            return new RetrievedCapability(rootPointer, rootWithHash);
                                        }));
                    }).thenCompose(x -> addRootEntryPointAndCommit(entrie, entry).thenApply(y -> {
                        this.entrie = y;
                        return x;
                    }));
        }), network.dhtClient);
    }

    public CompletableFuture<PublicSigningKey> getSigningKey(PublicKeyHash keyhash) {
        return network.dhtClient.get(keyhash).thenApply(cborOpt -> cborOpt.map(PublicSigningKey::fromCbor).get());
    }

    public CompletableFuture<PublicBoxingKey> getBoxingKey(PublicKeyHash keyhash) {
        return network.dhtClient.get(keyhash).thenApply(cborOpt -> cborOpt.map(PublicBoxingKey::fromCbor).get());
    }

    public CompletableFuture<Optional<Pair<PublicKeyHash, PublicBoxingKey>>> getPublicKeys(String username) {
        return network.coreNode.getPublicKeyHash(username)
                .thenCompose(signerOpt -> getSigningKey(signerOpt.get())
                        .thenCompose(signer -> getWriterData(network, signerOpt.get(), signerOpt.get())
                                .thenCompose(wd -> getBoxingKey(wd.props.followRequestReceiver.get())
                                        .thenApply(boxer -> Optional.of(new Pair<>(signerOpt.get(), boxer))))));
    }

    private CompletableFuture<CommittedWriterData> addOwnedKeyAndCommit(SigningPrivateKeyAndPublicHash owned,
                                                                        TransactionId tid) {
        return writeSynchronizer.applyUpdate(signer.publicKeyHash, signer.publicKeyHash, wd ->
            wd.props.addOwnedKey(signer.publicKeyHash, signer, OwnerProof.build(owned, signer.publicKeyHash), network.dhtClient)
                    .thenCompose(updated -> updated.commit(signer.publicKeyHash, signer, wd.hash, network, tid)));
    }

    public CompletableFuture<CommittedWriterData> addNamedOwnedKeyAndCommit(String keyName,
                                                                            SigningPrivateKeyAndPublicHash owned) {
        return writeSynchronizer.applyUpdate(signer.publicKeyHash, signer.publicKeyHash, wd -> {
            WriterData writerData = wd.props.addNamedKey(keyName, OwnerProof.build(owned, signer.publicKeyHash));
            return IpfsTransaction.call(signer.publicKeyHash,
                    tid -> writerData.commit(signer.publicKeyHash, signer, wd.hash, network, tid),
                    network.dhtClient);
        });
    }

    public CompletableFuture<CommittedWriterData> makePublic(FileWrapper file) {
        if (! file.getOwnerName().equals(username))
            return Futures.errored(new IllegalStateException("Only the owner of a file can make it public!"));
        return writeSynchronizer.applyUpdate(signer.publicKeyHash, signer.publicKeyHash, wd -> file.getPath(network).thenCompose(path -> {
            ensureAllowedToShare(file, username, false);
            return IpfsTransaction.call(signer.publicKeyHash,
                    tid -> {
                        Optional<Multihash> publicData = wd.props.publicData;

                        Function<ByteArrayWrapper, byte[]> hasher = x -> Hash.sha256(x.data);
                        CompletableFuture<ChampWrapper> champ = publicData.isPresent() ?
                                ChampWrapper.create(publicData.get(), hasher, network.dhtClient) :
                                ChampWrapper.create(signer.publicKeyHash, signer, hasher, tid, network.dhtClient);

                        AbsoluteCapability cap = file.getPointer().capability.readOnly();
                        return network.dhtClient.put(signer.publicKeyHash, signer, cap.serialize(), tid)
                                .thenCompose(capHash ->
                                        champ.thenCompose(c -> c.put(signer.publicKeyHash, signer, path.getBytes(),
                                                MaybeMultihash.empty(), capHash, tid))
                                                .thenCompose(newRoot -> wd.props.withPublicRoot(newRoot)
                                                        .commit(signer.publicKeyHash, signer, wd.hash, network, tid)));
                    },
                    network.dhtClient);
        }));
    }

    private static void ensureAllowedToShare(FileWrapper file, String ourname, boolean isWrite) {
        if (file.isUserRoot())
            throw new IllegalStateException("You cannot share your home directory public!");
        if (isWrite && ! file.getOwnerName().equals(ourname))
            throw new IllegalStateException("Only the owner of a file can grant write access!");
    }

    @JsMethod
    public CompletableFuture<Set<FileWrapper>> getFriendRoots() {
        List<CompletableFuture<Optional<FileWrapper>>> friendRoots = entrie.getChildNames()
                .stream()
                .filter(p -> !p.startsWith(username))
                .map(p -> getByPath(p)).collect(Collectors.toList());
        return Futures.combineAll(friendRoots)
                .thenApply(set -> set.stream().filter(opt -> opt.isPresent()).map(opt -> opt.get()).collect(Collectors.toSet()));
    }

    @JsMethod
    public CompletableFuture<Set<String>> getFollowing() {
        return getFriendRoots()
                .thenApply(set -> set.stream()
                        .map(froot -> froot.getOwnerName())
                        .filter(name -> !name.equals(username))
                        .collect(Collectors.toSet()));
    }

    @JsMethod
    public CompletableFuture<Set<String>> getFollowerNames() {
        return getFollowerRoots().thenApply(m -> m.keySet());
    }

    public CompletableFuture<Map<String, FileWrapper>> getFollowerRoots() {
        return getSharingFolder()
                .thenCompose(sharing -> sharing.getChildren(network))
                .thenApply(children -> children.stream()
                        .collect(Collectors.toMap(e -> e.getFileProperties().name, e -> e)));
    }

    private CompletableFuture<Set<String>> getFollowers() {
        return getByPath(Paths.get(username, ENTRY_POINTS_FROM_FRIENDS_FILENAME))
                .thenCompose(fopt -> fopt
                        .map(f -> {
                            Set<EntryPoint> res = new HashSet<>();
                            return f.getInputStream(network, crypto.random, x -> {})
                                    .thenCompose(reader -> reader.parseStream(EntryPoint::fromCbor, res::add, f.getSize())
                                            .thenApply(x -> res.stream()
                                                    .map(e -> e.ownerName)
                                                    .filter(name -> ! name.equals(username))
                                                    .collect(Collectors.toSet())));
                        }).orElse(CompletableFuture.completedFuture(Collections.emptySet())));
    }

    @JsMethod
    public CompletableFuture<SocialState> getSocialState() {
        return processFollowRequests()
                .thenCompose(pending -> getFollowerRoots()
                        .thenCompose(followerRoots -> getFriendRoots()
                                .thenCompose(followingRoots -> getFollowers()
                                        .thenApply(followers -> new SocialState(pending, followers, followerRoots, followingRoots)))));
    }

    @JsMethod
    public CompletableFuture<Boolean> sendInitialFollowRequest(String targetUsername) {
        if (username.equals(targetUsername)) {
            return CompletableFuture.completedFuture(false);
        }
        return sendFollowRequest(targetUsername, SymmetricKey.random());
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
                                network.social.removeFollowRequest(signer.publicKeyHash, signer.secret.signMessage(initialRequestAndRaw.cipher.serialize()))
                        );
            });
        }

        return CompletableFuture.completedFuture(true).thenCompose(b -> {
            if (accept) {
                return getSharingFolder().thenCompose(sharing -> {
                    return sharing.mkdir(theirUsername, network, initialRequest.key.get(), true, crypto.random, crypto.hasher)
                            .thenCompose(friendRoot -> {
                                // add a note to our entry point store so we know who we sent the read access to
                                EntryPoint entry = new EntryPoint(friendRoot
                                        .withWritingKey(sharing.writer())
                                        .toAbsolute(sharing.getPointer().capability.readOnly()),
                                        username);

                                return addExternalEntryPoint(this.entrie, entry).thenApply(trie -> {
                                    this.entrie = trie;
                                    return entry;
                                });
                            });
                });
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

            return getPublicKeys(initialRequest.entry.get().ownerName).thenCompose(pair -> {
                PublicBoxingKey targetUser = pair.get().right;
                return blindAndSendFollowRequest(initialRequest.entry.get().pointer.owner, targetUser, reply);
            });
        }).thenCompose(b -> {
            if (reciprocate)
                return addExternalEntryPoint(entrie, initialRequest.entry.get());
            return CompletableFuture.completedFuture(entrie);
        }).thenCompose(trie -> {
            // remove original request
            entrie = trie;
            return network.social.removeFollowRequest(signer.publicKeyHash, signer.secret.signMessage(initialRequestAndRaw.cipher.serialize()));
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
            return sharing.getChildren(network).thenCompose(children -> {
                boolean alreadySentRequest = children.stream()
                        .filter(f -> f.getFileProperties().name.equals(targetUsername))
                        .findAny()
                        .isPresent();
                if (alreadySentRequest)
                    return CompletableFuture.completedFuture(false);
                // check for them not reciprocating
                return getFollowing().thenCompose(following -> {
                    boolean alreadyFollowing = following.stream().filter(x -> x.equals(targetUsername)).findAny().isPresent();
                    if (alreadyFollowing)
                        return CompletableFuture.completedFuture(false);

                    return getPublicKeys(targetUsername).thenCompose(targetUserOpt -> {
                        if (!targetUserOpt.isPresent())
                            return CompletableFuture.completedFuture(false);
                        PublicBoxingKey targetUser = targetUserOpt.get().right;
                        return sharing.mkdir(targetUsername, network, null, true, crypto.random, crypto.hasher).thenCompose(friendRoot -> {

                            // if they accept the request we will add a note to our static data so we know who we sent the read access to
                            EntryPoint entry = new EntryPoint(friendRoot
                                    .withWritingKey(sharing.writer())
                                    .toAbsolute(sharing.getPointer().capability.readOnly()), username);

                            FollowRequest followReq = new FollowRequest(Optional.of(entry), Optional.ofNullable(requestedKey));

                            PublicKeyHash targetSigner = targetUserOpt.get().left;
                            return blindAndSendFollowRequest(targetSigner, targetUser, followReq);
                        });
                    });
                });
            });
        });
    }

    public CompletableFuture<Boolean> unShareReadAccess(Path path, String readerToRemove) {
        return unShareReadAccess(path, Collections.singleton(readerToRemove));
    }

    public CompletableFuture<Boolean> unShareWriteAccess(Path path, String writerToRemove) {
        return unShareWriteAccess(path, Collections.singleton(writerToRemove));
    }

    public CompletableFuture<Boolean> unShareWriteAccess(Path path, Set<String> writersToRemove) {
        // 1. Add new writer pair as an owned key to parent's writer
        // 2. Rotate symmetric writing keys of subtree
        // 3. Change the signing key pair of the subtree
        // 4. Rotate the symmetric read keys
        // 5. Remove old writer from parent owned keys
        String pathString = path.toString();
        String absolutePathString = pathString.startsWith("/") ? pathString : "/" + pathString;
        return getByPath(path).thenCompose(opt -> {
            FileWrapper toUnshare = opt.orElseThrow(() -> new IllegalStateException("Specified un-shareWith path "
                    + absolutePathString + " does not exist"));
            return getByPath(path.getParent().toString())
                    .thenCompose(parent -> addOwnedKeyToParent(parent.get().owner(), parent.get().signingPair(),
                            SigningKeyPair.random(crypto.random, crypto.signer), network)
                            .thenCompose(newSigner -> toUnshare.rotateWriteKeys(parent.get(), network, crypto.random, crypto.hasher)
                                    .thenCompose(pair -> pair.left.changeSigningKey(newSigner, pair.right, network, crypto.random, crypto.hasher))
                                    .thenCompose(pair -> pair.left.rotateReadKeys(network, crypto.random, crypto.hasher, pair.right))
                                    .thenCompose(x -> removeOwnedKeyFromParent(parent.get().owner(),
                                            parent.get().signingPair(), toUnshare.writer(), network))
                                    .thenCompose(x -> {
                                        network.sharedWithCache.removeSharedWith(SharedWithCache.Access.WRITE,
                                                toUnshare.getPointer().capability, writersToRemove);
                                        return shareWriteAccessWith(path,
                                                network.sharedWithCache.getSharedWith(SharedWithCache.Access.WRITE,
                                                        toUnshare.getPointer().capability));
                                    })
                            )
                    );
        });
    }

    public CompletableFuture<Boolean> unShareReadAccess(Path path, Set<String> readersToRemove) {
        String pathString = path.toString();
        String absolutePathString = pathString.startsWith("/") ? pathString : "/" + pathString;
        return getByPath(absolutePathString).thenCompose(opt -> {
            FileWrapper toUnshare = opt.orElseThrow(() -> new IllegalStateException("Specified un-shareWith path " + absolutePathString + " does not exist"));
            // now change to new base keys, clean some keys and mark others as dirty
            return getByPath(path.getParent().toString())
                    .thenCompose(parent ->
                            toUnshare.rotateReadKeys(network, crypto.random, crypto.hasher, parent.get())
                                    .thenCompose(markedDirty -> {
                                        AbsoluteCapability cap = toUnshare.getPointer().capability;
                                        network.sharedWithCache.removeSharedWith(SharedWithCache.Access.READ, cap, readersToRemove);
                                        return shareReadAccessWith(path, network.sharedWithCache.getSharedWith(SharedWithCache.Access.READ, cap));
                                    }));
        });
    }

    @JsMethod
    public CompletableFuture<Pair<Set<String>, Set<String>>> sharedWith(FileWrapper file) {
        return file.getPath(network).thenCompose(path -> {
            AbsoluteCapability cap = file.getPointer().capability;
            Set<String> sharedReadAccessWith = network.sharedWithCache.getSharedWith(SharedWithCache.Access.READ, cap);
            Set<String> sharedWriteAccessWith = network.sharedWithCache.getSharedWith(SharedWithCache.Access.WRITE, cap);
            return CompletableFuture.completedFuture(new Pair<>(sharedReadAccessWith, sharedWriteAccessWith));
        });
    }

    public CompletableFuture<Boolean> shareReadAccessWith(Path path, Set<String> readersToAdd) {
        return getByPath(path.toString())
                .thenCompose(file -> shareReadAccessWithAll(file.orElseThrow(() ->
                        new IllegalStateException("Could not find path " + path.toString())), readersToAdd));
    }

    public CompletableFuture<Boolean> shareWriteAccessWith(Path path, Set<String> writersToAdd) {
        return getByPath(path.getParent().toString())
                .thenCompose(parent -> {
                    if (! parent.isPresent())
                        throw new IllegalStateException("Could not find path " + path.getParent().toString());
                    return parent.get().getChild(path.getFileName().toString(), network)
                            .thenCompose(file -> {
                                if (! file.isPresent())
                                    throw new IllegalStateException("Could not find path " + path.toString());
                                return shareWriteAccessWithAll(file.get(), parent.get(), writersToAdd);
                            });
                });
    }

    public CompletableFuture<Boolean> shareReadAccessWithAll(FileWrapper file, Set<String> readersToAdd) {
        ensureAllowedToShare(file, username, false);
        BiFunction<FileWrapper, FileWrapper, CompletableFuture<Boolean>> sharingFunction = (sharedDir, fileWrapper) ->
                CapabilityStore.addReadOnlySharingLinkTo(sharedDir, fileWrapper.getPointer().capability,
                        network, crypto.random, crypto.hasher)
                        .thenCompose(ee -> CompletableFuture.completedFuture(true));
        return Futures.reduceAll(readersToAdd,
                true,
                (x, username) -> shareAccessWith(file, username, sharingFunction),
                (a, b) -> a && b).thenCompose(result -> {
            if (!result) {
                CompletableFuture<Boolean> res = new CompletableFuture<>();
                res.complete(false);
                return res;
            }
            return updatedSharedWithCache(file, readersToAdd, SharedWithCache.Access.READ);
        });
    }

    public CompletableFuture<Boolean> shareWriteAccessWithAll(FileWrapper file,
                                                              FileWrapper parent,
                                                              Set<String> writersToAdd) {
        ensureAllowedToShare(file, username, true);
        SigningKeyPair newSignerPair = SigningKeyPair.random(crypto.random, crypto.signer);

        return addOwnedKeyToParent(parent.owner(), parent.signingPair(), newSignerPair, network)
                .thenCompose(newSigner -> file.changeSigningKey(newSigner, parent, network, crypto.random, crypto.hasher)
                        .thenCompose(updatedFile -> {
                            BiFunction<FileWrapper, FileWrapper, CompletableFuture<Boolean>> sharingFunction =
                                    (sharedDir, fileToShare) -> CapabilityStore.addEditSharingLinkTo(sharedDir,
                                            updatedFile.left.writableFilePointer(), network, crypto.random, crypto.hasher)
                                            .thenCompose(ee -> CompletableFuture.completedFuture(true));
                            return Futures.reduceAll(writersToAdd,
                                    true,
                                    (x, username) -> shareAccessWith(file, username, sharingFunction),
                                    (a, b) -> a && b)
                                    .thenCompose(result -> updatedSharedWithCache(file, writersToAdd, SharedWithCache.Access.WRITE));
                        })
                );
    }

    /**
     * Add an new owned siging key pair to the writer data of a parent signing pair
     * @param owner
     * @param parentSigner
     * @param newSignerPair
     * @param network
     * @return The hashed new signing pair
     */
    public CompletableFuture<SigningPrivateKeyAndPublicHash> addOwnedKeyToParent(PublicKeyHash owner,
                                                                                        SigningPrivateKeyAndPublicHash parentSigner,
                                                                                        SigningKeyPair newSignerPair,
                                                                                        NetworkAccess network) {
        byte[] signature = parentSigner.secret.signatureOnly(newSignerPair.publicSigningKey.serialize());
        return IpfsTransaction.call(owner,
                tid -> network.dhtClient.putSigningKey(signature, owner, parentSigner.publicKeyHash,
                        newSignerPair.publicSigningKey, tid)
                        .thenCompose(newSignerHash -> writeSynchronizer.applyUpdate(owner, parentSigner.publicKeyHash, wd -> {
                                    SigningPrivateKeyAndPublicHash newSigner =
                                            new SigningPrivateKeyAndPublicHash(newSignerHash, newSignerPair.secretSigningKey);
                                    return wd.props.addOwnedKey(owner, parentSigner,
                                            OwnerProof.build(newSigner, parentSigner.publicKeyHash), network.dhtClient)
                                            .thenCompose(updated -> updated.commit(owner, parentSigner, wd.hash, network, tid));
                                }).thenApply(cwd -> newSignerHash)
                        ).thenApply(newSignerHash -> new SigningPrivateKeyAndPublicHash(newSignerHash, newSignerPair.secretSigningKey))
                , network.dhtClient);
    }

    /**
     * Remove an owned signing key pair from the writer data of a parent signing pair
     * @param owner
     * @param parentSigner
     * @param toRemove
     * @param network
     * @return The hashed new signing pair
     */
    public CompletableFuture<CommittedWriterData> removeOwnedKeyFromParent(PublicKeyHash owner,
                                                                                  SigningPrivateKeyAndPublicHash parentSigner,
                                                                                  PublicKeyHash toRemove,
                                                                                  NetworkAccess network) {
        return IpfsTransaction.call(owner,
                tid -> writeSynchronizer.applyUpdate(owner, parentSigner.publicKeyHash, wd ->
                        wd.props.removeOwnedKey(owner, parentSigner, toRemove, network.dhtClient)
                                .thenCompose(updated -> updated.commit(owner, parentSigner, wd.hash, network, tid))),
                network.dhtClient);
    }

    private CompletableFuture<Boolean> updatedSharedWithCache(FileWrapper file, Set<String> usersToAdd,
                                                              SharedWithCache.Access access) {
        return file.getPath(network).thenCompose(path -> {
            network.sharedWithCache.addSharedWith(access, file.getPointer().capability, usersToAdd);
            CompletableFuture<Boolean> res = new CompletableFuture<>();
            res.complete(true);
            return res;
        });
    }

    @JsMethod
    public CompletableFuture<Boolean> shareReadAccessWith(FileWrapper file, String usernameToGrantReadAccess) {
        Set<String> readersToAdd = new HashSet<>();
        readersToAdd.add(usernameToGrantReadAccess);
        return shareReadAccessWithAll(file, readersToAdd);
    }

    @JsMethod
    public CompletableFuture<Boolean> shareWriteAccessWith(FileWrapper file, FileWrapper parent, String usernameToGrantWriteAccess) {
        Set<String> readersToAdd = new HashSet<>();
        readersToAdd.add(usernameToGrantWriteAccess);
        return shareWriteAccessWithAll(file, parent, readersToAdd);
    }

    public CompletableFuture<Boolean> shareAccessWith(FileWrapper file, String usernameToGrantAccess,
                                                      BiFunction<FileWrapper, FileWrapper, CompletableFuture<Boolean>> sharingFunction) {
        return getByPath("/" + username + "/shared/" + usernameToGrantAccess)
                .thenCompose(shared -> {
                    if (!shared.isPresent())
                        return CompletableFuture.completedFuture(true);
                    FileWrapper sharedDir = shared.get();
                    return sharingFunction.apply(sharedDir, file);
                });
    }

    private synchronized CompletableFuture<TrieNode> addRootEntryPointAndCommit(TrieNode root, EntryPoint entry) {
        return writeSynchronizer.applyUpdate(signer.publicKeyHash, signer.publicKeyHash, wd -> {
            Optional<UserStaticData> updated = wd.props.staticData.map(sd -> {
                List<EntryPoint> entryPoints = sd.getEntryPoints(rootKey);
                entryPoints.add(entry);
                return new UserStaticData(entryPoints, rootKey);
            });
            return IpfsTransaction.call(signer.publicKeyHash,
                    tid -> wd.props.withStaticData(updated)
                            .commit(signer.publicKeyHash, signer, wd.hash, network, tid),
                    network.dhtClient
            );
        }).thenCompose(res -> addEntryPoint(username, root, entry, network, crypto.random, crypto.hasher));
    }

    private synchronized CompletableFuture<TrieNode> addExternalEntryPoint(TrieNode root, EntryPoint entry) {
        boolean isOurs = username.equals(entry.ownerName);
        String filename = isOurs ? ENTRY_POINTS_FROM_US_FILENAME : ENTRY_POINTS_FROM_FRIENDS_FILENAME;
        return getByPath(Paths.get(username, filename))
                .thenCompose(existing -> {
                    long offset = existing.map(f -> f.getSize()).orElse(0L);
                    byte[] data = entry.serialize();
                    AsyncReader reader = AsyncReader.build(data);
                    Optional<SymmetricKey> base = existing.map(f -> f.getPointer().capability.rBaseKey);
                    return getUserRoot().thenCompose(home ->
                            home.uploadFileSection(filename, reader, true, offset,
                                    offset + data.length, base, true, network,
                                    crypto.random, crypto.hasher, x -> {},
                                    home.generateChildLocations(1, crypto.random)));
                }).thenCompose(res -> addEntryPoint(username, root, entry, network, crypto.random, crypto.hasher));
    }

    private CompletableFuture<List<BlindFollowRequest>> getFollowRequests() {
        byte[] time = new CborObject.CborLong(System.currentTimeMillis()).serialize();
        byte[] auth = signer.secret.signMessage(time);
        return network.social.getFollowRequests(signer.publicKeyHash, auth).thenApply(reqs -> {
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
                getFollowerRoots().thenCompose(followerRoots -> {
                    List<FollowRequestWithCipherText> withDecrypted = all.stream()
                            .map(b -> new FollowRequestWithCipherText(b.followRequest.decrypt(boxer.secretBoxingKey, b.dummySource, FollowRequest::fromCbor), b))
                            .collect(Collectors.toList());

                    List<FollowRequestWithCipherText> replies = withDecrypted.stream()
                            .filter(p -> followerRoots.containsKey(p.req.entry.get().ownerName))
                            .collect(Collectors.toList());

                    BiFunction<TrieNode, FollowRequestWithCipherText, CompletableFuture<TrieNode>> addToStatic = (root, p) -> {
                        FollowRequest freq = p.req;
                        if (!Arrays.equals(freq.entry.get().pointer.rBaseKey.serialize(), SymmetricKey.createNull().serialize())) {
                            CompletableFuture<TrieNode> updatedRoot = freq.entry.get().ownerName.equals(username) ?
                                    CompletableFuture.completedFuture(root) : // ignore responses claiming to be owned by us
                                    addExternalEntryPoint(root, freq.entry.get());
                            return updatedRoot.thenCompose(newRoot -> {
                                entrie = newRoot;
                                // clear their response follow req too
                                return network.social.removeFollowRequest(signer.publicKeyHash, signer.secret.signMessage(p.cipher.serialize()))
                                        .thenApply(b -> newRoot);
                            });
                        }
                        return CompletableFuture.completedFuture(root);
                    };

                    BiFunction<TrieNode, FollowRequestWithCipherText, CompletableFuture<TrieNode>> mozart = (trie, p) -> {
                        FollowRequest freq = p.req;
                        // delete our folder if they didn't reciprocate
                        FileWrapper ourDirForThem = followerRoots.get(freq.entry.get().ownerName);
                        byte[] ourKeyForThem = ourDirForThem.getKey().serialize();
                        byte[] keyFromResponse = freq.key.map(k -> k.serialize()).orElse(null);
                        if (keyFromResponse == null || !Arrays.equals(keyFromResponse, ourKeyForThem)) {
                            // They didn't reciprocate (follow us)
                            CompletableFuture<FileWrapper> removeDir = ourDirForThem.remove(sharing, network, crypto.hasher);

                            return removeDir.thenCompose(b -> addToStatic.apply(trie, p));
                        } else if (freq.entry.get().pointer.isNull()) {
                            // They reciprocated, but didn't accept (they follow us, but we can't follow them)
                            // add entry point to static data to signify their acceptance
                            EntryPoint entryWeSentToThem = new EntryPoint(ourDirForThem.getPointer().capability.readOnly(),
                                    username);
                            return addExternalEntryPoint(trie, entryWeSentToThem);
                        } else {
                            // they accepted and reciprocated
                            // add entry point to static data to signify their acceptance
                            EntryPoint entryWeSentToThem = new EntryPoint(ourDirForThem.getPointer().capability.readOnly(),
                                    username);

                            // add new entry point to tree root
                            EntryPoint entry = freq.entry.get();
                            if (entry.ownerName.equals(username))
                                throw new IllegalStateException("Received a follow request claiming to be owned by us!");
                            return addExternalEntryPoint(trie, entryWeSentToThem)
                                    .thenCompose(newRoot -> network.retrieveEntryPoint(entry)
                                            .thenCompose(treeNode ->
                                                    treeNode.get().getPath(network))
                                            .thenApply(path -> newRoot.put(path, entry)
                                            ).thenCompose(trieres -> addToStatic.apply(trieres, p)));
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
                })
        );
    }

    public CompletableFuture<Set<FileWrapper>> getChildren(String path) {
        return entrie.getChildren(path, network);
    }

    public CompletableFuture<Optional<FileWrapper>> getByPath(Path path) {
        return getByPath(path.toString());
    }

    @JsMethod
    public CompletableFuture<Optional<FileWrapper>> getByPath(String path) {
        if (path.equals("/"))
            return CompletableFuture.completedFuture(Optional.of(FileWrapper.createRoot(entrie)));
        return entrie.getByPath(path.startsWith("/") ? path : "/" + path, network);
    }

    public CompletableFuture<FileWrapper> getUserRoot() {
        return getByPath("/" + username).thenApply(opt -> opt.get());
    }

    /**
     * @return TrieNode for root of filesystem containing only our files
     */
    private static CompletableFuture<TrieNode> createOurFileTreeOnly(String ourName,
                                                                     SymmetricKey rootKey,
                                                                     WriterData userData,
                                                                     NetworkAccess network,
                                                                     SafeRandom random,
                                                                     Hasher hasher,
                                                                     Fragmenter fragmenter) {
        TrieNode root = TrieNodeImpl.empty();
        if (!userData.staticData.isPresent())
            throw new IllegalStateException("Cannot retrieve file tree for a filesystem without entrypoints!");
        List<EntryPoint> ourFileSystemEntries = userData.staticData.get()
                .getEntryPoints(rootKey)
                .stream()
                .filter(e -> e.ownerName.equals(ourName))
                .collect(Collectors.toList());
        return Futures.reduceAll(ourFileSystemEntries, root, (t, e) -> addEntryPoint(ourName, t, e, network, random, hasher), (a, b) -> a)
                .exceptionally(Futures::logAndThrow);
    }

    /**
     * @return TrieNode for root of filesystem
     */
    private CompletableFuture<TrieNode> createFileTree(TrieNode ourRoot,
                                                       String ourName,
                                                       NetworkAccess network,
                                                       SafeRandom random,
                                                       Hasher hasher) {
        // need to to retrieve all the entry points of our friends
        return getFriendsEntryPoints()
                .thenCompose(friendEntries -> Futures.reduceAll(friendEntries, ourRoot,
                        (t, e) -> addEntryPoint(ourName, t, e, network, random, hasher), (a, b) -> a))
                .exceptionally(Futures::logAndThrow);
    }

    private CompletableFuture<List<EntryPoint>> getFriendsEntryPoints() {
        return getByPath(Paths.get(username, ENTRY_POINTS_FROM_FRIENDS_FILENAME))
                .thenCompose(fopt -> fopt
                        .map(f -> {
                            List<EntryPoint> res = new ArrayList<>();
                            return f.getInputStream(network, crypto.random, x -> {})
                                    .thenCompose(reader -> reader.parseStream(EntryPoint::fromCbor, res::add, f.getSize())
                                            .thenApply(x -> res));
                        }).orElse(CompletableFuture.completedFuture(Collections.emptyList())))
                .thenCompose(fromFriends -> {
                    // filter out blocked friends
                    return getByPath(Paths.get(username, BLOCKED_USERNAMES_FILE))
                            .thenCompose(fopt -> fopt
                                    .map(f -> f.getInputStream(network, crypto.random, x -> {})
                                            .thenCompose(in -> Serialize.readFully(in, f.getSize()))
                                            .thenApply(data -> new HashSet<>(Arrays.asList(new String(data).split("\n")))
                                                    .stream()
                                                    .collect(Collectors.toSet())))
                                    .orElse(CompletableFuture.completedFuture(Collections.emptySet())))
                            .thenApply(toRemove -> fromFriends.stream()
                                    .filter(e -> ! toRemove.contains(e.ownerName))
                                    .collect(Collectors.toList()));
                });
    }

    private static CompletableFuture<TrieNode> addRetrievedEntryPoint(String ourName,
                                                                      TrieNode root,
                                                                      EntryPoint fileCap,
                                                                      String path,
                                                                      NetworkAccess network,
                                                                      SafeRandom random,
                                                                      Hasher hasher) {
        // check entrypoint doesn't forge the owner
        return (fileCap.ownerName.equals(ourName) ? CompletableFuture.completedFuture(true) :
                fileCap.isValid(path, network)).thenCompose(valid -> {
            String[] parts = path.split("/");
            if (parts.length < 3 || !parts[2].equals(SHARED_DIR_NAME))
                return CompletableFuture.completedFuture(root.put(path, fileCap));
            String username = parts[1];
            if (username.endsWith(ourName)) // This is a sharing directory of ours for a friend
                return CompletableFuture.completedFuture(root);
            // This is a friend's sharing directory, create a wrapper to read the capabilities lazily from it
            Supplier<CompletableFuture<FileWrapper>> cacheDirSupplier =
                    () -> root.getByPath(Paths.get(ourName).toString(), network).thenApply(opt -> opt.get());
            return FriendSourcedTrieNode.build(cacheDirSupplier, fileCap, network, random, hasher)
                    .thenApply(fromUser -> fromUser.map(userEntrie -> root.putNode(username, userEntrie)).orElse(root));
        });
    }

    private static CompletableFuture<TrieNode> addEntryPoint(String ourName,
                                                             TrieNode root,
                                                             EntryPoint e,
                                                             NetworkAccess network,
                                                             SafeRandom random,
                                                             Hasher hasher) {
        return network.retrieveEntryPoint(e).thenCompose(metadata -> {
            if (metadata.isPresent()) {
                return metadata.get().getPath(network)
                        .thenCompose(path -> addRetrievedEntryPoint(ourName, root, e, path, network, random, hasher)
                                .exceptionally(t -> {
                                    LOG.log(Level.WARNING, t.getMessage(), t);
                                    LOG.severe("Couldn't add entry point (failed retrieving parent dir or it was invalid): " + metadata.get().getName());
                                    // Allow the system to continue without this entry point
                                    return root;
                                })
                        );
            }
            return CompletableFuture.completedFuture(root);
        }).exceptionally(Futures::logAndThrow);
    }

    public static CompletableFuture<CommittedWriterData> getWriterData(NetworkAccess network, PublicKeyHash owner, PublicKeyHash writer) {
        return getWriterDataCbor(network, owner, writer)
                .thenApply(pair -> new CommittedWriterData(MaybeMultihash.of(pair.left), WriterData.fromCbor(pair.right)));
    }

    public static CompletableFuture<Pair<Multihash, CborObject>> getWriterDataCbor(NetworkAccess network, String username) {
        return network.coreNode.getPublicKeyHash(username)
                .thenCompose(signer -> {
                    PublicKeyHash owner = signer.orElseThrow(
                            () -> new IllegalStateException("No public-key for user " + username));
                    return getWriterDataCbor(network, owner, owner);
                });
    }

    private static CompletableFuture<Pair<Multihash, CborObject>> getWriterDataCbor(NetworkAccess network, PublicKeyHash owner, PublicKeyHash writer) {
        return network.mutable.getPointer(owner, writer)
                .thenCompose(casOpt -> network.dhtClient.getSigningKey(writer)
                        .thenApply(signer -> casOpt.map(raw -> HashCasPair.fromCbor(CborObject.fromByteArray(
                                signer.get().unsignMessage(raw))).updated)
                                .orElse(MaybeMultihash.empty())))
                .thenCompose(key -> network.dhtClient.get(key.get())
                        .thenApply(Optional::get)
                        .thenApply(cbor -> new Pair<>(key.get(), cbor))
                );
    }

    @JsMethod
    public CompletableFuture<Boolean> unfollow(String friendName) {
        LOG.info("Unfollowing: " + friendName);
        return getUserRoot()
                .thenCompose(home -> home.appendToChild(BLOCKED_USERNAMES_FILE, (friendName + "\n").getBytes(), true,
                        network, crypto.random, crypto.hasher, x -> {}))
                .thenApply(b -> {
                    entrie = entrie.removeEntry("/" + friendName + "/");
                    return true;
                });
    }

    @JsMethod
    public CompletableFuture<Boolean> removeFollower(String username) {
        LOG.info("Remove follower: " + username);
        // remove /$us/shared/$them
        return getSharingFolder()
                .thenCompose(sharing -> getByPath(Paths.get(this.username, SHARED_DIR_NAME, username))
                        .thenCompose(dir -> dir.get().remove(sharing, network, crypto.hasher)))
                .thenApply(x -> true);
    }

    public void logout() {
        entrie = TrieNodeImpl.empty();
    }

    @JsMethod
    public CompletableFuture<Boolean> submitFeedback(String feedback) {
        LOG.info("Checking if feedback directory exists before posting...");

        String timestamp = LocalDateTime.now().toString();
        String filename = "feedback_" + timestamp + ".txt";
        Path path = Paths.get(username, FEEDBACK_DIR_NAME);

        return getByPath(path)
            .thenCompose(feedbackWrapper -> {
                if (feedbackWrapper.isPresent()) {
                    LOG.info("Feedback directory already exists... nothing to do here!");
                    return CompletableFuture.completedFuture(feedbackWrapper.get());
                } else {
                    LOG.info("Creating a directory for feedback!");
                    return getUserRoot()
                        .thenCompose(root -> root.mkdir(FEEDBACK_DIR_NAME, network, false, crypto.random, crypto.hasher))
                        .thenCompose(dir -> getByPath(path))
                        .thenApply(Optional::get);
                }
            }
            )
            .thenCompose(feedbackWrapper -> {
                LOG.info("Posting the feedback!");
                byte[] feedbackBytes = feedback.getBytes();
                return feedbackWrapper.uploadOrOverwriteFile(filename, AsyncReader.build(feedbackBytes), feedbackBytes.length,
                        network, crypto.random, crypto.hasher, x -> {}, feedbackWrapper.generateChildLocationsFromSize(feedbackBytes.length, crypto.random));
            }
            )
            .thenCompose(x -> shareReadAccessWith(path.resolve(filename), Collections.singleton(PEERGOS_USERNAME)));
    }

}
