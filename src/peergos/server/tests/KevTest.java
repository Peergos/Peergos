package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import peergos.server.Main;
import peergos.server.UserService;
import peergos.server.util.Args;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.social.FollowRequestWithCipherText;
import peergos.shared.storage.CachingStorage;
import peergos.shared.user.*;
import peergos.shared.user.fs.AbsoluteCapability;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileProperties;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.PathUtil;

import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static peergos.server.tests.PeergosNetworkUtils.ensureSignedUp;

@RunWith(Parameterized.class)
public class KevTest {

    private static Args args = UserTests.buildArgs()
            .with("useIPFS", "false")
            .with("default-quota", Long.toString(2 * 1024 * 1024));

    private static int RANDOM_SEED = 666;
    private final UserService service;
    private final NetworkAccess network1;
    private final NetworkAccess network2;
    private static final Crypto crypto = Main.initCrypto();

    private static Random random = new Random(RANDOM_SEED);

    public KevTest(Args args) throws Exception {
        service = Main.PKI_INIT.main(args);

        this.network1 = peergos.shared.NetworkAccess.buildJSKev(
                new URL("http://localhost:" + args.getInt("port"))
        ).join();
        this.network2 = peergos.shared.NetworkAccess.buildJSKev(
                new URL("http://localhost:" + args.getInt("port"))
        ).join();
/*
        WriteSynchronizer synchronizer = new WriteSynchronizer(service.mutable, service.storage, crypto.hasher);
        MutableTree mutableTree = new MutableTreeImpl(service.mutable, service.storage, crypto.hasher, synchronizer);
        this.network1 = new NetworkAccess(service.coreNode, service.account, service.social, new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, Optional.empty(), service.mutable, mutableTree, synchronizer, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);

        WriteSynchronizer synchronizer2 = new WriteSynchronizer(service.mutable, service.storage, crypto.hasher);
        MutableTree mutableTree2 = new MutableTreeImpl(service.mutable, service.storage, crypto.hasher, synchronizer2);
        this.network2 = new NetworkAccess(service.coreNode, service.account, service.social, new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, Optional.empty(), service.mutable, mutableTree2, synchronizer2, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
*/
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][]{
                {args}
        });
    }

    public static String generateUsername(Random random) {
        return "username-" + Math.abs(random.nextInt() % 1_000_000_000);
    }

    public static String generatePassword() {
        return ArrayOps.bytesToHex(crypto.random.randomBytes(32));
    }

    @Test
    public void kevTest()  {
        NetworkAccess sharerNode = network1;
        NetworkAccess shareeNode = network1;
        int shareeCount = 1;
        Random random = new Random();
        //sign up a user on sharerNode

        String sharerUsername = generateUsername(random);
        String sharerPassword = generatePassword();
        UserContext sharerUser = ensureSignedUp(sharerUsername, sharerPassword, sharerNode.clear(), crypto);

        //sign up some users on shareeNode
        List<String> shareePasswords = IntStream.range(0, shareeCount)
                .mapToObj(i -> generatePassword())
                .collect(Collectors.toList());
        List<UserContext> shareeUsers = getUserContextsForNode(shareeNode, random, shareeCount, shareePasswords);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharerUser), shareeUsers);

        // upload a file to "a"'s space
        FileWrapper u1Root = sharerUser.getUserRoot().join();
        String filename = "somefile.txt";
        byte[] originalFileContents = "Hello!".getBytes();
        AsyncReader reader = AsyncReader.build(originalFileContents);
        FileWrapper uploaded = u1Root.uploadOrReplaceFile(filename, reader, originalFileContents.length,
                sharerUser.network, crypto, l -> {}).join();

        // share the file from sharer to each of the sharees
        String filePath = sharerUser.username + "/" + filename;
        FileWrapper u1File = sharerUser.getByPath(filePath).join().get();
        sharerUser.shareWriteAccessWith(PathUtil.get(sharerUser.username, filename), shareeUsers.stream().map(u -> u.username).collect(Collectors.toSet())).join();

        // check other users can read the file
        UserContext shareeUser = shareeUsers.get(0);
        FileWrapper shareeFile = shareeUser.getByPath(filePath).join().get();
        String shareeContents = new String(readFile(shareeUser, shareeFile).join());

        FileWrapper sharerFile = sharerUser.getByPath(filePath).join().get();
        String sharerContents = new String(readFile(sharerUser, sharerFile).join());
        Assert.assertTrue("Contents match", shareeContents.equals(sharerContents));

        try {
            //sharee modifies file
            Snapshot priorShareeVersion = shareeFile.version;
            shareeFile = saveFile(shareeContents + "-sharee", shareeUser, shareeFile).join();
            Snapshot postShareeVersion = shareeFile.version;
            shareeContents = new String(readFile(shareeUser, shareeFile).join());
            //sharer should get a CAS exception when modifying file
            Snapshot priorSharerVersion = sharerFile.version;
            Assert.assertTrue(priorSharerVersion.equals(priorShareeVersion));
            sharerFile = saveFile(sharerContents + "-sharer", sharerUser, sharerFile).join();
            Snapshot postSharerVersion = sharerFile.version;
            Assert.assertTrue("Should not have made it here", false);
        } catch (Throwable ex) {
            String msg = URLDecoder.decode(ex.getMessage(), StandardCharsets.UTF_8);
            boolean isExpected = msg.contains("CAS exception updating cryptree node.")
                    ||   msg.contains("Mutable pointer update failed! Concurrent Modification.");
            Assert.assertTrue("Was expecting CAS exception", isExpected);
        }
    }
    private CompletableFuture<FileWrapper> saveFile(String contents, UserContext context, FileWrapper file) throws Throwable {
        byte[] bytes = contents.getBytes();
        AsyncReader reader = peergos.shared.user.fs.AsyncReader.build(bytes);
        return file.overwriteFileJS(reader, 0, bytes.length, context.network, context.crypto, len -> {});
    }
    private CompletableFuture<byte[]> readFile(UserContext context, FileWrapper file) {
        FileProperties props = file.getFileProperties();
        return file.getInputStream(context.network, context.crypto, props.sizeHigh(), props.sizeLow(), read -> {})
	        .thenCompose(reader -> {
            byte[] data =new byte[(int)props.size];
            return reader.readIntoArray(data, 0, data.length).thenApply(read -> data);
        });
    }
    public static void friendBetweenGroups(List<UserContext> a, List<UserContext> b) {
        for (UserContext userA : a) {
            for (UserContext userB : b) {
                // send initial request
                userA.sendFollowRequest(userB.username, SymmetricKey.random()).join();

                // make sharer reciprocate all the follow requests
                List<FollowRequestWithCipherText> sharerRequests = userB.processFollowRequests().join();
                for (FollowRequestWithCipherText u1Request : sharerRequests) {
                    AbsoluteCapability pointer = u1Request.req.entry.get().pointer;
                    Assert.assertTrue("Read only capabilities are shared", ! pointer.wBaseKey.isPresent());
                    boolean accept = true;
                    boolean reciprocate = true;
                    userB.sendReplyFollowRequest(u1Request, accept, reciprocate).join();
                }

                // complete the friendship connection
                userA.processFollowRequests().join();
            }
        }
    }

    public static List<UserContext> getUserContextsForNode(NetworkAccess network, Random random, int size, List<String> passwords) {
        return IntStream.range(0, size)
                .mapToObj(e -> {
                    String username = generateUsername(random);
                    String password = passwords.get(e);
                    try {
                        return ensureSignedUp(username, password, network.clear(), crypto);
                    } catch (Exception ioe) {
                        throw new IllegalStateException(ioe);
                    }
                }).collect(Collectors.toList());
    }
}

