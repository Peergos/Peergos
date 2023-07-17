package peergos.server.tests;

import org.junit.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
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
        SqliteBlockMetadataStorage store = new SqliteBlockMetadataStorage(() -> instance, new SqliteCommands(), 1024 * 1024, storeFile);
        long initialSize = store.currentSize();
        assertTrue(initialSize == 16384);
        Cid cid = randomCid();
        BlockMetadata meta = new BlockMetadata(10240, randomCids(20));
        store.put(cid, meta);
        long sizeWithBlock = store.currentSize();
        store.ensureWithinSize();

        // add same cid again
        store.put(cid, meta);
    }

    @Test
    public void compaction() throws Exception {
        Path dir = Files.createTempDirectory("peergos-block-metadata");
        File storeFile = dir.resolve("metadata.sql" + System.currentTimeMillis()).toFile();
        String sqlFilePath = storeFile.getPath();
        Connection memory = Sqlite.build(sqlFilePath);
        Connection instance = new Sqlite.UncloseableConnection(memory);
        int maxFileSize = 1024 * 1024;
        SqliteBlockMetadataStorage store = new SqliteBlockMetadataStorage(() -> instance, new SqliteCommands(), maxFileSize, storeFile);
        long initialSize = store.currentSize();
        assertTrue(initialSize == 16384);
        for (int i=0; i < 1500; i++)
            store.put(randomCid(), new BlockMetadata(10240, randomCids(20)));
        long sizeWithBlocks = store.currentSize();
        assertTrue(sizeWithBlocks > maxFileSize);
        store.ensureWithinSize();
        long sizeAfterCompaction = store.currentSize();
        assertTrue(sizeAfterCompaction < 0.6 * maxFileSize);
    }
}
