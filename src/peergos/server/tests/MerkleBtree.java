package peergos.server.tests;

import org.junit.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MerkleBtree {

    private Crypto crypto = Crypto.initJava();

    public RAMStorage createStorage() {
        return RAMStorage.getSingleton();
    }

    public CompletableFuture<MerkleBTree> createTree(SigningPrivateKeyAndPublicHash user) throws IOException {
        return createTree(user, RAMStorage.getSingleton());
    }

    public SigningPrivateKeyAndPublicHash createUser() {
        SigningKeyPair random = SigningKeyPair.random(crypto.random, crypto.signer);
        try {
            PublicKeyHash publicHash = createStorage().putSigningKey("", random.secretSigningKey.signOnly(random.publicSigningKey.serialize()), random.publicSigningKey).get();
            return new SigningPrivateKeyAndPublicHash(publicHash, random.secretSigningKey);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<MerkleBTree> createTree(SigningPrivateKeyAndPublicHash user, ContentAddressedStorage dht) throws IOException {
        return MerkleBTree.create(user, dht);
    }

    public Multihash hash(byte[] in) {
        return new Multihash(Multihash.Type.sha2_256, RAMStorage.hash(in));
    }

    @Test
    public void basic() throws Exception {
        SigningPrivateKeyAndPublicHash user = createUser();
        MerkleBTree tree = createTree(user).get();
        byte[] key1 = new byte[]{0, 1, 2, 3};
        Multihash value1 = hash(new byte[]{1, 1, 1, 1});
        tree.put(user, key1, MaybeMultihash.empty(), value1).get();
        MaybeMultihash res1 = tree.get(key1).get();
        if (!res1.get().equals(value1))
            throw new IllegalStateException("Results not equal");
    }

    @Test
    public void basic2() throws Exception {
        SigningPrivateKeyAndPublicHash user = createUser();
        RAMStorage dht = createStorage();
        MerkleBTree tree = createTree(user, dht).get();
        for (int i=0; i < 16; i++) {
            byte[] key1 = new byte[]{0, 1, 2, (byte)i};
            Multihash value1 = hash(new byte[]{1, 1, 1, (byte)i});
            tree.put(user, key1, MaybeMultihash.empty(), value1).get();
            MaybeMultihash res1 = tree.get(key1).get();
            if (! res1.get().equals(value1))
                throw new IllegalStateException("Results not equal");
        }
        if (tree.root.keys.size() != 2)
            throw new IllegalStateException("New root should have two children!");
    }

    @Test
    public void overwriteValue() throws Exception {
        SigningPrivateKeyAndPublicHash user = createUser();
        MerkleBTree tree = createTree(user).get();
        byte[] key1 = new byte[]{0, 1, 2, 3};
        Multihash value1 = hash(new byte[]{1, 1, 1, 1});
        tree.put(user, key1, MaybeMultihash.empty(), value1);
        MaybeMultihash res1 = tree.get(key1).get();
        if (! res1.get().equals(value1))
            throw new IllegalStateException("Results not equal");
        Multihash value2 = hash(new byte[]{2, 2, 2, 2});
        tree.put(user, key1, MaybeMultihash.of(value1), value2);
        MaybeMultihash res2 = tree.get(key1).get();
        if (! res2.get().equals(value2))
            throw new IllegalStateException("Results not equal");
    }

//    @Test
    public void huge() throws Exception {
        SigningPrivateKeyAndPublicHash user = createUser();
        MerkleBTree tree = createTree(user).get();
        long t1 = System.currentTimeMillis();
        for (int i=0; i < 1000000; i++) {
            byte[] key1 = new byte[]{0, 1, 2, (byte)i};
            Multihash value1 = hash(new byte[]{1, 1, 1, (byte)i});
            tree.put(user, key1, MaybeMultihash.empty(), value1).get();
            MaybeMultihash res1 = tree.get(key1).get();
            if (! res1.get().equals(value1))
                throw new IllegalStateException("Results not equal");
        }
        long t2 = System.currentTimeMillis();
        for (int i=0; i < 1000000; i++) {
            byte[] key1 = new byte[]{0, 1, 2, (byte)i};
            byte[] value1 = new byte[]{1, 1, 1, (byte)i};
            MaybeMultihash res1 = tree.get(key1).get();
            if (! res1.get().equals(hash(value1)))
                throw new IllegalStateException("Results not equal");
        }
        System.out.printf("Put+get rate = %f /s\n", 1000000.0 / (t2 - t1) * 1000);
        ((RAMStorage)tree.storage).clear();
    }

    @Test
    public void random() throws Exception {
        SigningPrivateKeyAndPublicHash user = createUser();
        MerkleBTree tree = createTree(user).get();
        int keylen = 32;

        long t1 = System.currentTimeMillis();
        Random r = new Random(1);
        int lim = 14000;
        for (int i = 0; i < lim; i++) {
            if (i % (lim/10) == 0)
                System.out.println((10*i/lim)+"0 %");
            byte[] key1 = new byte[keylen];
            r.nextBytes(key1);
            byte[] value1Raw = new byte[keylen];
            r.nextBytes(value1Raw);
            Multihash value1 = hash(value1Raw);
            tree.put(user, key1, MaybeMultihash.empty(), value1).get();

            MaybeMultihash res1 = tree.get(key1).get();
            if (! res1.get().equals(value1))
                throw new IllegalStateException("Results not equal");
        }
        long t2 = System.currentTimeMillis();
        System.out.printf("Put+get rate = %f /s\n", (double)lim / (t2 - t1) * 1000);
        ((RAMStorage)tree.storage).clear();
    }

//    @Test
    public void delete() throws Exception {
        SigningPrivateKeyAndPublicHash user = createUser();
        MerkleBTree tree = createTree(user).get();
        int keylen = 32;

        Random r = new Random(1);
        SortedSet<ByteArrayWrapper> keys = new TreeSet<>();
        int lim = 10000;
        for (int i = 0; i < lim; i++) {
            if (i % (lim/10) == 0)
                System.out.println((10*i/lim)+"0 % of building");
            byte[] key1 = new byte[keylen];
            keys.add(new ByteArrayWrapper(key1));
            r.nextBytes(key1);
            byte[] value1Raw = new byte[keylen];
            r.nextBytes(value1Raw);
            Multihash value1 = hash(value1Raw);
            MaybeMultihash existing = tree.get(key1).get();
            if (existing.isPresent())
                throw new IllegalStateException("Already present!");
            tree.put(user, key1, MaybeMultihash.empty(), value1).get();

            MaybeMultihash res1 = tree.get(key1).get();
            if (! res1.get().equals(value1))
                throw new IllegalStateException("Results not equal");
        }

        ByteArrayWrapper[] keysArray = keys.toArray(new ByteArrayWrapper[keys.size()]);
        long t1 = System.currentTimeMillis();
        for (int i = 0; i < lim; i++) {
            if (i % (lim / 10) == 0)
                System.out.println((10 * i / lim) + "0 % of deleting");
            int size = tree.size().get();
            if (size != lim)
                throw new IllegalStateException("Missing keys from tree!");
            ByteArrayWrapper key = keysArray[r.nextInt(keysArray.length)];
            MaybeMultihash value = tree.get(key.data).get();
            if (! value.isPresent())
                throw new IllegalStateException("Key not present!");
            tree.delete(user, key.data, value).get();
            if (tree.get(key.data).get().isPresent())
                throw new IllegalStateException("Key still present!");
            tree.put(user, key.data, MaybeMultihash.empty(), value.get()).get();
        }
        long t2 = System.currentTimeMillis();
        System.out.printf("size+get+delete+get+put rate = %f /s\n", (double)lim / (t2 - t1) * 1000);
        ((RAMStorage)tree.storage).clear();
    }

    @Test
    public void storageSize() throws Exception {
        SigningPrivateKeyAndPublicHash user = createUser();
        MerkleBTree tree = createTree(user).get();
        int keylen = 32;

        Random r = new Random(1);
        SortedSet<ByteArrayWrapper> keys = new TreeSet<>();
        // make a 3 node tree
        int lim = 22;
        for (int i = 0; i < lim; i++) {
            if (i % (lim/10) == 0)
                System.out.println((10*i/lim)+"0 % of building");
            byte[] key1 = toLittleEndian(i);
            keys.add(new ByteArrayWrapper(key1));
            byte[] value1Raw = new byte[keylen];
            r.nextBytes(value1Raw);
            Multihash value1 = hash(value1Raw);
            tree.put(user, key1, MaybeMultihash.empty(), value1).get();

            MaybeMultihash res1 = tree.get(key1).get();
            if (! res1.get().equals(value1))
                throw new IllegalStateException("Results not equal");
        }

        int size = ((RAMStorage)tree.storage).size();
        if (size != 31)
            throw new IllegalStateException("Storage size != 3");

        long t1 = System.currentTimeMillis();
        for (int i = 16; i < 22; i++) {
            byte[] key = toLittleEndian(i);
            MaybeMultihash value = tree.get(key).get();
            if (! value.isPresent())
                throw new IllegalStateException("Key not present!");
            tree.delete(user, key, value).get();
            if (tree.get(key).get().isPresent())
                throw new IllegalStateException("Key still present!");
        }
        long t2 = System.currentTimeMillis();
        System.out.printf("size+get+delete+get+put rate = %f /s\n", (double)lim / (t2 - t1) * 1000);
        ((RAMStorage)tree.storage).clear();
    }

    private static byte[] toLittleEndian(int x) {
        byte[] res = new byte[4];
        for (int i=0; i < 4; i++)
            res[i] = (byte) (x >> 8*i);
        return res;
    }
}
