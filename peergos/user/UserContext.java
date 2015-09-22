package peergos.user;

import peergos.corenode.AbstractCoreNode;
import peergos.crypto.SymmetricKey;
import peergos.crypto.SymmetricLocationLink;
import peergos.crypto.User;
import peergos.crypto.UserPublicKey;
import peergos.user.fs.*;
import peergos.util.ArrayOps;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;
import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class UserContext
{
    public static final int MAX_USERNAME_SIZE = 1024;
    public static final int MAX_KEY_SIZE = 96;

    public final String username;
    private User us;
    private DHTUserAPI dht;
    private AbstractCoreNode core;
    private Map<UserPublicKey, EntryPoint> staticData = new TreeMap<>();
    private ExecutorService executor = Executors.newFixedThreadPool(2);


    public UserContext(String username, User user, DHTUserAPI dht, AbstractCoreNode core)
    {
        this.username = username;
        this.us = user;
        this.dht = dht;
        this.core = core;
    }

    public void shutdown() {
        executor.shutdown();
    }

    public boolean register()
    {
        byte[] rawStatic = serializeStatic();
        byte[] signed = us.signMessage(ArrayOps.concat(username.getBytes(), us.getPublicKeys(), rawStatic));
        return core.addUsername(username, us.getPublicKeys(), signed, rawStatic);
    }

    public boolean isRegistered()
    {
        String name = core.getUsername(us.getPublicKeys());
        return username.equals(name);
    }

    public boolean sendFollowRequest(UserPublicKey friend) throws IOException
    {
        // check friend is a registered user
        String friendUsername = core.getUsername(friend.getPublicKeys());
        if (friendUsername == null)
            throw new IllegalStateException("User isn't registered! "+friend);

        // create sharing keypair and give it write access
        User sharing = User.random();
        addSharingKey(sharing);
        ByteArrayWrapper rootMapKey = new ByteArrayWrapper(ArrayOps.random(32));

        // add a note to our static data so we know who we sent the private key to
        ReadableFilePointer friendRoot = new ReadableFilePointer(us, sharing, rootMapKey, SymmetricKey.random());
        SortedSet<String> writers = new TreeSet<>();
        writers.add(core.getUsername(friend.getPublicKeys()));
        EntryPoint entry = new EntryPoint(friendRoot, username, new TreeSet<>(), writers);
        addToStaticData(entry);

        // send details to allow friend to share with us (i.e. we follow them)

        // create a tmp keypair whose public key we can append to the request without leaking information
        User tmp = User.random();
        byte[] payload = entry.serializeAndEncrypt(tmp,  friend);
        return core.followRequest(friend, ArrayOps.concat(tmp.publicBoxingKey, payload));
    }

    public List<byte[]> getFollowRequests()
    {
        byte[] raw = core.getFollowRequests(us);
        List<byte[]> requests = new ArrayList();
        DataInput din = new DataInputStream(new ByteArrayInputStream(raw));
        try {
            int number = din.readInt();
            for (int i=0; i < number; i++)
                requests.add(Serialize.deserializeByteArray(din, Integer.MAX_VALUE));
            return requests;
        } catch (IOException e)
        {
            e.printStackTrace();
            return requests;
        }
    }

    public EntryPoint decodeFollowRequest(byte[] data)
    {
        byte[] keys = new byte[64];
        System.arraycopy(data, 0, keys, 32, 32); // signing key is not used
        UserPublicKey tmp = new UserPublicKey(keys);
        try {
            return EntryPoint.decryptAndDeserialize(Arrays.copyOfRange(data, 32, data.length), us, tmp);
        } catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public synchronized byte[] serializeStatic()
    {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutput dout = new DataOutputStream(bout);
            dout.writeInt(staticData.size());
            for (UserPublicKey sharer : staticData.keySet())
                Serialize.serialize(staticData.get(sharer).serializeAndEncrypt(us, us), dout);
            return bout.toByteArray();
        } catch (IOException e) {throw new IllegalStateException(e.getMessage());}
    }

    public boolean addSharingKey(UserPublicKey pub)
    {
        byte[] signed = us.signMessage(pub.getPublicKeys());
        return core.allowSharingKey(us, signed);
    }

    private boolean addToStaticData(EntryPoint entry)
    {
        staticData.put(entry.pointer.writer, entry);
        byte[] rawStatic = serializeStatic();
        return core.updateStaticData(us, us.signMessage(rawStatic));
    }

    private Future uploadFragment(Fragment f, UserPublicKey targetUser, User sharer, byte[] mapKey)
    {
        return dht.put(f.getHash(), f.getData(), targetUser.getPublicKeys(), sharer.getPublicKeys(), mapKey, sharer.signMessage(ArrayOps.concat(sharer.getPublicKeys(), f.getHash())));
    }

    private boolean uploadChunk(FileAccess meta, Fragment[] fragments, UserPublicKey target, User sharer, byte[] mapKey)
    {
        // tell core node first to allow fragments
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);
        try {
            meta.serialize(dout);
            dout.flush();
        } catch (IOException e) {e.printStackTrace();}
        byte[] metaBlob = bout.toByteArray();
        System.out.println("Storing metadata blob of " + metaBlob.length + " bytes.");
        if (!core.addMetadataBlob(target, sharer.getPublicKeys(), mapKey, metaBlob, sharer.signMessage(ArrayOps.concat(mapKey, metaBlob))))
            System.out.println("Meta blob store failed.");
        if (fragments.length > 0 ) {
            // now upload fragments to DHT
            List<Future<Object>> futures = new ArrayList<>();
            for (Fragment f : fragments)
                try {
                    futures.add(uploadFragment(f, target, sharer, mapKey));
                } catch (Exception e) {
                    e.printStackTrace();
                }

            // wait for all fragments to upload
            Countdown<Object> all = new Countdown(futures.size(), futures);
            try {
                all.await();
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }

    public Fragment[] downloadFragments(List<ByteArrayWrapper> hashes)
    {
        Fragment[] res = new Fragment[hashes.size()];
        List<CompletableFuture<ByteArrayWrapper>> futs = new ArrayList<>(res.length);
        for (int i=0; i < res.length; i++)
            futs.add(dht.get(hashes.get(i).data));
        Countdown<ByteArrayWrapper> first50 = new Countdown<>(50, futs);
        first50.await();
        List<Fragment> frags = new ArrayList<>();
        for (ByteArrayWrapper frag: first50.results)
            frags.add(new Fragment(frag.data));
        return frags.toArray(new Fragment[frags.size()]);
    }

    public Map<EntryPoint, FileAccess> getRoots() throws IOException
    {
        byte[] staticData = core.getStaticData(us);
        DataInput din = new DataInputStream(new ByteArrayInputStream(staticData));
        int entries = din.readInt();
        this.staticData = new HashMap<>();
        for (int i=0; i < entries; i++) {
            EntryPoint entry = EntryPoint.decryptAndDeserialize(Serialize.deserializeByteArray(din, EntryPoint.MAX_SIZE), us, us);
            this.staticData.put(entry.pointer.writer, entry);
        }

        Map<EntryPoint, FileAccess> res = new HashMap<>();
        for (UserPublicKey pub: this.staticData.keySet()) {
            EntryPoint root = this.staticData.get(pub);
            FileAccess dir = getMetadata(new Location(root.pointer.owner, root.pointer.writer, root.pointer.mapKey));
            if (dir != null)
                res.put(root, dir);
        }
        return res;
    }

    public FileAccess getMetadata(Location loc) throws IOException {
        AbstractCoreNode.MetadataBlob meta = core.getMetadataBlob(loc.owner, loc.writerKey.getPublicKeys(), loc.mapKey.data);
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(meta.metadata().data));
        return FileAccess.deserialize(din);
    }

    public Map<SymmetricLocationLink, FileAccess> retrieveMetadata(Collection<SymmetricLocationLink> links, SymmetricKey parentFolder) throws IOException {
        Map<SymmetricLocationLink, FileAccess> res = new HashMap<>();
        for (SymmetricLocationLink link: links) {
            Location loc = link.targetLocation(parentFolder);
            FileAccess fa = getMetadata(loc);
            res.put(link, fa);
        }
        return res;
    }

    public static class Countdown<V>
    {
        CountDownLatch left;
        AtomicInteger failuresAllowed;
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        Set<V> results = new ConcurrentSkipListSet<>();

        public Countdown(int needed, List<CompletableFuture<V>> futs)
        {
            left = new CountDownLatch(needed);
            failuresAllowed = new AtomicInteger(futs.size()-needed);
            for (CompletableFuture<V> fut: futs)
                fut.thenAccept(o -> {
                    results.add(o);
                    left.countDown();
                }).exceptionally(e -> {
                    failuresAllowed.decrementAndGet();
                    errors.add(e);
                    return null;
                });
        }

        public void await()
        {
            while ((left.getCount() > 0) && (failuresAllowed.get() >= 0))
                try {
                    left.await();
                } catch (InterruptedException e) {}
        }
    }

    public static class Test {
        private static String coreNodeAddress, storageAddress;
        private static String username, followerName;

        public static void setCoreNodeAddress(String address) {
            Test.coreNodeAddress = address;
        }

        public static void setStorageAddress(String address) {
            Test.storageAddress = address;
        }

        public static void setUser(String name) {
            username = name;
        }

        public static void setFollower(String name) {
            followerName = name;
        }

        public Test() {}

        public static void mediumFileTest(UserPublicKey owner, User sharer, UserContext receiver, UserContext sender) throws IOException {
            // create a root dir and a file to it, then retrieve and decrypt the file using the receiver
            // create root cryptree
            SymmetricKey rootRKey = SymmetricKey.random();
            String name = "/";
            byte[] rootMapKey = ArrayOps.random(32); // root will be stored under this in the core node
            DirAccess root = DirAccess.createRoot(sharer, rootRKey, new FileProperties(name, 0));

            // generate file (two chunks)
            Random r = new Random();
            byte[] nonce1 = new byte[SymmetricKey.NONCE_BYTES];
            r.nextBytes(nonce1);
            byte[] raw1 = new byte[Chunk.MAX_SIZE];
            byte[] raw2 = new byte[Chunk.MAX_SIZE];
            byte[] template = "Hello secure cloud! Goodbye NSA!".getBytes();
            byte[] template2 = "Second hi safe cloud! Adios NSA!".getBytes();
            for (int i = 0; i < raw1.length / 32; i++)
                System.arraycopy(template, 0, raw1, 32 * i, 32);
            for (int i = 0; i < raw2.length / 32; i++)
                System.arraycopy(template2, 0, raw2, 32 * i, 32);

            // add file to root dir
            String filename = "HiNSA.bin"; // /photos/tree.jpg
            SymmetricKey fileKey = SymmetricKey.random();
            byte[] fileMapKey = ArrayOps.random(32); // file metablob will be stored under this in the core node
            byte[] chunk2MapKey = ArrayOps.random(32); // file metablob 2 will be stored under this in the core node
            Location fileLocation = new Location(owner, sharer, new ByteArrayWrapper(fileMapKey));
            Location chunk2Location = new Location(owner, sharer, new ByteArrayWrapper(chunk2MapKey));

            root.addFile(fileLocation, rootRKey, fileKey);

            // 1st chunk
            Chunk chunk1 = new Chunk(raw1, fileKey);
            EncryptedChunk encryptedChunk1 = new EncryptedChunk(chunk1.encrypt(nonce1));
            Fragment[] fragments1 = encryptedChunk1.generateFragments();
            List<ByteArrayWrapper> hashes1 = new ArrayList<>(fragments1.length);
            for (Fragment f : fragments1)
                hashes1.add(new ByteArrayWrapper(f.getHash()));
            FileProperties props1 = new FileProperties(filename, raw1.length + raw2.length);
            Optional<FileRetriever> ret = Optional.of(new EncryptedChunkRetriever(nonce1, encryptedChunk1.getAuth(), hashes1, Optional.of(chunk2Location)));
            Location rootDirLocation = new Location(receiver.us, sharer, new ByteArrayWrapper(rootMapKey));
            FileAccess file = FileAccess.create(fileKey, props1, ret, rootDirLocation);

            // 2nd chunk
            Chunk chunk2 = new Chunk(raw2, fileKey);
            byte[] nonce2 = new byte[SymmetricKey.NONCE_BYTES];
            EncryptedChunk encryptedChunk2 = new EncryptedChunk(chunk2.encrypt(nonce2));
            Fragment[] fragments2 = encryptedChunk2.generateFragments();
            List<ByteArrayWrapper> hashes2 = new ArrayList<>(fragments2.length);
            for (Fragment f : fragments2)
                hashes2.add(new ByteArrayWrapper(f.getHash()));
            Optional<FileRetriever> ret2 = Optional.of(new EncryptedChunkRetriever(nonce2, encryptedChunk2.getAuth(), hashes2, Optional.empty()));
            FileAccess meta2 = FileAccess.create(fileKey, new FileProperties("", raw2.length), ret2, rootDirLocation);

            //now create a sub folder
            SymmetricKey subfolderkey = SymmetricKey.random();
            SymmetricKey rootParentKey = root.getRParentKey(rootRKey);
            String subDirName = "subdir";
            DirAccess subfolder = DirAccess.create(sharer, subfolderkey, new FileProperties(subDirName, 0), rootDirLocation, rootParentKey);
            
            byte[] subDirMapKey = ArrayOps.random(32); 
            Location subDirLocation = new Location(receiver.us, sharer, new ByteArrayWrapper(subDirMapKey));
            root.addSubFolder(new SymmetricLocationLink(rootRKey, subfolderkey, subDirLocation));
            
            
            // now write the root to the core nodes
            EntryPoint rootEntry = new EntryPoint(new ReadableFilePointer(receiver.us, sharer, new ByteArrayWrapper(rootMapKey), rootRKey), receiver.username, new TreeSet<>(), new TreeSet<>());
            receiver.addToStaticData(rootEntry);
            sender.uploadChunk(root, new Fragment[0], owner, sharer, rootMapKey);
            // now upload the file meta blobs
            System.out.printf("Uploading chunk with %d fragments\n", fragments1.length);
            sender.uploadChunk(file, fragments1, owner, sharer, fileMapKey);
            System.out.printf("Uploading chunk with %d fragments\n", fragments2.length);
            sender.uploadChunk(meta2, fragments2, owner, sharer, chunk2MapKey);
            
            sender.uploadChunk(subfolder, new Fragment[0], owner, sharer, subDirMapKey);
            
            // now check the retrieval from zero knowledge
            Map<EntryPoint, FileAccess> roots = receiver.getRoots();
            for (EntryPoint dirPointer : roots.keySet()) {
                SymmetricKey rootDirKey = dirPointer.pointer.rootDirKey;
                DirAccess dir = (DirAccess) roots.get(dirPointer);
                try {
                    Map<SymmetricLocationLink, FileAccess> files = receiver.retrieveMetadata(dir.getFiles(), rootDirKey);
                    for (SymmetricLocationLink fileLoc : files.keySet()) {
                        SymmetricKey baseKey = fileLoc.target(rootDirKey);
                        FileAccess fileBlob = files.get(fileLoc);
                        // download fragments in chunk
                        FileProperties fileProps = fileBlob.getFileProperties(baseKey);

                        byte[] original = new byte[(int) fileProps.getSize()];
                        InputStream in = fileBlob.getRetriever().getFile(receiver, baseKey);
                        new DataInputStream(in).readFully(original);
                        // checks
                        System.out.println("Retrieved file with name: "+fileProps.name);
                        assertTrue("Correct filename", fileProps.name.equals(filename));
                        assertTrue("Correct file contents", Arrays.equals(original, ArrayOps.concat(raw1, raw2)));
                    }
                    DirAccess subDir = null;
                    SymmetricKey parentKey = null;
                    Map<SymmetricLocationLink, FileAccess> subFolders = receiver.retrieveMetadata(dir.getSubfolders(), rootDirKey);
                    for (SymmetricLocationLink fileLoc : subFolders.keySet()) {
                        SymmetricKey baseKey = fileLoc.target(rootDirKey);
                    	subDir = (DirAccess)subFolders.get(fileLoc);
                        parentKey = subDir.getRParentKey(baseKey);
                        FileProperties fileProps = subDir.getFileProperties(parentKey);
                        assertTrue("Correct directory name", fileProps.name.equals(subDirName));
                    }
                    ArrayList<SymmetricLocationLink> dirs = new ArrayList<>();
                    dirs.add(subDir.getParent());
                    Map<SymmetricLocationLink, FileAccess> parentFolder = receiver.retrieveMetadata(dirs, parentKey);
                    for (SymmetricLocationLink fileLoc : parentFolder.keySet()) {
                        SymmetricKey baseKey = fileLoc.target(parentKey);
                    	subDir = (DirAccess)parentFolder.get(fileLoc);
                        FileProperties fileProps = subDir.getFileProperties(baseKey);
                        assertTrue("Parent directory name", fileProps.name.equals(name));
                    }
                } catch (IOException e) {
                    System.err.println("Couldn't get File metadata!");
                    throw new IllegalStateException(e);
                }
            }
        }
    }
}
