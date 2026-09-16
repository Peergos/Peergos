package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.Sqlite;
import peergos.shared.Crypto;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.TransactionId;
import peergos.shared.util.Futures;
import org.peergos.*;

import java.nio.file.*;
import java.sql.Connection;
import java.util.concurrent.CompletableFuture;
import java.util.*;

/** getLinks has to say when a block is absent rather than return an empty list, because the gc cannot
 *  otherwise tell an unreadable node from a leaf. Callers that legitimately walk incomplete stores -
 *  partitioning a legacy blockstore - handle that signal instead of being silently misled.
 */
public class FileStorageLinksTests {

    private static final Crypto crypto = Main.initCrypto();

    private static FileContentAddressedStorage build(Path dir) throws Exception {
        Connection conn = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        SqlSupplier cmds = new SqliteCommands();
        Cid ourId = Cid.buildCidV1(Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32]);
        FileContentAddressedStorage storage = new FileContentAddressedStorage(dir, ourId,
                JdbcTransactionStore.build(() -> conn, cmds),
                (c, b, s, auth) -> Futures.of(true),
                PartitionStatus.DONE, crypto.hasher);
        // blocks are stored under the owner's username, which is resolved through the pki
        storage.setPki(new RamPki() {
            @Override
            public CompletableFuture<String> getUsername(PublicKeyHash owner) {
                return Futures.of("user");
            }
        });
        return storage;
    }

    @Test
    public void getLinksReportsAnAbsentBlock() throws Exception {
        FileContentAddressedStorage storage = build(Files.createTempDirectory("file-storage-links"));
        PublicKeyHash owner = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.sha2_256, new byte[32]));
        Cid neverStored = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, Hash.sha256("absent".getBytes()));

        try {
            storage.getLinks(owner, neverStored, List.of()).join();
            Assert.fail("an absent block must not look like a leaf");
        } catch (Exception e) {
            Throwable cause = e instanceof BlockAbsentException ? e : e.getCause();
            Assert.assertTrue("absence is signalled distinctly: " + cause, cause instanceof BlockAbsentException);
        }
    }

    @Test
    public void getLinksStillReadsAStoredBlock() throws Exception {
        Path dir = Files.createTempDirectory("file-storage-links");
        FileContentAddressedStorage storage = build(dir);
        PublicKeyHash owner = new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.sha2_256, new byte[32]));
        TransactionId tid = storage.startTransaction(owner).join();

        Cid leaf = storage.putRaw(owner, owner, List.of(new byte[0]), List.of("leaf".getBytes()), tid, x -> {})
                .join().get(0);
        byte[] rootBlock = new CborObject.CborList(List.of(new CborObject.CborMerkleLink(leaf))).serialize();
        Cid root = storage.put(owner, owner, List.of(new byte[0]), List.of(rootBlock), tid).join().get(0);

        Assert.assertEquals(List.of(leaf), storage.getLinks(owner, root, List.of()).join());
        // a raw block has no links, which is a leaf rather than an absence
        Assert.assertEquals(Collections.emptyList(), storage.getLinks(owner, leaf, List.of()).join());
    }
}
