package peergos.server.tests;

import org.junit.*;
import org.peergos.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;

public class GCTests {



    @Test
    public void correctMarkPhase() throws IOException, SQLException {
        Path dir = Files.createTempDirectory("peergos-block-metadata");
        File storeFile = dir.resolve("metadata.sql" + System.currentTimeMillis()).toFile();
        String sqlFilePath = storeFile.getPath();
        Connection db = Sqlite.build(sqlFilePath);
        Connection instance = new Sqlite.UncloseableConnection(db);
        BlockMetadataStore metadb = new JdbcBlockMetadataStore(() -> instance, new SqliteCommands());

        String filename = "temp.sql";
        Path file = Path.of(filename);
        SqliteBlockReachability reachability = SqliteBlockReachability.createReachabilityDb(file);

        int nUsers = 1;
        int nRawBlocks = 1 << 9;
        ForkJoinPool listPool = Threads.newPool(2, "GC-list-");
        List<ForkJoinTask<Cid>> futs = IntStream.range(0, nUsers)
                .mapToObj(i -> listPool.submit(() -> generateTree(i, nRawBlocks,
                        blocks ->  reachability.addBlocks(blocks.stream().map(c ->  new BlockVersion(c, null, true)).collect(Collectors.toList())),
                        (b, links) -> metadb.put(b, null, new BlockMetadata(0, links, Collections.emptyList()))
                        )))
                .collect(Collectors.toList());
        List<Cid> roots = futs.stream()
                .map(ForkJoinTask::join)
                .collect(Collectors.toList());

        long size = reachability.size();
        Assert.assertTrue(size > 0);

        int markParallelism = 10;
        ForkJoinPool markPool = Threads.newPool(markParallelism, "GC-mark-");
        AtomicLong totalReachable = new AtomicLong(0);
        List<ForkJoinTask<Boolean>> usageMarked = roots.stream()
                .map(r -> markPool.submit(() -> {
                    try {
                        return GarbageCollector.markReachable(null, r,
                                "user-" + r, reachability, metadb, totalReachable);
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                }))
                .collect(Collectors.toList());
        usageMarked.forEach(ForkJoinTask::join);

        List<BlockVersion> garbage = new ArrayList<>();
        reachability.getUnreachable(garbage::addAll);
        Assert.assertTrue(garbage.isEmpty());
    }

    private Cid generateTree(int seed, int nRawBlocksLeft, Consumer<List<Cid>> listConsumer, BiConsumer<Cid, List<Cid>> linksConsumer) {
        Random r = new Random(seed);
        List<Cid> buffer = new ArrayList<>(1000);
        Cid root = generateTree(r, nRawBlocksLeft, buffer, listConsumer, linksConsumer);
        listConsumer.accept(List.of(root));
        listConsumer.accept(buffer);
        System.out.println("Generated tree " + seed);
        return root;
    }

    private Cid generateTree(Random r, int nRawBlocksLeft, List<Cid> buffer, Consumer<List<Cid>> listConsumer, BiConsumer<Cid, List<Cid>> linksConsumer) {
        if (nRawBlocksLeft == 1) {
            Cid leaf = randomRaw(r);
            linksConsumer.accept(leaf, Collections.emptyList());
            return leaf;
        }
        int nLeft = nRawBlocksLeft / 2;
        Cid left = generateTree(r, nLeft, buffer, listConsumer, linksConsumer);
        Cid right = generateTree(r, nRawBlocksLeft - nLeft, buffer, listConsumer, linksConsumer);
        byte[] raw = new CborObject.CborList(List.of(
                new CborObject.CborMerkleLink(left),
                new CborObject.CborMerkleLink(right)
        )).serialize();
        Cid root = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, Hash.sha256(raw));
        linksConsumer.accept(root, List.of(left, right));
        buffer.add(left);
        buffer.add(right);
        if (buffer.size() > 1000) {
            listConsumer.accept(buffer);
            buffer.clear();
        }
        return root;
    }

    private Cid randomRaw(Random r) {
        byte[] hash = new byte[32];
        r.nextBytes(hash);
        return new Cid(1, Cid.Codec.Raw, Multihash.Type.sha2_256, hash);
    }
}
