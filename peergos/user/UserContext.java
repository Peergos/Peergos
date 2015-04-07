package peergos.user;

import peergos.corenode.AbstractCoreNode;
import peergos.corenode.HTTPCoreNode;
import peergos.crypto.SymmetricKey;
import peergos.crypto.SymmetricLocationLink;
import peergos.crypto.User;
import peergos.crypto.UserPublicKey;
import peergos.storage.dht.FutureWrapper;
import peergos.user.fs.*;
import peergos.user.fs.erasure.Erasure;
import peergos.util.ArrayOps;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import static org.junit.Assert.*;

import java.io.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.PrivateKey;
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
    private Map<UserPublicKey, StaticDataElement> staticData = new TreeMap<>();
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

    public boolean sendFollowRequest(String friend)
    {
        // check friend is a registered user
        UserPublicKey friendKey = core.getPublicKey(friend);
        if (friendKey == null)
            throw new IllegalStateException("User isn't registered! "+friend);

        // create sharing keypair and give it write access
        User sharing = User.random();
        addSharingKey(sharing);
        ByteArrayWrapper rootMapKey = new ByteArrayWrapper(ArrayOps.random(32));

        // add a note to our static data so we know who we sent the private key to
        SharedRootDir friendRoot = new SharedRootDir(friend, sharing, rootMapKey, SymmetricKey.random());
        addToStaticData(sharing, friendRoot);

        // send details to allow friend to share with us (i.e. we follow them)
        byte[] raw = friendRoot.toByteArray();

        // create a tmp keypair whose public key we can append to the request without leaking information
        User tmp = User.random();
        byte[] payload = friendKey.encryptMessageFor(raw, tmp.secretBoxingKey);
        return core.followRequest(friend, ArrayOps.concat(tmp.publicBoxingKey, payload));
    }


    public List<byte[]> getFollowRequests()
    {
        byte[] raw = core.getFollowRequests(username);
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

    public SharedRootDir decodeFollowRequest(byte[] data)
    {
        byte[] keys = new byte[64];
        System.arraycopy(data, 0, keys, 32, 32); // signing key is not used
        UserPublicKey tmp = new UserPublicKey(keys);
        byte[] decrypted = us.decryptMessage(Arrays.copyOfRange(data, 32, data.length), tmp.publicBoxingKey);
        try {
            SharedRootDir root = (SharedRootDir) StaticDataElement.deserialize(new DataInputStream(new ByteArrayInputStream(decrypted)));
            return root;
        } catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    // returns Map<Owner, File>
    public Map<String, FileWrapper> getRootFiles() {
        Map<StaticDataElement, DirAccess> roots = getRoots();
        Map<String, FileWrapper> res = new HashMap();
        for (StaticDataElement s: roots.keySet()) {
            if (s instanceof SharedRootDir)
                res.put(((SharedRootDir) s).username, new FileWrapper(this, roots.get(s), ((SharedRootDir) s).rootDirKey));
        }
        return res;
    }

    public synchronized byte[] serializeStatic()
    {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutput dout = new DataOutputStream(bout);
            dout.writeInt(staticData.size());
            for (UserPublicKey sharer : staticData.keySet())
                Serialize.serialize(staticData.get(sharer).toByteArray(), dout);
            return bout.toByteArray();
        } catch (IOException e) {throw new IllegalStateException(e.getMessage());}
    }

    public boolean addSharingKey(UserPublicKey pub)
    {
        byte[] signed = us.signMessage(pub.getPublicKeys());
        return core.allowSharingKey(username, signed);
    }

    private boolean addToStaticData(UserPublicKey pub, StaticDataElement root)
    {
        staticData.put(pub, root);
        byte[] rawStatic = serializeStatic();
        return core.updateStaticData(username, us.signMessage(rawStatic), rawStatic);
    }

    private Future uploadFragment(Fragment f, String targetUser, User sharer, byte[] mapKey)
    {
        return dht.put(f.getHash(), f.getData(), targetUser, sharer.getPublicKeys(), mapKey, sharer.signMessage(ArrayOps.concat(sharer.getPublicKeys(), f.getHash())));
    }

    private boolean uploadChunk(Metadata meta, Fragment[] fragments, String target, User sharer, byte[] mapKey)
    {
        // tell core node first to allow fragments
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);
        try {
            meta.serialize(dout);
            dout.flush();
        } catch (IOException e) {e.printStackTrace();}
        List<ByteArrayWrapper> allHashes = meta.getFragmentHashes();
        byte[] metaBlob = bout.toByteArray();
        System.out.println("Storing metadata blob of "+metaBlob.length + " bytes.");
        if (!core.addMetadataBlob(target, sharer.getPublicKeys(), mapKey, metaBlob, sharer.signMessage(ArrayOps.concat(mapKey, metaBlob))))
            System.out.println("Meta blob store failed.");
        if (fragments.length > 0 ) {
            core.addFragmentHashes(target, sharer.getPublicKeys(), mapKey, metaBlob, meta.getFragmentHashes(), sharer.signMessage(ArrayOps.concat(mapKey, metaBlob, ArrayOps.concat(allHashes))));

            // now upload fragments to DHT
            List<Future<Object>> futures = new ArrayList();
            for (Fragment f : fragments)
                try {
                    futures.add(uploadFragment(f, target, sharer, mapKey));
                } catch (Exception e) {
                    e.printStackTrace();
                }

            // wait for all fragments to upload
            Countdown<Object> all = new Countdown(futures.size(), futures, executor);
            try {
                all.await();
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }

    private Fragment[] downloadFragments(Metadata meta)
    {
        List<ByteArrayWrapper> hashes = meta.getFragmentHashes();
        Fragment[] res = new Fragment[hashes.size()];
        List<Future<ByteArrayWrapper>> futs = new ArrayList<>(res.length);
        for (int i=0; i < res.length; i++)
            futs.add(dht.get(hashes.get(i).data));
        Countdown<ByteArrayWrapper> first50 = new Countdown<>(50, futs, executor);
        first50.await();
        List<Fragment> frags = new ArrayList<>();
        for (ByteArrayWrapper frag: first50.results)
            frags.add(new Fragment(frag.data));
        return frags.toArray(new Fragment[frags.size()]);
    }

    public Map<StaticDataElement, DirAccess> getRoots()
    {
        Map<StaticDataElement, DirAccess> res = new HashMap();
        for (UserPublicKey pub: staticData.keySet()) {
            StaticDataElement dataElement = staticData.get(pub);
            if (dataElement instanceof SharedRootDir) {
                DirAccess dir = recreateDir((SharedRootDir) dataElement);
                if (dir != null)
                    res.put(dataElement, dir);
            }
        }
        return res;
    }

    private DirAccess recreateDir(SharedRootDir raw)
    {
        ByteArrayWrapper mapKey = raw.mapKey;
        User owner = raw.owner;
        AbstractCoreNode.MetadataBlob meta = core.getMetadataBlob(username, owner.getPublicKeys(), mapKey.data);
        ByteArrayWrapper rawMeta = meta.metadata();
        System.out.println("Retrieved dir metadata blob of "+rawMeta.data.length + " bytes.");
        try {
            return (DirAccess) Metadata.deserialize(new DataInputStream(new ByteArrayInputStream(rawMeta.data)), raw.rootDirKey);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public Metadata getMetadata(Location loc, SymmetricKey key) throws IOException {
        AbstractCoreNode.MetadataBlob meta = core.getMetadataBlob(loc.owner, loc.subKey.getPublicKeys(), loc.mapKey.data);
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(meta.metadata().data));
        Metadata m =  Metadata.deserialize(din, key);
        byte[] fragmentHashesRaw = core.getFragmentHashes(loc.owner, loc.subKey, loc.mapKey.data);
        List<ByteArrayWrapper> fragmentHashes = ArrayOps.split(fragmentHashesRaw, UserPublicKey.HASH_BYTES);
        m.setFragments(fragmentHashes);
        return m;
    }

    public Map<SymmetricLocationLink, Metadata> retrieveMetadata(Collection<SymmetricLocationLink> links, SymmetricKey parentFolder) throws IOException {
        Map<SymmetricLocationLink, Metadata> res = new HashMap();
        for (SymmetricLocationLink link: links) {
            Location loc = link.targetLocation(parentFolder);
            Metadata fa = getMetadata(loc, link.target(parentFolder));
            if (fa instanceof FileAccess) {
                byte[] fragmentHashesRaw = core.getFragmentHashes(loc.owner, loc.subKey, loc.mapKey.data);
                List<ByteArrayWrapper> fragmentHashes = ArrayOps.split(fragmentHashesRaw, UserPublicKey.HASH_BYTES);
                ((FileAccess) fa).setFragments(fragmentHashes);
            }
            res.put(link, fa);
        }
        return res;
    }

    public static abstract class StaticDataElement
    {
        public final int type;

        public StaticDataElement(int type)
        {
            this.type = type;
        }

        public static StaticDataElement deserialize(DataInput din) throws IOException {
            int type = din.readInt();
            switch (type){
                case 1:
                    return SharedRootDir.deserialize(din);
                default: throw new IllegalStateException("Unknown DataElement Type: "+type);
            }
        }

        public byte[] toByteArray()
        {
            try {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                serialize(new DataOutputStream(bout));
                return bout.toByteArray();
            } catch (IOException e) {throw new IllegalStateException(e.getMessage());}
        }

        public void serialize(DataOutput dout) throws IOException
        {
            dout.writeInt(type);
        }
    }

    public static class SharedRootDir extends StaticDataElement
    {
        public final String username;
        public final User owner;
        public final ByteArrayWrapper mapKey;
        public final SymmetricKey rootDirKey;

        public SharedRootDir(String username, User owner, ByteArrayWrapper mapKey, SymmetricKey rootDirKey)
        {
            super(1);
            this.username = username;
            this.owner = owner;
            this.mapKey = mapKey;
            this.rootDirKey = rootDirKey;
        }

        public static SharedRootDir deserialize(DataInput din) throws IOException
        {
            String username = Serialize.deserializeString(din, MAX_USERNAME_SIZE);
            byte[] privBytes = Serialize.deserializeByteArray(din, MAX_KEY_SIZE);
            ByteArrayWrapper mapKey = new ByteArrayWrapper(Serialize.deserializeByteArray(din, MAX_KEY_SIZE));
            byte[] secretRootDirKey = Serialize.deserializeByteArray(din, MAX_KEY_SIZE);
            return new SharedRootDir(username, User.deserialize(privBytes), mapKey, new SymmetricKey(secretRootDirKey));
        }

        @Override
        public void serialize(DataOutput dout) throws IOException {
            super.serialize(dout);
            // TODO encrypt this
            Serialize.serialize(username, dout);
            Serialize.serialize(owner.getPrivateKeys(), dout);
            Serialize.serialize(mapKey.data, dout);
            Serialize.serialize(rootDirKey.getKey(), dout);
        }
    }

    public static class Countdown<V>
    {
        CountDownLatch left;
        AtomicInteger failuresAllowed;
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        Set<V> results = new ConcurrentSkipListSet<>();

        public Countdown(int needed, List<Future<V>> futs, ExecutorService context)
        {
            left = new CountDownLatch(needed);
            failuresAllowed = new AtomicInteger(futs.size()-needed);
            for (Future<V> fut: futs)
                FutureWrapper.followWith(fut, o -> {
                    results.add((V)o);
                    left.countDown();
                }, e -> {
                    failuresAllowed.decrementAndGet();
                    errors.add(e);
                }, context);
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

        @org.junit.Test
        public void all() {
            UserContext us = null, alice = null;
            DHTUserAPI dht = null;

            try {
                String coreIP = coreNodeAddress;
                String storageIP = storageAddress;
                String ourname = username;
                String friendName = followerName;

                System.out.println("Doing UserContext tests with core address = "+coreIP + " and storage address = "+storageIP);

                int storagePort = 8000;
                URL coreURL = new URL("http://" + coreIP + ":" + AbstractCoreNode.PORT + "/");
                HTTPCoreNode clientCoreNode = new HTTPCoreNode(coreURL);

                // create a new us
                long t1 = System.nanoTime();

                User ourKeys = User.random();

                long t2 = System.nanoTime();
                System.out.printf("User generation took %d mS\n", (t2 - t1) / 1000000);

                // create a DHT API
                dht = new HttpsUserAPI(new InetSocketAddress(InetAddress.getByName(storageIP), storagePort));

                // make and register us
                us = new UserContext(ourname, ourKeys, dht, clientCoreNode);
                if (!us.isRegistered())
                    us.register();

                // make another user
                t1 = System.nanoTime();

                User friendKeys = User.random();

                t2 = System.nanoTime();
                System.out.printf("User generation took %d mS\n", (t2 - t1) / 1000000);

                alice = new UserContext(friendName, friendKeys, dht, clientCoreNode);
                if (!alice.isRegistered())
                    alice.register();

                // make Alice follow Bob (Alice gives Bob write permission to a folder in Alice's space)
                alice.sendFollowRequest(ourname);

                // get the sharing key alice sent us
                List<byte[]> reqs = us.getFollowRequests();
                assertTrue("Got follow Request", reqs.size() == 1);
                SharedRootDir root = us.decodeFollowRequest(reqs.get(0));
                User sharer = root.owner;

                // store a chunk in alice's space using the permitted sharing key (this could be alice or bob at this point)
                int frags = 120;
                int port = (int) System.currentTimeMillis() % (Short.MAX_VALUE -1024) + 1024;

                InetSocketAddress address = new InetSocketAddress("localhost", port);
                for (int i = 0; i < frags; i++) {
                    byte[] frag = ArrayOps.random(32);
                    byte[] message = ArrayOps.concat(sharer.getPublicKeys(), frag);
                    byte[] signed = sharer.signMessage(message);
                    if (!clientCoreNode.registerFragmentStorage(friendName, address, friendName, signed)) {
                        System.out.println("Failed to register fragment storage!");
                    }
                }
                long quota = clientCoreNode.getQuota(friendName);
                System.out.println("Generated quota: " + quota/1024 + " KiB");
                t1 = System.nanoTime();
                mediumFileTest(alice.username, sharer, alice, us);
                t2 = System.nanoTime();
                System.out.printf("File test took %d mS\n", (t2 - t1) / 1000000);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                us.shutdown();
                alice.shutdown();
                dht.shutdown();
            }
        }



        public static void mediumFileTest(String owner, User sharer, UserContext receiver, UserContext sender) {
            // create a root dir and a file to it, then retrieve and decrypt the file using the receiver
            // create root cryptree
            SymmetricKey rootRKey = SymmetricKey.random();
            SymmetricKey rootWKey = SymmetricKey.random();
            String name = "/";
            byte[] rootIV = SymmetricKey.createNonce();
            byte[] rootMapKey = ArrayOps.random(32); // root will be stored under this in the core node
            DirAccess root = new DirAccess(sharer, rootRKey, new FileProperties(name, rootIV, new byte[0], 0, null), rootWKey);
            root.setFragments(new ArrayList());

            // generate file (two chunks)
            Random r = new Random();
            byte[] nonce = new byte[SymmetricKey.NONCE_BYTES];
            r.nextBytes(nonce);
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
            EncryptedChunk encryptedChunk1 = new EncryptedChunk(chunk1.encrypt(nonce));
            Fragment[] fragments1 = encryptedChunk1.generateFragments();
            List<ByteArrayWrapper> hashes1 = new ArrayList(fragments1.length);
            for (Fragment f : fragments1)
                hashes1.add(new ByteArrayWrapper(f.getHash()));
            FileProperties props1 = new FileProperties(filename, nonce, encryptedChunk1.getAuth(), raw1.length + raw2.length, chunk2Location);
            FileAccess file = new FileAccess(sharer, fileKey, props1, hashes1);

            // 2nd chunk
            Chunk chunk2 = new Chunk(raw2, fileKey);
            EncryptedChunk encryptedChunk2 = new EncryptedChunk(chunk2.encrypt(nonce));
            Fragment[] fragments2 = encryptedChunk2.generateFragments();
            List<ByteArrayWrapper> hashes2 = new ArrayList(fragments2.length);
            for (Fragment f : fragments2)
                hashes2.add(new ByteArrayWrapper(f.getHash()));
            ChunkProperties props2 = new ChunkProperties(nonce, encryptedChunk2.getAuth(), null);
            Metadata meta2 = new Metadata(props2, fileKey, nonce);
            meta2.setFragments(hashes2);

            // now write the root to the core nodes
            receiver.addToStaticData(sharer, new SharedRootDir(receiver.username, sharer, new ByteArrayWrapper(rootMapKey), rootRKey));
            sender.uploadChunk(root, new Fragment[0], owner, sharer, rootMapKey);
            // now upload the file meta blobs
            System.out.printf("Uploading chunk with %d fragments\n", fragments1.length);
            sender.uploadChunk(file, fragments1, owner, sharer, fileMapKey);
            System.out.printf("Uploading chunk with %d fragments\n", fragments2.length);
            sender.uploadChunk(meta2, fragments2, owner, sharer, chunk2MapKey);

            // now check the retrieval from zero knowledge
            Map<StaticDataElement, DirAccess> roots = receiver.getRoots();
            for (StaticDataElement dirPointer : roots.keySet()) {
                SymmetricKey rootDirKey = ((SharedRootDir) dirPointer).rootDirKey;
                DirAccess dir = roots.get(dirPointer);
                try {
                    Map<SymmetricLocationLink, Metadata> files = receiver.retrieveMetadata(dir.getFiles(), rootDirKey);
                    for (SymmetricLocationLink fileLoc : files.keySet()) {
                        SymmetricKey baseKey = fileLoc.target(rootDirKey);
                        FileAccess fileBlob = (FileAccess) files.get(fileLoc);
                        // download fragments in chunk
                        Fragment[] retrievedfragments1 = receiver.downloadFragments(fileBlob);
                        FileProperties fileProps = fileBlob.getProps(baseKey);

                        byte[] enc1 = Erasure.recombine(reorder(fileBlob, retrievedfragments1), Chunk.MAX_SIZE, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
                        EncryptedChunk encrypted1 = new EncryptedChunk(ArrayOps.concat(fileProps.getAuth(), enc1));
                        byte[] original1 = encrypted1.decrypt(baseKey, fileProps.getNonce());
                        // checks
                        assertTrue("Correct filename", fileProps.name.equals(filename));
                        assertTrue("Correct file contents", Arrays.equals(original1, raw1));

                        // 2nd chunk
                        Metadata second = receiver.getMetadata(fileProps.getNextChunkLocation(), baseKey);
                        Fragment[] retrievedfragments2 = receiver.downloadFragments(second);
                        byte[] enc2 = Erasure.recombine(reorder(second, retrievedfragments2), Chunk.MAX_SIZE, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
                        EncryptedChunk encrypted2 = new EncryptedChunk(ArrayOps.concat(second.getProps(baseKey, fileProps.getNonce()).getAuth(), enc2));
                        byte[] original2 = encrypted2.decrypt(baseKey, fileProps.getNonce());
                        assertTrue("Correct file contents (2nd chunk)", Arrays.equals(original2, raw2));
                    }
                } catch (IOException e) {
                    System.err.println("Couldn't get File metadata!");
                    throw new IllegalStateException(e);
                }
            }
        }

        public void fileTest(String owner, User sharer, PrivateKey sharerPriv, UserContext receiver, UserContext sender) {
            // create a root dir and add a file to it, then retrieve and decrypt the file using the receiver
            // create root cryptree
            SymmetricKey rootRKey = SymmetricKey.random();
            SymmetricKey rootWKey = SymmetricKey.random();
            String name = "/";
            byte[] rootIV = SymmetricKey.createNonce();
            byte[] rootMapKey = ArrayOps.random(32); // root will be stored under this in the core node
            DirAccess root = new DirAccess(sharer, rootRKey, new FileProperties(name, rootIV, new byte[0], 0, null), rootWKey);

            // generate file (single chunk)
            Random r = new Random();
            byte[] nonce = new byte[SymmetricKey.NONCE_BYTES];
            r.nextBytes(nonce);
            byte[] raw = new byte[Chunk.MAX_SIZE];
            byte[] template = "Hello secure cloud! Goodbye NSA!".getBytes();
            for (int i = 0; i < raw.length / 32; i++)
                System.arraycopy(template, 0, raw, 32 * i, 32);

            // add file to root dir
            String filename = "tree.jpg"; // /photos/tree.jpg
            SymmetricKey fileKey = SymmetricKey.random();
            byte[] fileMapKey = ArrayOps.random(32); // file metablob will be stored under this in the core node
            Location fileLocation = new Location(owner, sharer, new ByteArrayWrapper(fileMapKey));

            root.addFile(fileLocation, rootRKey, fileKey);

            Chunk chunk = new Chunk(raw, fileKey);
            EncryptedChunk encryptedChunk = new EncryptedChunk(chunk.encrypt(nonce));
            Fragment[] fragments = encryptedChunk.generateFragments();
            List<ByteArrayWrapper> hashes = new ArrayList(fragments.length);
            for (Fragment f : fragments)
                hashes.add(new ByteArrayWrapper(f.getHash()));
            FileProperties props = new FileProperties(filename, nonce, encryptedChunk.getAuth(), raw.length, null);
            FileAccess file = new FileAccess(sharer, fileKey, props, hashes);

            // now write the root to the core nodes
            receiver.addToStaticData(sharer, new SharedRootDir(receiver.username, sharer, new ByteArrayWrapper(rootMapKey), rootRKey));
            sender.uploadChunk(root, new Fragment[0], owner, sharer, rootMapKey);
            // now upload the file meta blob
            sender.uploadChunk(file, fragments, owner, sharer, fileMapKey);


            // now check the retrieval from zero knowledge
            Map<StaticDataElement, DirAccess> roots = receiver.getRoots();
            for (StaticDataElement dirPointer : roots.keySet()) {
                SymmetricKey rootDirKey = ((SharedRootDir) dirPointer).rootDirKey;
                DirAccess dir = roots.get(dirPointer);
                try {
                    Map<SymmetricLocationLink, Metadata> files = receiver.retrieveMetadata(dir.getFiles(), rootDirKey);
                    for (SymmetricLocationLink fileLoc : files.keySet()) {
                        FileAccess fileBlob = (FileAccess) files.get(fileLoc);
                        // download fragments in chunk
                        Fragment[] retrievedfragments = sender.downloadFragments(fileBlob);
                        FileProperties fileProps = fileBlob.getProps(fileLoc.target(rootDirKey));

                        byte[] enc = Erasure.recombine(reorder(fileBlob, retrievedfragments), Chunk.MAX_SIZE, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
                        EncryptedChunk encrypted = new EncryptedChunk(enc);
                        byte[] original = encrypted.decrypt(fileLoc.target(rootDirKey), fileProps.getNonce());
                        // checks
                        assertTrue("Correct filename", fileProps.name.equals(filename));
                        assertTrue("Correct file contents", Arrays.equals(original, raw));
                    }
                } catch (IOException e) {
                    System.err.println("Couldn't get File metadata!");
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    public static byte[][] reorder(Metadata meta, Fragment[] received)
    {
        List<ByteArrayWrapper> hashes = meta.getFragmentHashes();
        byte[][] originalHashes = new byte[hashes.size()][];
        for (int i=0; i < originalHashes.length; i++)
            originalHashes[i] = hashes.get(i).data;
        byte[][] res = new byte[originalHashes.length][];
        for (int i=0; i < res.length; i++)
        {
            for (int j=0; j < received.length; j++)
                if (Arrays.equals(originalHashes[i], received[j].getHash()))
                {
                    res[i] = received[j].getData();
                    break;
                }
            if (res[i] == null)
                res[i] = new byte[received[0].getData().length];
        }
        return res;
    }
}
