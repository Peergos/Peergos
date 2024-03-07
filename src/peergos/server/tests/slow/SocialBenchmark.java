package peergos.server.tests.slow;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.social.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

@RunWith(Parameterized.class)
public class SocialBenchmark {

    private static int RANDOM_SEED = 666;
    private final UserService service;
    private final NetworkAccess network;
    private final Crypto crypto = Main.initCrypto();

    private static Random random = new Random(RANDOM_SEED);

    public SocialBenchmark(String useIPFS, Random r) throws Exception {
        Pair<UserService, NetworkAccess> pair = buildHttpNetworkAccess(useIPFS.equals("IPFS"), r);
        this.service = pair.left;
        this.network = pair.right;
    }

    private static Pair<UserService, NetworkAccess> buildHttpNetworkAccess(boolean useIpfs, Random r) throws Exception {
        Args args = UserTests.buildArgs().with("useIPFS", "" + useIpfs);
        UserService service = Main.PKI_INIT.main(args).localApi;
        NetworkAccess net = Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false).join();
        int delayMillis = 50;
        NetworkAccess delayed = net.withStorage(s -> new DelayingStorage(s, delayMillis, delayMillis));
        return new Pair<>(service, delayed);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
//                {"IPFS", new Random(0)}
                {"NOTIPFS", new Random(0)}
        });
    }

    // SendFollowRequest(19) duration: 2340 mS, best: 1519 mS, worst: 2340 mS, av: 1734 mS
    @Test
    public void social() {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        List<String> names = new ArrayList<>();
        IntStream.range(0, 20).forEach(i -> names.add(generateUsername()));
        names.forEach(name -> ensureSignedUp(name, password, network, crypto));

        long worst = 0, best = Long.MAX_VALUE, start = System.currentTimeMillis();

        for (int i = 0; i < 20; i++) {
            long t1 = System.currentTimeMillis();
            context.sendInitialFollowRequest(names.get(i)).join();
            long duration = System.currentTimeMillis() - t1;
            worst = Math.max(worst, duration);
            best = Math.min(best, duration);
            System.err.printf("SendFollowRequest(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration, best, worst, (t1 + duration - start) / (i + 1));
        }
    }

    // ReplyToFollowRequest(19) duration: 4291 mS, best: 3392 mS, worst: 5239 mS, av: 3898 mS
    @Test
    public void replyToFollowRequest() {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        List<String> names = new ArrayList<>();
        IntStream.range(0, 20).forEach(i -> names.add(generateUsername()));
        List<UserContext> users = names.stream()
                .map(name -> ensureSignedUp(name, password, network, crypto))
                .collect(Collectors.toList());

        for (int i = 0; i < 20; i++) {
            users.get(i).sendInitialFollowRequest(username).join();
        }

        List<FollowRequestWithCipherText> pending = context.getSocialState().join().pendingIncoming;
        long worst = 0, best = Long.MAX_VALUE, start = System.currentTimeMillis();
        // Profile accepting the requests
        for (int i = 0; i < 20; i++) {
            FollowRequestWithCipherText req = pending.get(i);
            long t1 = System.currentTimeMillis();
            context.sendReplyFollowRequest(req, true, true).join();
            long duration = System.currentTimeMillis() - t1;
            worst = Math.max(worst, duration);
            best = Math.min(best, duration);
            System.err.printf("ReplyToFollowRequest(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration, best, worst, (t1 + duration - start) / (i + 1));
        }
    }

    @Test
    public void manyFriendsAndShares() {
        String username = generateUsername();
        String password = "password";
        Pair<UserContext, Long> initial = time(() -> ensureSignedUp(username, password, network, crypto));
        long initialTime = initial.right;
        UserContext us = initial.left;
//        Assert.assertTrue(initialTime < 30_000);

        int nFriends = 20;
        List<Pair<String, String>> otherUsers = IntStream.range(0, nFriends)
                .mapToObj(x -> new Pair<>(generateUsername(), password))
                .collect(Collectors.toList());
        List<UserContext> friends = otherUsers.stream()
                .map(p -> ensureSignedUp(p.left, p.right, network, crypto))
                .collect(Collectors.toList());

        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(us), friends);
        long withFriends = time(() -> ensureSignedUp(username, password, network.clear(), crypto)).right;
//        Assert.assertTrue(withFriends < 3_000);

        // Add n files, each shared read only with a random friend
        int nFiles = 40;
        for (int i=0; i < nFiles; i++) {
            byte[] fileData = "dataaaa".getBytes();
            String filename = "File" + i;
            us.getUserRoot().join().uploadOrReplaceFile(filename, AsyncReader.build(fileData),
                    fileData.length, network, crypto, x -> {}).join();
            String sharee = otherUsers.get(random.nextInt(otherUsers.size())).left;
            us.shareReadAccessWith(PathUtil.get(username, filename), Collections.singleton(sharee)).join();
        }

        long initialWithReadSharingOut = time(() -> ensureSignedUp(username, password, network.clear(), crypto)).right;
        long withReadSharingOut = time(() -> ensureSignedUp(username, password, network.clear(), crypto)).right;

        // Add n files owned by friends, each shared read only with us
        for (int i=0; i < nFiles; i++) {
            byte[] fileData = "dataaaa".getBytes();
            String filename = "File" + i;
            UserContext friend = friends.get(random.nextInt(friends.size()));
            friend.getUserRoot().join().uploadOrReplaceFile(filename, AsyncReader.build(fileData),
                    fileData.length, network, crypto, x -> {}).join();
            friend.shareReadAccessWith(PathUtil.get(friend.username, filename), Collections.singleton(username)).join();
        }

        long initialWithReadSharingIn = time(() -> ensureSignedUp(username, password, network.clear(), crypto)).right;
        long withReadSharingIn = time(() -> ensureSignedUp(username, password, network.clear(), crypto)).right;
//        Assert.assertTrue(withReadSharingIn < 4_000);

        for (int i=0; i < nFiles; i++) {
            byte[] fileData = "dataaaa".getBytes();
            String filename = "FileW" + i;
            us.getUserRoot().join().uploadOrReplaceFile(filename, AsyncReader.build(fileData),
                    fileData.length, network, crypto, x -> {}).join();
            String sharee = otherUsers.get(random.nextInt(otherUsers.size())).left;
            us.shareWriteAccessWith(PathUtil.get(username, filename), Collections.singleton(sharee)).join();
        }

        long initialWithWriteSharingOut = time(() -> ensureSignedUp(username, password, network.clear(), crypto)).right;
        long withWriteSharingOut = time(() -> ensureSignedUp(username, password, network.clear(), crypto)).right;

        // Add n files owned by friends, each shared read only with us
        for (int i=0; i < nFiles; i++) {
            byte[] fileData = "dataaaa".getBytes();
            String filename = "FileW" + i;
            UserContext friend = friends.get(random.nextInt(friends.size()));
            friend.getUserRoot().join().uploadOrReplaceFile(filename, AsyncReader.build(fileData),
                    fileData.length, network, crypto, x -> {}).join();
            friend.shareWriteAccessWith(PathUtil.get(friend.username, filename), Collections.singleton(username)).join();
        }

        long initialWithWriteSharingIn = time(() -> ensureSignedUp(username, password, network.clear(), crypto)).right;
        long withWriteSharingIn = time(() -> ensureSignedUp(username, password, network.clear(), crypto)).right;
//        Assert.assertTrue(withWriteSharingIn < 4_000);

        int nFriendsLeaving = 5;
        for (int i=0; i < nFriendsLeaving; i++) {
            int index = random.nextInt(friends.size());
            UserContext friend = friends.get(index);
            friend.deleteAccount(otherUsers.get(index).right, UserTests::noMfa).join();
            friends.remove(friend);
        }

        long afterLeaving = time(() -> ensureSignedUp(username, password, network.clear(), crypto)).right;
//        Assert.assertTrue(afterLeaving < 4_000);

        // Time login after a GC
        service.gc.collect(s -> Futures.of(true));
        long afterGc = time(() -> ensureSignedUp(username, password, network.clear(), crypto)).right;
        Assert.assertTrue(afterGc < 4_000);
    }

    @Test
    public void readingSharedFiles() {
        String username = generateUsername();
        String password = "password";
        Pair<UserContext, Long> initial = time(() -> ensureSignedUp(username, password, network, crypto));
        UserContext us = initial.left;

        int nFriends = 1;
        List<Pair<String, String>> otherUsers = IntStream.range(0, nFriends)
                .mapToObj(x -> new Pair<>(generateUsername(), password))
                .collect(Collectors.toList());
        List<UserContext> friends = otherUsers.stream()
                .map(p -> ensureSignedUp(p.left, p.right, network, crypto))
                .collect(Collectors.toList());

        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(us), friends);

        // Add a few files, shared read only with a random friend friend
        int nFiles = 40;
        for (int i=0; i < nFiles; i++) {
            byte[] fileData = "dataaaa".getBytes();
            String filename = "File" + i;
            us.getUserRoot().join().uploadOrReplaceFile(filename, AsyncReader.build(fileData),
                    fileData.length, network, crypto, x -> {}).join();
            String sharee = otherUsers.get(random.nextInt(otherUsers.size())).left;
            us.shareReadAccessWith(PathUtil.get(username, filename), Collections.singleton(sharee)).join();
        }

        int reps = 100;
        long start = System.currentTimeMillis();
        for (int j=0; j < reps; j++) {
            for (int i = 0; i < nFiles; i++) {
                String name = "File" + i;
                System.out.println("getByPath took " + time(() -> friends.get(0).getByPath(PathUtil.get(username, name)).join()).right);
            }
        }
        long end = System.currentTimeMillis();
        System.out.println("Took " + (end - start) + "mS");
    }

    @Test
    public void groupSharing() {
        String username = generateUsername();
        String password = "password";
        Pair<UserContext, Long> initial = time(() -> ensureSignedUp(username, password, network, crypto));
        UserContext us = initial.left;

        int nFriends = 20;
        List<Pair<String, String>> otherUsers = IntStream.range(0, nFriends)
                .mapToObj(x -> new Pair<>(generateUsername(), password))
                .collect(Collectors.toList());
        List<UserContext> friends = otherUsers.stream()
                .map(p -> ensureSignedUp(p.left, p.right, network, crypto))
                .collect(Collectors.toList());

        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(us), friends);

        // Share a file with all friends individually\
        String filename = "File1";
        byte[] fileData = "dataaaa".getBytes();
        us.getUserRoot().join().uploadOrReplaceFile(filename, AsyncReader.build(fileData),
                fileData.length, network, crypto, x -> {}).join();
        long t0 = System.currentTimeMillis();
        for (Pair<String, String> sharee : otherUsers) {
            us.shareReadAccessWith(PathUtil.get(username, filename), Collections.singleton(sharee.left)).join();
        }
        long t1 = System.currentTimeMillis();

        // Share a file with all friend via a group
        String file2name = "File2";
        String friendsGroup = us.getSocialState().join().getFriendsGroupUid();
        us.getUserRoot().join().uploadOrReplaceFile(file2name, AsyncReader.build(fileData),
                fileData.length, network, crypto, x -> {}).join();
        long groupShareDuration = time(() -> us.shareReadAccessWith(PathUtil.get(username, file2name), Collections.singleton(friendsGroup)).join()).right;
        double ratio = (double) (t1 - t0) / groupShareDuration;
        Assert.assertTrue(ratio > nFriends - 1);
    }

    private String generateUsername() {
        return "test" + (random.nextInt() % 10000);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) {
        return PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
    }

    public static <V> Pair<V, Long> time(Supplier<V> work) {
        long t0 = System.currentTimeMillis();
        V res = work.get();
        long t1 = System.currentTimeMillis();
        return new Pair<>(res, t1 - t0);
    }
}
