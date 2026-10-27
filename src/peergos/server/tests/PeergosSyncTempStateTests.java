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

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Each sync pass left its temporary state dbs open, which Windows can't delete, so they filled the disk */
public class PeergosSyncTempStateTests {
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

    private static List<Path> tempStates(Path peergosDir) throws IOException {
        try (Stream<Path> kids = Files.list(peergosDir)) {
            return kids.filter(p -> p.getFileName().toString().startsWith("peergos-sync"))
                    .collect(Collectors.toList());
        }
    }

    /** Linux lets an open file be deleted, which hides the leak there, but its descriptor stays open */
    private static long openTempStates(Path peergosDir) throws IOException {
        Path fds = Paths.get("/proc/self/fd");
        Assume.assumeTrue(Files.isDirectory(fds));
        try (Stream<Path> kids = Files.list(fds)) {
            return kids.map(fd -> {
                        try {
                            return Files.readSymbolicLink(fd).toString();
                        } catch (IOException e) {
                            return "";
                        }
                    })
                    .filter(target -> target.startsWith(peergosDir.resolve("peergos-sync").toString()))
                    .count();
        }
    }

    private byte[] randomBytes() {
        byte[] data = new byte[1024];
        random.nextBytes(data);
        return data;
    }

    @Test
    public void tempStatesAreClosedAndDeleted() throws Exception {
        UserContext context = signIn("tmp" + Math.abs(random.nextInt() % 1_000_000));
        context.getUserRoot().join().mkdir("sync", context.network, false, context.mirrorBatId(), crypto).join();
        Path remoteRoot = PathUtil.get(context.username, "sync");
        LinkProperties link = context.createSecretLink(remoteRoot.toString(), true, Optional.empty(), Optional.<Integer>empty(), "", false).join();
        String linkString = link.toLinkString(context.signer.publicKeyHash);
        Path localDir = Files.createTempDirectory("peergos-sync");
        Path peergosDir = Files.createTempDirectory("peergos-dir");
        // as left by a crash, or by any pass before this fix on Windows
        Files.write(peergosDir.resolve("peergos-sync123456.sqlite"), new byte[]{1});

        Files.write(localDir.resolve("a.bin"), randomBytes());
        for (int pass = 0; pass < 3; pass++) {
            boolean ok = DirectorySync.syncDirs(List.of(linkString), List.of(localDir.toString()), List.of(true), List.of(true),
                    32, 5, true, root -> new LocalFileSystem(Paths.get(root), crypto.hasher), peergosDir,
                    new SyncRunner.StatusHolder(), DirectorySync::log, e -> {}, context.network, crypto);
            Assert.assertTrue(ok);
            Assert.assertEquals(0, openTempStates(peergosDir));
            Assert.assertEquals(List.of(), tempStates(peergosDir));
            // a change on each side, so the next pass scans both into temporary states
            Files.write(localDir.resolve("local" + pass + ".bin"), randomBytes());
            byte[] data = randomBytes();
            context.getByPath(remoteRoot).join().get()
                    .uploadOrReplaceFile("remote" + pass + ".bin", AsyncReader.build(data), data.length, context.network, crypto, () -> false, x -> {}).join();
        }
        Assert.assertTrue(Files.exists(localDir.resolve("remote1.bin")));
        Assert.assertTrue(context.getByPath(remoteRoot.resolve("local1.bin")).join().isPresent());
    }
}
