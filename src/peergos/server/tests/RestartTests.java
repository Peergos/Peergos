package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.social.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.Assert.*;

public class RestartTests {

    private static Args args = UserTests.buildArgs()
            .with("mutable-pointers-file", "mutable.sql")
            .with("social-sql-file", "social.sql")
            .with("useIPFS", "true")
            .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, ""); // no bootstrapping;
    private final NetworkAccess network;
    private static final Crypto crypto = Main.initCrypto();
    private static Process server;

    public RestartTests() throws Exception {
        this.network = Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false).join();
    }

    @BeforeClass
    public static void init() throws Exception {
        Files.copy(PathUtil.get("Peergos.jar"), args.getPeergosDirChild("Peergos.jar"));
        Files.copy(PathUtil.get("lib"), args.getPeergosDirChild("lib"));
        for (Path file : Files.list(PathUtil.get("lib")).collect(Collectors.toList()))
            Files.copy(file, args.getPeergosDirChild("lib").resolve(file.getFileName()));

        server = start("pki-init");
        waitUntilReady();
    }

    @AfterClass
    public static void shutdown() {
        server.destroy();
    }

    private static void restart() throws Exception {
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false)
                .join();
        Multihash pkiNodeId = network.dhtClient.id().join();
        PublicKeyHash peergosId = network.coreNode.getPublicKeyHash("peergos").join().get();
        args = args.setArg("pki-node-id", pkiNodeId.toString());
        args = args.setArg("peergos.identity.hash", peergosId.toString());
        server.destroy();
        server.destroyForcibly();
        server.waitFor();
        try {Thread.sleep(30_000);} catch (InterruptedException e) {}
        server = start("pki");
        waitUntilReady();
    }

    private static void waitUntilReady() {
        for (int i=0; i < 30; i++) {
            try {
                Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false).join();
                return;
            } catch (Exception e) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException f) {}
            }
        }
    }

    public static Process start(String command) throws IOException {
        Stream<String> classPath = Stream.concat(Stream.of("Peergos.jar"),
                Files.list(args.getPeergosDirChild("lib")).map(Path::toString));
        List<String> peergosArgs = Stream.concat(
                Stream.of("java", "-cp", classPath.collect(Collectors.joining(System.getProperty("path.separator"))), "peergos.server.Main", "-" + command),
                args.getAllArgs().stream())
                .collect(Collectors.toList());

        ProcessBuilder pb = new ProcessBuilder(peergosArgs);
        pb.directory(args.getPeergosDir().toFile());
        try {
            Process started = pb.start();
            new Thread(() -> Logging.log(started.getInputStream(),
                    "$(peergos server) out: "), "Peergos output stream").start();
            new Thread(() -> Logging.log(started.getErrorStream(),
                    "$(peergos server) err: "), "Peergos error stream").start();
            return started;
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe.getMessage(), ioe);
        }
    }

    private String random() {
        return ArrayOps.bytesToHex(crypto.random.randomBytes(15));
    }

    @Ignore
    @Test
    public void friendPasswordChange() throws Exception {
        String username1 = random();
        String password1 = random();
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(username1, password1, network, crypto);
        String username2 = random();
        String password2 = random();
        UserContext u2 = PeergosNetworkUtils.ensureSignedUp(username2, password2, network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random()).join();
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().join();
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true).join();
        // complete connection
        List<FollowRequestWithCipherText> u2FollowRequests = u2.processFollowRequests().join();

        // change password for u2
        String password3 = random();
        u2.changePassword(password2, password3, UserTests::noMfa).join();

        // restart the server
        restart();

        UserContext freshU1 = UserContext.signIn(username1, password1, UserTests::noMfa, network.clear(), crypto).join();
        Optional<FileWrapper> u2ToU1 = freshU1.getByPath("/" + u2.username).join();
        assertTrue("Friend root present after their password change", u2ToU1.isPresent());
    }
}
