package peergos.server.tests.slow;

import org.junit.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class GCBenchmark {
    private static final Crypto crypto = Main.initCrypto();
    private static final Random r = new Random(28);

    @Test
    public void millionObjects() throws IOException {
        DeletableContentAddressedStorage storage = new FileContentAddressedStorage(Files.createTempDirectory("peergos-tmp" + System.currentTimeMillis()),
                JdbcTransactionStore.build(Main.buildEphemeralSqlite(), new SqliteCommands()), (a, b, c, d) -> Futures.of(true), crypto.hasher);
        JdbcIpnsAndSocial pointers = new JdbcIpnsAndSocial(Main.buildEphemeralSqlite(), new SqliteCommands());
        UsageStore usage = new JdbcUsageStore(Main.buildEphemeralSqlite(), new SqliteCommands());

        int nLeavesPerUser = 1_000;
        int nPointers = 200;
        for (int i=0; i < nPointers; i++) {
            SigningKeyPair pair = SigningKeyPair.random(crypto.random, crypto.signer);
            PublicKeyHash owner = ContentAddressedStorage.hashKey(pair.publicSigningKey);
            TransactionId tid = storage.startTransaction(owner).join();
            Multihash root = generateTree(r, owner, storage, nLeavesPerUser, tid);
            PointerUpdate cas = new PointerUpdate(MaybeMultihash.empty(), MaybeMultihash.of(root), Optional.of(Long.valueOf(i)));
            pointers.setPointer(owner, Optional.empty(), pair.signMessage(cas.serialize()).join()).join();
            generateTree(r, owner, storage, nLeavesPerUser/2, tid); // garbage tree
            storage.closeTransaction(owner, tid).join();
        }

        GarbageCollector.collect(storage, pointers, usage, Paths.get("reachability.sql"), s -> Futures.of(true),
                new RamBlockMetadataStore(), (d, c) -> Futures.of(true), false);
    }

    private static Multihash generateTree(Random r, PublicKeyHash owner, ContentAddressedStorage storage, int nLeaves, TransactionId tid) {
        List<Multihash> leaves = new ArrayList<>();
        for (int i=0; i < nLeaves; i++) {
            byte[] leaf = (r.nextInt() + "block-" + i).getBytes();
            leaves.add(storage.put(owner, null, null, leaf, tid).join());
        }
        List<Multihash> internal = generateSublayer(r, owner, storage, leaves, tid);
        while (internal.size() > 1)
            internal = generateSublayer(r, owner, storage, internal, tid);
        return internal.get(0);
    }

    private static List<Multihash> generateSublayer(Random r, PublicKeyHash owner, ContentAddressedStorage storage, List<Multihash> childLayer, TransactionId tid) {
        List<Multihash> nodes = new ArrayList<>();
        int branchRatio = 4;
        for (int i=0; i < childLayer.size(); i+=branchRatio) {
            Map<String, Cborable> links = childLayer.subList(i, Math.min(i + branchRatio, childLayer.size()))
                    .stream()
                    .collect(Collectors.toMap(Multihash::toString, CborObject.CborMerkleLink::new));
            byte[] block = CborObject.CborMap.build(links).serialize();
            nodes.add(storage.put(owner, null, null, block, tid).join());
        }
        return nodes;
    }
}
