package peergos.server.tests;

import org.junit.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.function.*;

public class ChampTests {

    private static final Crypto crypto = Crypto.initJava();

    @Test
    public void insertAndRetrieve() throws Exception {
        RAMStorage storage = new RAMStorage();
        SigningPrivateKeyAndPublicHash user = createUser(storage, crypto);
        Random r = new Random(28);

        Supplier<Multihash> randomHash = () -> {
            byte[] hash = new byte[32];
            r.nextBytes(hash);
            return new Multihash(Multihash.Type.sha2_256, hash);
        };

        Map<ByteArrayWrapper, MaybeMultihash> state = new HashMap<>();

        Champ current = Champ.empty();
        Multihash currentHash = storage.put(user, current.serialize()).get();;
        // build a random tree and keep track of the state
        for (int i = 0; i < 1000; i++) {
            ByteArrayWrapper key = new ByteArrayWrapper(randomHash.get().toBytes());
            Multihash value = randomHash.get();
            Pair<Champ, Multihash> updated = current.put(user, key, 0, MaybeMultihash.empty(), value, storage, currentHash).get();
            MaybeMultihash result = updated.left.get(key, 0, storage).get();
            if (! result.equals(MaybeMultihash.of(value)))
                throw new IllegalStateException("Incorrect result!");
            current = updated.left;
            currentHash = updated.right;
            state.put(key, MaybeMultihash.of(value));
        }

        // check every mapping
        for (Map.Entry<ByteArrayWrapper, MaybeMultihash> e : state.entrySet()) {
            MaybeMultihash res = current.get(e.getKey(), 0, storage).get();
            if (! res.equals(e.getValue()))
                throw new IllegalStateException("Incorrect state!");
        }

        // change the value for every key and check
        for (Map.Entry<ByteArrayWrapper, MaybeMultihash> e : state.entrySet()) {
            ByteArrayWrapper key = e.getKey();
            Multihash value = randomHash.get();
            MaybeMultihash currentValue = current.get(e.getKey(), 0, storage).get();
            Pair<Champ, Multihash> updated = current.put(user, key, 0, currentValue, value, storage, currentHash).get();
            MaybeMultihash result = updated.left.get(key, 0, storage).get();
            if (! result.equals(MaybeMultihash.of(value)))
                throw new IllegalStateException("Incorrect result!");
            current = updated.left;
            currentHash = updated.right;
        }

        // remove each key and check the mapping is gone
        for (Map.Entry<ByteArrayWrapper, MaybeMultihash> e : state.entrySet()) {
            ByteArrayWrapper key = e.getKey();
            Pair<Champ, Multihash> updated = current.remove(user, key, 0, storage, currentHash).get();
            MaybeMultihash result = updated.left.get(key, 0, storage).get();
            if (! result.equals(MaybeMultihash.empty()))
                throw new IllegalStateException("Incorrect state!");
        }

        // add a random mapping, then remove it, and check we return to the canonical state
        for (int i = 0; i < 100; i++) {
            ByteArrayWrapper key = new ByteArrayWrapper(randomHash.get().toBytes());
            Multihash value = randomHash.get();
            Pair<Champ, Multihash> updated = current.put(user, key, 0, MaybeMultihash.empty(), value, storage, currentHash).get();
            Pair<Champ, Multihash> removed = updated.left.remove(user, key, 0, storage, updated.right).get();
            if (! removed.right.equals(currentHash))
                throw new IllegalStateException("Non canonical state!");
        }
    }

    @Test
    public void canonicalDelete() throws Exception {
        RAMStorage storage = new RAMStorage();
        SigningPrivateKeyAndPublicHash user = createUser(storage, crypto);
        Random r = new Random(28);

        Supplier<Multihash> randomHash = () -> {
            byte[] hash = new byte[32];
            r.nextBytes(hash);
            return new Multihash(Multihash.Type.sha2_256, hash);
        };

        for (int prefixLen = 0; prefixLen < 5; prefixLen++)
            for (int i=0; i < 100; i++) {
                int suffixLen = 5;
                int nKeys = r.nextInt(10);
                Pair<Champ, Multihash> root = randomTree(user, r, prefixLen, suffixLen, nKeys, randomHash, storage);
                byte[] keyBytes = new byte[prefixLen + suffixLen];
                r.nextBytes(keyBytes);
                ByteArrayWrapper key = new ByteArrayWrapper(keyBytes);
                Pair<Champ, Multihash> added = root.left.put(user, key, 0, MaybeMultihash.empty(), randomHash.get(), storage, root.right).get();
                Pair<Champ, Multihash> removed = added.left.remove(user, key, 0, storage, added.right).get();
                if (! removed.right.equals(root.right))
                    throw new IllegalStateException("Non canonical delete!");
            }
    }

    @Test
    public void sizeComparisonToBtree() throws Exception {
        Random r = new Random(28);

        Supplier<Multihash> randomHash = () -> {
            byte[] hash = new byte[32];
            r.nextBytes(hash);
            return new Multihash(Multihash.Type.sha2_256, hash);
        };

        Map<ByteArrayWrapper, MaybeMultihash> state = new HashMap<>();

        // build a random tree and keep track of the state
        for (int i = 0; i < 1000; i++) {
            ByteArrayWrapper key = new ByteArrayWrapper(randomHash.get().getHash());
            Multihash value = randomHash.get();
            state.put(key, MaybeMultihash.of(value));
        }

        RAMStorage champStorage = new RAMStorage();
        SigningPrivateKeyAndPublicHash champUser = createUser(champStorage, crypto);
        Pair<Champ, Multihash> current = new Pair<>(Champ.empty(), champStorage.put(champUser, Champ.empty().serialize()).get());

        RAMStorage btreeStorage = new RAMStorage();
        SigningPrivateKeyAndPublicHash btreeUser = createUser(champStorage, crypto);
        MerkleBTree btree = MerkleBTree.create(btreeUser, btreeStorage).get();

        for (Map.Entry<ByteArrayWrapper, MaybeMultihash> e : state.entrySet()) {
            current = current.left.put(champUser, e.getKey(), 0, MaybeMultihash.empty(), e.getValue().get(), champStorage, current.right).get();
            btree.put(btreeUser, e.getKey().data, MaybeMultihash.empty(), e.getValue().get()).get();
        }

        int btreeSize = btreeStorage.totalSize();
        int champSize = champStorage.totalSize();
        long btreeUsage = btreeStorage.getRecursiveBlockSize(btree.root.hash.get()).get();
        long champUsage = champStorage.getRecursiveBlockSize(current.right).get();

        System.out.println("Btree used size: " + btreeSize + ", Champ used size: " + champSize
                + ", Btree usage after gc: " + btreeUsage + ", Champ usage after gc: " + champUsage);
    }


    private static byte[] randomKey(byte[] startingWith, int extraBytes, Random r) {
        byte[] suffix = new byte[extraBytes];
        r.nextBytes(suffix);
        byte[] res = new byte[startingWith.length + extraBytes];
        System.arraycopy(startingWith, 0, res, 0, startingWith.length);
        System.arraycopy(suffix, 0, res, startingWith.length, suffix.length);
        return res;
    }

    private static Pair<Champ, Multihash> randomTree(SigningPrivateKeyAndPublicHash user,
                                                     Random r,
                                                     int prefixLen,
                                                     int suffixLen,
                                                     int nKeys,
                                                     Supplier<Multihash> randomHash,
                                                     RAMStorage storage) throws Exception {
        Champ current = Champ.empty();
        Multihash currentHash = storage.put(user, current.serialize()).get();
        // build a random tree and keep track of the state
        byte[] prefix = new byte[prefixLen];
        r.nextBytes(prefix);
        for (int i = 0; i < nKeys; i++) {
            ByteArrayWrapper key = new ByteArrayWrapper(randomKey(prefix, suffixLen, r));
            Multihash value = randomHash.get();
            Pair<Champ, Multihash> updated = current.put(user, key, 0, MaybeMultihash.empty(), value, storage, currentHash).get();
            current = updated.left;
            currentHash = updated.right;
        }
        return new Pair<>(current, currentHash);
    }

    private static SigningPrivateKeyAndPublicHash createUser(RAMStorage storage, Crypto crypto) {
        SigningKeyPair random = SigningKeyPair.random(crypto.random, crypto.signer);
        try {
            PublicKeyHash publicHash = storage.putSigningKey(
                    random.secretSigningKey.signatureOnly(random.publicSigningKey.serialize()),
                    storage.hashKey(random.publicSigningKey),
                    random.publicSigningKey).get();
            return new SigningPrivateKeyAndPublicHash(publicHash, random.secretSigningKey);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
