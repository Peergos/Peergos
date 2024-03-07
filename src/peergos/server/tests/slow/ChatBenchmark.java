package peergos.server.tests.slow;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import peergos.server.Builder;
import peergos.server.Main;
import peergos.server.UserService;
import peergos.server.storage.DelayingStorage;
import peergos.server.tests.PeergosNetworkUtils;
import peergos.server.tests.UserTests;
import peergos.server.util.Args;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.messaging.ChatController;
import peergos.shared.messaging.MessageEnvelope;
import peergos.shared.messaging.MessageRef;
import peergos.shared.messaging.Messenger;
import peergos.shared.messaging.messages.ApplicationMessage;
import peergos.shared.messaging.messages.ReplyTo;
import peergos.shared.social.FollowRequestWithCipherText;
import peergos.shared.social.SharedItem;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AbsoluteCapability;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Futures;
import peergos.shared.util.Pair;
import peergos.shared.util.PathUtil;

import java.net.URL;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@RunWith(Parameterized.class)
public class ChatBenchmark {

    private static int RANDOM_SEED = 666;
    private final UserService service;
    private final NetworkAccess network;
    private final Crypto crypto = Main.initCrypto();

    private static Random random = new Random(RANDOM_SEED);

    public ChatBenchmark(String useIPFS, Random r) throws Exception {
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

    public List<UserContext> getUserContexts(NetworkAccess network, int size, List<String> passwords) {
        return IntStream.range(0, size)
                .mapToObj(e -> {
                    String username = generateUsername();
                    String password = passwords.get(e);
                    try {
                        return ensureSignedUp(username, password, network.clear(), crypto);
                    } catch (Exception ioe) {
                        throw new IllegalStateException(ioe);
                    }
                }).collect(Collectors.toList());
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

    // createChat(9) duration: 8571 mS, best: 6011 mS, worst: 9163 mS, av: 7669 mS
    // invite(9) duration: 2138 mS, best: 1215 mS, worst: 2358 mS, av: 1942 mS
    // sendMessage*3(9) duration: 1890 mS, best: 1443 mS, worst: 2164 mS, av: 1893 mS
    // cloneLocallyAndJoin(9) duration: 10388 mS, best: 9556 mS, worst: 11258 mS, av: 10145 mS
    // mergeMessages(9) duration: 5359 mS, best: 4446 mS, worst: 5560 mS, av: 5080 mS
    @Test
    public void createChat() {
        String username = generateUsername();
        String password = "test01";
        UserContext a = ensureSignedUp(username, password, network, crypto);

        List<UserContext> shareeUsers = getUserContexts(network, 1, Arrays.asList(password));
        UserContext b = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(a), shareeUsers);

        Messenger msgA = new Messenger(a);

        long worst1 = 0, best1 = Long.MAX_VALUE, accum1 = 0;
        long worst2 = 0, best2 = Long.MAX_VALUE, accum2 = 0;
        long worst3 = 0, best3 = Long.MAX_VALUE, accum3 = 0;
        int limit = 10;
        for (int i = 0; i < limit; i++) {
            long t1 = System.currentTimeMillis();
            ChatController controllerA = msgA.createChat().join();
            long duration1 = System.currentTimeMillis() - t1;
            accum1 = accum1 + duration1;
            worst1 = Math.max(worst1, duration1);
            best1 = Math.min(best1, duration1);
            System.err.printf("createChat(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration1, best1, worst1, (accum1) / (i + 1));

            long t2 = System.currentTimeMillis();
            controllerA = msgA.invite(controllerA, Arrays.asList(b.username), Arrays.asList(b.signer.publicKeyHash)).join();
            long duration2 = System.currentTimeMillis() - t2;
            accum2 = accum2 + duration2;
            worst2 = Math.max(worst2, duration2);
            best2 = Math.min(best2, duration2);
            System.err.printf("invite(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration2, best2, worst2, (accum2) / (i + 1));

            ApplicationMessage msg1 = ApplicationMessage.text("message 1/3 in chat: " + i);
            ApplicationMessage msg2 = ApplicationMessage.text("message 2/3 in chat: " + i);
            ApplicationMessage msg3 = ApplicationMessage.text("message 3/3 in chat: " + i);
            long t3 = System.currentTimeMillis();
            controllerA = msgA.sendMessage(controllerA, msg1).join();
            controllerA = msgA.sendMessage(controllerA, msg2).join();
            controllerA = msgA.sendMessage(controllerA, msg3).join();
            long duration3 = System.currentTimeMillis() - t3;
            accum3 = accum3 + duration3;
            worst3 = Math.max(worst3, duration3);
            best3 = Math.min(best3, duration3);
            System.err.printf("sendMessage*3(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration3, best3, worst3, (accum3) / (i + 1));
        }
        List<Pair<SharedItem, FileWrapper>> feed = b.getSocialFeed().join().update().join().getSharedFiles(0, limit + 10).join();
        Messenger msgB = new Messenger(b);
        long worst4 = 0, best4 = Long.MAX_VALUE, accum4 = 0;
        long worst5 = 0, best5 = Long.MAX_VALUE, accum5 = 0;
        for (int i = 0; i < limit; i++) {
            FileWrapper chatSharedDir = feed.get(2 + i).right;
            long t4 = System.currentTimeMillis();
            ChatController controllerB = msgB.cloneLocallyAndJoin(chatSharedDir).join();
            long duration4 = System.currentTimeMillis() - t4;
            accum4 = accum4 + duration4;
            worst4 = Math.max(worst4, duration4);
            best4 = Math.min(best4, duration4);
            System.err.printf("cloneLocallyAndJoin(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration4, best4, worst4, (accum4) / (i + 1));

            long t5 = System.currentTimeMillis();
            controllerB = msgB.mergeMessages(controllerB, a.username).join();
            long duration5 = System.currentTimeMillis() - t5;
            accum5 = accum5 + duration5;
            worst5 = Math.max(worst5, duration5);
            best5 = Math.min(best5, duration5);
            System.err.printf("mergeMessages(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration5, best5, worst5, (accum5) / (i + 1));
            List<MessageEnvelope> initialMessages = controllerB.getMessages(0, 10).join();
            Assert.assertEquals(initialMessages.size(), 7);
        }
        System.currentTimeMillis();
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
