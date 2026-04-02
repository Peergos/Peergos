package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.Sqlite;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.*;

public class FileBlockBufferTests {

    /**
     * Regression test for the startup-flush bug where FileBlockBuffer.applyToAll() silently
     * skipped all user-partitioned blocks (root/username/shard/HASH.data).
     *
     * The old implementation delegated to FileContentAddressedStorage.getFilesRecursive(),
     * which expected paths with PublicKeyHash components (root/PublicKeyHash/shard/HASH.data).
     * For the username-based layout it computed path.relativize(root) = "../..", giving
     * nameCount=2, so owner was always set to null.  The startup flush then called
     * blockBuffer.get(null, cid), which looked for the file at the legacy path
     * root/shard/HASH.data, found nothing, and silently skipped the block.
     *
     * After the fix, applyToAll() resolves each top-level directory name as a username via
     * usage.getOwnerKey(username), yielding the correct PublicKeyHash owner.
     */
    @Test
    public void applyToAllFindsUserPartitionedBlocks() throws Exception {
        Crypto crypto = Main.initCrypto();

        // Set up an in-memory usage store with a registered user and owner key.
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcUsageStore usageStore = new JdbcUsageStore(() -> db, new SqliteCommands());
        String username = "alice";
        usageStore.addUserIfAbsent(username);
        PublicKeyHash ownerKey = new PublicKeyHash(
                Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));
        usageStore.addWriter(username, ownerKey);

        Path tmpDir = Files.createTempDirectory("peergos-block-buffer-test");
        try {
            FileBlockBuffer buffer = new FileBlockBuffer(tmpDir, usageStore);

            // Write a dag-cbor block into the buffer.
            byte[] data = "test dag-cbor block content".getBytes();
            Multihash hash = new Multihash(Multihash.Type.sha2_256, crypto.hasher.sha256(data).join());
            Cid cid = new Cid(1, Cid.Codec.DagCbor, hash.type, hash.getHash());

            buffer.put(ownerKey, cid, data).join();

            // applyToAll simulates what the startup flush does to re-populate blocksToFlush.
            List<Pair<PublicKeyHash, Cid>> found = new ArrayList<>();
            buffer.applyToAll((owner, c) -> found.add(new Pair<>(owner, c)));

            Assert.assertEquals("applyToAll should find exactly one block", 1, found.size());

            // Before the fix this was null; after the fix it is ownerKey.
            Assert.assertEquals(
                    "applyToAll must return the correct owner PublicKeyHash, not null",
                    ownerKey, found.get(0).left);
            Assert.assertEquals(cid, found.get(0).right);

            // Verify the block is actually retrievable using the owner returned by applyToAll.
            // Before the fix, found.get(0).left was null so get() used the legacy path and
            // returned Optional.empty(), meaning the startup flush would skip the block entirely.
            Optional<byte[]> retrieved = buffer.get(found.get(0).left, found.get(0).right).join();
            Assert.assertTrue(
                    "Block must be retrievable via the owner returned by applyToAll",
                    retrieved.isPresent());
            Assert.assertArrayEquals(data, retrieved.get());
        } finally {
            try (Stream<Path> paths = Files.walk(tmpDir)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }
}
