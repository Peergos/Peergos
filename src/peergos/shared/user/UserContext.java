package peergos.shared.user;

import peergos.client.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.merklebtree.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import jsinterop.annotations.*;

public class UserContext {
    public static final String SHARED_DIR_NAME = "shared";

    public final String username;
    public final User user;
    private final SymmetricKey rootKey;
    private final SortedMap<UserPublicKey, EntryPoint> staticData = new TreeMap<>();
    private final TrieNode entrie = new TrieNode(); // ba dum che!
    private Set<String> usernames;
    private final Fragmenter fragmenter;

    // Contact external world
    public final DHTClient dhtClient;
    public final CoreNode corenodeClient;
    public final Btree btree;
    // In process only
    public final SafeRandom random;
    private final LoginHasher hasher;
    private final Salsa20Poly1305 symmetricProvider;
    private final Ed25519 signer;
    private final Curve25519 boxer;
    private final boolean useJavaScript;

    private static class TrieNode {
        Map<String, TrieNode> children = new HashMap<>();
        Optional<EntryPoint> value = Optional.empty();

        public CompletableFuture<Optional<FileTreeNode>> getByPath(String path, UserContext context) {
            System.out.println("GetByPath: "+path);
            String finalPath = path.startsWith("/") ? path.substring(1) : path;
            if (finalPath.length() == 0) {
                if (!value.isPresent()) { // find a child entry and traverse parent links
                    return children.values().stream().findAny().get()
                            .getByPath("", context)
                            .thenCompose(child -> child.get().retrieveParent(context));
                }
                return !value.isPresent() ?
                        CompletableFuture.completedFuture(Optional.empty()) :
                        context.retrieveEntryPoint(value.get());
            }
            String[] elements = finalPath.split("/");
            // There may be an entry point further down the tree, but it will have <= permission than this one
            if (value.isPresent())
                return context.retrieveEntryPoint(value.get())
                        .thenCompose(dir -> dir.get().getDescendentByPath(finalPath, context));
            if (!children.containsKey(elements[0]))
                return CompletableFuture.completedFuture(Optional.empty());
            return children.get(elements[0]).getByPath(finalPath.substring(elements[0].length()), context);
        }

        public CompletableFuture<Set<FileTreeNode>> getChildren(String path, UserContext context) {
            String trimmedPath = path.startsWith("/") ? path.substring(1) : path;
            if (trimmedPath.length() == 0) {
                if (!value.isPresent()) { // find a child entry and traverse parent links
                    Set<CompletableFuture<Optional<FileTreeNode>>> kids = children.values().stream()
                            .map(t -> t.getByPath("", context)).collect(Collectors.toSet());
                    return Futures.combineAll(kids)
                            .thenApply(set -> set.stream()
                                    .filter(opt -> opt.isPresent())
                                    .map(opt -> opt.get())
                                    .collect(Collectors.toSet()));
                }
                return context.retrieveEntryPoint(value.get())
                        .thenCompose(dir -> dir.get().getChildren(context));
            }
            String[] elements = trimmedPath.split("/");
            if (!children.containsKey(elements[0]))
                return context.retrieveEntryPoint(value.get())
                        .thenCompose(dir -> dir.get().getDescendentByPath(trimmedPath, context)
                                .thenCompose(parent -> parent.get().getChildren(context)));
            return children.get(elements[0]).getChildren(trimmedPath.substring(elements[0].length()), context);
        }

        public void put(String path, EntryPoint e) {
            if (path.startsWith("/"))
                path = path.substring(1);
            if (path.length() == 0) {
                value = Optional.of(e);
                return;
            }
            String[] elements = path.split("/");
            if (!children.containsKey(elements[0]))
                children.put(elements[0], new TrieNode());
            children.get(elements[0]).put(path.substring(elements[0].length()), e);
        }

        public void clear() {
            children.clear();
            value = Optional.empty();
        }
    }

    public UserContext(String username, User user, SymmetricKey root, DHTClient dht, Btree btree, CoreNode coreNode,
                       LoginHasher hasher, Salsa20Poly1305 provider, SafeRandom random, Ed25519 signer, Curve25519 boxer, boolean useJavaScript) {
        this(username, user, root, dht, btree, coreNode, hasher, provider, random, signer, boxer, new ErasureFragmenter(40, 10), useJavaScript);
    }

    public UserContext(String username, User user, SymmetricKey root, DHTClient dht, Btree btree, CoreNode coreNode,
                       LoginHasher hasher, Salsa20Poly1305 provider, SafeRandom random, Ed25519 signer,
                       Curve25519 boxer, Fragmenter fragmenter, boolean useJavaScript) {
        this.username = username;
        this.user = user;
        this.rootKey = root;
        this.dhtClient = dht;
        this.corenodeClient = coreNode;
        this.btree = btree;
        this.hasher = hasher;
        this.symmetricProvider = provider;
        this.random = random;
        this.signer = signer;
        this.boxer = boxer;
        this.fragmenter = fragmenter;
        this.useJavaScript = useJavaScript;
    }

    @JsMethod
    public static CompletableFuture<UserContext> ensureSignedUp(String username, String password, int webPort) throws IOException {
        return ensureSignedUp(username, password, webPort, true);
    }

    public static CompletableFuture<UserContext> ensureSignedUp(String username, String password, int webPort, boolean useJavaScript) throws IOException {
        if (useJavaScript) {
            System.setOut(new ConsolePrintStream());
        }
        LoginHasher hasher = useJavaScript ? new ScryptJS() : new ScryptJava();
        HttpPoster poster = useJavaScript ? new JavaScriptPoster() : new JavaPoster(new URL("http://localhost:" + webPort + "/"));
        CoreNode coreNode = new HTTPCoreNode(poster);
        DHTClient dht = new DHTClient.CachingDHTClient(new DHTClient.HTTP(poster), 1000, 50*1024);
        Btree btree = new Btree.HTTP(poster);
//        Btree btree = new BtreeImpl(coreNode, dht);
        Salsa20Poly1305 provider = /*useJavaScript ? new SymmetricJS() :*/ new Salsa20Poly1305.Java();
        SymmetricKey.addProvider(SymmetricKey.Type.TweetNaCl, provider);
        Ed25519 signer = /*useJavaScript ? new JSEd25519() :*/ new JavaEd25519();
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, signer);
        SafeRandom random = /*useJavaScript ? new JSRandom() :*/ new SafeRandom.Java();
        SymmetricKey.setRng(SymmetricKey.Type.TweetNaCl, random);
        Curve25519 boxer = /*useJavaScript ? new JSCurve25519() :*/ new JavaCurve25519();
        PublicBoxingKey.addProvider(PublicBoxingKey.Type.Curve25519, boxer);
        PublicBoxingKey.setRng(PublicBoxingKey.Type.Curve25519, random);
        return UserContext.ensureSignedUp(username, password, dht, btree, coreNode, hasher, provider, random, signer, boxer, useJavaScript);
    }

    public static CompletableFuture<UserContext> ensureSignedUp(String username, String password, DHTClient dht, Btree btree, CoreNode coreNode,
                                             LoginHasher hasher, Salsa20Poly1305 provider, SafeRandom random,
                                             Ed25519 signer, Curve25519 boxer, boolean useJavaScript) {
        UserWithRoot userWithRoot = UserUtil.generateUser(username, password, hasher, provider, random, signer, boxer);
        UserContext context = new UserContext(username, userWithRoot.getUser(), userWithRoot.getRoot(),
                dht, btree, coreNode, hasher, provider, random, signer, boxer, useJavaScript);
        System.out.println("made user context");
        CompletableFuture<UserContext> result = new CompletableFuture<>();
        context.isRegistered().thenAccept(registered -> {
            if (!registered) {
                System.out.println("User is not registered");

                context.isAvailable().thenAccept(available -> {
                    if (available) {
                        System.out.println("Registering username " + username);
                        context.register().thenAccept(successfullyRegistered -> {
                            if (! successfullyRegistered) {
                                System.out.println("Couldn't register username");
                                result.completeExceptionally(new IllegalStateException("Couldn't register username: " + username));
                                return;
                            }
                            System.out.println("Creating user's root directory");
                            long t1 = System.currentTimeMillis();
                            try {
                                context.createEntryDirectory(username).thenAccept(userRoot -> {
                                    System.out.println("Creating root directory took " + (System.currentTimeMillis() - t1) + " mS");
                                    ((DirAccess) userRoot.fileAccess).mkdir(SHARED_DIR_NAME, context,
                                            (User) userRoot.filePointer.location.writer,
                                            userRoot.filePointer.location.getMapKey(), userRoot.filePointer.baseKey, null, true, random)
                                            .thenApply(x -> result.complete(context));
                                });
                            } catch (Throwable e) {
                                result.completeExceptionally(e);
                            }
                        });
                    } else
                        result.completeExceptionally(new IllegalStateException("username already registered with different public key!"));
                });
            } else
                result.complete(context);
        });

        return result.thenApply(ctx -> {
            System.out.println("Initializing context..");
            ctx.init();
            return context;
        });
    }

    private CompletableFuture<Void> init() {
        staticData.clear();
        try {
            return createFileTree().thenCompose(y -> getByPath("/"+username + "/" + "shared").thenCompose(sharedOpt -> {
                if (!sharedOpt.isPresent())
                    throw new IllegalStateException("Couldn't find shared folder!");
                return corenodeClient.getAllUsernames().thenAccept(x -> usernames = x.stream().collect(Collectors.toSet()));
            }));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Set<String> getUsernames() {
        return usernames;
    }

    public CompletableFuture<FileTreeNode> getSharingFolder() {
        return getByPath("/"+username + "/shared").thenApply(opt -> opt.get());
    }

    @JsMethod
    public CompletableFuture<Boolean> isRegistered() {
        System.out.println("isRegistered");
        return corenodeClient.getUsername(user).thenApply(registeredUsername -> {
            System.out.println("got username \"" + registeredUsername + "\"");
            return this.username.equals(registeredUsername);
        });
    }

    @JsMethod
    public CompletableFuture<Boolean> isAvailable() {
        return corenodeClient.getPublicKey(username)
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
        return corenodeClient.updateChain(username, claimChain);
    }

    @JsMethod
    public CompletableFuture<UserContext> changePassword(String newPassword) throws IOException{
        System.out.println("changing password");
        LocalDate expiry = LocalDate.now();
        // set claim expiry to two months from now
        expiry.plusMonths(2);
        UserWithRoot updatedUser = UserUtil.generateUser(username, newPassword, hasher, symmetricProvider, random, signer, boxer);
        CompletableFuture<UserContext> result = new CompletableFuture<>();
        commitStaticData(updatedUser.getUser(), staticData, updatedUser.getRoot(), dhtClient, corenodeClient).thenApply(updated -> {
            if (!updated)
                return result.completeExceptionally(new IllegalStateException("Change Password Failed: couldn't upload new file system entry points!"));

            List<UserPublicKeyLink> claimChain = UserPublicKeyLink.createChain(user, updatedUser.getUser(), username, expiry);
            return corenodeClient.updateChain(username, claimChain).thenApply(updatedChain -> {
                if (!updatedChain)
                    return result.completeExceptionally(new IllegalStateException("Couldn't register new public keys during password change!"));

                return UserContext.ensureSignedUp(username, newPassword, dhtClient, btree, corenodeClient,
                        hasher, symmetricProvider, random, signer, boxer, useJavaScript)
                        .thenApply(context -> result.complete(context));
            });
        });
        return result;
    }

    public CompletableFuture<RetrievedFilePointer> createEntryDirectory(String directoryName) throws IOException {
        long t1 = System.currentTimeMillis();
        User writer = User.random(random, signer, boxer);
        System.out.println("Random User generation took " + (System.currentTimeMillis()-t1) + " mS");
        byte[] rootMapKey = new byte[32]; // root will be stored under this in the core node
        random.randombytes(rootMapKey, 0, 32);
        SymmetricKey rootRKey = SymmetricKey.random();
        System.out.println("Random keys generation took " + (System.currentTimeMillis()-t1) + " mS");

        // and authorise the writer key
        ReadableFilePointer rootPointer = new ReadableFilePointer(this.user, writer, rootMapKey, rootRKey);
        EntryPoint entry = new EntryPoint(rootPointer, this.username, Collections.emptySet(), Collections.emptySet());

        long t2 = System.currentTimeMillis();
        DirAccess root = DirAccess.create(rootRKey, new FileProperties(directoryName, 0, LocalDateTime.now(), false, Optional.empty()), (Location)null, null, null);
        Location rootLocation = new Location(this.user, writer, rootMapKey);
        System.out.println("Uploading entry point directory");
        return this.uploadChunk(root, rootLocation, Collections.emptyList()).thenApply(uploaded -> {
            long t3 = System.currentTimeMillis();
            System.out.println("Uploading root dir metadata took " + (t3 - t2) + " mS");
            try {
                addToStaticDataAndCommit(entry);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println("Committing static data took " + (System.currentTimeMillis() - t3) + " mS");

            if (uploaded)
                return new RetrievedFilePointer(rootPointer, root);
            throw new IllegalStateException("Failed to create entry directory!");
        });
    }

    public CompletableFuture<Set<FileTreeNode>> getFriendRoots() {
        List<CompletableFuture<Optional<FileTreeNode>>> friendRoots = entrie.children.keySet()
                .stream()
                .filter(p -> !p.startsWith(username))
                .map(p -> getByPath(p)).collect(Collectors.toList());
        return Futures.combineAll(friendRoots)
                .thenApply(set -> set.stream().filter(opt -> opt.isPresent()).map(opt -> opt.get()).collect(Collectors.toSet()));
    }

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
        return getFollowRequests()
                .thenCompose(pending -> getFollowerRoots()
                        .thenCompose(followerRoots -> getFriendRoots()
                                .thenApply(followingRoots -> new SocialState(pending, followerRoots, followingRoots))));
    }

    public CompletableFuture<Boolean> sendInitialFollowRequest(String targetUsername) throws IOException {
        return sendFollowRequest(targetUsername, SymmetricKey.random());
    }

    // FollowRequest, boolean, boolean
    public CompletableFuture<Boolean> sendReplyFollowRequest(FollowRequest initialRequest, boolean accept, boolean reciprocate) {
        /*String theirUsername = initialRequest.entry.get().owner;
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
            User tmp = User.random(random, signer, boxer);
            byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

            DataSink resp = new DataSink();
            resp.writeArray(tmp.getPublicKeys());
            resp.writeArray(payload);
            corenodeClient.followRequest(initialRequest.entry.get().pointer.location.owner, resp.toByteArray());
            // remove pending follow request from them
            return corenodeClient.removeFollowRequest(user, user.signMessage(initialRequest.rawCipher));
        }

        DataSink dout = new DataSink();
        if (accept) {
            ReadableFilePointer friendRoot = getSharingFolder().mkdir(theirUsername, this, initialRequest.key.get(), true, random).get();
            // add a note to our static data so we know who we sent the read access to
            EntryPoint entry = new EntryPoint(friendRoot.readOnly(), username, Stream.of(theirUsername).collect(Collectors.toSet()), Collections.EMPTY_SET);

            addToStaticDataAndCommit(entry);

            dout.writeArray(entry.serialize());
        } else {
            EntryPoint entry = new EntryPoint(ReadableFilePointer.createNull(), username, Collections.EMPTY_SET, Collections.EMPTY_SET);
            dout.writeArray(entry.serialize());
        }

        if (! reciprocate) {
            dout.writeArray(new byte[0]); // tell them we're not reciprocating
        } else {
            // if reciprocate, add entry point to their shared directory (we follow them) and then
            dout.writeArray(initialRequest.entry.get().pointer.baseKey.serialize()); // tell them we are reciprocating
        }
        byte[] plaintext = dout.toByteArray();
        UserPublicKey targetUser = initialRequest.entry.get().pointer.location.owner;
        // create a tmp keypair whose public key we can prepend to the request without leaking information
        User tmp = User.random(random, signer, boxer);
        byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

        DataSink resp = new DataSink();
        resp.writeArray(tmp.getPublicKeys());
        resp.writeArray(payload);
        corenodeClient.followRequest(initialRequest.entry.get().pointer.location.owner.toUserPublicKey(), resp.toByteArray());
        if (reciprocate)
            addToStaticDataAndCommit(initialRequest.entry.get());
        // remove original request
        return corenodeClient.removeFollowRequest(user.toUserPublicKey(), user.signMessage(initialRequest.rawCipher));*/
        throw new IllegalStateException("Unimplemented!");
    }

    // string, RetrievedFilePointer, SymmetricKey
    public CompletableFuture<Boolean> sendFollowRequest(String targetUsername, SymmetricKey requestedKey) throws IOException {
        /*FileTreeNode sharing = getSharingFolder();
        Set<FileTreeNode> children = sharing.getChildren(this);
        boolean alreadyFollowed = children.stream()
                .filter(f -> f.getFileProperties().name.equals(targetUsername))
                .findAny()
                .isPresent();
        if (alreadyFollowed)
            return CompletableFuture.completedFuture(false);
        // check for them not reciprocating
        Set<String> following = getFollowing();
        alreadyFollowed = following.stream().filter(x -> x.equals(targetUsername)).findAny().isPresent();
        if (alreadyFollowed)
            return CompletableFuture.completedFuture(false);

        return corenodeClient.getPublicKey(targetUsername).thenApply(targetUserOpt -> {
            if (!targetUserOpt.isPresent())
                return false;
            try {
                UserPublicKey targetUser = targetUserOpt.get();
                ReadableFilePointer friendRoot = sharing.mkdir(targetUsername, this, null, true, random).get();

                // add a note to our static data so we know who we sent the read access to
                EntryPoint entry = new EntryPoint(friendRoot.readOnly(), username, Stream.of(targetUsername).collect(Collectors.toSet()), Collections.EMPTY_SET);
                addToStaticDataAndCommit(entry);
                // send details to allow friend to follow us, and optionally let us follow them
                // create a tmp keypair whose public key we can prepend to the request without leaking information
                User tmp = User.random(random, signer, boxer);
                DataSink buf = new DataSink();
                buf.writeArray(entry.serialize());
                buf.writeArray(requestedKey != null ? requestedKey.serialize() : new byte[0]);
                byte[] plaintext = buf.toByteArray();
                byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

                DataSink res = new DataSink();
                res.writeArray(tmp.getPublicKeys());
                res.writeArray(payload);
                return corenodeClient.followRequest(targetUser.toUserPublicKey(), res.toByteArray());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });*/
        throw new IllegalStateException("Unimplemented!");
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

    public void unShare(Path path, String readerToRemove) throws IOException {
        unShare(path, Collections.singleton(readerToRemove));
    }

    public void unShare(Path path, Set<String> readersToRemove) throws IOException {
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
        throw new IllegalStateException("Unimplemented!");
    }

    public CompletableFuture<Set<String>> sharedWith(FileTreeNode file) {
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
        throw new IllegalStateException("Unimplemented!");
    }

    public void share(Path path, Set<String> readersToAdd) throws IOException {
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
        throw new IllegalStateException("Unimplemented!");
    }

    private boolean addToStaticData(EntryPoint entry) {
        for (int i=0; i < staticData.size(); i++)
            if (entry.equals(staticData.get(entry.pointer.location.writer)))
                return true;
        staticData.put(entry.pointer.location.writer, entry);
        return true;
    }

    private void addToStaticDataAndCommit(EntryPoint entry) throws IOException {
        addToStaticData(entry);
        commitStaticData(user, staticData, rootKey, dhtClient, corenodeClient);
        addEntryPoint(entry);
    }

    private static CompletableFuture<Boolean> commitStaticData(User user, SortedMap<UserPublicKey, EntryPoint> staticData, SymmetricKey rootKey
            , DHTClient dhtClient, CoreNode corenodeClient) {
        byte[] rawStatic = serializeStatic(staticData, rootKey);
        return dhtClient.put(rawStatic, user, Collections.emptyList())
                .thenCompose(blobHash -> corenodeClient.getMetadataBlob(user)
                        .thenCompose(currentHash -> {
                            DataSink bout = new DataSink();
                            try {
                                currentHash.serialize(bout);
                                bout.writeArray(blobHash.toBytes());
                                byte[] signed = user.signMessage(bout.toByteArray());
                                return corenodeClient.setMetadataBlob(user, user, signed);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
                );
    }

    private CompletableFuture<Boolean> removeFromStaticData(FileTreeNode fileTreeNode) throws IOException {
        ReadableFilePointer pointer = fileTreeNode.getPointer().filePointer;
        // find and remove matching entry point
        Iterator<Map.Entry<UserPublicKey, EntryPoint>> iter = staticData.entrySet().iterator();
        for (;iter.hasNext();) {
            Map.Entry<UserPublicKey, EntryPoint> entry = iter.next();
            if (entry.getValue().pointer.equals(pointer)) {
                iter.remove();
                return commitStaticData(user, staticData, rootKey, dhtClient, corenodeClient);
            }
        }
        return CompletableFuture.completedFuture(true);
    };

    public CompletableFuture<List<FollowRequest>> getFollowRequests() {
        /*
        byte[] reqs = corenodeClient.getFollowRequests(user.toUserPublicKey());
        DataSource din = new DataSource(reqs);
        int n = din.readInt();
        List<FollowRequest> all = IntStream.range(0, n)
                .mapToObj(i -> i)
                .flatMap(i -> {
                    try {
                        return Stream.of(decodeFollowRequest(din.readArray()));
                    } catch (IOException ioe) {
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toList());
        Map<String, FileTreeNode> followerRoots = getFollowerRoots();
        List<FollowRequest> initialRequests = all.stream()
                .filter(freq -> {
                    try {
                        if (followerRoots.containsKey(freq.entry.get().owner)) {
                            // delete our folder if they didn't reciprocate
                            FileTreeNode ourDirForThem = followerRoots.get(freq.entry.get().owner);
                            byte[] ourKeyForThem = ourDirForThem.getKey().serialize();
                            byte[] keyFromResponse = freq.key.map(k -> k.serialize()).orElse(null);
                            if (keyFromResponse == null || !Arrays.equals(keyFromResponse, ourKeyForThem)) {
                                ourDirForThem.remove(this, getSharingFolder());
                                // remove entry point as well
                                removeFromStaticData(ourDirForThem);
                                // clear their response follow req too
                                corenodeClient.removeFollowRequest(user.toUserPublicKey(), user.signMessage(freq.rawCipher));
                            } else if (freq.entry.get().pointer.isNull()) {
                                // They reciprocated, but didn't accept (they follow us, but we can't follow them)
                            } else {
                                // add new entry to tree root
                                EntryPoint entry = freq.entry.get();
                                FileTreeNode treenode = retrieveEntryPoint(entry).get();
                                String path = treenode.getPath(this);
                                entrie.put(path, entry);
                            }
                            // add entry point to static data
                            if (!Arrays.equals(freq.entry.get().pointer.baseKey.serialize(), SymmetricKey.createNull().serialize())) {
                                addToStaticDataAndCommit(freq.entry.get());
                                return corenodeClient.removeFollowRequest(user.toUserPublicKey(), user.signMessage(freq.rawCipher));
                            }
                            return false;
                        }
                        return !followerRoots.containsKey(freq.entry.get().owner);
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                        return false;
                    }
                })
                .collect(Collectors.toList());
        return initialRequests;*/
        throw new IllegalStateException("Unimplemented!");
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
        return dhtClient.put(f.data, targetUser, Collections.emptyList());
    }

    public CompletableFuture<List<Multihash>> uploadFragments(List<Fragment> fragments, UserPublicKey owner, UserPublicKey sharer, byte[] mapKey, Consumer<Long> progressCounter) {
        List<CompletableFuture<Multihash>> futures = fragments.stream()
                .map(f -> uploadFragment(f, owner)
                        .thenApply(hash -> {
                            if (progressCounter != null)
                                progressCounter.accept(1L);
                            return hash;
                        }))
                .collect(Collectors.toList());
        return Futures.combineAll(futures)
                .thenApply(set -> set.stream().collect(Collectors.toList()));
    }

    public CompletableFuture<Boolean> uploadChunk(FileAccess metadata, Location location, List<Multihash> linkHashes) {
        DataSink dout = new DataSink();
        try {
            metadata.serialize(dout);
            byte[] metaBlob = dout.toByteArray();
            System.out.println("Storing metadata blob of " + metaBlob.length + " bytes. to mapKey: " + location.toString());
            return dhtClient.put(metaBlob, location.owner, linkHashes).thenCompose(blobHash -> {
                User sharer = (User) location.writer;
                return btree.put(sharer, location.getMapKey(), blobHash).thenCompose(newBtreeRootCAS -> {
                    if (newBtreeRootCAS.left.equals(newBtreeRootCAS.right))
                        return CompletableFuture.completedFuture(true);
                    byte[] signed = sharer.signMessage(newBtreeRootCAS.toByteArray());
                    return corenodeClient.setMetadataBlob(location.owner, sharer, signed);
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

    private CompletableFuture<byte[]> getStaticData() throws IOException {
        return corenodeClient.getMetadataBlob(user.toUserPublicKey())
                .thenCompose(key -> dhtClient.get(key.get())
                        .thenApply(opt -> opt.get()));
    }

    private CompletableFuture<Void> createFileTree() throws IOException {
        return getEntryPoints()
                .thenAccept(entryPoints -> entryPoints.forEach(e -> addEntryPoint(e)));
    }

    private CompletableFuture<Boolean> addEntryPoint(EntryPoint e) {
        return retrieveEntryPoint(e).thenCompose(metadata -> {
            if (metadata.isPresent()) {
                System.out.println("Added entry point: " + metadata.get());
                return metadata.get().getPath(this).thenApply(path -> {
                    entrie.put(path, e);
                    return true;
                });
            }
            return CompletableFuture.completedFuture(false);
        });
    }

    private CompletableFuture<Set<EntryPoint>> getEntryPoints() throws IOException {
        return getStaticData().thenApply(raw -> {
            try {
                DataSource source = new DataSource(raw);
                int count = source.readInt();
                Set<EntryPoint> res = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    EntryPoint entry = EntryPoint.symmetricallyDecryptAndDeserialize(source.readArray(), rootKey);
                    res.add(entry);
                    addToStaticData(entry);
                }
                return res;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private CompletableFuture<Optional<FileTreeNode>> retrieveEntryPoint(EntryPoint e) {
        return downloadEntryPoint(e)
                .thenApply(faOpt ->faOpt.map(fa -> new FileTreeNode(new RetrievedFilePointer(e.pointer, fa), e.owner,
                        e.readers, e.writers, e.pointer.location.writer)));
    }

    private CompletableFuture<Optional<FileAccess>> downloadEntryPoint(EntryPoint entry) {
        // download the metadata blob for this entry point
        return btree.get(entry.pointer.location.writer, entry.pointer.location.getMapKey()).thenCompose(btreeValue -> {
            if (btreeValue.isPresent())
                return dhtClient.get(btreeValue.get())
                        .thenApply(value -> value.map(FileAccess::deserialize));
            return CompletableFuture.completedFuture(Optional.empty());
        });
    }

    public CompletableFuture<List<RetrievedFilePointer>> retrieveAllMetadata(List<SymmetricLocationLink> links, SymmetricKey baseKey) {
        List<CompletableFuture<Optional<RetrievedFilePointer>>> all = links.stream()
                .map(link -> {
                    Location loc = link.targetLocation(baseKey);
                    return btree.get(loc.writer, loc.getMapKey())
                            .thenCompose(key -> dhtClient.get(key.get()))
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
        return btree.get(loc.writer, loc.getMapKey()).thenCompose(blobHash -> {
            if (!blobHash.isPresent())
                return CompletableFuture.completedFuture(Optional.empty());
            return dhtClient.get(blobHash.get())
                    .thenApply(rawOpt -> rawOpt.map(FileAccess::deserialize));
        });
    };

    public CompletableFuture<List<FragmentWithHash>> downloadFragments(List<Multihash> hashes, Consumer<Long> monitor) {
        List<CompletableFuture<Optional<FragmentWithHash>>> futures = hashes.stream()
                .map(h -> dhtClient.get(h)
                        .thenApply(dataOpt -> {
                            monitor.accept(1L);
                            return dataOpt.map(data -> new FragmentWithHash(new Fragment(data), h));
                        }))
                .collect(Collectors.toList());

        return Futures.combineAll(futures)
                .thenApply(set -> set.stream().filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList()));
    }

    public byte[] randomBytes(int length) {
        byte[] res = new byte[length];
        random.randombytes(res, 0, length);
        return res;
    }

    public void unfollow(String username) throws IOException {
        /*
        System.out.println("Unfollowing: "+username);
        // remove entry point from static data
        Optional<FileTreeNode> dir = getByPath("/"+username+"/shared/"+username);
        // remove our static data entry storing that we've granted them access
        removeFromStaticData(dir.get());
        Optional<FileTreeNode> entry = getByPath("/"+username);
        entry.get().remove(this, FileTreeNode.createRoot());*/
        throw new IllegalStateException("Unimplemented!");
    }

    public void removeFollower(String username) throws IOException {
        /*
        System.out.println("Remove follower: " + username);
        // remove /$us/shared/$them
        Optional<FileTreeNode> dir = getByPath("/"+username+"/shared/"+username);
        dir.get().remove(this, getSharingFolder());
        // remove our static data entry storing that we've granted them access
        removeFromStaticData(dir.get());*/
        throw new IllegalStateException("Unimplemented!");
    }

    public void logout() {
        entrie.clear();
    }

    public Fragmenter fragmenter() {
        return fragmenter;
    }
}
