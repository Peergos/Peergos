package peergos.user;

import org.ipfs.api.Multihash;
import peergos.corenode.*;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.crypto.SymmetricLocationLink;
import peergos.crypto.User;
import peergos.crypto.UserPublicKey;
import peergos.server.storage.ContentAddressedStorage;
import peergos.user.fs.*;
import peergos.util.ArrayOps;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class UserContext
{
    // TODO translate back from Javascript
    public static final int MAX_USERNAME_SIZE = 1024;
    public static final int MAX_KEY_SIZE = 96;

    public final String username;
    private User us;
    private ContentAddressedStorage dht;
    private CoreNode core;
    private Map<UserPublicKey, EntryPoint> staticData = new TreeMap<>();
    private ExecutorService executor = Executors.newFixedThreadPool(2);


    public UserContext(String username, User user, ContentAddressedStorage dht, CoreNode core)
    {
        this.username = username;
        this.us = user;
        this.dht = dht;
        this.core = core;
    }

    public void shutdown() {
        executor.shutdown();
    }

    public boolean register() throws IOException
    {
        byte[] rawStatic = serializeStatic();
        byte[] signed = us.signMessage(ArrayOps.concat(username.getBytes(), us.serialize(), rawStatic));
        return core.addUsername(username, us.serialize(), signed, rawStatic);
    }

    public boolean isRegistered() throws IOException
    {
        String name = core.getUsername(us.serialize());
        return username.equals(name);
    }

    public boolean sendFollowRequest(UserPublicKey friend) throws IOException
    {
        // check friend is a registered user
        String friendUsername = core.getUsername(friend.serialize());
        if (friendUsername == null)
            throw new IllegalStateException("User isn't registered! "+friend);

        // create sharing keypair
        User sharing = User.random();
        ByteArrayWrapper rootMapKey = new ByteArrayWrapper(ArrayOps.random(32));

        // add a note to our static data so we know who we sent the private key to
        ReadableFilePointer friendRoot = new ReadableFilePointer(us, sharing, rootMapKey, SymmetricKey.random());
        SortedSet<String> writers = new TreeSet<>();
        writers.add(core.getUsername(friend.serialize()));
        EntryPoint entry = new EntryPoint(friendRoot, username, new TreeSet<>(), writers);
        addToStaticData(entry);

        // send details to allow friend to share with us (i.e. we follow them)

        // create a tmp keypair whose public key we can append to the request without leaking information
        User tmp = User.random();
        byte[] payload = entry.serializeAndEncrypt(tmp.secretBoxingKey,  friend.publicBoxingKey);
        return core.followRequest(friend, ArrayOps.concat(tmp.getPublicBoxingKey(), payload));
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
        try {
            UserPublicKey tmp = UserPublicKey.deserialize(new DataInputStream(new ByteArrayInputStream(keys)));
            return EntryPoint.decryptAndDeserialize(Arrays.copyOfRange(data, 32, data.length), us.secretBoxingKey, tmp.publicBoxingKey);
        } catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public synchronized byte[] serializeStatic()
    {
        // TODO translate back from Javascript
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutput dout = new DataOutputStream(bout);
            dout.writeInt(staticData.size());
            for (UserPublicKey sharer : staticData.keySet())
                Serialize.serialize(staticData.get(sharer).serializeAndEncrypt(us.secretBoxingKey, us.publicBoxingKey), dout);
            return bout.toByteArray();
        } catch (IOException e) {throw new IllegalStateException(e.getMessage());}
    }

    private boolean addToStaticData(EntryPoint entry) throws IOException
    {
        staticData.put(entry.pointer.writer, entry);
        byte[] rawStatic = serializeStatic();
        byte[] putHash = dht.put(rawStatic);
        return core.setMetadataBlob(us.serialize(), us.serialize(), us.signMessage(putHash));
    }

    private Multihash uploadFragment(Fragment f, UserPublicKey targetUser, User sharer, byte[] mapKey)
    {
        return new Multihash(dht.put(f.getData()));
    }

    private boolean uploadChunk(FileAccess meta, Fragment[] fragments, UserPublicKey target, User sharer, byte[] mapKey) throws IOException
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
        // TODO correct this once we reinstate the Java API
        if (!core.setMetadataBlob(us.serialize(), sharer.serialize(), sharer.signMessage(ArrayOps.concat(mapKey, metaBlob))))
            System.out.println("Meta blob store failed.");
        if (fragments.length > 0 ) {
            // now upload fragments to DHT
            List<Multihash> hashes = new ArrayList<>();
            for (Fragment f : fragments)
                try {
                    hashes.add(uploadFragment(f, target, sharer, mapKey));
                } catch (Exception e) {
                    e.printStackTrace();
                }
        }
        return true;
    }

    public Fragment[] downloadFragments(List<ByteArrayWrapper> hashes)
    {
        List<Fragment> frags = new ArrayList<>();
        for (ByteArrayWrapper hash: hashes)
            frags.add(new Fragment(dht.get(hash.data)));
        return frags.toArray(new Fragment[frags.size()]);
    }

    public Map<EntryPoint, FileAccess> getRoots() throws Exception
    {
        byte[] staticData = dht.get(core.getMetadataBlob(us.serialize()));
        DataInput din = new DataInputStream(new ByteArrayInputStream(staticData));
        int entries = din.readInt();
        this.staticData = new HashMap<>();
        for (int i=0; i < entries; i++) {
            EntryPoint entry = EntryPoint.decryptAndDeserialize(Serialize.deserializeByteArray(din, EntryPoint.MAX_SIZE), us.secretBoxingKey, us.publicBoxingKey);
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
        // TODO correct with Btree once we reinstate Java API
        byte[] meta = core.getMetadataBlob(loc.writerKey.serialize());
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(meta));
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
}
