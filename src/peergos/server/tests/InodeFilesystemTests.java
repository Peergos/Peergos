package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.hamt.*;
import peergos.shared.inode.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class InodeFilesystemTests {

    private static final Crypto crypto = Main.initCrypto();

    public InodeFilesystemTests() {}

    @Test
    public void insertAndRetrieve() throws Exception {
        ContentAddressedStorage storage = new FileContentAddressedStorage(Files.createTempDirectory("peergos-tmp"),
                JdbcTransactionStore.build(Main.buildEphemeralSqlite(), new SqliteCommands()));
        SigningPrivateKeyAndPublicHash user = createUser(storage, crypto);
        Random r = new Random(28);

        Map<String, AbsoluteCapability> state = new HashMap<>();

        TransactionId tid = storage.startTransaction(user.publicKeyHash).get();
        InodeFileSystem current = InodeFileSystem.createEmpty(user.publicKeyHash, user, storage, crypto.hasher, tid).join();

        // build a random tree and keep track of the state
        int nKeys = 1000;
        for (int i = 0; i < nKeys; i++) {
            String path = randomPath(r, 3);
            AbsoluteCapability cap = randomCap(user.publicKeyHash, r);
            current = current.addCap(user.publicKeyHash, user, path, cap, tid).join();
            state.put(path, cap);
        }

        // check every mapping
        for (Map.Entry<String, AbsoluteCapability> e : state.entrySet()) {
            AbsoluteCapability res = current.getByPath(e.getKey()).join().get().left.cap.get();
            if (! res.equals(e.getValue()))
                throw new IllegalStateException("Incorrect state!");
        }
    }

    private static AbsoluteCapability randomCap(PublicKeyHash owner, Random r) {
        byte[] mapKey = new byte[32];
        r.nextBytes(mapKey);
        SymmetricKey readKey = SymmetricKey.random();
        return new AbsoluteCapability(owner, owner, mapKey, readKey);
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
            TransactionId tid = storage.startTransaction(ownerHash).get();
            PublicKeyHash publicHash = storage.putSigningKey(
                    random.secretSigningKey.signMessage(random.publicSigningKey.serialize()),
                    ownerHash,
                    random.publicSigningKey, tid).get();
            return new SigningPrivateKeyAndPublicHash(publicHash, random.secretSigningKey);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
