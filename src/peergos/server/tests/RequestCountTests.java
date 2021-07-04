package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.display.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

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
        Assert.assertTrue("signup request count: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 25);

        storageCounter.reset();
        PeergosNetworkUtils.ensureSignedUp(sharer.username, password, network, crypto);
        Assert.assertTrue("login request count: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 3);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 1, Arrays.asList(password, password));
        UserContext a = shareeUsers.get(0);

        // initialize friend and follower groups
        a.getGroupNameMappings().join();
        // friend sharer with other user
        storageCounter.reset();
        // send initial request
        sharer.sendFollowRequest(a.username, SymmetricKey.random()).join();
        Assert.assertTrue("send initial followrequest: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 25);

        // make sharer reciprocate all the follow requests
        storageCounter.reset();
        List<FollowRequestWithCipherText> sharerRequests = a.processFollowRequests().join();
        Assert.assertTrue("friending 2 users: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 3);
        storageCounter.reset();
        for (FollowRequestWithCipherText u1Request : sharerRequests) {
            AbsoluteCapability pointer = u1Request.req.entry.get().pointer;
            Assert.assertTrue("Read only capabilities are shared", ! pointer.wBaseKey.isPresent());
            boolean accept = true;
            boolean reciprocate = true;
            a.sendReplyFollowRequest(u1Request, accept, reciprocate).join();
        }
        Assert.assertTrue("send reply follow request: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 140);

        // complete the friendship connection
        storageCounter.reset();
        sharer.processFollowRequests().join();
        Assert.assertTrue("friending complete: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 110);

        // friends are now connected
        // share a file from u1 to u2
        byte[] fileData = sharer.crypto.random.randomBytes(1*1024*1024);
        Path file1 = Paths.get(sharer.username, "first-file.txt");
        uploadAndShare(fileData, file1, sharer, a.username);

        // check 'a' can see the shared file in their social feed
        storageCounter.reset();
        SocialFeed feed = a.getSocialFeed().join();
        Assert.assertTrue("initialise social feed: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 170);
        int feedSize = 2;

        storageCounter.reset();
        List<SharedItem> items = feed.getShared(feedSize, feedSize + 1, a.crypto, a.network).join();
        Assert.assertTrue(storageCounter.requestTotal() <= 1);

        storageCounter.reset();
        a.getFiles(items).join();
        Assert.assertTrue(storageCounter.requestTotal() <= 0);

        SocialState social = sharer.getSocialState().join();
        String friends = social.getFriendsGroupUid();
        SocialFeed sharerFeed = sharer.getSocialFeed().join().update().join();
        { // Do an initial post to ensure all directories are created
            List<Text> postBody = Arrays.asList(new Text("Initial post."));
            SocialPost post = SocialPost.createInitialPost(sharer.username, postBody, SocialPost.Resharing.Friends);
            Pair<Path, FileWrapper> p = sharerFeed.createNewPost(post).join();
            sharer.shareReadAccessWith(p.left, Set.of(friends)).join();
        }
        storageCounter.reset();
        {
            List<Text> postBody = Arrays.asList(new Text("G'day, skip!"));
            SocialPost post = SocialPost.createInitialPost(sharer.username, postBody, SocialPost.Resharing.Friends);
            Pair<Path, FileWrapper> p = sharerFeed.createNewPost(post).join();
            sharer.shareReadAccessWith(p.left, Set.of(friends)).join();
        }
        Assert.assertTrue("Adding a post to social feed: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 20);
        a.getSocialFeed().join().update().join();

        // share more items
        for (int i=0; i < 5; i++) {
            byte[] data = sharer.crypto.random.randomBytes(1*1024*1024);
            Path file = Paths.get(sharer.username, random.nextInt() + "first-file.txt");
            uploadAndShare(data, file, sharer, a.username);
        }

        storageCounter.reset();
        SocialFeed feed2 = a.getSocialFeed().join().update().join();
        Assert.assertTrue("load 5 items in social feed: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 70);

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
