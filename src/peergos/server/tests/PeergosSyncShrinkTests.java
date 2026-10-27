package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.sync.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** A file shrunk in place by more than a chunk failed to sync to the other clients: the diff included chunks past the
 *  new end, which reading from the source failed on.
 */
public class PeergosSyncShrinkTests {
    private static final Args args = UserTests.useMemoryDbs(UserTests.buildArgs()).with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();
    private final Random random = new Random();

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    private UserContext signIn(String username) {
        NetworkAccess network = NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher, Arrays.asList("peergos"), false);
        return PeergosNetworkUtils.ensureSignedUp(username, "password", network, crypto);
    }

    private static void sync(LocalFileSystem local, PeergosSyncFS remote, SyncState state) throws Exception {
        DirectorySync.syncDir(local, remote, true, true, null, null, state, 32, 5,
                Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
    }

    @Test
    public void shrinkByManyChunks() throws Exception {
        shrink(chunk -> 3L * chunk + 12345);
    }

    @Test
    public void shrinkPastOneChunkBoundary() throws Exception {
        shrink(chunk -> 6L * chunk + 12345);
    }

    @Test
    public void shrinkByLessThanAChunk() throws Exception {
        shrink(chunk -> 7L * chunk + 1234);
    }

    @Test
    public void shrinkToAChunkBoundary() throws Exception {
        shrink(chunk -> 5L * chunk);
    }

    @Test
    public void shrinkToOneChunk() throws Exception {
        shrink(chunk -> (long) chunk);
    }

    @Test
    public void shrinkToEmpty() throws Exception {
        shrink(chunk -> 0L);
    }

    private void shrink(Function<Integer, Long> newSize) throws Exception {
        UserContext context = signIn("shr" + Math.abs(random.nextInt() % 1_000_000));
        context.getUserRoot().join().mkdir("sync", context.network, false, context.mirrorBatId(), crypto).join();
        Path remoteRoot = PathUtil.get(context.username, "sync");
        LinkProperties link = context.createSecretLink(remoteRoot.toString(), true, Optional.empty(), Optional.<Integer>empty(), "", false).join();
        PeergosSyncFS remote1 = DirectorySync.buildRemote(link.toLinkString(context.signer.publicKeyHash), context.network, crypto);
        PeergosSyncFS remote2 = DirectorySync.buildRemote(link.toLinkString(context.signer.publicKeyHash), context.network, crypto);
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");
        LocalFileSystem local1 = new LocalFileSystem(base1, crypto.hasher);
        LocalFileSystem local2 = new LocalFileSystem(base2, crypto.hasher);
        SyncState state1 = new JdbcTreeState(":memory:");
        SyncState state2 = new JdbcTreeState(":memory:");

        String name = "shrink-test.bin";
        byte[] data = new byte[8 * Chunk.LEGACY_SIZE + 4321];
        random.nextBytes(data);
        Files.write(base1.resolve(name), data);
        sync(local1, remote1, state1);
        sync(local2, remote2, state2);
        Assert.assertArrayEquals(data, Files.readAllBytes(base2.resolve(name)));

        int chunkSize = state2.byPath(name).chunkSize;
        long size = newSize.apply(chunkSize);
        try (RandomAccessFile raf = new RandomAccessFile(base2.resolve(name).toFile(), "rw")) {
            raf.setLength(size);
        }
        byte[] shrunk = Arrays.copyOfRange(data, 0, (int) size);
        sync(local2, remote2, state2);
        FileWrapper remoteFile = context.getByPath(remoteRoot.resolve(name)).join().get();
        Assert.assertEquals(size, remoteFile.getSize());
        Assert.assertArrayEquals(shrunk, Serialize.readFully(remoteFile.getInputStream(context.network, crypto, x -> {}).join(), size).join());

        sync(local1, remote1, state1);
        Assert.assertArrayEquals(shrunk, Files.readAllBytes(base1.resolve(name)));
        try (var children = Files.list(base1)) {
            Assert.assertEquals(List.of(name), children.map(p -> p.getFileName().toString())
                    .filter(n -> ! n.startsWith(".")).toList());
        }
        // a second pass of each finds nothing left to do, rather than failing again
        sync(local1, remote1, state1);
        sync(local2, remote2, state2);
        Assert.assertArrayEquals(shrunk, Files.readAllBytes(base1.resolve(name)));
        Assert.assertArrayEquals(shrunk, Files.readAllBytes(base2.resolve(name)));
    }
}
