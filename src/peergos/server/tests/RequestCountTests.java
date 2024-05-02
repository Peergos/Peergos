package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.display.*;
import peergos.shared.mutable.*;
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
        RequestCountingStorage requestCounter = new RequestCountingStorage(service.storage);
        this.storageCounter = requestCounter;
        CachingVerifyingStorage dhtClient = new CachingVerifyingStorage(requestCounter, 50 * 1024, 1_000, service.storage.ids().join(), crypto.hasher);

        BufferedStorage blockBuffer = new BufferedStorage(dhtClient, hasher);
        MutablePointers unbufferedMutable = new CachingPointers(service.mutable, 7_000);
        BufferedPointers mutableBuffer = new BufferedPointers(unbufferedMutable);
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutableBuffer, blockBuffer, hasher);
        MutableTree tree = new MutableTreeImpl(mutableBuffer, blockBuffer, hasher, synchronizer);

        int bufferSize = 20 * 1024 * 1024;
        this.network = new BufferedNetworkAccess(blockBuffer, mutableBuffer, bufferSize, service.coreNode, service.account, service.social,
                blockBuffer, unbufferedMutable, service.bats, Optional.empty(), tree, synchronizer, service.controller, service.usage,
                service.serverMessages, hasher, Arrays.asList("peergos"), false);
    }

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    @Test
    public void socialFeedRequestCount() {
        CryptreeNode.setMaxChildLinkPerBlob(10);
        String password = "notagoodone";

        storageCounter.reset();
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);
        Assert.assertTrue("signup request count: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 32);

        storageCounter.reset();
        PeergosNetworkUtils.ensureSignedUp(sharer.username, password, network, crypto);
        Assert.assertTrue("login request count: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 5);

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
        Assert.assertTrue("send reply follow request: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 23);

        // complete the friendship connection
        storageCounter.reset();
        sharer.processFollowRequests().join();
        Assert.assertTrue("friending complete: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 31);

        // friends are now connected
        // share a file from u1 to u2
        byte[] fileData = new byte[1*1024*1024];
        random.nextBytes(fileData);
        Path file1 = PathUtil.get(sharer.username, "first-file.txt");
        uploadAndShare(fileData, file1, sharer, a.username);

        // check 'a' can see the shared file in their social feed
        storageCounter.reset();
        SocialFeed feed = a.getSocialFeed().join();
        Assert.assertTrue("initialise social feed: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 36);
        int feedSize = 2;

        storageCounter.reset();
        List<SharedItem> items = feed.getShared(feedSize, feedSize + 1, a.crypto, a.network).join();
        Assert.assertTrue(storageCounter.requestTotal() <= 2);

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
        Assert.assertTrue("Adding a post to social feed: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 5);
        a.getSocialFeed().join().update().join();

        // share more items
        for (int i=0; i < 5; i++) {
            byte[] data = new byte[1*1024*1024];
            random.nextBytes(data);
            Path file = PathUtil.get(sharer.username, random.nextInt() + "first-file.txt");
            uploadAndShare(data, file, sharer, a.username);
        }

        storageCounter.reset();
        SocialFeed feed2 = a.getSocialFeed().join().update().join();
        Assert.assertTrue("load 5 items in social feed: " + storageCounter.requestTotal(), storageCounter.requestTotal() <= 23);

        storageCounter.reset();
        List<SharedItem> items2 = feed2.getShared(feedSize + 1, feedSize + 6, a.crypto, a.network).join();
        Assert.assertTrue(storageCounter.requestTotal() <= 1);
    }

    private static void uploadAndShare(byte[] data, Path file, UserContext sharer, String sharee) {
        String filename = file.getFileName().toString();
        sharer.getByPath(file.getParent()).join().get()
                .uploadOrReplaceFile(filename, AsyncReader.build(data), data.length, sharer.network, crypto, l -> {}).join();
        sharer.shareReadAccessWith(file, Set.of(sharee)).join();
    }
}
