package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
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
        BlockMetadata meta = new BlockMetadata(10240, randomCids(20), Collections.emptyList());
        store.put(cid, "alpha", meta);

        // add same cid again
        store.put(cid, "beta", meta);
        Cid cid2 = randomCid();
        BlockMetadata meta2 = new BlockMetadata(10240, randomCids(20),
                List.of(BatId.inline(Bat.random(crypto.random)), BatId.inline(Bat.random(crypto.random))));
        store.put(cid2, "gammaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", meta2);

        List<BlockVersion> ls = store.list().collect(Collectors.toList());
        Assert.assertTrue(ls.size() == 2);

        long size = store.size();
        Assert.assertTrue(size == 2);

        // null versions
        Cid cid3 = randomCid();
        BlockMetadata meta3 = new BlockMetadata(10240, randomCids(20),
                List.of(BatId.inline(Bat.random(crypto.random)), BatId.inline(Bat.random(crypto.random))));
        store.put(cid3, null, meta3);
        Assert.assertTrue(store.list().filter(v -> v.cid.equals(cid3)).findFirst().get().version == null);
    }
}
