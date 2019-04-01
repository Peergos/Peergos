package peergos.server.tests;

import org.junit.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.function.*;

public class ChampTests {

    private static final Crypto crypto = Crypto.initJava();

    @Test
    public void insertAndRetrieve() throws Exception {
        ContentAddressedStorage storage = new FileContentAddressedStorage(Files.createTempDirectory("peergos-tmp"));
        SigningPrivateKeyAndPublicHash user = createUser(storage, crypto);
        Random r = new Random(28);

        Supplier<Multihash> randomHash = () -> {
            byte[] hash = new byte[32];
            r.nextBytes(hash);
            return new Multihash(Multihash.Type.sha2_256, hash);
        };

        Map<ByteArrayWrapper, MaybeMultihash> state = new HashMap<>();

        Champ current = Champ.empty();
        TransactionId tid = storage.startTransaction(user.publicKeyHash).get();
        Multihash currentHash = storage.put(user.publicKeyHash, user, current.serialize(), tid).get();
        int bitWidth = 5;
        int maxCollisions = 3;
        // build a random tree and keep track of the state
        int nKeys = 1000;
        for (int i = 0; i < nKeys; i++) {
            ByteArrayWrapper key = new ByteArrayWrapper(randomHash.get().toBytes());
            Multihash value = randomHash.get();
            Pair<Champ, Multihash> updated = current.put(user.publicKeyHash, user, key, key.data, 0,
                    MaybeMultihash.empty(), MaybeMultihash.of(value), bitWidth, maxCollisions, x -> x.data, tid, storage, currentHash).get();
            MaybeMultihash result = updated.left.get(key, key.data, 0, bitWidth, storage).get();
            if (! result.equals(MaybeMultihash.of(value)))
                throw new IllegalStateException("Incorrect result!");
            current = updated.left;
            currentHash = updated.right;
            state.put(key, MaybeMultihash.of(value));
        }

        // check every mapping
        for (Map.Entry<ByteArrayWrapper, MaybeMultihash> e : state.entrySet()) {
            MaybeMultihash res = current.get(e.getKey(), e.getKey().data, 0, bitWidth, storage).get();
            if (! res.equals(e.getValue()))
                throw new IllegalStateException("Incorrect state!");
        }

        long size = current.size(0, storage).get();
        if (size != nKeys)
            throw new IllegalStateException("Incorrect number of mappings! " + size);

        // change the value for every key and check
        for (Map.Entry<ByteArrayWrapper, MaybeMultihash> e : state.entrySet()) {
            ByteArrayWrapper key = e.getKey();
            Multihash value = randomHash.get();
            MaybeMultihash currentValue = current.get(e.getKey(), e.getKey().data, 0, bitWidth, storage).get();
            Pair<Champ, Multihash> updated = current.put(user.publicKeyHash, user, key, key.data, 0, currentValue,
                    MaybeMultihash.of(value), bitWidth, maxCollisions, x -> x.data, tid, storage, currentHash).get();
            MaybeMultihash result = updated.left.get(key, key.data, 0, bitWidth, storage).get();
            if (! result.equals(MaybeMultihash.of(value)))
                throw new IllegalStateException("Incorrect result!");
            state.put(key, MaybeMultihash.of(value));
            current = updated.left;
            currentHash = updated.right;
        }

        // remove each key and check the mapping is gone
        for (Map.Entry<ByteArrayWrapper, MaybeMultihash> e : state.entrySet()) {
            ByteArrayWrapper key = e.getKey();
            MaybeMultihash currentValue = current.get(e.getKey(), e.getKey().data, 0, bitWidth, storage).get();
            Pair<Champ, Multihash> updated = current.remove(user.publicKeyHash, user, key, key.data, 0, currentValue,
                    bitWidth, maxCollisions, tid, storage, currentHash).get();
            MaybeMultihash result = updated.left.get(key, key.data, 0, bitWidth, storage).get();
            if (! result.equals(MaybeMultihash.empty()))
                throw new IllegalStateException("Incorrect state!");
        }

        // add a random mapping, then remove it, and check we return to the canonical state
        for (int i = 0; i < 100; i++) {
            ByteArrayWrapper key = new ByteArrayWrapper(randomHash.get().toBytes());
            Multihash value = randomHash.get();
            Pair<Champ, Multihash> updated = current.put(user.publicKeyHash, user, key, key.data, 0, MaybeMultihash.empty(),
                    MaybeMultihash.of(value), bitWidth, maxCollisions, x -> x.data, tid, storage, currentHash).get();
            Pair<Champ, Multihash> removed = updated.left.remove(user.publicKeyHash, user, key, key.data, 0,
                    MaybeMultihash.of(value), bitWidth, maxCollisions, tid, storage, updated.right).get();
            if (! removed.right.equals(currentHash))
                throw new IllegalStateException("Non canonical state!");
        }

        testInsertionOrderIndependence(state, currentHash, bitWidth, maxCollisions, storage, user);
    }

    private void testInsertionOrderIndependence(Map<ByteArrayWrapper, MaybeMultihash> state,
                                                Multihash expectedRoot,
                                                int bitWidth,
                                                int maxCollisions,
                                                ContentAddressedStorage storage,
                                                SigningPrivateKeyAndPublicHash user) {
        ArrayList<Map.Entry<ByteArrayWrapper, MaybeMultihash>> mappings = new ArrayList<>(state.entrySet());
        Random r = new Random();
        int orderings = 1;
        for (int i = 0; i < orderings; i++) {
            Collections.shuffle(mappings, r);
            Champ current = Champ.empty();
            TransactionId tid = storage.startTransaction(user.publicKeyHash).join();
            Multihash currentHash = storage.put(user.publicKeyHash, user, current.serialize(), tid).join();
            for (int k=0; k < mappings.size(); k++) {
                ByteArrayWrapper key = mappings.get(k).getKey();
                Multihash value = mappings.get(k).getValue().get();
                Pair<Champ, Multihash> updated = current.put(user.publicKeyHash, user, key, key.data, 0,
                        MaybeMultihash.empty(), MaybeMultihash.of(value), bitWidth, maxCollisions, x -> x.data, tid, storage, currentHash).join();
                current = updated.left;
                currentHash = updated.right;
            }
            if (! currentHash.equals(expectedRoot))
                throw new IllegalStateException("Champ is not insertion order independent!");
        }
    }

    @Test
    public void canonicalDelete() throws Exception {
        RAMStorage storage = new RAMStorage();
        int bitWidth = 5;
        int maxCollisions = 3;
        SigningPrivateKeyAndPublicHash user = createUser(storage, crypto);
        Random r = new Random(28);

        Supplier<Multihash> randomHash = () -> {
            byte[] hash = new byte[32];
            r.nextBytes(hash);
            return new Multihash(Multihash.Type.sha2_256, hash);
        };
        TransactionId tid = storage.startTransaction(user.publicKeyHash).get();

        for (int prefixLen = 0; prefixLen < 5; prefixLen++)
            for (int i=0; i < 100; i++) {
                int suffixLen = 5;
                int nKeys = r.nextInt(10);
                Pair<Champ, Multihash> root = randomTree(user, r, prefixLen, suffixLen, nKeys, bitWidth, maxCollisions, randomHash, storage);
                byte[] keyBytes = new byte[prefixLen + suffixLen];
                r.nextBytes(keyBytes);
                ByteArrayWrapper key = new ByteArrayWrapper(keyBytes);
                Multihash value = randomHash.get();
                Pair<Champ, Multihash> added = root.left.put(user.publicKeyHash, user, key, keyBytes, 0,
                        MaybeMultihash.empty(), MaybeMultihash.of(value), bitWidth, maxCollisions, x -> x.data, tid, storage, root.right).get();
                Pair<Champ, Multihash> removed = added.left.remove(user.publicKeyHash, user, key, keyBytes, 0,
                        MaybeMultihash.of(value), bitWidth, maxCollisions, tid, storage, added.right).get();
                if (! removed.right.equals(root.right))
                    throw new IllegalStateException("Non canonical delete!");
            }
    }

    @Test
    public void correctDelete() throws Exception {
        ContentAddressedStorage storage = new FileContentAddressedStorage(Files.createTempDirectory("peergos-tmp"));
        SigningPrivateKeyAndPublicHash user = createUser(storage, crypto);
        Random r = new Random(28);

        Supplier<Multihash> randomHash = () -> {
            byte[] hash = new byte[32];
            r.nextBytes(hash);
            return new Multihash(Multihash.Type.sha2_256, hash);
        };

        Map<ByteArrayWrapper, MaybeMultihash> state = new HashMap<>();

        Champ current = Champ.empty();
        TransactionId tid = storage.startTransaction(user.publicKeyHash).get();
        Multihash currentHash = storage.put(user.publicKeyHash, user, current.serialize(), tid).get();
        int bitWidth = 4;
        int maxCollisions = 2;
        // build a random tree and keep track of the state
        for (int i = 0; i < 3; i++) {
            ByteArrayWrapper key = new ByteArrayWrapper(new byte[]{0, (byte)i, 0});
            Multihash value = randomHash.get();
            Pair<Champ, Multihash> updated = current.put(user.publicKeyHash, user, key, key.data, 0,
                    MaybeMultihash.empty(), MaybeMultihash.of(value), bitWidth, maxCollisions, x -> x.data, tid, storage, currentHash).get();
            current = updated.left;
            currentHash = updated.right;
            state.put(key, MaybeMultihash.of(value));
        }

        // check every mapping
        for (Map.Entry<ByteArrayWrapper, MaybeMultihash> e : state.entrySet()) {
            MaybeMultihash res = current.get(e.getKey(), e.getKey().data, 0, bitWidth, storage).get();
            if (! res.equals(e.getValue()))
                throw new IllegalStateException("Incorrect state!");
        }

        long size = current.size(0, storage).get();
        if (size != 3)
            throw new IllegalStateException("Incorrect number of mappings! " + size);

        // delete one entry
        ByteArrayWrapper key = new ByteArrayWrapper(new byte[]{0, 1, 0});
        MaybeMultihash currentValue = current.get(key, key.data, 0, bitWidth, storage).get();
        Pair<Champ, Multihash> updated = current.remove(user.publicKeyHash, user, key, key.data, 0, currentValue,
                bitWidth, maxCollisions, tid, storage, currentHash).get();
        state.remove(key);
        MaybeMultihash result = updated.left.get(key, key.data, 0, bitWidth, storage).get();
        if (! result.equals(MaybeMultihash.empty()))
            throw new IllegalStateException("Incorrect state!");

        // check every mapping
        for (Map.Entry<ByteArrayWrapper, MaybeMultihash> e : state.entrySet()) {
            MaybeMultihash res = current.get(e.getKey(), e.getKey().data, 0, bitWidth, storage).get();
            if (! res.equals(e.getValue()))
                throw new IllegalStateException("Incorrect state!");
        }
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
                                                     int bitWidth,
                                                     int maxCollisions,
                                                     Supplier<Multihash> randomHash,
                                                     RAMStorage storage) throws Exception {
        Champ current = Champ.empty();
        TransactionId tid = storage.startTransaction(user.publicKeyHash).get();
        Multihash currentHash = storage.put(user.publicKeyHash, user, current.serialize(), tid).get();
        // build a random tree and keep track of the state
        byte[] prefix = new byte[prefixLen];
        r.nextBytes(prefix);
        for (int i = 0; i < nKeys; i++) {
            ByteArrayWrapper key = new ByteArrayWrapper(randomKey(prefix, suffixLen, r));
            Multihash value = randomHash.get();
            Pair<Champ, Multihash> updated = current.put(user.publicKeyHash, user, key, key.data, 0, MaybeMultihash.empty(), MaybeMultihash.of(value),
                    bitWidth, maxCollisions, x -> x.data, tid, storage, currentHash).get();
            current = updated.left;
            currentHash = updated.right;
        }
        return new Pair<>(current, currentHash);
    }

    public static SigningPrivateKeyAndPublicHash createUser(ContentAddressedStorage storage, Crypto crypto) {
        SigningKeyPair random = SigningKeyPair.random(crypto.random, crypto.signer);
        try {
            PublicKeyHash ownerHash = ContentAddressedStorage.hashKey(random.publicSigningKey);
            TransactionId tid = storage.startTransaction(ownerHash).get();
            PublicKeyHash publicHash = storage.putSigningKey(
                    random.secretSigningKey.signatureOnly(random.publicSigningKey.serialize()),
                    ownerHash,
                    random.publicSigningKey, tid).get();
            return new SigningPrivateKeyAndPublicHash(publicHash, random.secretSigningKey);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
