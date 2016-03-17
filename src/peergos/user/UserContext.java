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
    private final User user;
    private final SymmetricKey rootKey;
    private final ContentAddressedStorage dhtClient;
    private final CoreNode corenodeClient;
    private final MerkleBTree btree;

    private final Map<Object, Object> staticData = new HashMap<>();
    private final Set<String> usernames = new HashSet<>();
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


    public void init() {
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
                        usernames = corenodeClient.fetchUsernames();
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

    public boolean isRegistered() {
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
        now.plusMonths(2)
        String expiry = now.toString(); //YYYY-MM-DD
        List<UserPublicKeyLink> claimChain = UserPublicKeyLink.createInitial(user, username, expiry);
        return corenodeClient.updateChain(username, claimChain);
    }

    public RetrievedFilePointer createEntryDirectory(String directoryName) {
        User writer = User.random();
        byte[] rootMapKey = TweetNaCl.securedRandom(32); // root will be stored under this in the core node
        SymmetricKey rootRKey = SymmetricKey.random();

        // and authorise the writer key
        ReadableFilePointer rootPointer = new ReadableFilePointer(this.user, writer, new ByteArrayWrapper(rootMapKey), rootRKey);
        EntryPoint entry = new EntryPoint(rootPointer, this.username, Arrays.asList(), Arrays.asList());

        addToStaticDataAndCommit(entry);
        DirAccess root = DirAccess.create(rootRKey, new FileProperties(directoryName, 0, LocalDateTime.now(), false, Optional.empty()));
        boolean uploaded = this.uploadChunk(root, this.user, writer, rootMapKey, new byte[0][]);
        if (uploaded)
            return new RetrievedFilePointer(rootPointer, root);
        throw new IllegalStateException("Failed to create entry directory!");
    }

    public Set<FileTreeNode> getFriendRoots() {
        return this.rootNode.getChildren(this).stream().filter(x -> !x.getOwner().equals(username)).collect(Collectors.toSet());
    }

    public Set<String> getFollowers() {
        return getSharingFolder().getChildren(this).stream().map(f -> f.getFileProperties().name).sorted(UserContext::humanSort).collect();
    }

    private static int humanSort(String a, String b) {
        return a.toLowerCase().compareTo(b.toLowerCase());
    }

    public Set<String> getFollowing() {
        return getFriendRoots().stream().map(froot -> froot.getOwner()).filter(name -> !name.equals(username));
    }

    Map<String, FileTreeNode> getFollowerRoots() {
        return getSharingFolder().getChildren(this).stream().collect(Collectors.toMap(e -> e.getFileProperties().name, e -> e));
    }

    public SocialState getSocialState() {
        return this.getFollowRequests().then(function(pending) {
            return getFollowerRoots().then(function(followerRoots) {
                return getFriendRoots().then(function(followingRoots) {
                    return Promise.resolve(new SocialState(pending, followerRoots, followingRoots));
                })
            })
        })
    }

    public boolean sendInitialFollowRequest(String targetUsername) {
        return sendFollowRequest(targetUsername, SymmetricKey.random());
    }

    // FollowRequest, boolean, boolean
    public boolean sendReplyFollowRequest(FollowRequest initialRequest, boolean accept, boolean reciprocate) throws IOException {
        String theirUsername = initialRequest.entry.owner;
        // if accept, create directory to share with them, note in entry points (they follow us)
        if (!accept) {
            // send a null entry and null key (full rejection)

            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);
            // write a null entry point
            EntryPoint entry = new EntryPoint(ReadableFilePointer.createNull(), username, Collections.EMPTY_SET, Collections.EMPTY_SET);
            dout.writeArray(entry.serialize());
            dout.writeArray(new byte[0]); // tell them we're not reciprocating
            byte[] plaintext = bout.toByteArray();
            UserPublicKey targetUser = initialRequest.entry.pointer.owner;
            // create a tmp keypair whose public key we can prepend to the request without leaking information
            User tmp = User.random();
            byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

            corenodeClient.followRequest(initialRequest.entry.pointer.owner.getPublicKeys(), concat(tmp.pBoxKey, payload));
            // remove pending follow request from them
            return corenodeClient.removeFollowRequest(context.user.getPublicKeys(), context.user.signMessage(initialRequest.rawCipher));
        }
        friendRoot = getSharingFolder().mkdir(theirUsername, context, initialRequest.key);
        // add a note to our static data so we know who we sent the read access to
        EntryPoint entry = new EntryPoint(friendRoot.readOnly(), context.username, [theirUsername], []);
        UserPublicKey targetUser = initialRequest.entry.pointer.owner;
        addToStaticDataAndCommit(entry);
        // create a tmp keypair whose public key we can prepend to the request without leaking information
        User tmp = User.random();
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);
        dout.writeArray(entry.serialize());
        if (! reciprocate) {
            dout.writeArray(new Uint8Array(0)); // tell them we're not reciprocating
        } else {
            // if reciprocate, add entry point to their shared dirctory (we follow them) and then
            dout.writeArray(initialRequest.entry.pointer.baseKey.serialize()); // tell them we are reciprocating
        }
        byte[] plaintext = bout.toByteArray();
        byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.secretBoxingKey);

        ByteArrayOutputStream rout = new ByteArrayOutputStream();
        DataOutputStream resp = new DataOutputStream(rout);
        resp.writeArray(tmp.getPublicKeys());
        resp.writeArray(payload);
        corenodeClient.followRequest(initialRequest.entry.pointer.owner.getPublicKeys(), rout.toByteArray());
        addToStaticDataAndCommit(initialRequest.entry.get());
        // remove original request
        return corenodeClient.removeFollowRequest(user.getPublicKeys(), user.signMessage(initialRequest.rawCipher));
    }

    // string, RetrievedFilePointer, SymmetricKey
    public boolean sendFollowRequest(String targetUsername, SymmetricKey requestedKey) {
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
        FileTreeNode friendRoot = sharing.mkdir(targetUsername, that);

        // add a note to our static data so we know who we sent the read access to
        EntryPoint entry = new EntryPoint(friendRoot.readOnly(), that.username, [targetUsername], []);
        addToStaticDataAndCommit(entry);
        // send details to allow friend to follow us, and optionally let us follow them
        // create a tmp keypair whose public key we can prepend to the request without leaking information
        User tmp = User.random();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.writeArray(entry.serialize());
        buf.writeArray(requestedKey != null ? requestedKey.serialize() : new byte[0]);
        byte[] plaintext = buf.toByteArray();
        byte[] payload = targetUser.encryptMessageFor(plaintext, tmp.sBoxKey);

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        res.writeArray(tmp.getPublicKeys());
        res.writeArray(payload);
        return corenodeClient.followRequest(targetUser.getPublicKeys(), bout.toByteArray());
    };

    public boolean sendWriteAccess(String targetUser) {
        // create sharing keypair and give it write access
        User sharing = User.random();
        byte[] rootMapKey = window.nacl.randomBytes(32);

        // add a note to our static data so we know who we sent the private key to
        ReadableFilePointer friendRoot = new ReadableFilePointer(user, sharing, rootMapKey, SymmetricKey.random());
        return corenodeClient.getUsername(targetUser.getPublicKeys()).then(function(name) {
            EntryPoint entry = new EntryPoint(friendRoot, username, [], [name]);
            return addToStaticDataAndCommit(entry).then(function(res) {
                // create a tmp keypair whose public key we can append to the request without leaking information
                User tmp = User.random();
                byte[] payload = entry.serializeAndEncrypt(tmp, targetUser);
                return corenodeClient.followRequest(targetUser.getPublicKeys(), concat(tmp.pBoxKey, payload));
            })
        })
    }

    public boolean addToStaticData(EntryPoint entry) {
        for (int i=0; i < this.staticData.size(); i++)
            if (this.staticData.get(i).equals(entry))
                return true;
        this.staticData.put(entry.pointer.writer, entry);
        return true;
    }

    public void addToStaticDataAndCommit(EntryPoint entry) {
        addToStaticData(entry);
        commitStaticData();
    }

    private boolean commitStaticData() throws IOException {
        byte[] rawStatic = serializeStatic();
        Multihash blobash = dhtClient.put(rawStatic, user.getPublicKeys());
        byte[] currentHash = corenodeClient.getMetadataBlob(user.getPublicKeys());
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        bout.writeArray(currentHash);
        bout.writeArray(blobHash);
        byte[] signed = user.signMessage(bout.toByteArray());
        boolean added = corenodeClient.addMetadataBlob(user.getPublicKeys(), user.getPublicKeys(), signed);
        if (!added) {
            System.out.println("Static data store failed.");
            return false;
        }
        return true;
    }

    private boolean removeFromStaticData(FileTreeNode fileTreeNode) {
        var pointer = fileTreeNode.getPointer().filePointer;
        // find and remove matching entry point
        for (int i=0; i < this.staticData.length; i++)
            if (this.staticData[i][1].pointer.equals(pointer)) {
                this.staticData.splice(i, 1);
                return this.commitStaticData();
            }
        return true;
    };

    public Set<FollowRequest> getFollowRequests() {
        return corenodeClient.getFollowRequests(user.getPublicKeys()).then(function(reqs){
            var all = reqs.map(decodeFollowRequest);
            return getFollowerRoots().then(function(followerRoots) {
                var initialRequests = all.filter(function(freq){
                    if (followerRoots[freq.entry.owner] != null) {
                        // delete our folder if they didn't reciprocate
                        var ourDirForThem = followerRoots[freq.entry.owner];
                        var ourKeyForThem = ourDirForThem.getKey().serialize();
                        var keyFromResponse = freq.key == null ? null : freq.key.serialize();
                        if (keyFromResponse == null || !arraysEqual(keyFromResponse, ourKeyForThem)) {
                            ourDirForThem.remove(that, getSharingFolder());
                            // remove entry point as well
                            that.removeFromStaticData(ourDirForThem);
                            // clear their response follow req too
                            corenodeClient.removeFollowRequest(that.user.getPublicKeys(), that.user.signMessage(freq.rawCipher));
                        } else // add new entry to tree root
                            that.downloadEntryPoints([freq.entry]).then(function(treenode) {
                            that.getAncestorsAndAddToTree(treenode, that);
                        });
                        // add entry point to static data
                        if (!arraysEqual(freq.entry.pointer.baseKey.serialize(), SymmetricKey.createNull().serialize()))
                            that.addToStaticDataAndCommit(freq.entry).then(function(res) {
                            return corenodeClient.removeFollowRequest(that.user.getPublicKeys(), that.user.signMessage(freq.rawCipher));
                        });
                        return false;
                    }
                    return followerRoots[freq.entry.owner] == null;
                });
                return Promise.resolve(initialRequests);
            })
        })
    }

    private FollowRequest decodeFollowRequest(byte[] raw) {
        ByteArrayInputStream bin = new ByteArrayInputStream(raw);
        DataInputStream buf = new DataInputStream(bin);
        UserPublicKey tmp = UserPublicKey.deserialize(new ByteArrayInputStream(Serialize.deserializeByteArray(buf, 4096)));
        byte[] cipher = buf.readArray();
        byte[] plaintext = user.decryptMessage(cipher, tmp.publicBoxingKey);
        ByteArrayInputStream input = new ByteArrayInputStream(plaintext);
        byte[] rawEntry = input.readArray();
        byte[] rawKey = input.readArray();
        return new FollowRequest(rawEntry.length > 0 ? EntryPoint.deserialize(rawEntry) : null,
                rawKey.length > 0 ? SymmetricKey.deserialize(rawKey) : null, raw);
    }

    public void uploadFragment(Fragment f, UserPublicKey targetUser) {
        return dhtClient.put(f.data, targetUser.getPublicKeys());
    }

    public void uploadFragments(List<Fragment> fragments, UserPublicKey owner, UserPublicKey sharer, byte[] mapKey, Runnable progressCounter) {
        // now upload fragments to DHT
        for (int i=0; i < fragments.size(); i++){
            if(progressCounter != null)
                progressCounter.run();
            uploadFragment(fragments.get(i), owner);
        }
    }

    public void uploadChunk(FileAccess metadata, UserPublicKey owner, UserPublicKey sharer, byte[] mapKey, byte[][] linkHashes) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);
        metadata.serialize(dout);
        byte[] metaBlob = bout.toByteArray();
        System.out.println("Storing metadata blob of " + metaBlob.length + " bytes. to mapKey: "+ArrayOps.bytesToHex(mapKey));
        return dhtClient.put(metaBlob, owner.getPublicKeys(), linkHashes).then(function(blobHash){
            return btree.put(sharer.getPublicKeys(), mapKey, blobHash).then(function(newBtreeRootCAS) {
                var msg = newBtreeRootCAS;
                var signed = sharer.signMessage(msg);
                return corenodeClient.addMetadataBlob(owner.getPublicKeys(), sharer.getPublicKeys(), signed)
                        .then(function(added) {
                    if (!added) {
                        System.out.println("Meta blob store failed.");
                        return Promise.resolve(false);
                    }
                    return Promise.resolve(true);
                })
            })
        })
    }

    private byte[] getStaticData() {
        return dhtClient.get(corenodeClient.getMetadataBlob(user));
    }

    private Map<EntryPoint, FileAcess> getRoots() throws IOException {
        byte[] raw = getStaticData();
        ByteArrayInputStream bin = new ByteArrayInputStream(raw);
        DataInputStream din = new DataInputStream(bin);

        int count = din.readInt();
        Set<EntryPoint> res = new HashSet<>();
        for (int i=0; i < count; i++) {
            EntryPoint entry = EntryPoint.symmetricallyDecryptAndDeserialize(buf.readArray(), rootKey);
            res.add(entry);
            addToStaticData(entry);
        }

        return downloadEntryPoints(res);
    }

    private Map<EntryPoint, FileAccess> downloadEntryPoints(Set<EntryPoint> entries) {
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
        Set<FileTreeNode> children = root.getChildren();

        if (children.size() == 0)
            throw new IllegalStateException("no children in user root!");
        List<FileTreeNode> userRoots = children.stream().filter(e -> e.getFileProperties().name.equals(username)).collect(Collectors.toList());
        if (userRoots.size() != 1)
            throw new IllegalStateException("user has "+ userRoots.size() +" roots!");
        return userRoots.get(0);
    }

    private void getAncestorsAndAddToTree(FileTreeNode treeNode) {
        try {
            // don't need to add our own files this way, as we'll find them going down from our root
            if (treeNode.getOwner() == username && !treeNode.isWritable())
                return;
            FileTreeNode parent = treeNode.retrieveParent(this);
            if (parent == null)
                return;
            parent.addChild(treeNode);
            getAncestorsAndAddToTree(parent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private FileTreeNode createFileTree() {
        Map<EntryPoint, FileAcess> roots = getRoots();
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

    public Map<ReadableFilePointer, FileAccess> retrieveAllMetadata(List<SymmetricLocationLink> links, SymmetricKey baseKey) {
        Map<ReadableFilePointer, FileAccess> res = new HashMap<>();
        for (SymmetricLocationLink link: links) {
            Location loc = link.targetLocation(baseKey);
            byte[] data = dhtClient.get(btree.get(loc.writer.getPublicKeys(), loc.mapKey));
            if (data.length > 0)
                res.put(link.toReadableFilePointer(baseKey), FileAccess.deserialize(data));
        }
        return res;
    }

    public getMetadata(Location loc) {
        return btree.get(loc.writer.getPublicKeys(), loc.mapKey).then(function(blobHash) {
            return dhtClient.get(blobHash).then(function(raw) {
                return Promise.resolve(FileAccess.deserialize(raw.data));
            });
        });
    };

    public List<Fragment> downloadFragments(List<Multihash> hashes, Consumer<Double> setProgressPercentage) {
        var result = {};
        result.fragments = [];
        result.nError = 0;

        var proms = [];
        for (var i=0; i < hashes.length; i++)
            proms.push(dhtClient.get(hashes[i]).then(function(val) {
            result.fragments.push(val);
            //System.out.println("Got Fragment.");
            if(setProgressPercentage != null){
                if(downloadFragmentTotal != 0){
                    var percentage = parseInt(++downloadFragmentCounter / downloadFragmentTotal * 100);
                    setProgressPercentage(percentage);
                    //document.title = "Peergos Downloading: " + percentage + "%" ;
                }
            }
        }).catch(function() {
            result.nError++;
        }));

        return Promise.all(proms).then(function (all) {
            System.out.println("All done.");
            //if (result.fragments.length < nRequired)
            //    throw "Not enough fragments!";
            return Promise.resolve(result.fragments);
        });
    }

    public void unfollow(String username) {
        System.out.println("Unfollowing: "+username);
        // remove entry point from static data
        FileTreeNode.ROOT.getDescendentByPath("/"+username+"/shared/"+username).then(function(dir) {
            // remove our static data entry storing that we've granted them access
            that.removeFromStaticData(dir);
        });
        FileTreeNode.ROOT.getDescendentByPath("/"+username).then(function(dir) {
            dir.remove(that, FileTreeNode.ROOT);
        })
    }

    public void removeFollower(String username) {
        System.out.println("Remove follower: " + username);
        // remove /$us/shared/$them
        FileTreeNode.ROOT.getDescendentByPath("/"+username+"/shared/"+username).then(function(dir) {
            dir.remove(that, getSharingFolder());
            // remove our static data entry storing that we've granted them access
            that.removeFromStaticData(dir);
        })
    }

    public void logout() {
        rootNode = null;
        FileTreeNode.ROOT = new FileTreeNode(null, null, [], [], null);
    }
}
