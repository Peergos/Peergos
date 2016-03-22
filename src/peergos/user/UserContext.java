package peergos.user;

import org.ipfs.api.*;
import peergos.corenode.*;
import peergos.crypto.*;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.server.merklebtree.*;
import peergos.server.storage.ContentAddressedStorage;
import peergos.user.fs.*;
import peergos.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class UserContext {
    public final String username;
    public final User user;
    private final SymmetricKey rootKey;
    public final ContentAddressedStorage dhtClient;
    public final CoreNode corenodeClient;
    public final MerkleBTree btree;

    private final SortedMap<Object, Object> staticData = new TreeMap<>();
    private Set<String> usernames;
    private FileTreeNode rootNode;
    private FileTreeNode sharingFolder;

    public UserContext(String username, User user, SymmetricKey root, ContentAddressedStorage dht, CoreNode coreNode) throws IOException {
        this.username = username;
        this.user = user;
        this.rootKey = root;
        this.dhtClient = dht;
        this.corenodeClient = coreNode;
        this.btree = MerkleBTree.create(new byte[0], dhtClient);
    }


    public void init() throws IOException {
        FileTreeNode.ROOT.clear();
        staticData.clear();
        this.rootNode = createFileTree();
        Set<FileTreeNode> children = rootNode.getChildren(this);
        for (FileTreeNode child: children) {
            if (child.getFileProperties().name.equals(username)) {
                Set<FileTreeNode> ourdirs = child.getChildren();
                for (FileTreeNode childNode: ourdirs) {
                    if (childNode.getFileProperties().name == "shared") {
                        sharingFolder = childNode;
                        usernames = corenodeClient.getAllUsernames().stream().collect(Collectors.toSet());
                    }
                }
            }
            throw new IllegalStateException("Couldn't find shared folder!");
        }
        throw new IllegalStateException("No root directory found!");
    }

    Set<String> getUsernames() {
        return usernames;
    }

    public FileTreeNode getSharingFolder() {
        return sharingFolder;
    }

    public boolean isRegistered() throws IOException {
        return corenodeClient.getUsername(user.getPublicKeys()).equals(username);
    }

    public byte[] serializeStatic() throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);
        dout.writeInt(staticData.size());
        for (int i = 0; i < this.staticData.size(); i++)
            Serialize.serialize(this.staticData[i][1].serializeAndSymmetricallyEncrypt(rootKey), dout);
        return bout.toByteArray();
    }

    public boolean register() {
        System.out.println("claiming username: "+username);
        LocalDate now = LocalDate.now();
        // set claim expiry to two months from now
        now.plusMonths(2);
        String expiry = now.toString(); //YYYY-MM-DD
        List<UserPublicKeyLink> claimChain = UserPublicKeyLink.createInitial(user, username, expiry);
        return corenodeClient.updateChain(username, claimChain);
    }

    public RetrievedFilePointer createEntryDirectory(String directoryName) throws IOException {
        User writer = User.random();
        byte[] rootMapKey = TweetNaCl.securedRandom(32); // root will be stored under this in the core node
        SymmetricKey rootRKey = SymmetricKey.random();

        // and authorise the writer key
        ReadableFilePointer rootPointer = new ReadableFilePointer(this.user, writer, rootMapKey, rootRKey);
        EntryPoint entry = new EntryPoint(rootPointer, this.username, Collections.EMPTY_SET,Collections.EMPTY_SET);

        addToStaticDataAndCommit(entry);
        DirAccess root = DirAccess.create(rootRKey, new FileProperties(directoryName, 0, LocalDateTime.now(), false, Optional.empty()), null, null, null);
        boolean uploaded = this.uploadChunk(root, this.user, writer, rootMapKey, Collections.EMPTY_LIST);
        if (uploaded)
            return new RetrievedFilePointer(rootPointer, root);
        throw new IllegalStateException("Failed to create entry directory!");
    }

    public Set<FileTreeNode> getFriendRoots() {
        return this.rootNode.getChildren(this).stream().filter(x -> !x.getOwner().equals(username)).collect(Collectors.toSet());
    }

    public Set<String> getFollowers() {
        return getSharingFolder().getChildren(this).stream()
                .flatMap(f -> {
                    try {
                        return Stream.of(f.getFileProperties().name);
                    } catch (IOException e) {
                        return Stream.empty();
                    }
                })
                .sorted(UserContext::humanSort).collect(Collectors.toSet());
    }

    private static int humanSort(String a, String b) {
        return a.toLowerCase().compareTo(b.toLowerCase());
    }

    public Set<String> getFollowing() {
        return getFriendRoots().stream().map(froot -> froot.getOwner()).filter(name -> !name.equals(username)).collect(Collectors.toSet());
    }

    Map<String, FileTreeNode> getFollowerRoots() {
        return getSharingFolder().getChildren(this).stream().collect(Collectors.toMap(e -> e.getFileProperties().name, e -> e));
    }

    public SocialState getSocialState() {
        Set<FollowRequest> pending = getFollowRequests();
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

            corenodeClient.followRequest(initialRequest.entry.get().pointer.owner.getPublicKeys(), ArrayOps.concat(tmp.publicBoxingKey, payload));
            // remove pending follow request from them
            return corenodeClient.removeFollowRequest(user.getPublicKeys(), user.signMessage(initialRequest.rawCipher));
        }
        ReadableFilePointer friendRoot = getSharingFolder().mkdir(theirUsername, this, initialRequest.key.get(), true);
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
        corenodeClient.followRequest(initialRequest.entry.get().pointer.owner.getPublicKeys(), resp.toByteArray());
        addToStaticDataAndCommit(initialRequest.entry.get());
        // remove original request
        return corenodeClient.removeFollowRequest(user.getPublicKeys(), user.signMessage(initialRequest.rawCipher));
    }

    // string, RetrievedFilePointer, SymmetricKey
    public boolean sendFollowRequest(String targetUsername, SymmetricKey requestedKey) throws IOException {
        FileTreeNode sharing = getSharingFolder();
        Set<FileTreeNode> children = sharing.getChildren(this);
        boolean alreadyFollowed = children.stream().filter(f -> f.getFileProperties().name.equals(targetUsername)).findAny().isPresent();
        if (alreadyFollowed)
            return false;
        // check for them not reciprocating
        Set<String> following = getFollowing();
        alreadyFollowed = following.stream().filter(x -> x.equals(targetUsername)).findAny().isPresent();
        if (alreadyFollowed)
            return false;

        UserPublicKey targetUser = corenodeClient.getPublicKey(targetUsername);
        FileTreeNode friendRoot = sharing.mkdir(targetUsername, this);

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
        return corenodeClient.followRequest(targetUser.getPublicKeys(), res.toByteArray());
    };

    public boolean sendWriteAccess(String targetUser) {
        // create sharing keypair and give it write access
        User sharing = User.random();
        byte[] rootMapKey = TweetNaCl.securedRandom(32);

        // add a note to our static data so we know who we sent the private key to
        ReadableFilePointer friendRoot = new ReadableFilePointer(user, sharing, rootMapKey, SymmetricKey.random());
        String name = corenodeClient.getUsername(targetUser.getPublicKeys());
        EntryPoint entry = new EntryPoint(friendRoot, username, Collections.EMPTY_SET, Stream.of(name).collect(Collectors.toSet()));
        addToStaticDataAndCommit(entry);
        // create a tmp keypair whose public key we can append to the request without leaking information
        User tmp = User.random();
        byte[] payload = entry.serializeAndEncrypt(tmp, targetUser);
        return corenodeClient.followRequest(targetUser.getPublicKeys(), ArrayOps.concat(tmp.publicBoxingKey, payload));
    }

    public boolean addToStaticData(EntryPoint entry) {
        for (int i=0; i < this.staticData.size(); i++)
            if (this.staticData.get(i).equals(entry))
                return true;
        this.staticData.put(entry.pointer.writer, entry);
        return true;
    }

    public void addToStaticDataAndCommit(EntryPoint entry) throws IOException {
        addToStaticData(entry);
        commitStaticData();
    }

    private boolean commitStaticData() throws IOException {
        byte[] rawStatic = serializeStatic();
        Multihash blobHash = dhtClient.put(rawStatic, user.getPublicKeys());
        byte[] currentHash = corenodeClient.getMetadataBlob(user.getPublicKeys());
        DataSink bout = new DataSink();
        bout.writeArray(currentHash);
        bout.writeArray(blobHash.toBytes());
        byte[] signed = user.signMessage(bout.toByteArray());
        boolean added = corenodeClient.setMetadataBlob(user.getPublicKeys(), user.getPublicKeys(), signed);
        if (!added) {
            System.out.println("Static data store failed.");
            return false;
        }
        return true;
    }

    private boolean removeFromStaticData(FileTreeNode fileTreeNode) throws IOException {
        ReadableFilePointer pointer = fileTreeNode.getPointer().filePointer;
        // find and remove matching entry point
        for (int i=0; i < this.staticData.size(); i++)
            if (this.staticData[i][1].pointer.equals(pointer)) {
                this.staticData.splice(i, 1);
                return commitStaticData();
            }
        return true;
    };

    public Set<FollowRequest> getFollowRequests() throws IOException {
        byte[] reqs = corenodeClient.getFollowRequests(user.getPublicKeys());
        DataSource din = new DataSource(reqs);
        int n = din.readInt();
        List<FollowRequest> all = IntStream.range(0, n).mapToObj(decodeFollowRequest(din.readArray()));
        Map<String, FileTreeNode> followerRoots = getFollowerRoots();
        List<FollowRequest> initialRequests = all.stream().filter(freq -> {
            if (followerRoots[freq.entry.get().owner] != null) {
                // delete our folder if they didn't reciprocate
                FileTreeNode ourDirForThem = followerRoots[freq.entry.get().owner];
                byte[] ourKeyForThem = ourDirForThem.getKey().serialize();
                byte[] keyFromResponse = freq.key == null ? null : freq.key.serialize();
                if (keyFromResponse == null || !arraysEqual(keyFromResponse, ourKeyForThem)) {
                    ourDirForThem.remove(this, getSharingFolder());
                    // remove entry point as well
                    removeFromStaticData(ourDirForThem);
                    // clear their response follow req too
                    corenodeClient.removeFollowRequest(user.getPublicKeys(), user.signMessage(freq.rawCipher));
                } else // add new entry to tree root
                    treeode = downloadEntryPoints(Arrays.asList(freq.entry.get())).stream().findAny().get();
                getAncestorsAndAddToTree(treenode, this);
                // add entry point to static data
                if (!Arrays.equals(freq.entry.get().pointer.baseKey.serialize(), SymmetricKey.createNull().serialize())) {
                    addToStaticDataAndCommit(freq.entry.get());
                    return corenodeClient.removeFollowRequest(user.getPublicKeys(), user.signMessage(freq.rawCipher));
                }
                return false;
            }
            return followerRoots[freq.entry.get().owner] == null;
        });
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
        return new FollowRequest(rawEntry.length > 0 ? EntryPoint.deserialize(rawEntry) : null,
                rawKey.length > 0 ? SymmetricKey.deserialize(rawKey) : null, raw);
    }

    public Multihash uploadFragment(Fragment f, UserPublicKey targetUser) throws IOException {
        return dhtClient.put(f.data, targetUser.getPublicKeys());
    }

    public List<Multihash> uploadFragments(List<Fragment> fragments, UserPublicKey owner, UserPublicKey sharer, byte[] mapKey, Consumer<Long> progressCounter) {
        // now upload fragments to DHT
        for (int i=0; i < fragments.size(); i++){
            uploadFragment(fragments.get(i), owner);
            if (progressCounter != null)
                progressCounter.accept(1L);
        }
    }

    public boolean uploadChunk(FileAccess metadata, UserPublicKey owner, User sharer, byte[] mapKey, List<Multihash> linkHashes) throws IOException {
        DataSink dout = new DataSink();
        metadata.serialize(dout);
        byte[] metaBlob = dout.toByteArray();
        System.out.println("Storing metadata blob of " + metaBlob.length + " bytes. to mapKey: "+ArrayOps.bytesToHex(mapKey));
        byte[] blobHash = dhtClient.put(metaBlob, owner.getPublicKeys(), linkHashes);
        byte[] newBtreeRootCAS = btree.put(sharer.getPublicKeys(), mapKey, blobHash);
        byte[] signed = sharer.signMessage(newBtreeRootCAS);
        boolean added =  corenodeClient.setMetadataBlob(owner.getPublicKeys(), sharer.getPublicKeys(), signed);
        if (!added) {
            System.out.println("Meta blob store failed.");
            return false;
        }
        return true;
    }

    private byte[] getStaticData() {
        return dhtClient.get(corenodeClient.getMetadataBlob(user.getPublicKeys()));
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
            byte[] value = dhtClient.get(btree.get(entry.pointer.writer.getPublicKeys(), entry.pointer.mapKey));
            if (value.length > 8) // otherwise this is a deleted directory
                res.put(entry, FileAccess.deserialize(value));
        }
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
                .filter(e -> { try {
            return e.getFileProperties().name.equals(username);
        } catch (IOException ioe) {
            return false;
        }}).collect(Collectors.toList());
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

    private FileTreeNode createFileTree() throws IOException {
        Map<EntryPoint, FileAccess> roots = getRoots();
        Set<FileTreeNode> entrypoints = roots.entrySet().stream()
                .map(e -> new FileTreeNode(new RetrievedFilePointer(e.getKey().pointer, e.getValue()), e.getKey().owner,
                        e.getKey().readers, e.getKey().writers, e.getKey().pointer.writer)).collect(Collectors.toSet());
        System.out.println("Entry points "+entrypoints);
        FileTreeNode globalRoot = FileTreeNode.ROOT;

        for (FileTreeNode current: entrypoints) {
            getAncestorsAndAddToTree(current);
        }
        return globalRoot;
    }

    public Map<ReadableFilePointer, FileAccess> retrieveAllMetadata(List<SymmetricLocationLink> links, SymmetricKey baseKey) throws IOException {
        Map<ReadableFilePointer, FileAccess> res = new HashMap<>();
        for (SymmetricLocationLink link: links) {
            Location loc = link.targetLocation(baseKey);
            byte[] data = dhtClient.get(btree.get(loc.writer.getPublicKeys(), loc.mapKey));
            if (data.length > 0)
                res.put(link.toReadableFilePointer(baseKey), FileAccess.deserialize(data));
        }
        return res;
    }

    public FileAccess getMetadata(Location loc) throws IOException {
        Multihash blobHash = btree.get(loc.writer.getPublicKeys(), loc.mapKey);
        byte[] raw = dhtClient.get(blobHash.toBytes());
        return FileAccess.deserialize(raw);
    };

    public List<Fragment> downloadFragments(List<Multihash> hashes, Consumer<Long> monitor) {
        return hashes.stream()
                .map(h -> {
                    Fragment f = new Fragment(new ByteArrayWrapper(dhtClient.get(h.toBytes())));
                    monitor.accept(1L);
                    return f;
                })
                .collect(Collectors.toList());
    }

    public void unfollow(String username) throws IOException {
        System.out.println("Unfollowing: "+username);
        // remove entry point from static data
        Optional<FileTreeNode> dir = FileTreeNode.ROOT.getDescendentByPath("/"+username+"/shared/"+username, this);
        // remove our static data entry storing that we've granted them access
        removeFromStaticData(dir.get());
        Optional<FileTreeNode> entry = FileTreeNode.ROOT.getDescendentByPath("/"+username, this);
        entry.get().remove(this, FileTreeNode.ROOT);
    }

    public void removeFollower(String username) throws IOException {
        System.out.println("Remove follower: " + username);
        // remove /$us/shared/$them
        Optional<FileTreeNode> dir = FileTreeNode.ROOT.getDescendentByPath("/"+username+"/shared/"+username, this);
        dir.get().remove(this, getSharingFolder());
        // remove our static data entry storing that we've granted them access
        removeFromStaticData(dir.get());
    }

    public void logout() {
        rootNode = null;
        FileTreeNode.ROOT = new FileTreeNode(null, null, Collections.EMPTY_SET, Collections.EMPTY_SET, null);
    }
}
