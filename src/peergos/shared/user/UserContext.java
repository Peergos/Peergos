package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.Multihash;
import peergos.shared.merklebtree.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import jsinterop.annotations.*;

public class UserContext {
    private static final boolean LOGGING = false;

    public static final String SHARED_DIR_NAME = "shared";
    @JsProperty
    public final String username;
    public final SigningPrivateKeyAndPublicHash signer;
    public final BoxingKeyPair boxer;
    public final Fragmenter fragmenter;
    public final Set<String> filesSharedWithFriends; //String is Location.toString()

    private CompletableFuture<CommittedWriterData> userData;
    @JsProperty
    public TrieNode entrie; // ba dum che!

    // Contact external world
    @JsProperty
    public final NetworkAccess network;

    // In process only
    @JsProperty
    public final Crypto crypto;

    public UserContext(String username, SigningPrivateKeyAndPublicHash signer, BoxingKeyPair boxer, NetworkAccess network, Crypto crypto,
                       CompletableFuture<CommittedWriterData> userData, TrieNode entrie) {
        this(username, signer, boxer, network, crypto, Fragmenter.getInstance(), userData, entrie);
    }

    public UserContext(String username, SigningPrivateKeyAndPublicHash signer, BoxingKeyPair boxer, NetworkAccess network,
                       Crypto crypto, Fragmenter fragmenter, CompletableFuture<CommittedWriterData> userData,
                       TrieNode entrie) {
        this.username = username;
        this.signer = signer;
        this.boxer = boxer;
        this.network = network;
        this.crypto = crypto;
        this.fragmenter = fragmenter;
        this.userData = userData;
        this.entrie = entrie;
        this.filesSharedWithFriends = new HashSet<>();
    }

    public boolean isJavascript() {
    	return this.network.isJavascript();
    }

    @JsMethod
    public static CompletableFuture<UserContext> signIn(String username, String password, NetworkAccess network, Crypto crypto) {
        return getWriterDataCbor(network, username)
                .thenCompose(pair -> {
                    Optional<UserGenerationAlgorithm> algorithmOpt = WriterData.extractUserGenerationAlgorithm(pair.right);
                    if (!algorithmOpt.isPresent())
                        throw new IllegalStateException("No login algorithm specified in user data!");
                    UserGenerationAlgorithm algorithm = algorithmOpt.get();
                    return UserUtil.generateUser(username, password, crypto.hasher, crypto.symmetricProvider,
                            crypto.random, crypto.signer, crypto.boxer, algorithm)
                            .thenCompose(userWithRoot -> {
                                try {
                                    WriterData userData = WriterData.fromCbor(pair.right, userWithRoot.getRoot());
                                    return createOurFileTreeOnly(username, userData, network)
                                            .thenCompose(root -> TofuCoreNode.load(username, root, network, crypto.random)
                                                    .thenCompose(keystore -> {
                                                        TofuCoreNode tofu = new TofuCoreNode(network.coreNode, keystore);
                                                        UserContext result = new UserContext(username,
                                                                new SigningPrivateKeyAndPublicHash(userData.controller, userWithRoot.getUser().secretSigningKey),
                                                                userWithRoot.getBoxingPair(), network.withCorenode(tofu), crypto,
                                                                CompletableFuture.completedFuture(new CommittedWriterData(MaybeMultihash.of(pair.left), userData)),
                                                                root);
                                                        tofu.setContext(result);
                                                        return result.getUsernameClaimExpiry()
                                                                .thenCompose(expiry -> expiry.isBefore(LocalDate.now().plusMonths(1)) ?
                                                                        result.renewUsernameClaim(LocalDate.now().plusMonths(2)) :
                                                                        CompletableFuture.completedFuture(true))
                                                                .thenCompose(x -> {
                                                                    System.out.println("Initializing context..");
                                                                    return result.init();
                                                                }).exceptionally(Futures::logError);
                                                    }));
                                } catch (Throwable t) {
                                    throw new IllegalStateException("Incorrect password");
                                }
                            });
                }).exceptionally(Futures::logError);
    }

    @JsMethod
    public static CompletableFuture<UserContext> signUp(String username, String password, NetworkAccess network, Crypto crypto) {
        return signUpGeneral(username, password, network, crypto, UserGenerationAlgorithm.getDefault());
    }

    public static CompletableFuture<UserContext> signUpGeneral(String username,
                                                               String password,
                                                               NetworkAccess network,
                                                               Crypto crypto,
                                                               UserGenerationAlgorithm algorithm) {
        return UserUtil.generateUser(username, password, crypto.hasher, crypto.symmetricProvider, crypto.random, crypto.signer, crypto.boxer, algorithm)
                .thenCompose(userWithRoot -> {
                    PublicSigningKey publicSigningKey = userWithRoot.getUser().publicSigningKey;
                    return network.dhtClient.putSigningKey(publicSigningKey)
                            .thenCompose(signerHash ->
                                    network.dhtClient.putBoxingKey(signerHash, userWithRoot.getBoxingPair().publicBoxingKey)
                                    .thenCompose(boxerHash -> {
                                        WriterData newUserData = WriterData.createEmpty(
                                                signerHash,
                                                Optional.of(new PublicKeyHash(boxerHash)),
                                                userWithRoot.getRoot());

                                        CommittedWriterData notCommitted = new CommittedWriterData(MaybeMultihash.empty(), newUserData);
                                        SigningPrivateKeyAndPublicHash signer = new SigningPrivateKeyAndPublicHash(signerHash, userWithRoot.getUser().secretSigningKey);
                                        UserContext context = new UserContext(username, signer, userWithRoot.getBoxingPair(),
                                                network, crypto, CompletableFuture.completedFuture(notCommitted), new TrieNode());
                                        System.out.println("Registering username " + username);
                                        return context.register().thenCompose(successfullyRegistered -> {
                                            if (!successfullyRegistered) {
                                                System.out.println("Couldn't register username");
                                                throw new IllegalStateException("Couldn't register username: " + username);
                                            }
                                            System.out.println("Creating user's root directory");
                                            long t1 = System.currentTimeMillis();
                                            return context.createEntryDirectory(username).thenCompose(userRoot -> {
                                                System.out.println("Creating root directory took " + (System.currentTimeMillis() - t1) + " mS");
                                                return ((DirAccess) userRoot.fileAccess).mkdir(
                                                        SHARED_DIR_NAME,
                                                        network,
                                                        userRoot.filePointer.getLocation().owner,
                                                        userRoot.filePointer.signer(),
                                                        userRoot.filePointer.location.getMapKey(),
                                                        userRoot.filePointer.baseKey,
                                                        null,
                                                        true,
                                                        crypto.random)
                                                        .thenCompose(x -> signIn(username, password, network.clear(), crypto));
                                            });
                                        });
                                    }));
                }).exceptionally(Futures::logError);
    }

    @JsMethod
    public static CompletableFuture<UserContext> fromPublicLink(String link, NetworkAccess network, Crypto crypto) {
        FilePointer entryPoint = FilePointer.fromLink(link);
        EntryPoint entry = new EntryPoint(entryPoint, "", Collections.emptySet(), Collections.emptySet());
        WriterData empty = WriterData.createEmpty(entryPoint.location.owner, Optional.empty(), null);
        CommittedWriterData committed = new CommittedWriterData(MaybeMultihash.empty(), empty);
        CompletableFuture<CommittedWriterData> userData = CompletableFuture.completedFuture(committed);
        UserContext context = new UserContext(null, null, null, network.clear(), crypto, userData, new TrieNode());
        return context.addEntryPoint(null, context.entrie, entry, network).thenApply(trieNode -> {
            context.entrie = trieNode;
            return context;
        });
    }

    @JsMethod
    public CompletableFuture<String> getEntryPath() {
        if (username != null)
            return CompletableFuture.completedFuture("/");

        CompletableFuture<Optional<FileTreeNode>> dir = getByPath("/");
        return dir.thenCompose(opt -> getLinkPath(opt.get()))
                .thenApply(path -> path.substring(1)); // strip off extra slash at root
    }

    private CompletableFuture<String> getLinkPath(FileTreeNode file) {
        if (! file.isDirectory())
            return CompletableFuture.completedFuture("");
        return file.getChildren(network)
                .thenCompose(children -> {
                    if (children.size() != 1)
                        return CompletableFuture.completedFuture(file.getName());
                    FileTreeNode child = children.stream().findAny().get();
                    if (child.isReadable()) // case where a directory was shared with exactly one direct child
                        return CompletableFuture.completedFuture(file.getName() + "/" + child.getName());
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

    private CompletableFuture<UserContext> init() {
        CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();
        return addToUserDataQueue(lock)
                .thenCompose(wd -> createFileTree(entrie, username, wd.props, network)
                        .thenCompose(root -> {
                            this.entrie = root;
                            return getByPath("/" + username + "/" + "shared")
                                    .thenApply(sharedOpt -> {
                                        if (!sharedOpt.isPresent())
                                            throw new IllegalStateException("Couldn't find shared folder!");
                                        lock.complete(wd);
                                        return this;
                                    });
                        }));
    }

    public CompletableFuture<Boolean> cleanEntryPoints() {
        CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();
        return addToUserDataQueue(lock)
                .thenCompose(wd -> Futures.reduceAll(
                                wd.props.staticData.get().getEntryPoints(),
                                true,
                                (t, e) -> cleanOurEntryPoint(e),
                                (a, b) -> a && b));
    }

    public CompletableFuture<FileTreeNode> getSharingFolder() {
        return getByPath("/"+username + "/shared").thenApply(opt -> opt.get());
    }

    @JsMethod
    public CompletableFuture<Boolean> isRegistered() {
        System.out.println("isRegistered");
        return network.coreNode.getUsername(signer.publicKeyHash).thenApply(registeredUsername -> {
            System.out.println("got username \"" + registeredUsername + "\"");
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
            LocalDate now = LocalDate.now();
            // set claim expiry to two months from now
            LocalDate expiry = now.plusMonths(2);
            System.out.println("claiming username: " + this.username + " with expiry " + expiry);
            List<UserPublicKeyLink> claimChain = UserPublicKeyLink.createInitial(signer, this.username, expiry);
            return network.coreNode.updateChain(this.username, claimChain);
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
        System.out.println("renewing username: " + username + " with expiry " + expiry);
        List<UserPublicKeyLink> claimChain = UserPublicKeyLink.createInitial(signer, username, expiry);
        return network.coreNode.updateChain(username, claimChain);
    }

    @JsMethod
    public CompletableFuture<Pair<Integer, Integer>> getTotalSpaceUsedJS(PublicKeyHash owner) {
        return getTotalSpaceUsed(owner)
                .thenApply(size -> new Pair<>((int)(size >> 32), size.intValue()));
    }

    public CompletableFuture<Long> getTotalSpaceUsed(PublicKeyHash ownerHash) {
        // assume no cycles in owned keys
        return getSigningKey(ownerHash)
                .thenCompose(owner -> getWriterData(network, ownerHash)
                        .thenCompose(cwd -> {
                            CompletableFuture<Long> subtree = Futures.reduceAll(cwd.props.ownedKeys
                                            .stream()
                                            .map(writer -> getTotalSpaceUsed(writer))
                                            .collect(Collectors.toList()),
                                    0L, (t, fut) -> fut.thenApply(x -> x + t), (a, b) -> a + b);
                            return subtree.thenCompose(ownedSize -> getRecursiveBlockSize(cwd.hash.get())
                                    .thenApply(descendentSize -> descendentSize + ownedSize));
                        }));
    }

    private CompletableFuture<Long> getRecursiveBlockSize(Multihash block) {
        return network.dhtClient.getLinks(block).thenCompose(links -> {
            List<CompletableFuture<Long>> subtrees = links.stream().map(this::getRecursiveBlockSize).collect(Collectors.toList());
            return network.dhtClient.getSize(block)
                    .thenCompose(sizeOpt -> {
                        CompletableFuture<Long> reduced = Futures.reduceAll(subtrees,
                                0L, (t, fut) -> fut.thenApply(x -> x + t), (a, b) -> a + b);
                        return reduced.thenApply(sum -> sum + sizeOpt.orElse(0));
                    });
        });
    }

    public CompletableFuture<UserGenerationAlgorithm> getKeyGenAlgorithm() {
        return getWriterDataCbor(this.network, this.username)
                .thenApply(pair -> {
                    Optional<UserGenerationAlgorithm> algorithmOpt = WriterData.extractUserGenerationAlgorithm(pair.right);
                    if (!algorithmOpt.isPresent())
                        throw new IllegalStateException("No login algorithm specified in user data!");
                    return algorithmOpt.get();
                });
    }

    @JsMethod
    public CompletableFuture<UserContext> changePassword(String oldPassword, String newPassword) {

        return getWriterDataCbor(this.network, this.username)
                .thenCompose(pair -> {
                    Optional<UserGenerationAlgorithm> algorithmOpt = WriterData.extractUserGenerationAlgorithm(pair.right);
                    if (! algorithmOpt.isPresent())
                        throw new IllegalStateException("No login algorithm specified in user data!");
                    UserGenerationAlgorithm algorithm = algorithmOpt.get();
                    return changePassword(oldPassword, newPassword, algorithm, algorithm);
                });
    }
    public CompletableFuture<UserContext> changePassword(String oldPassword, String newPassword,
                                                         UserGenerationAlgorithm existingAlgorithm,
                                                         UserGenerationAlgorithm newAlgorithm) {
        // set claim expiry to two months from now
        LocalDate expiry = LocalDate.now().plusMonths(2);
        System.out.println("Changing password and setting expiry to: " + expiry);

        return UserUtil.generateUser(username, oldPassword, crypto.hasher, crypto.symmetricProvider, crypto.random, crypto.signer, crypto.boxer, existingAlgorithm)
                .thenCompose(existingUser -> {
                    if (!existingUser.getUser().secretSigningKey.equals(this.signer.secret))
                        throw new IllegalArgumentException("Incorrect existing password during change password attempt!");
                    return UserUtil.generateUser(username, newPassword, crypto.hasher, crypto.symmetricProvider,
                            crypto.random, crypto.signer, crypto.boxer, newAlgorithm)
                            .thenCompose(updatedUser ->{
                                CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();
                                return addToUserDataQueue(lock)
                                        .thenCompose(wd -> network.dhtClient.putSigningKey(updatedUser.getUser().publicSigningKey)
                                                .thenCompose(newSignerHash -> wd.props
                                                        .changeKeys(new SigningPrivateKeyAndPublicHash(newSignerHash, updatedUser.getUser().secretSigningKey),
                                                                wd.hash,
                                                                updatedUser.getBoxingPair().publicBoxingKey,
                                                                updatedUser.getRoot(),
                                                                newAlgorithm,
                                                                network,
                                                                lock::complete)
                                                        .thenCompose(userData -> {
                                                            SigningPrivateKeyAndPublicHash newUser =
                                                                    new SigningPrivateKeyAndPublicHash(newSignerHash, updatedUser.getUser().secretSigningKey);
                                                            List<UserPublicKeyLink> claimChain = UserPublicKeyLink.createChain(signer, newUser, username, expiry);
                                                            return network.coreNode.updateChain(username, claimChain)
                                                                    .thenCompose(updatedChain -> {
                                                                        if (!updatedChain)
                                                                            throw new IllegalStateException("Couldn't register new public keys during password change!");

                                                                        return UserContext.ensureSignedUp(username, newPassword, network, crypto);
                                                                    });
                                                        })
                                                )
                                        );
                            });
                });
    }

    public CompletableFuture<RetrievedFilePointer> createEntryDirectory(String directoryName) {
        long t1 = System.currentTimeMillis();
        SigningKeyPair writer = SigningKeyPair.random(crypto.random, crypto.signer);
        System.out.println("Random User generation took " + (System.currentTimeMillis()-t1) + " mS");
        return network.dhtClient.putSigningKey(writer.publicSigningKey).thenCompose(writerHash -> {
            byte[] rootMapKey = new byte[32]; // root will be stored under this in the core node
            crypto.random.randombytes(rootMapKey, 0, 32);
            SymmetricKey rootRKey = SymmetricKey.random();
            System.out.println("Random keys generation took " + (System.currentTimeMillis() - t1) + " mS");

            // and authorise the writer key
            SigningPrivateKeyAndPublicHash writerWithHash = new SigningPrivateKeyAndPublicHash(writerHash, writer.secretSigningKey);
            FilePointer rootPointer = new FilePointer(this.signer.publicKeyHash, writerWithHash, rootMapKey, rootRKey);
            EntryPoint entry = new EntryPoint(rootPointer, this.username, Collections.emptySet(), Collections.emptySet());

            long t2 = System.currentTimeMillis();
            DirAccess root = DirAccess.create(MaybeMultihash.empty(), rootRKey, new FileProperties(directoryName, "",
                    0, LocalDateTime.now(), false, Optional.empty()), (Location) null, null, null);
            Location rootLocation = new Location(this.signer.publicKeyHash, writerHash, rootMapKey);
            System.out.println("Uploading entry point directory");
            return network.uploadChunk(root, rootLocation, writerWithHash).thenCompose(chunkHash -> {
                long t3 = System.currentTimeMillis();
                System.out.println("Uploading root dir metadata took " + (t3 - t2) + " mS");
                return addToStaticDataAndCommit(entry)
                        .thenCompose(x -> addOwnedKeyAndCommit(entry.pointer.location.writer))
                        .thenApply(x -> {
                            System.out.println("Committing static data took " + (System.currentTimeMillis() - t3) + " mS");
                            return new RetrievedFilePointer(rootPointer, root.withHash(chunkHash));
                        });
            });
        });
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
                        .thenCompose(signer -> getWriterData(network, signerOpt.get())
                                .thenCompose(wd -> getBoxingKey(wd.props.followRequestReceiver.get())
                                        .thenApply(boxer -> Optional.of(new Pair<>(signerOpt.get(), boxer))))));
    }

    private synchronized CompletableFuture<CommittedWriterData> addToUserDataQueue(CompletableFuture<CommittedWriterData> replacement) {
        CompletableFuture<CommittedWriterData> existing = this.userData;
        this.userData = replacement;
        return existing;
    }

    private CompletableFuture<CommittedWriterData> addOwnedKeyAndCommit(PublicKeyHash owned) {
        CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();
        return addToUserDataQueue(lock)
                .thenCompose(wd -> {
                    Set<PublicKeyHash> updated = Stream.concat(
                            wd.props.ownedKeys.stream(),
                            Stream.of(owned)
                    ).collect(Collectors.toSet());

                    WriterData writerData = wd.props.withOwnedKeys(updated);
                    return writerData.commit(signer, wd.hash, network, lock::complete);
                });
    }

    @JsMethod
    public CompletableFuture<Set<FileTreeNode>> getFriendRoots() {
        List<CompletableFuture<Optional<FileTreeNode>>> friendRoots = entrie.getChildNames()
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
                        .map(froot -> froot.getOwner())
                        .filter(name -> !name.equals(username))
                        .collect(Collectors.toSet()));
    }

    @JsMethod
    public CompletableFuture<Set<String>> getFollowerNames() {
        return getFollowerRoots().thenApply(m -> m.keySet());
    }

    public CompletableFuture<Map<String, FileTreeNode>> getFollowerRoots() {
        return getSharingFolder()
                .thenCompose(sharing -> sharing.getChildren(network))
                .thenApply(children -> children.stream()
                        .collect(Collectors.toMap(e -> e.getFileProperties().name, e -> e)));
    }

    private CompletableFuture<Set<String>> getFollowers() {
        CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();
        return addToUserDataQueue(lock).thenApply(wd -> {
            lock.complete(wd);
            return wd.props.staticData.get()
                    .getEntryPoints()
                    .stream()
                    .map(e -> e.owner)
                    .filter(name -> ! name.equals(username))
                    .collect(Collectors.toSet());
        });
    }

    @JsMethod
    public CompletableFuture<SocialState> getSocialState() {
        return processFollowRequests()
                .thenCompose(pending -> getFollowerRoots()
                        .thenCompose(followerRoots -> getFriendRoots()
                                .thenCompose(followingRoots -> getFollowers().thenApply(followers -> new SocialState(pending, followers, followerRoots, followingRoots)))));
    }

    @JsMethod
    public CompletableFuture<Boolean> sendInitialFollowRequest(String targetUsername) {
        if(username.equals(targetUsername)) {
            return CompletableFuture.completedFuture(false);
        }
        return sendFollowRequest(targetUsername, SymmetricKey.random());
    }

    @JsMethod
    public CompletableFuture<Boolean> sendReplyFollowRequest(FollowRequest initialRequest, boolean accept, boolean reciprocate) {
        String theirUsername = initialRequest.entry.get().owner;
        // if accept, create directory to share with them, note in entry points (they follow us)
        if (!accept && !reciprocate) {
            // send a null entry and null key (full rejection)
            DataSink dout = new DataSink();
            // write a null entry point
            EntryPoint entry = new EntryPoint(FilePointer.createNull(), username, Collections.emptySet(), Collections.emptySet());
            dout.writeArray(entry.serialize());
            dout.writeArray(new byte[0]); // tell them we're not reciprocating
            byte[] plaintext = dout.toByteArray();

            return getPublicKeys(initialRequest.entry.get().owner).thenCompose(pair -> {
                PublicBoxingKey targetUser = pair.get().right;
                // create a tmp keypair whose public key we can prepend to the request without leaking information
                BoxingKeyPair tmp = BoxingKeyPair.random(crypto.random, crypto.boxer);
                byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

                DataSink resp = new DataSink();
                resp.writeArray(tmp.publicBoxingKey.serialize());
                resp.writeArray(payload);
                network.coreNode.addFollowRequest(initialRequest.entry.get().pointer.location.owner, resp.toByteArray());
                // remove pending follow request from them
                return network.coreNode.removeFollowRequest(signer.publicKeyHash, signer.secret.signMessage(initialRequest.rawCipher));
            });
        }

        return CompletableFuture.completedFuture(true).thenCompose(b -> {
            DataSink dout = new DataSink();
            if (accept) {
                return getSharingFolder().thenCompose(sharing -> {
                    return sharing.mkdir(theirUsername, network, initialRequest.key.get(), true, crypto.random)
                            .thenCompose(friendRoot -> {
                                // add a note to our static data so we know who we sent the read access to
                                EntryPoint entry = new EntryPoint(friendRoot.readOnly(), username, Collections.singleton(theirUsername), Collections.emptySet());

                                return addToStaticDataAndCommit(entry).thenApply(trie -> {
                                    this.entrie = trie;
                                    dout.writeArray(entry.serialize());
                                    return dout;
                                });
                            });
                });
            } else {
                EntryPoint entry = new EntryPoint(FilePointer.createNull(), username, Collections.emptySet(), Collections.emptySet());
                dout.writeArray(entry.serialize());
                return CompletableFuture.completedFuture(dout);
            }
        }).thenCompose(dout -> {

            if (!reciprocate) {
                dout.writeArray(new byte[0]); // tell them we're not reciprocating
            } else {
                // if reciprocate, add entry point to their shared directory (we follow them) and then
                dout.writeArray(initialRequest.entry.get().pointer.baseKey.serialize()); // tell them we are reciprocating
            }
            byte[] plaintext = dout.toByteArray();

            return getPublicKeys(initialRequest.entry.get().owner).thenCompose(pair -> {
                PublicBoxingKey targetUser = pair.get().right;
                // create a tmp keypair whose public key we can prepend to the request without leaking information
                BoxingKeyPair tmp = BoxingKeyPair.random(crypto.random, crypto.boxer);
                byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

                DataSink resp = new DataSink();
                resp.writeArray(tmp.publicBoxingKey.serialize());
                resp.writeArray(payload);
                return network.coreNode.addFollowRequest(initialRequest.entry.get().pointer.location.owner, resp.toByteArray());
            });
        }).thenCompose(b -> {
            if (reciprocate)
                return addToStaticDataAndCommit(initialRequest.entry.get());
            return CompletableFuture.completedFuture(entrie);
        }).thenCompose(trie -> {
            // remove original request
            entrie = trie;
            return network.coreNode.removeFollowRequest(signer.publicKeyHash, signer.secret.signMessage(initialRequest.rawCipher));
        });
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
                        return sharing.mkdir(targetUsername, network, null, true, crypto.random).thenCompose(friendRoot -> {

                            // if they accept the request we will add a note to our static data so we know who we sent the read access to
                            EntryPoint entry = new EntryPoint(friendRoot.readOnly(), username, Collections.singleton(targetUsername), Collections.emptySet());

                            // send details to allow friend to follow us, and optionally let us follow them
                            // create a tmp keypair whose public key we can prepend to the request without leaking information
                            BoxingKeyPair tmp = BoxingKeyPair.random(crypto.random, crypto.boxer);
                            DataSink buf = new DataSink();
                            buf.writeArray(entry.serialize());
                            buf.writeArray(requestedKey != null ? requestedKey.serialize() : new byte[0]);
                            byte[] plaintext = buf.toByteArray();
                            byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

                            DataSink res = new DataSink();
                            res.writeArray(tmp.publicBoxingKey.serialize());
                            res.writeArray(payload);
                            PublicKeyHash targetSigner = targetUserOpt.get().left;
                            return network.coreNode.addFollowRequest(targetSigner, res.toByteArray());
                        });
                    });
                });
            });
        });
    };

    public CompletableFuture<Boolean> sendWriteAccess(PublicKeyHash targetUser) {
        /*
        // create sharing keypair and give it write access
        User sharing = User.random(random, signer, boxer);
        byte[] rootMapKey = new byte[32];
        random.randombytes(rootMapKey, 0, 32);

        // add a note to our static data so we know who we sent the private key to
        FilePointer friendRoot = new FilePointer(user, sharing, rootMapKey, SymmetricKey.random());
        return corenodeClient.getUsername(targetUser).thenCompose(name -> {
            EntryPoint entry = new EntryPoint(friendRoot, username, Collections.emptySet(), Stream.of(name).collect(Collectors.toSet()));
            try {
                addToStaticDataAndCommit(entry);
                // create a tmp keypair whose public key we can append to the request without leaking information
                User tmp = User.random(random, signer, boxer);
                byte[] payload = entry.serializeAndEncrypt(tmp, targetUser);
                return corenodeClient.addFollowRequest(targetUser, ArrayOps.concat(tmp.publicBoxingKey.toByteArray(), payload));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });*/
        throw new IllegalStateException("Unimplemented!");
    }

    @JsMethod
    public CompletableFuture<Boolean> unShare(FileTreeNode file, String readerToRemove) {

        return file.getPath(network).thenCompose(pathString -> {
            return unShareItem(Paths.get(pathString), file, Collections.singleton(readerToRemove));
        });

    }

    public CompletableFuture<Boolean> unShare(Path path, String readerToRemove) {
        return unShare(path, Collections.singleton(readerToRemove));
    }

    public CompletableFuture<Boolean> unShare(Path path, Set<String> readersToRemove) {
        String pathString = path.toString();
        CompletableFuture<Optional<FileTreeNode>> byPath = getByPath(pathString);
        return byPath.thenCompose(opt -> {
            FileTreeNode toUnshare = opt.orElseThrow(() -> new IllegalStateException("Specified un-shareWith path " + pathString + " does not exist"));
            return unShareItem(path, toUnshare, readersToRemove);
        });
    }

    public CompletableFuture<Boolean> unShareItem(Path path, FileTreeNode toUnshare, Set<String> readersToRemove) {
        //
        // first remove links from shared directory
        //
        Optional<String> empty = Optional.empty();

        Function<String, CompletableFuture<Optional<String>>> unshareWith = user -> getByPath("/" + username + "/shared/" + user)
                .thenCompose(sharedWithOpt -> {
                    if (!sharedWithOpt.isPresent())
                        return CompletableFuture.completedFuture(empty);
                    FileTreeNode sharedRoot = sharedWithOpt.get();
                    return sharedRoot.removeChild(toUnshare, network)
                            .thenCompose(x -> CompletableFuture.completedFuture(Optional.of(user)));
                });

            // now change to new base keys, clean some keys and mark others as dirty
        return getByPath(path.getParent().toString())
                .thenCompose(parent -> sharedWith(toUnshare)
                            .thenCompose(sharedWithUsers ->
                                    toUnshare.makeDirty(network, crypto.random, parent.get(), readersToRemove)
                                            .thenCompose(markedDirty -> {
                                                Set<CompletableFuture<Optional<String>>> collect = sharedWithUsers.stream()
                                                        .map(unshareWith::apply) //remove link from shared directory
                                                        .collect(Collectors.toSet());

                                                return Futures.combineAll(collect);})
                                            .thenCompose(x -> {
                                                List<String> allSharees = x.stream()
                                                        .flatMap(e -> e.isPresent() ? Stream.of(e.get()) : Stream.empty())
                                                        .collect(Collectors.toList());

                                                Set<String> remainingReaders = allSharees.stream()
                                                        .filter(reader -> !readersToRemove.contains(reader))
                                                        .collect(Collectors.toSet());

                                                if(remainingReaders.size() == 0) {
                                                    this.filesSharedWithFriends.remove(shareKey(toUnshare));
                                                } else {
                                                    this.filesSharedWithFriends.add(shareKey(toUnshare));
                                                }
                                                return shareWithAll(toUnshare, remainingReaders);
                                            })));
    }

    private String shareKey(FileTreeNode file) {
        if(file == null) {
            return "";
        }
        String key = file.getPointer().filePointer.getLocation().toString();
        return key;
    }

    @JsMethod
    public boolean isShared(FileTreeNode file) {
        String key = shareKey(file);
        return this.filesSharedWithFriends.contains(key);
    }

    @JsMethod
    public CompletableFuture<Set<String>> sharedWith(FileTreeNode file) {

        Location fileLocation = file.getLocation();
        String path = "/" + username + "/shared";

        Function<FileTreeNode, CompletableFuture<Optional<String>>> func = sharedUserDir -> {
            CompletableFuture<Set<FileTreeNode>> children = sharedUserDir.getChildren(network);
            return children.thenCompose(e -> {
                boolean present = e.stream()
                        .filter(sharedFile -> sharedFile.getLocation().equals(fileLocation))
                        .findFirst()
                        .isPresent();
                String userName = present ? sharedUserDir.getFileProperties().name : null;
                return CompletableFuture.completedFuture(Optional.ofNullable(userName));
            });
        };

        return getByPath(path)
                .thenCompose(sharedDirOpt -> {
                    FileTreeNode sharedDir = sharedDirOpt.orElseThrow(() -> new IllegalStateException("No such directory" + path));
                    return sharedDir.getChildren(network)
                            .thenCompose(sharedUserDirs -> {
                                List<CompletableFuture<Optional<String>>> collect = sharedUserDirs.stream()
                                        .map(func::apply)
                                        .collect(Collectors.toList());

                                return Futures.combineAll(collect);
                            }).thenCompose(optSet -> {
                                Set<String> sharedWith = optSet.stream()
                                        .flatMap(e -> e.isPresent() ? Stream.of(e.get()) : Stream.empty())
                                        .collect(Collectors.toSet());
                                if(sharedWith.size() == 0) {
                                    this.filesSharedWithFriends.remove(shareKey(file));
                                } else {
                                    this.filesSharedWithFriends.add(shareKey(file));
                                }
                                return CompletableFuture.completedFuture(sharedWith);
                            });
                });

        /*
        FileTreeNode sharedDir = getByPath("/" + username + "/shared").get();
        Set<FileTreeNode> friendDirs = sharedDir.getChildren(this);
        return friendDirs.stream()
                .filter(friendDir -> friendDir.getChildren(this)
                        .stream()
                        .filter(f -> f.getLocation().equals(file.getLocation()))
                        .findAny()
                        .isPresent())
                .map(u -> u.getFileProperties().name)
                .collect(Collectors.toSet());*/
//        throw new IllegalStateException("Unimplemented!");
    }

    public CompletableFuture<Boolean> shareWith(Path path, Set<String> readersToAdd) {
        return getByPath(path.toString())
                .thenCompose(file -> shareWithAll(file.orElseThrow(() -> new IllegalStateException("Could not find path " + path.toString())), readersToAdd));
    }

    public CompletableFuture<Boolean> shareWithAll(FileTreeNode file, Set<String> readersToAdd) {
        return Futures.reduceAll(readersToAdd,
                true,
                (x, username) -> shareWith(file, username),
                (a, b) -> a && b);
    }

    @JsMethod
    public CompletableFuture<Boolean> shareWith(FileTreeNode file, String usernameToGrantReadAccess) {
        return getByPath("/" + username + "/shared/" + usernameToGrantReadAccess)
                .thenCompose(shared -> {
                    if (!shared.isPresent())
                        return CompletableFuture.completedFuture(true);
                    FileTreeNode sharedTreeNode = shared.get();
                    return sharedTreeNode.addLinkTo(file, network, crypto.random)
                            .thenCompose(ee -> {
                                this.filesSharedWithFriends.add(shareKey(file));
                                return CompletableFuture.completedFuture(true);
                            });
                });
    }

    private CompletableFuture<TrieNode> addToStaticDataAndCommit(EntryPoint entry) {
        return addToStaticDataAndCommit(entrie, entry);
    }

    private synchronized CompletableFuture<TrieNode> addToStaticDataAndCommit(TrieNode root, EntryPoint entry) {
        CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();
        return addToUserDataQueue(lock).thenCompose(wd -> {
            wd.props.staticData.ifPresent(sd -> sd.add(entry));
            return wd.props.commit(signer, wd.hash, network, lock::complete)
                    .thenCompose(res -> addEntryPoint(username, root, entry, network))
                    .exceptionally(t -> {
                        lock.complete(wd);
                        return root;
                    });
        });
    }

    private CompletableFuture<CommittedWriterData> removeFromStaticData(FileTreeNode fileTreeNode) {
        CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();
        return addToUserDataQueue(lock)
                .thenCompose(wd -> wd.props.removeFromStaticData(fileTreeNode, signer, wd.hash, network, lock::complete));
    }

    /**
     * Process any responses to our follow requests.
     *
     * @return initial follow requests
     */
    public CompletableFuture<List<FollowRequest>> processFollowRequests() {
        return network.coreNode.getFollowRequests(signer.publicKeyHash).thenCompose(reqs -> {
            DataSource din = new DataSource(reqs);
            List<FollowRequest> all;
            try {
                int n = din.readInt();
                all = IntStream.range(0, n)
                        .mapToObj(i -> i)
                        .flatMap(i -> {
                            try {
                                return Stream.of(decodeFollowRequest(din.readArray()));
                            } catch (IOException ioe) {
                                return Stream.empty();
                            }
                        })
                        .collect(Collectors.toList());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            return processFollowRequests(all);
        });
    }

    private CompletableFuture<List<FollowRequest>> processFollowRequests(List<FollowRequest> all) {
        return getSharingFolder().thenCompose(sharing ->
                getFollowerRoots().thenCompose(followerRoots -> {
                    List<FollowRequest> replies = all.stream()
                            .filter(freq -> followerRoots.containsKey(freq.entry.get().owner))
                            .collect(Collectors.toList());

                    BiFunction<TrieNode, FollowRequest, CompletableFuture<TrieNode>> addToStatic = (root, freq) -> {
                        if (!Arrays.equals(freq.entry.get().pointer.baseKey.serialize(), SymmetricKey.createNull().serialize())) {
                            CompletableFuture<TrieNode> updatedRoot = freq.entry.get().owner.equals(username) ?
                                    CompletableFuture.completedFuture(root) : // ignore responses claiming to be owned by us
                                    addToStaticDataAndCommit(root, freq.entry.get());
                            return updatedRoot.thenCompose(newRoot -> {
                                entrie = newRoot;
                                // clear their response follow req too
                                return network.coreNode.removeFollowRequest(signer.publicKeyHash, signer.secret.signMessage(freq.rawCipher))
                                        .thenApply(b -> newRoot);
                            });
                        }
                        return CompletableFuture.completedFuture(root);
                    };

                    BiFunction<TrieNode, FollowRequest, CompletableFuture<TrieNode>> mozart = (trie, freq) -> {
                        // delete our folder if they didn't reciprocate
                        FileTreeNode ourDirForThem = followerRoots.get(freq.entry.get().owner);
                        byte[] ourKeyForThem = ourDirForThem.getKey().serialize();
                        byte[] keyFromResponse = freq.key.map(k -> k.serialize()).orElse(null);
                        if (keyFromResponse == null || !Arrays.equals(keyFromResponse, ourKeyForThem)) {
                            // They didn't reciprocate (follow us)
                            CompletableFuture<FileTreeNode> removeDir = ourDirForThem.remove(network, sharing);
                            // remove entry point as well
                            CompletableFuture<CommittedWriterData> cleanStatic = removeFromStaticData(ourDirForThem);

                            return removeDir.thenCompose(x -> cleanStatic)
                                    .thenCompose(b -> addToStatic.apply(trie, freq));
                        } else if (freq.entry.get().pointer.isNull()) {
                            // They reciprocated, but didn't accept (they follow us, but we can't follow them)
                            // add entry point to static data to signify their acceptance
                            EntryPoint entryWeSentToThem = new EntryPoint(ourDirForThem.getPointer().filePointer.readOnly(),
                                    username, Collections.singleton(ourDirForThem.getName()), Collections.emptySet());
                            return addToStaticDataAndCommit(trie, entryWeSentToThem);
                        } else {
                            // they accepted and reciprocated
                            // add entry point to static data to signify their acceptance
                            EntryPoint entryWeSentToThem = new EntryPoint(ourDirForThem.getPointer().filePointer.readOnly(),
                                    username, Collections.singleton(ourDirForThem.getName()), Collections.emptySet());

                            // add new entry point to tree root
                            EntryPoint entry = freq.entry.get();
                            if (entry.owner.equals(username))
                                throw new IllegalStateException("Received a follow request claiming to be owned by us!");
                            return addToStaticDataAndCommit(trie, entryWeSentToThem)
                                    .thenCompose(newRoot -> network.retrieveEntryPoint(entry)
                                            .thenCompose(treeNode ->
                                                    treeNode.get().getPath(network))
                                            .thenApply(path -> newRoot.put(path, entry)
                                    ).thenCompose(trieres -> addToStatic.apply(trieres, freq)));
                        }
                    };
                    List<FollowRequest> initialRequests = all.stream()
                            .filter(freq -> !followerRoots.containsKey(freq.entry.get().owner))
                            .collect(Collectors.toList());
                    return Futures.reduceAll(replies, entrie, mozart, (a, b) -> a)
                            .thenApply(newRoot -> {
                                entrie = newRoot;
                                return initialRequests;
                            });
                })
        );
    }

    private FollowRequest decodeFollowRequest(byte[] raw) throws IOException {
        DataSource buf = new DataSource(raw);
        PublicBoxingKey tmp = PublicBoxingKey.fromCbor(CborObject.fromByteArray(Serialize.deserializeByteArray(buf, 4096)));
        byte[] cipher = buf.readArray();
        byte[] plaintext = boxer.secretBoxingKey.decryptMessage(cipher, tmp);
        DataSource input = new DataSource(plaintext);
        byte[] rawEntry = input.readArray();
        byte[] rawKey = input.readArray();
        return new FollowRequest(rawEntry.length > 0 ? Optional.of(EntryPoint.fromCbor(CborObject.fromByteArray(rawEntry))) : Optional.empty(),
                rawKey.length > 0 ? Optional.of(SymmetricKey.fromByteArray(rawKey)) : Optional.empty(), raw);
    }


    @JsMethod
    public CompletableFuture<Set<FileTreeNode>> getChildren(FileTreeNode directory) {
        String path = "/" + username + "/shared";
        return getByPath(path).thenCompose(sharedDirOpt -> {
            FileTreeNode sharedDir = sharedDirOpt.orElseThrow(() -> new IllegalStateException("No such directory" + path));
            return sharedDir.getChildren(network).thenCompose(sharedUserDirs -> {
                return directory.getChildren(network).thenCompose(childrenFTNs -> {
                    return Futures.combineAll(childrenFTNs.stream()
                            .map(childFTN -> updateSharedWith(childFTN, sharedUserDirs))
                            .collect(Collectors.toList()))
                            .thenApply(x -> childrenFTNs);
                });
            });
        });
    }

    private CompletableFuture<Boolean> updateSharedWith(FileTreeNode file, Set<FileTreeNode> sharedUserDirs) {

        Location fileLocation = file.getLocation();

        Function<FileTreeNode, CompletableFuture<Boolean>> func = sharedUserDir -> {
            CompletableFuture<Set<FileTreeNode>> children = sharedUserDir.getChildren(network);
            return children.thenApply(e -> {
                return e.stream()
                        .filter(sharedFile -> sharedFile.getLocation().equals(fileLocation))
                        .findFirst()
                        .isPresent();
            });
        };

        List<CompletableFuture<Boolean>> collect = sharedUserDirs.stream()
                .map(func)
                .collect(Collectors.toList());

        return Futures.combineAll(collect).thenApply(presentSet -> {
            if (! presentSet.stream().anyMatch(f -> f)) {
                this.filesSharedWithFriends.remove(shareKey(file));
            } else {
                this.filesSharedWithFriends.add(shareKey(file));
            }
            return true;
        });
    }

    @JsMethod
    public CompletableFuture<Optional<FileTreeNode>> getByPath(String path) {
        if (path.equals("/"))
            return CompletableFuture.completedFuture(Optional.of(FileTreeNode.createRoot(entrie)));
        return entrie.getByPath(path.startsWith("/") ? path : "/" + path, network);
    }

    public CompletableFuture<FileTreeNode> getUserRoot() {
        return getByPath("/"+username).thenApply(opt -> opt.get());
    }

    /**
     *
     * @return TrieNode for root of filesystem containing only our files
     */
    private static CompletableFuture<TrieNode> createOurFileTreeOnly(String ourName, WriterData userData, NetworkAccess network) {
        TrieNode root = new TrieNode();
        if (! userData.staticData.isPresent())
            throw new IllegalStateException("Cannot retrieve file tree for a filesystem without entrypoints!");
        List<EntryPoint> ourFileSystemEntries = userData.staticData.get()
                .getEntryPoints()
                .stream()
                .filter(e -> e.owner.equals(ourName))
                .collect(Collectors.toList());
        return Futures.reduceAll(ourFileSystemEntries, root, (t, e) -> addEntryPoint(ourName, t, e, network), (a, b) -> a)
                .exceptionally(Futures::logError);
    }

    /**
     *
     * @return TrieNode for root of filesystem
     */
    private static CompletableFuture<TrieNode> createFileTree(TrieNode ourRoot, String ourName, WriterData userData, NetworkAccess network) {
        List<EntryPoint> notOurFileSystemEntries = userData.staticData.get()
                .getEntryPoints()
                .stream()
                .filter(e -> ! e.owner.equals(ourName))
                .collect(Collectors.toList());
        return Futures.reduceAll(notOurFileSystemEntries, ourRoot, (t, e) -> addEntryPoint(ourName, t, e, network), (a, b) -> a)
                .exceptionally(Futures::logError);
    }

    private static CompletableFuture<TrieNode> addEntryPoint(String ourName, TrieNode root, EntryPoint e, NetworkAccess network) {
        return network.retrieveEntryPoint(e).thenCompose(metadata -> {
            if (metadata.isPresent()) {
                return metadata.get().getPath(network)
                        .thenCompose(path -> {
                            // check entrypoint doesn't forge the owner
                            return  (e.owner.equals(ourName) ? CompletableFuture.completedFuture(true) :
                             e.isValid(path, network)).thenApply(valid -> {

                                System.out.println("Added entry point: " + metadata.get() + " at path " + path);
                                String[] parts = path.split("/");
                                if (parts.length < 3 || !parts[2].equals(SHARED_DIR_NAME))
                                    return root.put(path, e);
                                TrieNode rootWithMapping = parts[1].equals(ourName) ? root : root.addPathMapping("/" + parts[1] + "/", path + "/");
                                return rootWithMapping.put(path, e);
                            });
                        }).exceptionally(t -> {
                            t.printStackTrace();
                            System.err.println("Couldn't add entry point (failed retrieving parent dir or it was invalid): " + metadata.get().getName());
                            // Allow the system to continue without this entry point
                            return root;
                        });
            }
            throw new IllegalStateException("Metadata blob not Present downloading entry point!");
        }).exceptionally(Futures::logError);
    }

    private CompletableFuture<Boolean> cleanOurEntryPoint(EntryPoint e) {
        if (! e.owner.equals(username))
            return CompletableFuture.completedFuture(false);
        return network.retrieveEntryPoint(e).thenCompose(fileOpt -> {
            if (! fileOpt.isPresent())
                return CompletableFuture.completedFuture(true);
            FileTreeNode file = fileOpt.get();
            return file.getPath(network)
                    .thenApply(x -> true)
                    .exceptionally(t -> {
                        // If the inaccessible entry point is into our space, remove the entry point,
                        // and the dir/file it points to
                        // first make it writable by combining with the root writing key
                        getByPath("/" + username).thenCompose(rootDir ->
                                new FileTreeNode(file.getPointer(), file.getOwner(), e.readers, e.writers, rootDir.get().getEntryWriterKey())
                                        .remove(network, null)
                                        .thenApply(x -> removeFromStaticData(file)));
                        return true;
                    });
        });
    }

    public static CompletableFuture<CommittedWriterData> getWriterData(NetworkAccess network, PublicKeyHash signer) {
        return getWriterDataCbor(network, signer)
                .thenApply(pair -> new CommittedWriterData(MaybeMultihash.of(pair.left), WriterData.fromCbor(pair.right, null)));
    }

    private static CompletableFuture<Pair<Multihash, CborObject>> getWriterDataCbor(NetworkAccess network, String username) {
        return network.coreNode.getPublicKeyHash(username)
                .thenCompose(signer -> {
                    PublicKeyHash publicSigningKey = signer.orElseThrow(
                            () -> new IllegalStateException("No public-key for user " + username));
                    return getWriterDataCbor(network, publicSigningKey);
                });
    }

    private static CompletableFuture<Pair<Multihash, CborObject>> getWriterDataCbor(NetworkAccess network, PublicKeyHash signerHash) {
        return network.mutable.getPointer(signerHash)
                .thenCompose(casOpt -> network.dhtClient.getSigningKey(signerHash)
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
        System.out.println("Unfollowing: "+friendName);
        // remove entry point from static data
        String friendPath = "/" + friendName + "/";
        return getByPath(friendPath)
                // remove our static data entry storing that they've granted us access
                .thenCompose(dir -> removeFromStaticData(dir.get()))
                .thenApply(b -> {
                    entrie = entrie.removeEntry(friendPath);
                    return true;
                });
    }

    @JsMethod
    public CompletableFuture<CommittedWriterData> removeFollower(String username) {
        System.out.println("Remove follower: " + username);
        // remove /$us/shared/$them
        return getSharingFolder()
                .thenCompose(sharing -> getByPath("/"+this.username+"/shared/"+username)
                        .thenCompose(dir -> dir.get().remove(network, sharing)
                                // remove our static data entry storing that we've granted them access
                                .thenCompose(b -> removeFromStaticData(dir.get()))));
    }

    public void logout() {
        entrie = entrie.clear();
    }

    @JsMethod
    public Fragmenter fragmenter() {
        return fragmenter;
    }
}
