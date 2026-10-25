package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.messaging.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

import static peergos.server.tests.PeergosNetworkUtils.*;

public class ChatSignalsTests {

    private static Args args = UserTests.useMemoryDbs(UserTests.buildArgs())
            .with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();
    private final Random random = new Random();
    private final NetworkAccess network;

    public ChatSignalsTests() {
        this.network = NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, service.signals, crypto.hasher,
                Arrays.asList("peergos"), false);
    }

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    private static List<Map<String, Object>> signals(String json) {
        return (List<Map<String, Object>>) ((Map<String, Object>) JSONParser.parse(json)).get("signals");
    }

    private static String next(String json) {
        return (String) ((Map<String, Object>) JSONParser.parse(json)).get("next");
    }

    @Test
    public void typingPresenceAndReceipts() throws Exception {
        String password = "notagoodone";
        UserContext a = ensureSignedUp("a-" + generateUsername(random), password, network, crypto);
        UserContext b = getUserContextsForNode(network, random, 1, Arrays.asList(password)).get(0);
        friendBetweenGroups(Arrays.asList(a), Arrays.asList(b));

        Messenger msgA = new Messenger(a);
        ChatController chatA = msgA.createChat().join();
        chatA = msgA.invite(chatA, Arrays.asList(b.username), Arrays.asList(b.signer.publicKeyHash)).join();
        List<Pair<SharedItem, FileWrapper>> feed = b.getSocialFeed().join().update().join().getSharedFiles(0, 10).join();
        Messenger msgB = new Messenger(b);
        ChatController chatB = msgB.cloneLocallyAndJoin(feed.get(feed.size() - 1).right).join();
        chatA = msgA.mergeMessages(chatA, b.username).join();

        Assert.assertFalse(msgA.hasSignals(chatA));
        Assert.assertFalse("no key, nothing sent", msgA.sendSignal(chatA, ChatSignals.TYPING, "").join());
        chatA = msgA.enableSignals(chatA).join();
        chatB = msgB.mergeMessages(chatB, a.username).join();
        Assert.assertTrue(msgB.hasSignals(chatB));

        // an event and two sticky signals of the same type: only the latest of those is kept
        Assert.assertTrue(msgA.sendSignal(chatA, ChatSignals.TYPING, "").join());
        Assert.assertTrue(msgA.sendSignal(chatA, ChatSignals.READ, "5").join());
        Assert.assertTrue(msgA.sendSignal(chatA, ChatSignals.READ, "6").join());
        String polled = msgB.pollSignals(chatB, "0", 0).join();
        List<Map<String, Object>> received = signals(polled);
        Assert.assertEquals(2, received.size());
        Assert.assertEquals(a.username, received.get(0).get("author"));
        Assert.assertEquals(ChatSignals.TYPING, received.get(0).get("type"));
        Assert.assertEquals(ChatSignals.READ, received.get(1).get("type"));
        Assert.assertEquals("6", received.get(1).get("value"));

        // nothing new after next, and our own signals are never returned to us
        Assert.assertEquals(0, signals(msgB.pollSignals(chatB, next(polled), 0).join()).size());
        Assert.assertEquals(0, signals(msgA.pollSignals(chatA, "0", 0).join()).size());

        // a waiting poll returns as soon as a signal arrives
        ChatController waitingB = chatB;
        String from = next(polled);
        long start = System.currentTimeMillis();
        CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> msgB.pollSignals(waitingB, from, 20_000).join());
        Thread.sleep(1_000);
        msgA.sendSignal(chatA, ChatSignals.STOPPED_TYPING, "").join();
        List<Map<String, Object>> woken = signals(waiting.get(10, TimeUnit.SECONDS));
        Assert.assertEquals(1, woken.size());
        Assert.assertEquals(ChatSignals.STOPPED_TYPING, woken.get(0).get("type"));
        Assert.assertTrue("woken well before the wait ran out", System.currentTimeMillis() - start < 10_000);

        // removing a member replaces the key, so they can't follow the chat's signals any more
        byte[] oldChannel = ChatSignals.channel(chatB, crypto.hasher).join();
        chatA = msgA.removeMember(chatA, b.username).join();
        Assert.assertFalse(Arrays.equals(oldChannel, ChatSignals.channel(chatA, crypto.hasher).join()));
        msgA.sendSignal(chatA, ChatSignals.TYPING, "").join();
        List<Map<String, Object>> afterRemoval = signals(msgB.pollSignals(chatB, next(polled), 0).join());
        Assert.assertTrue(afterRemoval.stream().noneMatch(s -> s.get("type").equals(ChatSignals.TYPING)));
    }

    @Test
    public void unknownTypesAndOversizedValuesAreRejected() {
        String password = "notagoodone";
        UserContext a = ensureSignedUp("a-" + generateUsername(random), password, network, crypto);
        Messenger msgA = new Messenger(a);
        ChatController chatA = msgA.enableSignals(msgA.createChat().join()).join();
        try {
            msgA.sendSignal(chatA, "something-else", "").join();
            Assert.fail("unknown type sent");
        } catch (IllegalArgumentException expected) {}
        try {
            msgA.sendSignal(chatA, ChatSignals.READ, "x".repeat(1000)).join();
            Assert.fail("oversized value sent");
        } catch (IllegalArgumentException expected) {}
    }
}
