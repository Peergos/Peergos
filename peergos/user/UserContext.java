package peergos.user;

import peergos.corenode.AbstractCoreNode;
import peergos.corenode.HTTPCoreNode;
import peergos.crypto.SymmetricKey;
import peergos.crypto.SymmetricLocationLink;
import peergos.crypto.User;
import peergos.crypto.UserPublicKey;
import peergos.storage.dht.FutureWrapper;
import peergos.user.fs.*;
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
    private Map<UserPublicKey, FilePointer> staticData = new TreeMap<>();
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

    public boolean sendFollowRequest(String friend) throws IOException
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
        FilePointer friendRoot = new FilePointer(friend, sharing, rootMapKey, SymmetricKey.random());
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

    public FilePointer decodeFollowRequest(byte[] data)
    {
        byte[] keys = new byte[64];
        System.arraycopy(data, 0, keys, 32, 32); // signing key is not used
        UserPublicKey tmp = new UserPublicKey(keys);
        byte[] decrypted = us.decryptMessage(Arrays.copyOfRange(data, 32, data.length), tmp.publicBoxingKey);
        try {
            return FilePointer.deserialize(new DataInputStream(new ByteArrayInputStream(decrypted)));
        } catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    // returns Map<Owner, File>
    public Map<String, FileWrapper> getRootFiles() throws IOException {
        Map<FilePointer, FileAccess> roots = getRoots();
        Map<String, FileWrapper> res = new HashMap();
        for (FilePointer s: roots.keySet()) {
            res.put(s.owner, new FileWrapper(this, roots.get(s), s.rootDirKey));
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

    private boolean addToStaticData(UserPublicKey pub, FilePointer root)
    {
        staticData.put(pub, root);
        byte[] rawStatic = serializeStatic();
        return core.updateStaticData(username, us.signMessage(rawStatic), rawStatic);
    }

    private Future uploadFragment(Fragment f, String targetUser, User sharer, byte[] mapKey)
    {
        return dht.put(f.getHash(), f.getData(), targetUser, sharer.getPublicKeys(), mapKey, sharer.signMessage(ArrayOps.concat(sharer.getPublicKeys(), f.getHash())));
    }

    private boolean uploadChunk(FileAccess meta, Fragment[] fragments, String target, User sharer, byte[] mapKey)
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

    public Fragment[] downloadFragments(List<ByteArrayWrapper> hashes)
    {
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

    public Map<FilePointer, FileAccess> getRoots() throws IOException
    {
        Map<FilePointer, FileAccess> res = new HashMap<>();
        for (UserPublicKey pub: staticData.keySet()) {
            FilePointer root = staticData.get(pub);
            FileAccess dir = getMetadata(new Location(root.owner, root.writer, root.mapKey));
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

    public static class FilePointer
    {
        public final String owner;
        public final User writer;
        public final ByteArrayWrapper mapKey;
        public final SymmetricKey rootDirKey;

        public FilePointer(String username, User owner, ByteArrayWrapper mapKey, SymmetricKey rootDirKey)
        {
            this.owner = username;
            this.writer = owner;
            this.mapKey = mapKey;
            this.rootDirKey = rootDirKey;
        }

        public static FilePointer deserialize(DataInput din) throws IOException
        {
            String username = Serialize.deserializeString(din, MAX_USERNAME_SIZE);
            byte[] privBytes = Serialize.deserializeByteArray(din, MAX_KEY_SIZE);
            ByteArrayWrapper mapKey = new ByteArrayWrapper(Serialize.deserializeByteArray(din, MAX_KEY_SIZE));
            byte[] secretRootDirKey = Serialize.deserializeByteArray(din, MAX_KEY_SIZE);
            return new FilePointer(username, User.deserialize(privBytes), mapKey, new SymmetricKey(secretRootDirKey));
        }

        public byte[] toByteArray() throws IOException {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            serialize(new DataOutputStream(bout));
            return bout.toByteArray();
        }

        public void serialize(DataOutput dout) throws IOException {
            // TODO encrypt this
            Serialize.serialize(owner, dout);
            Serialize.serialize(writer.getPrivateKeys(), dout);
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
                    results.add((V) o);
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

        public static void mediumFileTest(String owner, User sharer, UserContext receiver, UserContext sender) throws IOException {
            // create a root dir and a file to it, then retrieve and decrypt the file using the receiver
            // create root cryptree
            SymmetricKey rootRKey = SymmetricKey.random();
            String name = "/";
            byte[] rootMapKey = ArrayOps.random(32); // root will be stored under this in the core node
            DirAccess root = DirAccess.create(sharer, rootRKey, new FileProperties(name, 0));

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
            FileAccess file = FileAccess.create(sharer, fileKey, props1, ret);

            // 2nd chunk
            Chunk chunk2 = new Chunk(raw2, fileKey);
            byte[] nonce2 = new byte[SymmetricKey.NONCE_BYTES];
            EncryptedChunk encryptedChunk2 = new EncryptedChunk(chunk2.encrypt(nonce2));
            Fragment[] fragments2 = encryptedChunk2.generateFragments();
            List<ByteArrayWrapper> hashes2 = new ArrayList<>(fragments2.length);
            for (Fragment f : fragments2)
                hashes2.add(new ByteArrayWrapper(f.getHash()));
            Optional<FileRetriever> ret2 = Optional.of(new EncryptedChunkRetriever(nonce2, encryptedChunk2.getAuth(), hashes2, Optional.empty()));
            FileAccess meta2 = FileAccess.create(sharer, fileKey, new FileProperties("", raw2.length), ret2);

            // now write the root to the core nodes
            receiver.addToStaticData(sharer, new FilePointer(receiver.username, sharer, new ByteArrayWrapper(rootMapKey), rootRKey));
            sender.uploadChunk(root, new Fragment[0], owner, sharer, rootMapKey);
            // now upload the file meta blobs
            System.out.printf("Uploading chunk with %d fragments\n", fragments1.length);
            sender.uploadChunk(file, fragments1, owner, sharer, fileMapKey);
            System.out.printf("Uploading chunk with %d fragments\n", fragments2.length);
            sender.uploadChunk(meta2, fragments2, owner, sharer, chunk2MapKey);

            // now check the retrieval from zero knowledge
            Map<FilePointer, FileAccess> roots = receiver.getRoots();
            for (FilePointer dirPointer : roots.keySet()) {
                SymmetricKey rootDirKey = dirPointer.rootDirKey;
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
                        assertTrue("Correct filename", fileProps.name.equals(filename));
                        assertTrue("Correct file contents", Arrays.equals(original, ArrayOps.concat(raw1, raw2)));
                    }
                } catch (IOException e) {
                    System.err.println("Couldn't get File metadata!");
                    throw new IllegalStateException(e);
                }
            }
        }
    }
}
