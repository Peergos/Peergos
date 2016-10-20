package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.ipfs.api.Multihash;
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
    public static final String SHARED_DIR_NAME = "shared";

    @JsProperty
    public final String username;
    public final User user;
    private final SymmetricKey rootKey;
    private final UserStaticData staticData;
    private TrieNode entrie = new TrieNode(); // ba dum che!
    private final Fragmenter fragmenter;

    // Contact external world
    public final NetworkAccess network;

    // In process only
    public final Crypto crypto;

    public UserContext(String username, User user, SymmetricKey root, NetworkAccess network, Crypto crypto) {
        this(username, user, root, network, crypto, new ErasureFragmenter(40, 10));
    }

    public UserContext(String username, User user, SymmetricKey root, NetworkAccess network,
                       Crypto crypto, Fragmenter fragmenter) {
        this.username = username;
        this.user = user;
        this.rootKey = root;
        this.network = network;
        this.crypto = crypto;
        this.fragmenter = fragmenter;
        this.staticData =  new UserStaticData();
    }

    @JsMethod
    public static CompletableFuture<UserContext> signIn(String username, String password, NetworkAccess network, Crypto crypto) {
        return UserUtil.generateUser(username, password, crypto.hasher, crypto.symmetricProvider, crypto.random, crypto.signer, crypto.boxer)
                .thenApply(userWithRoot -> new UserContext(username, userWithRoot.getUser(), userWithRoot.getRoot(), network, crypto))
                .thenCompose(ctx -> {
                    System.out.println("Initializing context..");
                    return ctx.init()
                            .thenApply(res -> ctx);
                }).exceptionally(Futures::logError);
    }

    @JsMethod
    public static CompletableFuture<UserContext> signUp(String username, String password, NetworkAccess network, Crypto crypto) {
        return UserUtil.generateUser(username, password, crypto.hasher, crypto.symmetricProvider, crypto.random, crypto.signer, crypto.boxer)
                .thenCompose(userWithRoot -> {
                    UserContext context = new UserContext(username, userWithRoot.getUser(), userWithRoot.getRoot(), network, crypto);
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
                            return ((DirAccess) userRoot.fileAccess).mkdir(SHARED_DIR_NAME, context, (User) userRoot.filePointer.location.writer,
                                    userRoot.filePointer.location.getMapKey(), userRoot.filePointer.baseKey, null, true, crypto.random)
                                    .thenCompose(x -> context.init().thenApply(inited -> context));
                        });
                    });
                }).exceptionally(Futures::logError);
    }

    public static CompletableFuture<UserContext> ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) {

        return network.isUsernameRegistered(username).thenCompose(isRegistered -> {
            if (isRegistered)
                return signIn(username, password, network, crypto);
            return signUp(username, password, network, crypto);
        });
    }

    private CompletableFuture<Boolean> init() {
        staticData.clear();
        return createFileTree()
                .thenCompose(root -> {
                    this.entrie = root;
                    return getByPath("/" + username + "/" + "shared")
                            .thenApply(sharedOpt -> {
                                if (!sharedOpt.isPresent())
                                    throw new IllegalStateException("Couldn't find shared folder!");
                                return true;
                            });
                });
    }

    public CompletableFuture<FileTreeNode> getSharingFolder() {
        return getByPath("/"+username + "/shared").thenApply(opt -> opt.get());
    }

    @JsMethod
    public CompletableFuture<Boolean> isRegistered() {
        System.out.println("isRegistered");
        return network.coreNode.getUsername(user).thenApply(registeredUsername -> {
            System.out.println("got username \"" + registeredUsername + "\"");
            return this.username.equals(registeredUsername);
        });
    }

    @JsMethod
    public CompletableFuture<Boolean> isAvailable() {
        return network.coreNode.getPublicKey(username)
                .thenApply(publicKey -> !publicKey.isPresent());
    }

    private static byte[] serializeStatic(SortedMap<UserPublicKey, EntryPoint> staticData, SymmetricKey rootKey) {
        DataSink sink = new DataSink();
        sink.writeInt(staticData.size());
        staticData.values().forEach(ep -> sink.writeArray(ep.serializeAndSymmetricallyEncrypt(rootKey)));
        return sink.toByteArray();
    }

    @JsMethod
    public CompletableFuture<Boolean> register() {
        LocalDate now = LocalDate.now();
        // set claim expiry to two months from now
        LocalDate expiry = now.plusMonths(2);
        System.out.println("claiming username: "+username + " with expiry " + expiry);
        List<UserPublicKeyLink> claimChain = UserPublicKeyLink.createInitial(user, username, expiry);
        return network.coreNode.updateChain(username, claimChain);
    }

    @JsMethod
    public CompletableFuture<UserContext> changePassword(String newPassword) throws IOException{
        System.out.println("changing password");
        LocalDate expiry = LocalDate.now();
        // set claim expiry to two months from now
        expiry.plusMonths(2);

        CompletableFuture<UserContext> result = new CompletableFuture<>();
        UserUtil.generateUser(username, newPassword, crypto.hasher, crypto.symmetricProvider, crypto.random, crypto.signer, crypto.boxer).thenAccept(updatedUser -> {
            commit(updatedUser.getUser(), updatedUser.getRoot(), network).thenApply(updated -> {
                if (!updated)
                    return result.completeExceptionally(new IllegalStateException("Change Password Failed: couldn't upload new file system entry points!"));

                List<UserPublicKeyLink> claimChain = UserPublicKeyLink.createChain(user, updatedUser.getUser(), username, expiry);
                return network.coreNode.updateChain(username, claimChain).thenApply(updatedChain -> {
                    if (!updatedChain)
                        return result.completeExceptionally(new IllegalStateException("Couldn't register new public keys during password change!"));

                    return UserContext.ensureSignedUp(username, newPassword, network, crypto)
                            .thenApply(context -> result.complete(context));
                });
            });
        });
        return result;
    }

    public CompletableFuture<RetrievedFilePointer> createEntryDirectory(String directoryName) {
        long t1 = System.currentTimeMillis();
        User writer = User.random(crypto.random, crypto.signer, crypto.boxer);
        System.out.println("Random User generation took " + (System.currentTimeMillis()-t1) + " mS");
        byte[] rootMapKey = new byte[32]; // root will be stored under this in the core node
        crypto.random.randombytes(rootMapKey, 0, 32);
        SymmetricKey rootRKey = SymmetricKey.random();
        System.out.println("Random keys generation took " + (System.currentTimeMillis()-t1) + " mS");

        // and authorise the writer key
        ReadableFilePointer rootPointer = new ReadableFilePointer(this.user, writer, rootMapKey, rootRKey);
        EntryPoint entry = new EntryPoint(rootPointer, this.username, Collections.emptySet(), Collections.emptySet());

        long t2 = System.currentTimeMillis();
        DirAccess root = DirAccess.create(rootRKey, new FileProperties(directoryName, 0, LocalDateTime.now(), false, Optional.empty()), (Location)null, null, null);
        Location rootLocation = new Location(this.user, writer, rootMapKey);
        System.out.println("Uploading entry point directory");
        return this.uploadChunk(root, rootLocation, Collections.emptyList()).thenCompose(uploaded -> {
            long t3 = System.currentTimeMillis();
            System.out.println("Uploading root dir metadata took " + (t3 - t2) + " mS");
            return addToStaticDataAndCommit(entry)
                    .thenApply(x -> {
                        System.out.println("Committing static data took " + (System.currentTimeMillis() - t3) + " mS");

                        if (uploaded)
                            return new RetrievedFilePointer(rootPointer, root);
                        throw new IllegalStateException("Failed to create entry directory!");
                    });
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

    public CompletableFuture<Map<String, FileTreeNode>> getFollowerRoots() {
        return getSharingFolder()
                .thenCompose(sharing -> sharing.getChildren(this))
                .thenApply(children -> children.stream()
                        .collect(Collectors.toMap(e -> e.getFileProperties().name, e -> e)));
    }

    public CompletableFuture<SocialState> getSocialState() throws IOException {
        return processFollowRequests()
                .thenCompose(pending -> getFollowerRoots()
                        .thenCompose(followerRoots -> getFriendRoots()
                                .thenApply(followingRoots -> new SocialState(pending, followerRoots, followingRoots))));
    }

    @JsMethod
    public CompletableFuture<Boolean> sendInitialFollowRequest(String targetUsername) throws IOException {
        return sendFollowRequest(targetUsername, SymmetricKey.random());
    }

    public CompletableFuture<Boolean> sendReplyFollowRequest(FollowRequest initialRequest, boolean accept, boolean reciprocate) {
        String theirUsername = initialRequest.entry.get().owner;
        // if accept, create directory to share with them, note in entry points (they follow us)
        if (!accept && !reciprocate) {
            // send a null entry and null key (full rejection)
            DataSink dout = new DataSink();
            // write a null entry point
            EntryPoint entry = new EntryPoint(ReadableFilePointer.createNull(), username, Collections.EMPTY_SET, Collections.EMPTY_SET);
            dout.writeArray(entry.serialize());
            dout.writeArray(new byte[0]); // tell them we're not reciprocating
            byte[] plaintext = dout.toByteArray();
            UserPublicKey targetUser = initialRequest.entry.get().pointer.location.owner;
            // create a tmp keypair whose public key we can prepend to the request without leaking information
            User tmp = User.random(crypto.random, crypto.signer, crypto.boxer);
            byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

            DataSink resp = new DataSink();
            resp.writeArray(tmp.getPublicKeys());
            resp.writeArray(payload);
            network.coreNode.followRequest(initialRequest.entry.get().pointer.location.owner, resp.toByteArray());
            // remove pending follow request from them
            return network.coreNode.removeFollowRequest(user, user.signMessage(initialRequest.rawCipher));
        }

        return CompletableFuture.completedFuture(true).thenCompose(b -> {
            DataSink dout = new DataSink();
            if (accept) {
                return getSharingFolder().thenCompose(sharing -> {
                    return sharing.mkdir(theirUsername, this, initialRequest.key.get(), true, crypto.random)
                            .thenCompose(friendRoot -> {
                                // add a note to our static data so we know who we sent the read access to
                                EntryPoint entry = new EntryPoint(friendRoot.readOnly(), username, Stream.of(theirUsername).collect(Collectors.toSet()), Collections.EMPTY_SET);

                                return addToStaticDataAndCommit(entry).thenApply(trie -> {
                                    dout.writeArray(entry.serialize());
                                    return dout;
                                });
                            });
                });
            } else {
                EntryPoint entry = new EntryPoint(ReadableFilePointer.createNull(), username, Collections.emptySet(), Collections.emptySet());
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
            UserPublicKey targetUser = initialRequest.entry.get().pointer.location.owner;
            // create a tmp keypair whose public key we can prepend to the request without leaking information
            User tmp = User.random(crypto.random, crypto.signer, crypto.boxer);
            byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

            DataSink resp = new DataSink();
            resp.writeArray(tmp.getPublicKeys());
            resp.writeArray(payload);
            return network.coreNode.followRequest(initialRequest.entry.get().pointer.location.owner.toUserPublicKey(), resp.toByteArray());
        }).thenCompose(b -> {
            if (reciprocate)
                return addToStaticDataAndCommit(initialRequest.entry.get());
            return CompletableFuture.completedFuture(entrie);
        }).thenCompose(trie -> {
            // remove original request
            entrie = trie;
            return network.coreNode.removeFollowRequest(user.toUserPublicKey(), user.signMessage(initialRequest.rawCipher));
        });
    }

    public CompletableFuture<Boolean> sendFollowRequest(String targetUsername, SymmetricKey requestedKey) throws IOException {
        return getSharingFolder().thenCompose(sharing -> {
            return sharing.getChildren(this).thenCompose(children -> {
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

                    return network.coreNode.getPublicKey(targetUsername).thenCompose(targetUserOpt -> {
                        if (!targetUserOpt.isPresent())
                            return CompletableFuture.completedFuture(false);
                        UserPublicKey targetUser = targetUserOpt.get();
                        return sharing.mkdir(targetUsername, this, null, true, crypto.random).thenCompose(friendRoot -> {

                            // add a note to our static data so we know who we sent the read access to
                            EntryPoint entry = new EntryPoint(friendRoot.readOnly(), username, Stream.of(targetUsername).collect(Collectors.toSet()), Collections.EMPTY_SET);
                            CompletableFuture<TrieNode> addToStatic = addToStaticDataAndCommit(entry);
                            // send details to allow friend to follow us, and optionally let us follow them
                            // create a tmp keypair whose public key we can prepend to the request without leaking information
                            User tmp = User.random(crypto.random, crypto.signer, crypto.boxer);
                            DataSink buf = new DataSink();
                            buf.writeArray(entry.serialize());
                            buf.writeArray(requestedKey != null ? requestedKey.serialize() : new byte[0]);
                            byte[] plaintext = buf.toByteArray();
                            byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

                            DataSink res = new DataSink();
                            res.writeArray(tmp.getPublicKeys());
                            res.writeArray(payload);
                            return addToStatic.thenCompose(newRoot -> {
                                entrie = newRoot;
                                return network.coreNode.followRequest(targetUser.toUserPublicKey(), res.toByteArray());
                            });
                        });
                    });
                });
            });
        });
    };

    public CompletableFuture<Boolean> sendWriteAccess(UserPublicKey targetUser) throws IOException {
        /*
        // create sharing keypair and give it write access
        User sharing = User.random(random, signer, boxer);
        byte[] rootMapKey = new byte[32];
        random.randombytes(rootMapKey, 0, 32);

        // add a note to our static data so we know who we sent the private key to
        ReadableFilePointer friendRoot = new ReadableFilePointer(user, sharing, rootMapKey, SymmetricKey.random());
        return corenodeClient.getUsername(targetUser).thenCompose(name -> {
            EntryPoint entry = new EntryPoint(friendRoot, username, Collections.emptySet(), Stream.of(name).collect(Collectors.toSet()));
            try {
                addToStaticDataAndCommit(entry);
                // create a tmp keypair whose public key we can append to the request without leaking information
                User tmp = User.random(random, signer, boxer);
                byte[] payload = entry.serializeAndEncrypt(tmp, targetUser);
                return corenodeClient.followRequest(targetUser, ArrayOps.concat(tmp.publicBoxingKey.toByteArray(), payload));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });*/
        throw new IllegalStateException("Unimplemented!");
    }

    public CompletableFuture<Boolean> unShare(Path path, String readerToRemove) throws IOException {
        return unShare(path, Collections.singleton(readerToRemove));
    }

    public CompletableFuture<Boolean> unShare(Path path, Set<String> readersToRemove) throws IOException {
        String pathString = path.toString();
        CompletableFuture<Optional<FileTreeNode>> byPath = getByPath(pathString);
        return byPath.thenCompose(opt -> {
            //
            // first remove links from shared directory
            //
            FileTreeNode sharedPath = opt.orElseThrow(() -> new IllegalStateException("Specified un-share path " + pathString + " does not exist"));
            Optional<String> empty = Optional.empty();

            Function<String, CompletableFuture<Optional<String>>> unshareWith = user -> getByPath("/" + username + "/shared/" + user)
                    .thenCompose(sharedWithOpt -> {
                        if (!sharedWithOpt.isPresent())
                            return CompletableFuture.completedFuture(empty);
                        FileTreeNode sharedRoot = sharedWithOpt.get();
                        return sharedRoot.removeChild(sharedPath, this)
                                .thenCompose(x -> CompletableFuture.completedFuture(Optional.of(user)));
                    });

            return sharedWith(sharedPath)
                    .thenCompose(sharedWithUsers -> {

                        Set<CompletableFuture<Optional<String>>> collect = sharedWithUsers.stream()
                                .map(unshareWith::apply) //remove link from shared directory
                                .collect(Collectors.toSet());

                        return Futures.combineAll(collect);
                    }).thenCompose(x -> {
                        List<String> allSharees = x.stream()
                                .flatMap(e -> e.isPresent() ? Stream.of(e.get()) : Stream.empty())
                                .collect(Collectors.toList());

                        Set<String> remainingReaders = allSharees.stream()
                                .filter(reader -> ! readersToRemove.contains(reader))
                                .collect(Collectors.toSet());

                        try {
                            return share(path, remainingReaders);
                        } catch (IOException ioe) {
                            throw new IllegalStateException(ioe);
                        }
                    });
        });


        /*
        Optional<FileTreeNode> f = getByPath(path.toString());
        if (! f.isPresent())
            return;
        FileTreeNode file = f.get();
        Set<String> sharees = sharedWith(file);
        // first remove links from shared directory
        for (String friendName: sharees) {
            Optional<FileTreeNode> opt = getByPath("/" + username + "/shared/" + friendName);
            if (!opt.isPresent())
                continue;
            FileTreeNode sharedRoot = opt.get();
            sharedRoot.removeChild(file, this);
        }

        // now change to new base keys, clean some keys and mark others as dirty
        FileTreeNode parent = getByPath(path.getParent().toString()).get();
        file.makeDirty(this, parent, readersToRemove);

        // now re-share new keys with remaining users
        Set<String> remainingReaders = sharees.stream().filter(name -> !readersToRemove.contains(name)).collect(Collectors.toSet());
        share(path, remainingReaders);*/

    }

    public CompletableFuture<Set<String>> sharedWith(FileTreeNode file) {

        Location fileLocation = file.getLocation();
        String path = "/" + username + "/shared";

        Function<FileTreeNode, CompletableFuture<Optional<String>>> func = sharedUserDir -> {
            CompletableFuture<Set<FileTreeNode>> children = sharedUserDir.getChildren(this);
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
                    return sharedDir.getChildren(this)
                            .thenCompose(sharedUserDirs -> {
                                List<CompletableFuture<Optional<String>>> collect = sharedUserDirs.stream()
                                        .map(func::apply)
                                        .collect(Collectors.toList());

                                return Futures.combineAll(collect);
                            }).thenCompose(optSet -> {
                                Set<String> sharedWith = optSet.stream()
                                        .flatMap(e -> e.isPresent() ? Stream.of(e.get()) : Stream.empty())
                                        .collect(Collectors.toSet());
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

    public CompletableFuture<Boolean> share(Path path, Set<String> readersToAdd) throws IOException {

        return getByPath(path.toString())
                .thenCompose(e -> {
                    FileTreeNode fileTreeNode = e.orElseThrow(() -> new IllegalStateException("Could not find path " + path.toString()));

                    BiFunction<Boolean, String, CompletableFuture<Boolean>> func = (x, user) -> getByPath("/" + username + "/shared/" + user)
                            .thenCompose(shared -> {
                                if (!shared.isPresent())
                                    return CompletableFuture.completedFuture(true);
                                FileTreeNode sharedTreeNode = shared.get();
                                return sharedTreeNode.addLinkTo(fileTreeNode, this)
                                        .thenCompose(ee -> CompletableFuture.completedFuture(true));
                            });


                    return Futures.reduceAll(readersToAdd,
                            true,
                            func,
                            (a, b) -> a && b);
                });

        /*
        Optional<FileTreeNode> f = getByPath(path.toString());
        if (!f.isPresent())
            return;
        FileTreeNode file = f.get();
        for (String friendName: readersToAdd) {
            Optional<FileTreeNode> opt = getByPath("/" + username + "/shared/" + friendName);
            if (!opt.isPresent())
                continue;
            FileTreeNode sharedRoot = opt.get();
            sharedRoot.addLinkTo(file, this);
        }*/
    }


    private CompletableFuture<TrieNode> addToStaticDataAndCommit(EntryPoint entry) {
        return addToStaticDataAndCommit(entrie, entry);
    }

    private CompletableFuture<TrieNode> addToStaticDataAndCommit(TrieNode root, EntryPoint entry) {
        staticData.add(entry);
        return commit(user, rootKey, network)
                .thenCompose(res -> addEntryPoint(root, entry));
    }

    private CompletableFuture<Boolean> commit(User user,
                                              SymmetricKey rootKey,
                                              NetworkAccess network) {

        byte[] rawStatic = staticData.serialize(rootKey);
        return network.dhtClient.put(rawStatic, user, Collections.emptyList())
                .thenCompose(blobHash -> network.coreNode.getMetadataBlob(user)
                        .thenCompose(currentHash -> {
                            DataSink bout = new DataSink();
                            try {
                                currentHash.serialize(bout);
                                bout.writeArray(blobHash.toBytes());
                                byte[] signed = user.signMessage(bout.toByteArray());
                                return network.coreNode.setMetadataBlob(user, user, signed);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
                );
    }

    private CompletableFuture<Boolean> removeFromStaticData(FileTreeNode fileTreeNode) {
        ReadableFilePointer pointer = fileTreeNode.getPointer().filePointer;

        boolean isRemoved = staticData.remove(pointer);

        return isRemoved ? commit(user, rootKey, network) :
                CompletableFuture.completedFuture(true);
    };

    /**
     * Process any responses to our follow requests.
     *
     * @return initial follow requests
     */
    public CompletableFuture<List<FollowRequest>> processFollowRequests() {
        return network.coreNode.getFollowRequests(user.toUserPublicKey()).thenCompose(reqs -> {
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
                            CompletableFuture<TrieNode> updatedRoot = addToStaticDataAndCommit(root, freq.entry.get());
                            return updatedRoot.thenCompose(newRoot -> {
                                entrie = newRoot;
                                return network.coreNode.removeFollowRequest(user.toUserPublicKey(), user.signMessage(freq.rawCipher))
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
                            CompletableFuture<Boolean> removeDir = ourDirForThem.remove(this, sharing);
                            // remove entry point as well
                            CompletableFuture<Boolean> cleanStatic = removeFromStaticData(ourDirForThem);
                            // clear their response follow req too
                            CompletableFuture<Boolean> clearPending = network.coreNode.removeFollowRequest(user.toUserPublicKey(), user.signMessage(freq.rawCipher));

                            return removeDir.thenCompose(x -> cleanStatic)
                                    .thenCompose(x -> clearPending)
                                    .thenCompose(b -> addToStatic.apply(trie, freq));
                        } else if (freq.entry.get().pointer.isNull()) {
                            // They reciprocated, but didn't accept (they follow us, but we can't follow them)
                            return CompletableFuture.completedFuture(trie);
                        } else {
                            // add new entry to tree root
                            EntryPoint entry = freq.entry.get();
                            return retrieveEntryPoint(entry).thenCompose(treeNode ->
                                    treeNode.get().getPath(this)).thenApply(path ->
                                    trie.put(path, entry)
                            ).thenCompose(trieres -> addToStatic.apply(trieres, freq).thenApply(b -> trieres));
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
        UserPublicKey tmp = UserPublicKey.deserialize(new DataSource(Serialize.deserializeByteArray(buf, 4096)));
        byte[] cipher = buf.readArray();
        byte[] plaintext = user.decryptMessage(cipher, tmp.publicBoxingKey);
        DataSource input = new DataSource(plaintext);
        byte[] rawEntry = input.readArray();
        byte[] rawKey = input.readArray();
        return new FollowRequest(rawEntry.length > 0 ? Optional.of(EntryPoint.deserialize(rawEntry)) : Optional.empty(),
                rawKey.length > 0 ? Optional.of(SymmetricKey.deserialize(rawKey)) : Optional.empty(), raw);
    }

    private CompletableFuture<Multihash> uploadFragment(Fragment f, UserPublicKey targetUser) {
        return network.dhtClient.put(f.data, targetUser, Collections.emptyList());
    }

    public CompletableFuture<List<Multihash>> uploadFragments(List<Fragment> fragments, UserPublicKey owner,
                                                              UserPublicKey sharer, byte[] mapKey, ProgressConsumer<Long> progressCounter) {
        List<CompletableFuture<Multihash>> futures = fragments.stream()
                .map(f -> uploadFragment(f, owner)
                        .thenApply(hash -> {
                            if (progressCounter != null)
                                progressCounter.accept(1L);
                            return hash;
                        }))
                .collect(Collectors.toList());
        return Futures.combineAllInOrder(futures);
    }

    public CompletableFuture<Boolean> uploadChunk(FileAccess metadata, Location location, List<Multihash> linkHashes) {
        DataSink dout = new DataSink();
        try {
            metadata.serialize(dout);
            byte[] metaBlob = dout.toByteArray();
            System.out.println("Storing metadata blob of " + metaBlob.length + " bytes. to mapKey: " + location.toString());
            return network.dhtClient.put(metaBlob, location.owner, linkHashes).thenCompose(blobHash -> {
                User sharer = (User) location.writer;
                return network.btree.put(sharer, location.getMapKey(), blobHash).thenCompose(newBtreeRootCAS -> {
                    if (newBtreeRootCAS.left.equals(newBtreeRootCAS.right))
                        return CompletableFuture.completedFuture(true);
                    byte[] signed = sharer.signMessage(newBtreeRootCAS.toByteArray());
                    return network.coreNode.setMetadataBlob(location.owner, sharer, signed);
                });
            });
        } catch (Exception e) {
            System.out.println(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<Set<FileTreeNode>> getChildren(String path) {
        return entrie.getChildren(path, this);
    }

    @JsMethod
    public CompletableFuture<Optional<FileTreeNode>> getByPath(String path) {
        return entrie.getByPath(path, this);
    }

    public CompletableFuture<FileTreeNode> getUserRoot() {
        return getByPath("/"+username).thenApply(opt -> opt.get());
    }

    private CompletableFuture<byte[]> getStaticData() {
        return network.coreNode.getMetadataBlob(user.toUserPublicKey())
                .thenCompose(key -> network.dhtClient.get(key.get())
                        .thenApply(opt -> opt.get()));
    }

    /**
     *
     * @return TrieNode for root of filesystem
     */
    private CompletableFuture<TrieNode> createFileTree() {
        return getEntryPoints()
                .thenCompose(entryPoints ->
                        Futures.reduceAll(entryPoints, entrie, (t, e) -> addEntryPoint(t, e), (a, b) -> a)
                ).exceptionally(Futures::logError);
    }

    private CompletableFuture<TrieNode> addEntryPoint(TrieNode root, EntryPoint e) {
        return retrieveEntryPoint(e).thenCompose(metadata -> {
            if (metadata.isPresent()) {
                System.out.println("Added entry point: " + metadata.get());
                return metadata.get().getPath(this).thenApply(path -> root.put(path, e));
            }
            throw new IllegalStateException("Metadata blob not Present!");
        }).exceptionally(Futures::logError);
    }

    private CompletableFuture<Set<EntryPoint>> getEntryPoints() {
        return getStaticData().thenApply(raw -> {
            try {
                DataSource source = new DataSource(raw);
                int count = source.readInt();
                System.out.println("Found "+count+" entry points");

                for (int i = 0; i < count; i++) {
                    EntryPoint entry = EntryPoint.symmetricallyDecryptAndDeserialize(source.readArray(), rootKey);
                    staticData.add(entry);
                }
                return staticData.getEntryPoints();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    protected CompletableFuture<Optional<FileTreeNode>> retrieveEntryPoint(EntryPoint e) {
        return downloadEntryPoint(e)
                .thenApply(faOpt ->faOpt.map(fa -> new FileTreeNode(new RetrievedFilePointer(e.pointer, fa), e.owner,
                        e.readers, e.writers, e.pointer.location.writer)));
    }

    private CompletableFuture<Optional<FileAccess>> downloadEntryPoint(EntryPoint entry) {
        // download the metadata blob for this entry point
        return network.btree.get(entry.pointer.location.writer, entry.pointer.location.getMapKey()).thenCompose(btreeValue -> {
            if (btreeValue.isPresent())
                return network.dhtClient.get(btreeValue.get())
                        .thenApply(value -> value.map(FileAccess::deserialize));
            return CompletableFuture.completedFuture(Optional.empty());
        });
    }

    public CompletableFuture<List<RetrievedFilePointer>> retrieveAllMetadata(List<SymmetricLocationLink> links, SymmetricKey baseKey) {
        List<CompletableFuture<Optional<RetrievedFilePointer>>> all = links.stream()
                .map(link -> {
                    Location loc = link.targetLocation(baseKey);
                    return network.btree.get(loc.writer, loc.getMapKey())
                            .thenCompose(key -> network.dhtClient.get(key.get()))
                            .thenApply(dataOpt -> {
                                if (!dataOpt.isPresent() || dataOpt.get().length == 0)
                                    return Optional.<RetrievedFilePointer>empty();
                                return dataOpt.map(data -> new RetrievedFilePointer(link.toReadableFilePointer(baseKey), FileAccess.deserialize(data)));
                            });
                }).collect(Collectors.toList());

        return Futures.combineAll(all).thenApply(optSet -> optSet.stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList()));
    }

    public CompletableFuture<Optional<FileAccess>> getMetadata(Location loc) {
        if (loc == null)
            return CompletableFuture.completedFuture(Optional.empty());
        return network.btree.get(loc.writer, loc.getMapKey()).thenCompose(blobHash -> {
            if (!blobHash.isPresent())
                return CompletableFuture.completedFuture(Optional.empty());
            return network.dhtClient.get(blobHash.get())
                    .thenApply(rawOpt -> rawOpt.map(FileAccess::deserialize));
        });
    };

    public CompletableFuture<List<FragmentWithHash>> downloadFragments(List<Multihash> hashes, ProgressConsumer<Long> monitor) {
        List<CompletableFuture<Optional<FragmentWithHash>>> futures = hashes.stream()
                .map(h -> network.dhtClient.get(h)
                        .thenApply(dataOpt -> {
                            monitor.accept(1L);
                            return dataOpt.map(data -> new FragmentWithHash(new Fragment(data), h));
                        }))
                .collect(Collectors.toList());

        return Futures.combineAllInOrder(futures)
                .thenApply(optList -> optList.stream().filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList()));
    }

    public byte[] randomBytes(int length) {
        byte[] res = new byte[length];
        crypto.random.randombytes(res, 0, length);
        return res;
    }

    public CompletableFuture<Boolean> unfollow(String friendName) throws IOException {
        System.out.println("Unfollowing: "+friendName);
        // remove entry point from static data
        String friendPath = "/" + friendName + "/shared/" + username;
        return getByPath(friendPath)
                // remove our static data entry storing that they've granted us access
                .thenCompose(dir -> removeFromStaticData(dir.get()))
                .thenApply(b -> {
                    entrie = entrie.removeEntry(friendPath);
                    return true;
                });
    }

    public CompletableFuture<Boolean> removeFollower(String username) throws IOException {
        System.out.println("Remove follower: " + username);
        // remove /$us/shared/$them
        return getSharingFolder()
                .thenCompose(sharing -> getByPath("/"+this.username+"/shared/"+username)
                        .thenCompose(dir -> dir.get().remove(this, sharing)
                                // remove our static data entry storing that we've granted them access
                                .thenCompose(b -> removeFromStaticData(dir.get()))));
    }

    public void logout() {
        entrie = entrie.clear();
    }

    public Fragmenter fragmenter() {
        return fragmenter;
    }
}
