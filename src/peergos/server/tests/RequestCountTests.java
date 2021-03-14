package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;

import java.nio.file.*;
import java.util.*;

import static peergos.server.tests.PeergosNetworkUtils.*;

public class RequestCountTests {

    private static final Crypto crypto = Main.initCrypto();
    private static Args args = UserTests.buildArgs();
    private static UserService service;
    private Random random = new Random();
    private final NetworkAccess network;
    private final RequestCountingStorage storageCounter;

    public RequestCountTests() {
        WriteSynchronizer synchronizer = new WriteSynchronizer(service.mutable, service.storage, crypto.hasher);
        MutableTree mutableTree = new MutableTreeImpl(service.mutable, service.storage, crypto.hasher, synchronizer);
        RequestCountingStorage requestCounter = new RequestCountingStorage(service.storage);
        this.storageCounter = requestCounter;
        CachingVerifyingStorage dhtClient = new CachingVerifyingStorage(requestCounter, 50 * 1024, 1_000, crypto.hasher);
        this.network = new NetworkAccess(service.coreNode, service.social, dhtClient,
                service.mutable, mutableTree, synchronizer, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args);
    }

    @Test
    public void socialFeedRequestCount() {
        CryptreeNode.setMaxChildLinkPerBlob(10);
        String password = "notagoodone";

        storageCounter.reset();
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);
        Assert.assertTrue("signup request count", storageCounter.requestTotal() <= 30);

        storageCounter.reset();
        PeergosNetworkUtils.ensureSignedUp(sharer.username, password, network, crypto);
        Assert.assertTrue("login request count", storageCounter.requestTotal() <= 5);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 1, Arrays.asList(password, password));
        UserContext a = shareeUsers.get(0);

        // friend sharer with other user
        storageCounter.reset();
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);
        Assert.assertTrue(storageCounter.requestTotal() <= 510);

        // friends are now connected
        // share a file from u1 to u2
        byte[] fileData = sharer.crypto.random.randomBytes(1*1024*1024);
        Path file1 = Paths.get(sharer.username, "first-file.txt");
        uploadAndShare(fileData, file1, sharer, a.username);

        // check 'a' can see the shared file in their social feed
        storageCounter.reset();
        SocialFeed feed = a.getSocialFeed().join();
        Assert.assertTrue(storageCounter.requestTotal() <= 85);
        int feedSize = 2;

        storageCounter.reset();
        List<SharedItem> items = feed.getShared(feedSize, feedSize + 1, a.crypto, a.network).join();
        Assert.assertTrue(storageCounter.requestTotal() <= 1);

        storageCounter.reset();
        a.getFiles(items).join();
        Assert.assertTrue(storageCounter.requestTotal() <= 0);

        // share more items
        for (int i=0; i < 5; i++) {
            byte[] data = sharer.crypto.random.randomBytes(1*1024*1024);
            Path file = Paths.get(sharer.username, random.nextInt() + "first-file.txt");
            uploadAndShare(data, file, sharer, a.username);
        }

        storageCounter.reset();
        SocialFeed feed2 = a.getSocialFeed().join().update().join();
        Assert.assertTrue(storageCounter.requestTotal() <= 40);

        storageCounter.reset();
        List<SharedItem> items2 = feed2.getShared(feedSize + 1, feedSize + 6, a.crypto, a.network).join();
        Assert.assertTrue(storageCounter.requestTotal() <= 1);
    }

    private static void uploadAndShare(byte[] data, Path file, UserContext sharer, String sharee) {
        String filename = file.getFileName().toString();
        sharer.getByPath(file.getParent()).join().get()
                .uploadOrReplaceFile(filename, AsyncReader.build(data), data.length,
                        sharer.network, crypto, l -> {}, crypto.random.randomBytes(32)).join();
        sharer.shareReadAccessWith(file, Set.of(sharee)).join();
    }
}
