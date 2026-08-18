package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.storage.auth.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.Assert.assertTrue;

public class SqliteBlockMetadataTest {

    private static final Random r = new Random(666);
    private static Crypto crypto = Main.initCrypto();

    private static Cid randomCid() {
        byte[] hash = new byte[32];
        r.nextBytes(hash);
        return new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
    }

    private static List<Cid> randomCids(int count) {
        byte[] hash = new byte[32];
        r.nextBytes(hash);
        return IntStream.range(0, count).mapToObj(i -> randomCid()).collect(Collectors.toList());
    }

    @Test
    public void basicUsage() throws Exception {
        Path dir = Files.createTempDirectory("peergos-block-metadata");
        File storeFile = dir.resolve("metadata.sql" + System.currentTimeMillis()).toFile();
        String sqlFilePath = storeFile.getPath();
        Connection memory = Sqlite.build(sqlFilePath);
        Connection instance = new Sqlite.UncloseableConnection(memory);
        BlockMetadataStore store = new JdbcBlockMetadataStore(() -> instance, new SqliteCommands());

        Cid cid = randomCid();
        PublicKeyHash owner = new PublicKeyHash(cid);
        BlockMetadata meta = new BlockMetadata(10240, randomCids(20), Collections.emptyList());
        store.put(owner, cid, "alpha", meta);

        // add same cid again
        store.put(owner, cid, "beta", meta);
        Cid cid2 = randomCid();
        BlockMetadata meta2 = new BlockMetadata(10240, randomCids(20),
                List.of(BatId.inline(Bat.random(crypto.random)), BatId.inline(Bat.random(crypto.random))));
        store.put(owner, cid2, "gammaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", meta2);

        List<BlockVersion> ls = store.list(owner).collect(Collectors.toList());
        Assert.assertTrue(ls.size() == 2);

        long size = store.size(owner);
        Assert.assertTrue(size == 2);

        // null versions
        Cid cid3 = randomCid();
        BlockMetadata meta3 = new BlockMetadata(10240, randomCids(20),
                List.of(BatId.inline(Bat.random(crypto.random)), BatId.inline(Bat.random(crypto.random))));
        store.put(owner, cid3, null, meta3);
        Assert.assertTrue(store.list(owner).filter(v -> v.cid.equals(cid3)).findFirst().get().version == null);

        List<Cid> all = new ArrayList<>();
        store.applyToAll(all::add);
        Assert.assertTrue(all.size() == 3);
    }

    @Test
    public void bulkPutMatchesIndividualPuts() throws Exception {
        Path dir = Files.createTempDirectory("peergos-block-metadata-bulk");
        BlockMetadataStore bulkStore = buildStore(dir.resolve("bulk.sql"));
        BlockMetadataStore oneByOneStore = buildStore(dir.resolve("onebyone.sql"));

        Cid ownerCid = randomCid();
        PublicKeyHash owner = new PublicKeyHash(ownerCid);

        // a mix of cbor blocks (which have links) and raw blocks (which have bats)
        List<Cid> cids = new ArrayList<>();
        List<byte[]> blocks = new ArrayList<>();
        for (int i=0; i < 50; i++) {
            if (i % 2 == 0) {
                byte[] block = new CborObject.CborList(randomCids(3).stream()
                        .map(CborObject.CborMerkleLink::new)
                        .collect(Collectors.toList()))
                        .toByteArray();
                cids.add(new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, RAMStorage.hash(block)));
                blocks.add(block);
            } else {
                byte[] block = new byte[1024];
                r.nextBytes(block);
                cids.add(new Cid(1, Cid.Codec.Raw, Multihash.Type.sha2_256, RAMStorage.hash(block)));
                blocks.add(block);
            }
        }

        bulkStore.put(owner, cids, blocks);
        for (int i=0; i < cids.size(); i++)
            oneByOneStore.put(owner, cids.get(i), null, blocks.get(i));

        Assert.assertEquals(cids.size(), bulkStore.size(owner));
        for (Cid cid : cids) {
            BlockMetadata expected = oneByOneStore.get(cid).get();
            BlockMetadata actual = bulkStore.get(cid).get();
            Assert.assertEquals("size of " + cid, expected.size, actual.size);
            Assert.assertEquals("links of " + cid, expected.links, actual.links);
            Assert.assertEquals("bats of " + cid, expected.batids, actual.batids);
            Assert.assertEquals("owner of " + cid, owner, bulkStore.getOwner(cid).get());
        }

        // versions are null on the bulk path, as they are for the individual puts it replaces
        Assert.assertTrue(bulkStore.list(owner).allMatch(v -> v.version == null));

        // an empty batch is a no-op, and the connection is still usable afterwards
        bulkStore.put(owner, Collections.emptyList(), Collections.emptyList());
        Assert.assertEquals(cids.size(), bulkStore.size(owner));
    }

    @Test
    public void concurrentBulkAndIndividualPuts() throws Exception {
        Path dir = Files.createTempDirectory("peergos-block-metadata-concurrent");
        BlockMetadataStore store = buildStore(dir.resolve("concurrent.sql"));
        PublicKeyHash owner = new PublicKeyHash(randomCid());

        // more rows than a single insert statement takes, so the bulk put is split over several statements
        int bulkCount = 2200;
        List<Cid> bulkCids = new ArrayList<>();
        List<byte[]> bulkBlocks = new ArrayList<>();
        for (int i=0; i < bulkCount; i++) {
            byte[] block = new byte[32];
            r.nextBytes(block);
            bulkCids.add(new Cid(1, Cid.Codec.Raw, Multihash.Type.sha2_256, RAMStorage.hash(block)));
            bulkBlocks.add(block);
        }
        List<Cid> singleCids = randomCids(500);
        BlockMetadata singleMeta = new BlockMetadata(1024, Collections.emptyList(), Collections.emptyList());

        // all the threads share a single sqlite connection, so no put may leave it in a transaction
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        Thread bulk = new Thread(() -> {
            try {
                store.put(owner, bulkCids, bulkBlocks);
            } catch (Throwable t) {
                errors.add(t);
            }
        });
        Thread individual = new Thread(() -> {
            try {
                for (Cid cid : singleCids) {
                    store.put(owner, cid, null, singleMeta);
                    store.size(owner);
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        });
        bulk.start();
        individual.start();
        bulk.join();
        individual.join();
        if (! errors.isEmpty())
            throw new RuntimeException(errors.get(0));

        Assert.assertEquals(bulkCount + singleCids.size(), store.size(owner));
    }

    private static BlockMetadataStore buildStore(Path file) throws Exception {
        Connection conn = new Sqlite.UncloseableConnection(Sqlite.build(file.toString()));
        return new JdbcBlockMetadataStore(() -> conn, new SqliteCommands());
    }

    @Ignore
    @Test
    public void pagination() throws Exception {
        Path dir = Files.createTempDirectory("peergos-block-metadata");
        File storeFile = dir.resolve("metadata.sql" + System.currentTimeMillis()).toFile();
        String sqlFilePath = storeFile.getPath();
        Connection memory = Sqlite.build(sqlFilePath);
        Connection instance = new Sqlite.UncloseableConnection(memory);
        BlockMetadataStore store = new JdbcBlockMetadataStore(() -> instance, new SqliteCommands());
        PublicKeyHash owner = new PublicKeyHash(randomCid());

        for (int i=0; i < 5 * JdbcBlockMetadataStore.PAGE_LIMIT/2; i++) {
            Cid cid = randomCid();
            BlockMetadata meta = new BlockMetadata(10240, randomCids(20), Collections.emptyList());
            store.put(owner, cid, "alpha", meta);
        }

        List<Cid> all = new ArrayList<>();
        store.applyToAll(all::add);
        Assert.assertTrue(all.size() == 5 * JdbcBlockMetadataStore.PAGE_LIMIT/2);
        HashSet<Cid> cidSet = new HashSet<>(all);
        Assert.assertTrue(cidSet.size() == 5 * JdbcBlockMetadataStore.PAGE_LIMIT/2);
    }
}
