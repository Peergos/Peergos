package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.Args;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.stream.*;

import static org.junit.Assert.*;

public class BlockTests {

    private static Args args = UserTests.useMemoryDbs(UserTests.buildArgs())
            .with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();
    private final NetworkAccess network;
    private final Random random = new Random();

    public BlockTests() {
        this.network = NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher, Arrays.asList("peergos"), false);
    }

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    private UserContext signUp() {
        return PeergosNetworkUtils.ensureSignedUp(PeergosNetworkUtils.generateUsername(random), "password", network.clear(), crypto);
    }

    private UserContext login(UserContext c) {
        return PeergosNetworkUtils.ensureSignedUp(c.username, "password", network.clear(), crypto);
    }

    private static void friends(UserContext a, UserContext b) {
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(a), Arrays.asList(b));
    }

    private static Set<String> following(UserContext c) {
        return c.getSocialState().join().getFollowing();
    }

    @Test
    public void unfollowIsNotBlocking() {
        UserContext a = signUp(), b = signUp();
        friends(a, b);
        a.unfollow(b.username).join();

        SocialState aState = login(a).getSocialState().join();
        assertTrue(aState.unfollowed.contains(b.username));
        assertFalse(aState.blocked.contains(b.username));
        assertFalse(aState.getFollowing().contains(b.username));
        assertTrue("unfollowing is local, they still follow us", aState.getFollowers().contains(b.username));
        assertTrue(following(login(b)).contains(a.username));

        a.followAgain(b.username).join();
        SocialState again = login(a).getSocialState().join();
        assertFalse(again.unfollowed.contains(b.username));
        assertTrue(again.getFriends().contains(b.username));
    }

    @Test
    public void blockingRemovesThemAsFollower() {
        UserContext a = signUp(), b = signUp();
        friends(a, b);
        a.block(b.username).join();

        SocialState aState = login(a).getSocialState().join();
        assertTrue(aState.blocked.contains(b.username));
        assertTrue("a block is also an unfollow, for older clients", aState.unfollowed.contains(b.username));
        assertFalse(aState.getFollowing().contains(b.username));
        assertFalse(aState.getFollowers().contains(b.username));
        assertFalse(a.getGroupMembers(aState.getFollowersGroupUid()).join().contains(b.username));
        assertFalse(a.getGroupMembers(aState.getFriendsGroupUid()).join().contains(b.username));
        assertFalse("they can no longer see us", following(login(b)).contains(a.username));
    }

    @Test
    public void unblockingLeavesThemUnfollowed() {
        UserContext a = signUp(), b = signUp();
        friends(a, b);
        a.block(b.username).join();
        try {
            a.followAgain(b.username).join();
            fail("Following a blocked user again should fail");
        } catch (Exception expected) {}

        a.unblock(b.username).join();
        SocialState aState = login(a).getSocialState().join();
        assertFalse(aState.blocked.contains(b.username));
        assertTrue(aState.unfollowed.contains(b.username));

        // they still let us follow them, so we can see them again
        a.followAgain(b.username).join();
        assertTrue(following(login(a)).contains(b.username));
    }

    @Test
    public void requestsFromBlockedUsersAreRemoved() {
        UserContext a = signUp(), b = signUp();
        a.block(b.username).join();
        b.sendInitialFollowRequest(a.username).join();

        UserContext freshA = login(a);
        assertTrue(freshA.processFollowRequests().join().isEmpty());
        assertTrue("the request is removed from the server", freshA.getFollowRequests().join().isEmpty());
        SocialState bState = login(b).getSocialState().join();
        assertTrue("it is denied, so they aren't left waiting", bState.pendingOutgoing.isEmpty());
        assertFalse(bState.getFollowing().contains(a.username));
    }

    @Test
    public void cannotRequestToFollowBlockedUser() {
        UserContext a = signUp(), b = signUp();
        a.block(b.username).join();
        try {
            a.sendInitialFollowRequest(b.username).join();
            fail("Sending a follow request to a blocked user should fail");
        } catch (Exception expected) {}
        assertTrue(login(b).processFollowRequests().join().isEmpty());
    }

    @Test
    public void blockingHidesTheirFilesSharedByOthers() {
        UserContext a = signUp(), b = signUp(), c = signUp();
        friends(a, c);
        friends(b, c);
        // b shares a file with c, who shares it on with a
        String filename = "from-b.txt";
        byte[] data = "hello".getBytes();
        b.getUserRoot().join().uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                b.network, crypto, () -> false, x -> {}).join();
        b.shareReadAccessWith(PathUtil.get(b.username, filename), Collections.singleton(c.username)).join();
        UserContext freshC = login(c);
        assertTrue("c can see b's file", freshC.getByPath(b.username + "/" + filename).join().isPresent());
        freshC.shareReadAccessWith(PathUtil.get(b.username, filename), Collections.singleton(a.username)).join();

        assertTrue("visible via a mutual friend before blocking", sharedOwners(login(a)).contains(b.username));

        a.unfollow(b.username).join();
        assertTrue("unfollowing doesn't hide things shared by others", sharedOwners(login(a)).contains(b.username));

        a.followAgain(b.username).join();
        a.block(b.username).join();
        assertFalse("blocking hides things shared by others", sharedOwners(login(a)).contains(b.username));

        a.unblock(b.username).join();
        assertTrue("unblocking shows them again", sharedOwners(login(a)).contains(b.username));
    }

    /** The owners of everything in our news feed which we can retrieve. */
    private static Set<String> sharedOwners(UserContext c) {
        SocialFeed feed = c.getSocialFeed().join().update().join();
        List<SharedItem> items = feed.getShared(0, feed.getFeedSize() + 1, c.crypto, c.network).join();
        return c.getFiles(items).join().stream()
                .map(p -> p.left.owner)
                .collect(Collectors.toSet());
    }
}
