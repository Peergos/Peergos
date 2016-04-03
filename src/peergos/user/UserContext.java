package peergos.user;

import org.ipfs.api.*;
import peergos.corenode.*;
import peergos.crypto.*;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.server.merklebtree.*;
import peergos.user.fs.*;
import peergos.util.*;

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class UserContext {
    public static final String SHARED_DIR_NAME = "shared";

    public final String username;
    public final User user;
    private final SymmetricKey rootKey;
    public final DHTClient dhtClient;
    public final CoreNode corenodeClient;
    public final Btree btree;

    private final SortedMap<UserPublicKey, EntryPoint> staticData = new TreeMap<>();
    private Set<String> usernames;
    private FileTreeNode rootNode;
    private FileTreeNode sharingFolder;

    public UserContext(String username, User user, SymmetricKey root, DHTClient dht, Btree btree, CoreNode coreNode) throws IOException {
        this.username = username;
        this.user = user;
        this.rootKey = root;
        this.dhtClient = dht;
        this.corenodeClient = coreNode;
        this.btree = btree;
    }

    public static UserContext ensureSignedUp(String username, String password, DHTClient dht, Btree btree, CoreNode coreNode) throws IOException {
        UserWithRoot userWithRoot = UserUtil.generateUser(username, password);
        UserContext context = new UserContext(username, userWithRoot.getUser(), userWithRoot.getRoot(), dht, btree, coreNode);
        if (!context.isRegistered()) {
            context.register();
            RetrievedFilePointer userRoot = context.createEntryDirectory(username);
            ReadableFilePointer shared = ((DirAccess) userRoot.fileAccess).mkdir(SHARED_DIR_NAME, context, (User) userRoot.filePointer.writer,
                    userRoot.filePointer.mapKey, userRoot.filePointer.baseKey, null, true);
        }
        context.init();
        return context;
    }

    public void init() throws IOException {
        staticData.clear();
        this.rootNode = FileTreeNode.createRoot();
        createFileTree();
        Set<FileTreeNode> children = rootNode.getChildren(this);
        for (FileTreeNode child: children) {
            if (child.getFileProperties().name.equals(username)) {
                Set<FileTreeNode> ourdirs = child.getChildren(this);
                for (FileTreeNode childNode: ourdirs) {
                    if (childNode.getFileProperties().name.equals("shared")) {
                        sharingFolder = childNode;
                        usernames = corenodeClient.getAllUsernames().stream().collect(Collectors.toSet());
                        return;
                    }
                }
            }
            throw new IllegalStateException("Couldn't find shared folder!");
        }
        throw new IllegalStateException("No root directory found!");
    }

    public Set<String> getUsernames() {
        return usernames;
    }

    public FileTreeNode getSharingFolder() {
        return sharingFolder;
    }

    public boolean isRegistered() throws IOException {
        return username.equals(corenodeClient.getUsername(user));
    }

    public byte[] serializeStatic() throws IOException {
        DataSink sink = new DataSink();
        sink.writeInt(staticData.size());
        staticData.values().forEach(ep -> sink.writeArray(ep.serializeAndSymmetricallyEncrypt(rootKey)));
        return sink.toByteArray();
    }

    public boolean register() {
        System.out.println("claiming username: "+username);
        LocalDate expiry = LocalDate.now();
        // set claim expiry to two months from now
        expiry.plusMonths(2);
        List<UserPublicKeyLink> claimChain = UserPublicKeyLink.createInitial(user, username, expiry);
        return corenodeClient.updateChain(username, claimChain);
    }

    public RetrievedFilePointer createEntryDirectory(String directoryName) throws IOException {
        User writer = User.random();
        byte[] rootMapKey = TweetNaCl.securedRandom(32); // root will be stored under this in the core node
        SymmetricKey rootRKey = SymmetricKey.random();

        // and authorise the writer key
        ReadableFilePointer rootPointer = new ReadableFilePointer(this.user, writer, rootMapKey, rootRKey);
        EntryPoint entry = new EntryPoint(rootPointer, this.username, Collections.emptySet(),Collections.emptySet());

        addToStaticDataAndCommit(entry);
        DirAccess root = DirAccess.create(rootRKey, new FileProperties(directoryName, 0, LocalDateTime.now(), false, Optional.empty()), (Location)null, null, null);
        boolean uploaded = this.uploadChunk(root, this.user, writer, rootMapKey, Collections.emptyList());
        if (uploaded)
            return new RetrievedFilePointer(rootPointer, root);
        throw new IllegalStateException("Failed to create entry directory!");
    }

    public Set<FileTreeNode> getFriendRoots() {
        return this.rootNode.getChildren(this).stream().filter(x -> !x.getOwner().equals(username)).collect(Collectors.toSet());
    }

    public Set<String> getFollowers() {
        return getSharingFolder().getChildren(this).stream()
                .flatMap(f -> Stream.of(f.getFileProperties().name))
                .sorted(UserContext::humanSort).collect(Collectors.toSet());
    }

    private static int humanSort(String a, String b) {
        return a.toLowerCase().compareTo(b.toLowerCase());
    }

    public Set<String> getFollowing() {
        return getFriendRoots().stream().map(froot -> froot.getOwner()).filter(name -> !name.equals(username)).collect(Collectors.toSet());
    }

    Map<String, FileTreeNode> getFollowerRoots() {
        return getSharingFolder()
                .getChildren(this)
                .stream()
                .flatMap(e -> Stream.of(new AbstractMap.SimpleEntry<>(e.getFileProperties().name, e)))
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
    }

    public SocialState getSocialState() throws IOException {
        List<FollowRequest> pending = getFollowRequests();
        Map<String, FileTreeNode> followerRoots = getFollowerRoots();
        Set<FileTreeNode> followingRoots = getFriendRoots();
        return new SocialState(pending, followerRoots, followingRoots);
    }

    public boolean sendInitialFollowRequest(String targetUsername) throws IOException {
        return sendFollowRequest(targetUsername, SymmetricKey.random());
    }

    // FollowRequest, boolean, boolean
    public boolean sendReplyFollowRequest(FollowRequest initialRequest, boolean accept, boolean reciprocate) throws IOException {
        String theirUsername = initialRequest.entry.get().owner;
        // if accept, create directory to share with them, note in entry points (they follow us)
        if (!accept) {
            // send a null entry and null key (full rejection)

            DataSink dout = new DataSink();
            // write a null entry point
            EntryPoint entry = new EntryPoint(ReadableFilePointer.createNull(), username, Collections.EMPTY_SET, Collections.EMPTY_SET);
            dout.writeArray(entry.serialize());
            dout.writeArray(new byte[0]); // tell them we're not reciprocating
            byte[] plaintext = dout.toByteArray();
            UserPublicKey targetUser = initialRequest.entry.get().pointer.owner;
            // create a tmp keypair whose public key we can prepend to the request without leaking information
            User tmp = User.random();
            byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

            corenodeClient.followRequest(initialRequest.entry.get().pointer.owner, ArrayOps.concat(tmp.publicBoxingKey.toByteArray(), payload));
            // remove pending follow request from them
            return corenodeClient.removeFollowRequest(user, user.signMessage(initialRequest.rawCipher));
        }
        ReadableFilePointer friendRoot = getSharingFolder().mkdir(theirUsername, this, initialRequest.key.get(), true).get();
        // add a note to our static data so we know who we sent the read access to
        EntryPoint entry = new EntryPoint(friendRoot.readOnly(), username, Stream.of(theirUsername).collect(Collectors.toSet()), Collections.EMPTY_SET);
        UserPublicKey targetUser = initialRequest.entry.get().pointer.owner;
        addToStaticDataAndCommit(entry);
        // create a tmp keypair whose public key we can prepend to the request without leaking information
        User tmp = User.random();
        DataSink dout = new DataSink();
        dout.writeArray(entry.serialize());
        if (! reciprocate) {
            dout.writeArray(new byte[0]); // tell them we're not reciprocating
        } else {
            // if reciprocate, add entry point to their shared dirctory (we follow them) and then
            dout.writeArray(initialRequest.entry.get().pointer.baseKey.serialize()); // tell them we are reciprocating
        }
        byte[] plaintext = dout.toByteArray();
        byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

        DataSink resp = new DataSink();
        resp.writeArray(tmp.getPublicKeys());
        resp.writeArray(payload);
        corenodeClient.followRequest(initialRequest.entry.get().pointer.owner.toUserPublicKey(), resp.toByteArray());
        addToStaticDataAndCommit(initialRequest.entry.get());
        // remove original request
        return corenodeClient.removeFollowRequest(user.toUserPublicKey(), user.signMessage(initialRequest.rawCipher));
    }

    // string, RetrievedFilePointer, SymmetricKey
    public boolean sendFollowRequest(String targetUsername, SymmetricKey requestedKey) throws IOException {
        FileTreeNode sharing = getSharingFolder();
        Set<FileTreeNode> children = sharing.getChildren(this);
        boolean alreadyFollowed = children.stream()
                .filter(f -> f.getFileProperties().name.equals(targetUsername))
                .findAny()
                .isPresent();
        if (alreadyFollowed)
            return false;
        // check for them not reciprocating
        Set<String> following = getFollowing();
        alreadyFollowed = following.stream().filter(x -> x.equals(targetUsername)).findAny().isPresent();
        if (alreadyFollowed)
            return false;

        UserPublicKey targetUser = corenodeClient.getPublicKey(targetUsername);
        ReadableFilePointer friendRoot = sharing.mkdir(targetUsername, this, null, true).get();

        // add a note to our static data so we know who we sent the read access to
        EntryPoint entry = new EntryPoint(friendRoot.readOnly(), username, Stream.of(targetUsername).collect(Collectors.toSet()), Collections.EMPTY_SET);
        addToStaticDataAndCommit(entry);
        // send details to allow friend to follow us, and optionally let us follow them
        // create a tmp keypair whose public key we can prepend to the request without leaking information
        User tmp = User.random();
        DataSink buf = new DataSink();
        buf.writeArray(entry.serialize());
        buf.writeArray(requestedKey != null ? requestedKey.serialize() : new byte[0]);
        byte[] plaintext = buf.toByteArray();
        byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

        DataSink res = new DataSink();
        res.writeArray(tmp.getPublicKeys());
        res.writeArray(payload);
        return corenodeClient.followRequest(targetUser.toUserPublicKey(), res.toByteArray());
    };

    public boolean sendWriteAccess(UserPublicKey targetUser) throws IOException {
        // create sharing keypair and give it write access
        User sharing = User.random();
        byte[] rootMapKey = TweetNaCl.securedRandom(32);

        // add a note to our static data so we know who we sent the private key to
        ReadableFilePointer friendRoot = new ReadableFilePointer(user, sharing, rootMapKey, SymmetricKey.random());
        String name = corenodeClient.getUsername(targetUser);
        EntryPoint entry = new EntryPoint(friendRoot, username, Collections.emptySet(), Stream.of(name).collect(Collectors.toSet()));
        addToStaticDataAndCommit(entry);
        // create a tmp keypair whose public key we can append to the request without leaking information
        User tmp = User.random();
        byte[] payload = entry.serializeAndEncrypt(tmp, targetUser);
        return corenodeClient.followRequest(targetUser, ArrayOps.concat(tmp.publicBoxingKey.toByteArray(), payload));
    }

    public boolean addToStaticData(EntryPoint entry) {
        for (int i=0; i < staticData.size(); i++)
            if (entry.equals(staticData.get(entry.pointer.writer)))
                return true;
        staticData.put(entry.pointer.writer, entry);
        return true;
    }

    public void addToStaticDataAndCommit(EntryPoint entry) throws IOException {
        addToStaticData(entry);
        commitStaticData();
    }

    private boolean commitStaticData() throws IOException {
        byte[] rawStatic = serializeStatic();
        Multihash blobHash = dhtClient.put(rawStatic, user, Collections.emptyList());
        MaybeMultihash currentHash = corenodeClient.getMetadataBlob(user);
        DataSink bout = new DataSink();
        currentHash.serialize(bout);
        bout.writeArray(blobHash.toBytes());
        byte[] signed = user.signMessage(bout.toByteArray());
        boolean added = corenodeClient.setMetadataBlob(user, user, signed);
        if (!added) {
            System.out.println("Static data store failed.");
            return false;
        }
        return true;
    }

    private boolean removeFromStaticData(FileTreeNode fileTreeNode) throws IOException {
        ReadableFilePointer pointer = fileTreeNode.getPointer().filePointer;
        // find and remove matching entry point
        Iterator<Map.Entry<UserPublicKey, EntryPoint>> iter = staticData.entrySet().iterator();
        for (;iter.hasNext();) {
            Map.Entry<UserPublicKey, EntryPoint> entry = iter.next();
            if (entry.getValue().pointer.equals(pointer)) {
                iter.remove();
                return commitStaticData();
            }
        }
        return true;
    };

    public List<FollowRequest> getFollowRequests() throws IOException {
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
                            byte[] keyFromResponse = freq.key == null ? null : freq.key.get().serialize();
                            if (keyFromResponse == null || !Arrays.equals(keyFromResponse, ourKeyForThem)) {
                                ourDirForThem.remove(this, getSharingFolder());
                                // remove entry point as well
                                removeFromStaticData(ourDirForThem);
                                // clear their response follow req too
                                corenodeClient.removeFollowRequest(user.toUserPublicKey(), user.signMessage(freq.rawCipher));
                            } else {
                                // add new entry to tree root
                                FileTreeNode treenode = downloadEntryPoints(Stream.of(freq.entry.get()).collect(Collectors.toSet()))
                                        .entrySet()
                                        .stream()
                                        .map(e -> new FileTreeNode(new RetrievedFilePointer(e.getKey().pointer, e.getValue()),
                                                e.getKey().owner, e.getKey().readers, e.getKey().writers, e.getKey().pointer.writer))
                                        .findAny().get();
                                getAncestorsAndAddToTree(treenode);
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
        return initialRequests;
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

    public Multihash uploadFragment(Fragment f, UserPublicKey targetUser) throws IOException {
        return dhtClient.put(f.data, targetUser, Collections.emptyList());
    }

    public List<Multihash> uploadFragments(List<Fragment> fragments, UserPublicKey owner, UserPublicKey sharer, byte[] mapKey, Consumer<Long> progressCounter) throws IOException {
        // now upload fragments to DHT

        return fragments.stream()
                .flatMap(f -> {
                    try {
                        Multihash result = uploadFragment(f, owner);
                        if (progressCounter != null)
                            progressCounter.accept(1L);
                        return Stream.of(result);
                    } catch (IOException ioe) {
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toList());
    }

    public boolean uploadChunk(FileAccess metadata, UserPublicKey owner, User sharer, byte[] mapKey, List<Multihash> linkHashes) throws IOException {
        DataSink dout = new DataSink();
        metadata.serialize(dout);
        byte[] metaBlob = dout.toByteArray();
        System.out.println("Storing metadata blob of " + metaBlob.length + " bytes. to mapKey: "+ArrayOps.bytesToHex(mapKey));
        Multihash blobHash = dhtClient.put(metaBlob, owner, linkHashes);
        PairMultihash newBtreeRootCAS = btree.put(sharer, mapKey, blobHash);
        byte[] signed = sharer.signMessage(newBtreeRootCAS.toByteArray());
        boolean added =  corenodeClient.setMetadataBlob(owner, sharer, signed);
        if (!added) {
            System.out.println("Meta blob store failed.");
            return false;
        }
        return true;
    }

    private byte[] getStaticData() throws IOException {
        Multihash key = corenodeClient.getMetadataBlob(user.toUserPublicKey()).get();
        return dhtClient.get(key).get();
    }

    private Map<EntryPoint, FileAccess> getRoots() throws IOException {
        byte[] raw = getStaticData();
        DataSource source = new DataSource(raw);

        int count = source.readInt();
        Set<EntryPoint> res = new HashSet<>();
        for (int i=0; i < count; i++) {
            EntryPoint entry = EntryPoint.symmetricallyDecryptAndDeserialize(source.readArray(), rootKey);
            res.add(entry);
            addToStaticData(entry);
        }

        return downloadEntryPoints(res);
    }

    private Map<EntryPoint, FileAccess> downloadEntryPoints(Set<EntryPoint> entries) throws IOException {
        // download the metadata blobs for these entry points
        Map<EntryPoint, FileAccess> res = new HashMap<>();
        for (EntryPoint entry: entries) {
            MaybeMultihash btreeValue = btree.get(entry.pointer.writer, entry.pointer.mapKey);
            Optional<byte[]> value = dhtClient.get(btreeValue.get());
            if (value.isPresent()) // otherwise this is a deleted directory
                res.put(entry, FileAccess.deserialize(value.get()));
        }
        return res;
    }

    public FileTreeNode getTreeRoot() {
        return rootNode;
    }

    public FileTreeNode getUserRoot() {
        FileTreeNode root = getTreeRoot();
        Set<FileTreeNode> children = root.getChildren(this);

        if (children.size() == 0)
            throw new IllegalStateException("no children in user root!");
        List<FileTreeNode> userRoots = children.stream()
                .filter(e -> e.getFileProperties().name.equals(username)).collect(Collectors.toList());
        if (userRoots.size() != 1)
            throw new IllegalStateException("user has "+ userRoots.size() +" roots!");
        return userRoots.get(0);
    }

    private void getAncestorsAndAddToTree(FileTreeNode treeNode) {
        try {
            // don't need to add our own files this way, as we'll find them going down from our root
            if (treeNode.getOwner() == username && !treeNode.isWritable())
                return;
            Optional<FileTreeNode> parent = treeNode.retrieveParent(this);
            if (!parent.isPresent())
                return;
            parent.get().addChild(treeNode);
            getAncestorsAndAddToTree(parent.get());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createFileTree() throws IOException {
        Map<EntryPoint, FileAccess> roots = getRoots();
        Set<FileTreeNode> entrypoints = roots.entrySet().stream()
                .map(e -> new FileTreeNode(new RetrievedFilePointer(e.getKey().pointer, e.getValue()), e.getKey().owner,
                        e.getKey().readers, e.getKey().writers, e.getKey().pointer.writer)).collect(Collectors.toSet());
        System.out.println("Entry points "+entrypoints);

        for (FileTreeNode current: entrypoints) {
            getAncestorsAndAddToTree(current);
        }
    }

    public Map<ReadableFilePointer, FileAccess> retrieveAllMetadata(List<SymmetricLocationLink> links, SymmetricKey baseKey) throws IOException {
        Map<ReadableFilePointer, FileAccess> res = new HashMap<>();
        for (SymmetricLocationLink link: links) {
            Location loc = link.targetLocation(baseKey);
            MaybeMultihash key = btree.get(loc.writer, loc.mapKey);
            byte[] data = dhtClient.get(key.get()).get();
            if (data.length > 0)
                res.put(link.toReadableFilePointer(baseKey), FileAccess.deserialize(data));
        }
        return res;
    }

    public FileAccess getMetadata(Location loc) throws IOException {
        MaybeMultihash blobHash = btree.get(loc.writer, loc.mapKey);
        byte[] raw = dhtClient.get(blobHash.get()).get();
        return FileAccess.deserialize(raw);
    };

    public List<FragmentWithHash> downloadFragments(List<Multihash> hashes, Consumer<Long> monitor) {
        return hashes.stream()
                .flatMap(h -> {
                    try {
                        Fragment f = new Fragment(dhtClient.get(h).get());
                        monitor.accept(1L);
                        return Stream.of(new FragmentWithHash(f, h));
                    } catch (IOException e) {
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toList());
    }

    public void unfollow(String username) throws IOException {
        System.out.println("Unfollowing: "+username);
        // remove entry point from static data
        Optional<FileTreeNode> dir = getTreeRoot().getDescendentByPath("/"+username+"/shared/"+username, this);
        // remove our static data entry storing that we've granted them access
        removeFromStaticData(dir.get());
        Optional<FileTreeNode> entry = getTreeRoot().getDescendentByPath("/"+username, this);
        entry.get().remove(this, getTreeRoot());
    }

    public void removeFollower(String username) throws IOException {
        System.out.println("Remove follower: " + username);
        // remove /$us/shared/$them
        Optional<FileTreeNode> dir = getTreeRoot().getDescendentByPath("/"+username+"/shared/"+username, this);
        dir.get().remove(this, getSharingFolder());
        // remove our static data entry storing that we've granted them access
        removeFromStaticData(dir.get());
    }

    public void logout() {
        rootNode = null;
    }
}
