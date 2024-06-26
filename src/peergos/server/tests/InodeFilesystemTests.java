package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.inode.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class InodeFilesystemTests {

    private static final Crypto crypto = Main.initCrypto();

    public InodeFilesystemTests() {}

    @Test
    public void deleteExample() throws IOException {
        ContentAddressedStorage storage = new FileContentAddressedStorage(Files.createTempDirectory("peergos-tmp"),
                JdbcTransactionStore.build(Main.buildEphemeralSqlite(), new SqliteCommands()), (a, b, c, d) -> Futures.of(true), crypto.hasher);
        SigningPrivateKeyAndPublicHash user = createUser(storage, crypto);
        Random r = new Random(28);

        Map<String, AbsoluteCapability> state = new HashMap<>();

        PublicKeyHash owner = user.publicKeyHash;
        TransactionId tid = storage.startTransaction(owner).join();
        InodeFileSystem current = InodeFileSystem.createEmpty(owner, user, storage, crypto.hasher, tid).join();

        String path1 = "username/webroot";
        AbsoluteCapability cap = randomCap(owner, r);
        current = current.addCap(owner, user, path1, cap, tid).join();
        state.put(path1, cap);

        String profileElement = "username/.profile/webroot";
        AbsoluteCapability cap2 = randomCap(owner, r);
        current = current.addCap(owner, user, profileElement, cap2, tid).join();
        state.put(profileElement, cap2);

        checkAllMappings(state, current);
        Assert.assertTrue(current.inodeCount == 3);

        current = current.removeCap(owner, user, path1, tid).join();
        state.remove(path1);
        checkAllMappings(state, current);

        String p3 = "username/.profile/webroot/somedir";
        AbsoluteCapability cap3 = randomCap(owner, r);
        current = current.addCap(owner, user, p3, cap3, tid).join();
        state.put(p3, cap3);
        checkAllMappings(state, current);
        Assert.assertTrue(current.inodeCount == 4);
    }

    @Test
    public void nameClash() throws Exception {
        ContentAddressedStorage storage = new FileContentAddressedStorage(Files.createTempDirectory("peergos-tmp"),
                JdbcTransactionStore.build(Main.buildEphemeralSqlite(), new SqliteCommands()), (a, b, c, d) -> Futures.of(true), crypto.hasher);
        SigningPrivateKeyAndPublicHash user = createUser(storage, crypto);
        Random r = new Random(28);

        Map<String, AbsoluteCapability> state = new HashMap<>();

        PublicKeyHash owner = user.publicKeyHash;
        TransactionId tid = storage.startTransaction(owner).join();
        InodeFileSystem current = InodeFileSystem.createEmpty(owner, user, storage, crypto.hasher, tid).join();

        String path = randomPath(r, 3);
        AbsoluteCapability cap = randomCap(owner, r);
        current = current.addCap(owner, user, path, cap, tid).join();
        state.put(path, cap);
        checkAllMappings(state, current);

        // Update the mapping to a new cap
        AbsoluteCapability newCap = randomCap(owner, r);
        current = current.addCap(owner, user, path, newCap, tid).join();
        state.put(path, newCap);
        checkAllMappings(state, current);

        // remove the mapping
        InodeFileSystem removed = current.removeCap(owner, user, path, tid).join();
        state.remove(path);
        checkAllMappings(state, removed);
        if (removed.inodeCount != current.inodeCount)
            throw new IllegalStateException("Incorrect inode count!");
    }

    @Test
    public void insertAndRetrieve() throws Exception {
        ContentAddressedStorage storage = new FileContentAddressedStorage(Files.createTempDirectory("peergos-tmp"),
                JdbcTransactionStore.build(Main.buildEphemeralSqlite(), new SqliteCommands()), (a, b, c, d) -> Futures.of(true), crypto.hasher);
        SigningPrivateKeyAndPublicHash user = createUser(storage, crypto);
        Random r = new Random(28);

        Map<String, AbsoluteCapability> state = new HashMap<>();

        PublicKeyHash owner = user.publicKeyHash;
        TransactionId tid = storage.startTransaction(owner).join();
        InodeFileSystem current = InodeFileSystem.createEmpty(owner, user, storage, crypto.hasher, tid).join();

        // build a random tree and keep track of the state
        int nKeys = 1000;
        for (int i = 0; i < nKeys; i++) {
            String path = randomPath(r, 3);
            AbsoluteCapability cap = randomCap(owner, r);
            current = current.addCap(owner, user, path, cap, tid).join();
            state.put(path, cap);
        }

        checkAllMappings(state, current);

        // add and remove a mapping and check result is canonical
        for (int i = 0; i < 100; i++) {
            String path = randomPath(r, 3);
            AbsoluteCapability cap = randomCap(owner, r);
            InodeFileSystem added = current.addCap(owner, user, path, cap, tid).join();
            InodeFileSystem removed = added.removeCap(owner, user, path, tid).join();
            checkAllMappings(state, removed);
            if (! removed.getRoot().equals(current.getRoot()))
                throw new IllegalStateException("Non canonical after delete!");
            if (removed.inodeCount != current.inodeCount + 1)
                throw new IllegalStateException("Incorrect inode count!");
        }

        // add a huge directory
        Set<String> dirContents = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String child = randomPathElement(r);
            dirContents.add(child);
            String path = "user/dir/" + child;
            AbsoluteCapability cap = randomCap(owner, r);
            current = current.addCap(owner, user, path, cap, tid).join();
            state.put(path, cap);
        }
        checkAllMappings(state, current);

        List<InodeCap> dir = current.listDirectory("user/dir").join();
        Assert.assertTrue(dir.size() == 1000);
        Assert.assertTrue(dir.stream().map(i -> i.inode.name.name).collect(Collectors.toSet()).equals(dirContents));
    }

    private static void checkAllMappings(Map<String, AbsoluteCapability> state, InodeFileSystem current) {
        for (Map.Entry<String, AbsoluteCapability> e : state.entrySet()) {
            Pair<InodeCap, String> access = current.getByPath(e.getKey()).join().get();
            AbsoluteCapability res = access.left.cap.get();
            // If a higher privilege cap is published it will be returned with the remaining path
            if (! res.equals(e.getValue()) && access.right.isEmpty())
                throw new IllegalStateException("Incorrect state!");
        }
        Map<String, AbsoluteCapability> allCaps = getAllCaps(current, "/");
        for (String key : allCaps.keySet()) {
            if (! state.containsKey(key.substring(1)))
                throw new IllegalStateException("Unexpected published cap for " + key);
        }
    }

    private static Map<String, AbsoluteCapability> getAllCaps(InodeFileSystem infs, String path) {
        Map<String, AbsoluteCapability> res = new HashMap<>();
        List<InodeCap> children = infs.listDirectory(path).join();
        for (InodeCap child : children) {
            String childPath = path + (path.endsWith("/") ? "" : "/") + child.inode.name.name;
            res.putAll(getAllCaps(infs, childPath));
            Optional<Pair<InodeCap, String>> cap = infs.getByPath(childPath).join();
            if (cap.isPresent() && cap.get().left.cap.isPresent())
                res.put(childPath, cap.get().left.cap.get());
        }
        return res;
    }

    private static AbsoluteCapability randomCap(PublicKeyHash owner, Random r) {
        byte[] mapKey = new byte[32];
        r.nextBytes(mapKey);
        SymmetricKey readKey = SymmetricKey.random();
        return new AbsoluteCapability(owner, owner, mapKey, Optional.of(Bat.random(crypto.random)), readKey);
    }

    private static String randomPath(Random r, int maxDepth) {
        int depth = 2 + r.nextInt(maxDepth - 2);
        return IntStream.range(0, depth)
                .mapToObj(i -> randomPathElement(r))
                .collect(Collectors.joining("/"));
    }

    private static String randomPathElement(Random r) {
        int length = 1 + r.nextInt(255);
        return IntStream.range(0, length)
                .mapToObj(x -> randomChar(r))
                .collect(Collectors.joining());
    }

    private static String[] chars = IntStream.concat(IntStream.range(97, 97 + 26), IntStream.range(48, 58))
            .mapToObj(i -> String.valueOf((char)i))
            .toArray(String[]::new);
    private static String randomChar(Random r) {
        return chars[r.nextInt(chars.length)];
    }

    public static SigningPrivateKeyAndPublicHash createUser(ContentAddressedStorage storage, Crypto crypto) {
        SigningKeyPair random = SigningKeyPair.random(crypto.random, crypto.signer);
        try {
            PublicKeyHash ownerHash = ContentAddressedStorage.hashKey(random.publicSigningKey);
            TransactionId tid = storage.startTransaction(ownerHash).join();
            PublicKeyHash publicHash = storage.putSigningKey(
                    random.secretSigningKey.signMessage(random.publicSigningKey.serialize()).join(),
                    ownerHash,
                    random.publicSigningKey, tid).join();
            return new SigningPrivateKeyAndPublicHash(publicHash, random.secretSigningKey);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
