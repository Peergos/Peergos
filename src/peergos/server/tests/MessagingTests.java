package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.display.*;
import peergos.shared.messaging.*;
import peergos.shared.messaging.messages.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;
import static peergos.shared.messaging.messages.ApplicationMessage.text;

public class MessagingTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher hasher = crypto.hasher;
    private static final ContentAddressedStorage ipfs = new RAMStorage(crypto.hasher);
    private static final Function<Chat, CompletableFuture<Boolean>> NO_OP = c -> Futures.of(true);
    private static final Function<FileRef, CompletableFuture<Boolean>> NO_OP2 = r -> Futures.of(true);

    @Test
    public void basicChat() {
        List<SigningPrivateKeyAndPublicHash> identities = generateUsers(2);
        List<PrivateChatState> chatIdentities = generateChatIdentities(2);
        List<RamMessageStore> stores = IntStream.range(0, 2).mapToObj(i -> new RamMessageStore()).collect(Collectors.toList());

        Chat chat1 = Chat.createNew("uid", "user1", identities.get(0).publicKeyHash);
        OwnerProof user1ChatId = OwnerProof.build(identities.get(0), chatIdentities.get(0).chatIdentity.publicKeyHash).join();
        ChatUpdate u1_1 = chat1.join(chat1.host(), user1ChatId, chatIdentities.get(0).chatIdPublic, identities.get(0), stores.get(0), ipfs, crypto).join();
        stores.get(0).apply(u1_1);

        ChatUpdate u1_2 = u1_1.state.inviteMember("user2", identities.get(1).publicKeyHash,
                chatIdentities.get(0).chatIdentity, identities.get(0), stores.get(0), ipfs, crypto).join();
        stores.get(0).apply(u1_2);
        Member user2 = u1_2.state.getMember("user2");
        Chat chat2 = u1_2.state.copy(user2);
        stores.get(1).mirror(stores.get(0));
        OwnerProof user2ChatId = OwnerProof.build(identities.get(1), chatIdentities.get(1).chatIdentity.publicKeyHash).join();
        ChatUpdate u2_1 = chat2.join(user2, user2ChatId, chatIdentities.get(1).chatIdPublic, identities.get(1), stores.get(1), ipfs, crypto).join();
        stores.get(1).apply(u2_1);

        ChatUpdate u1_3 = u1_2.state.sendMessage(text("Welcome!"), chatIdentities.get(0).chatIdentity, identities.get(0), stores.get(0), ipfs, crypto).join();
        stores.get(0).apply(u1_3);
        MessageEnvelope msg1 = u1_3.newMessages.get(u1_3.newMessages.size() - 1).msg;
        ChatUpdate u2_2 = u2_1.state.merge("chat-uid", chat1.host, identities.get(1), stores.get(0), ipfs, crypto).join();
        stores.get(1).apply(u2_2);
        Assert.assertTrue(stores.get(1).messages.get(3).msg.equals(msg1));

        ReplyTo reply = ReplyTo.build(msg1, text("This is cool!"), hasher).join();
        ChatUpdate u2_3 = u2_2.state.sendMessage(reply, chatIdentities.get(1).chatIdentity, identities.get(1), stores.get(1), ipfs, crypto).join();
        stores.get(1).apply(u2_3);
        MessageEnvelope msg2 = u2_3.newMessages.get(u2_3.newMessages.size() - 1).msg;;

        ChatUpdate u1_4 = u1_3.state.merge("chat-uid", chat2.host, identities.get(0), stores.get(1), ipfs, crypto).join();
        stores.get(0).apply(u1_4);
        Assert.assertTrue(stores.get(0).messages.get(4).msg.equals(msg2));
    }

    @Test
    public void multipleInvites() {
        List<SigningPrivateKeyAndPublicHash> identities = generateUsers(3);
        List<PrivateChatState> chatIdentities = generateChatIdentities(3);
        List<RamMessageStore> stores = IntStream.range(0, 3).mapToObj(i -> new RamMessageStore()).collect(Collectors.toList());

        Chat chat1 = Chat.createNew("uid", "user1", identities.get(0).publicKeyHash);
        OwnerProof user1ChatId = OwnerProof.build(identities.get(0), chatIdentities.get(0).chatIdentity.publicKeyHash).join();
        ChatUpdate u1_1 = chat1.join(chat1.host(), user1ChatId, chatIdentities.get(0).chatIdPublic, identities.get(0), stores.get(0), ipfs, crypto).join();
        stores.get(0).apply(u1_1);

        ChatUpdate u1_2 = u1_1.state.inviteMember("user2", identities.get(1).publicKeyHash,
                chatIdentities.get(0).chatIdentity, identities.get(0), stores.get(0), ipfs, crypto).join();
        stores.get(0).apply(u1_2);
        Member user2 = u1_2.state.getMember("user2");

        Chat chat2 = u1_2.state.copy(user2);
        stores.get(1).mirror(stores.get(0));
        OwnerProof user2ChatId = OwnerProof.build(identities.get(1), chatIdentities.get(1).chatIdentity.publicKeyHash).join();
        chat2.join(user2, user2ChatId, chatIdentities.get(1).chatIdPublic, identities.get(1), stores.get(1), ipfs, crypto).join();

        ChatUpdate u1_3 = u1_2.state.inviteMember("user3", identities.get(2).publicKeyHash,
                chatIdentities.get(0).chatIdentity, identities.get(0), stores.get(0), ipfs, crypto).join();
        stores.get(0).apply(u1_3);
        Member user3 = u1_3.state.getMember("user3");

        Chat chat3 = u1_3.state.copy(user3);
        stores.get(2).mirror(stores.get(0));
        OwnerProof user3ChatId = OwnerProof.build(identities.get(2), chatIdentities.get(2).chatIdentity.publicKeyHash).join();
        chat3.join(user3, user3ChatId, chatIdentities.get(2).chatIdPublic, identities.get(2), stores.get(0), ipfs, crypto).join();

        Assert.assertTrue(! user2.id.equals(user3.id));
    }

    @Test
    public void messagePropagation() {
        List<SigningPrivateKeyAndPublicHash> identities = generateUsers(3);
        List<PrivateChatState> chatIdentities = generateChatIdentities(3);
        List<RamMessageStore> stores = IntStream.range(0, 3).mapToObj(i -> new RamMessageStore()).collect(Collectors.toList());

        Chat chat1 = Chat.createNew("uid", "user1", identities.get(0).publicKeyHash);
        OwnerProof user1ChatId = OwnerProof.build(identities.get(0), chatIdentities.get(0).chatIdentity.publicKeyHash).join();
        ChatUpdate u1_1 = chat1.join(chat1.host(), user1ChatId, chatIdentities.get(0).chatIdPublic, identities.get(0), stores.get(0), ipfs, crypto).join();
        stores.get(0).apply(u1_1);

        ChatUpdate u1_2 = u1_1.state.inviteMember("user2", identities.get(1).publicKeyHash,
                chatIdentities.get(0).chatIdentity, identities.get(0), stores.get(0), ipfs, crypto).join();
        stores.get(0).apply(u1_2);
        Member user2 = u1_2.state.getMember("user2");
        Chat chat2 = u1_2.state.copy(user2);
        stores.get(1).mirror(stores.get(0));
        OwnerProof user2ChatId = OwnerProof.build(identities.get(1), chatIdentities.get(1).chatIdentity.publicKeyHash).join();
        ChatUpdate u2_1 = chat2.join(user2, user2ChatId, chatIdentities.get(1).chatIdPublic, identities.get(1), stores.get(1), ipfs, crypto).join();
        stores.get(1).apply(u2_1);

        ChatUpdate u2_2 = u2_1.state.inviteMember("user3", identities.get(2).publicKeyHash,
                chatIdentities.get(1).chatIdentity, identities.get(1), stores.get(1), ipfs, crypto).join();
        stores.get(1).apply(u2_2);
        Member user3 = u2_2.state.getMember("user3");
        Chat chat3 = u2_2.state.copy(user3);
        stores.get(2).mirror(stores.get(1));
        OwnerProof user3ChatId = OwnerProof.build(identities.get(2), chatIdentities.get(2).chatIdentity.publicKeyHash).join();
        ChatUpdate u3_1 = chat3.join(user3, user3ChatId, chatIdentities.get(2).chatIdPublic, identities.get(2), stores.get(2), ipfs, crypto).join();
        stores.get(2).apply(u3_1);

        ChatUpdate u3_2 = u3_1.state.sendMessage(text("Hey All!"), chatIdentities.get(2).chatIdentity, identities.get(2), stores.get(2), ipfs, crypto).join();
        stores.get(2).apply(u3_2);
        MessageEnvelope msg1 = u3_2.newMessages.get(u3_2.newMessages.size() - 1).msg;
        ChatUpdate u2_3 = u2_2.state.merge("chat-uid", chat3.host, identities.get(1), stores.get(2), ipfs, crypto).join();
        stores.get(1).apply(u2_3);
        Assert.assertTrue(stores.get(1).messages.get(5).msg.equals(msg1));

        ChatUpdate u1_3 = u1_2.state.merge("chat-uid", chat2.host, identities.get(0), stores.get(1), ipfs, crypto).join();
        stores.get(0).apply(u1_3);
        Assert.assertTrue(stores.get(0).messages.get(5).msg.equals(msg1));
    }

    @Test
    public void partitionAndJoin() {
        List<SigningPrivateKeyAndPublicHash> identities = generateUsers(4);
        List<PrivateChatState> chatIdentities = generateChatIdentities(4);
        List<RamMessageStore> stores = IntStream.range(0, 4).mapToObj(i -> new RamMessageStore()).collect(Collectors.toList());

        List<Chat> chats = Chat.createNew("uid",
                Arrays.asList("user1", "user2", "user3", "user4"),
                identities.stream().map(p -> p.publicKeyHash).collect(Collectors.toList()));
        TreeClock genesis = chats.get(0).current;
        Chat chat1 = chats.get(0);
        Chat chat2 = chats.get(1);
        Chat chat3 = chats.get(2);
        Chat chat4 = chats.get(3);
        OwnerProof user1ChatId = OwnerProof.build(identities.get(0), chatIdentities.get(0).chatIdentity.publicKeyHash).join();
        ChatUpdate u1_1 = chat1.join(chat1.host(), user1ChatId, chatIdentities.get(0).chatIdPublic, identities.get(0), stores.get(0), ipfs, crypto).join();
        stores.get(0).apply(u1_1);

        OwnerProof user2ChatId = OwnerProof.build(identities.get(1), chatIdentities.get(1).chatIdentity.publicKeyHash).join();
        ChatUpdate u2_1 = chat2.join(chat2.host(), user2ChatId, chatIdentities.get(1).chatIdPublic, identities.get(1), stores.get(1), ipfs, crypto).join();
        stores.get(1).apply(u2_1);

        OwnerProof user3ChatId = OwnerProof.build(identities.get(2), chatIdentities.get(2).chatIdentity.publicKeyHash).join();
        ChatUpdate u3_1 = chat3.join(chat3.host(), user3ChatId, chatIdentities.get(2).chatIdPublic, identities.get(2), stores.get(2), ipfs, crypto).join();
        stores.get(2).apply(u3_1);

        OwnerProof user4ChatId = OwnerProof.build(identities.get(3), chatIdentities.get(3).chatIdentity.publicKeyHash).join();
        ChatUpdate u4_1 = chat4.join(chat4.host(), user4ChatId, chatIdentities.get(3).chatIdPublic, identities.get(3), stores.get(3), ipfs, crypto).join();
        stores.get(3).apply(u4_1);

        // partition and chat between user1 and user2
        TreeClock t1_0 = u1_1.state.current;
        ChatUpdate u1_2 = u1_1.state.sendMessage(text("Hey All, I'm user1!"), chatIdentities.get(0).chatIdentity, identities.get(0), stores.get(0), ipfs, crypto).join();
        stores.get(0).apply(u1_2);
        MessageEnvelope msg1 = u1_2.newMessages.get(u1_2.newMessages.size() - 1).msg;
        Assert.assertTrue(msg1.timestamp.isIncrementOf(t1_0));

        String chatUid = "chat-uid";
        ChatUpdate u2_2 = u2_1.state.merge(chatUid, chat1.host, identities.get(1), stores.get(0), ipfs, crypto).join();
        stores.get(1).apply(u2_2);
        TreeClock t2_0 = u2_2.state.current;
        ChatUpdate u2_3 = u2_2.state.sendMessage(text("Hey user1! I'm user2."), chatIdentities.get(1).chatIdentity, identities.get(1), stores.get(1), ipfs, crypto).join();
        stores.get(1).apply(u2_3);
        MessageEnvelope msg2 = u2_3.newMessages.get(u2_3.newMessages.size() - 1).msg;
        Assert.assertTrue(msg2.timestamp.isIncrementOf(t2_0));

        ChatUpdate u1_3 = u1_2.state.merge(chatUid, chat2.host, identities.get(0), stores.get(1), ipfs, crypto).join();
        stores.get(0).apply(u1_3);
        TreeClock t1_1 = u1_3.state.current;
        ChatUpdate u1_4 = u1_3.state.sendMessage(text("Hey user2, whats up?"), chatIdentities.get(0).chatIdentity, identities.get(0), stores.get(0), ipfs, crypto).join();
        stores.get(0).apply(u1_4);
        MessageEnvelope msg3 = u1_4.newMessages.get(u1_4.newMessages.size() - 1).msg;
        Assert.assertTrue(msg3.timestamp.isIncrementOf(t1_1));

        ChatUpdate u2_4 = u2_3.state.merge(chatUid, chat1.host, identities.get(1), stores.get(0), ipfs, crypto).join();
        stores.get(1).apply(u2_4);
        TreeClock t2_1 = u2_4.state.current;
        ChatUpdate u2_5 = u2_4.state.sendMessage(text("Just saving the world one decentralized chat at a time.."),
                chatIdentities.get(1).chatIdentity, identities.get(1), stores.get(1), ipfs, crypto).join();
        stores.get(1).apply(u2_5);
        MessageEnvelope msg4 = u2_5.newMessages.get(u2_5.newMessages.size() - 1).msg;
        Assert.assertTrue(msg4.timestamp.isIncrementOf(t2_1));

        ChatUpdate u1_5 = u1_4.state.merge(chatUid, chat2.host, identities.get(0), stores.get(1), ipfs, crypto).join();
        stores.get(0).apply(u1_5);
        Assert.assertTrue(stores.get(1).messages.containsAll(stores.get(0).messages));
        Assert.assertEquals(stores.get(1).messages.size(), 6);

        // also between user3 and user4
        ChatUpdate u3_2 = u3_1.state.sendMessage(text("Hey All, I'm user3!"), chatIdentities.get(2).chatIdentity, identities.get(2), stores.get(2), ipfs, crypto).join();
        stores.get(2).apply(u3_2);

        ChatUpdate u4_2 = u4_1.state.merge(chatUid, chat3.host, identities.get(3), stores.get(2), ipfs, crypto).join();
        stores.get(3).apply(u4_2);
        ChatUpdate u4_3 = u4_2.state.sendMessage(text("Hey user3! I'm user4."), chatIdentities.get(3).chatIdentity, identities.get(3), stores.get(3), ipfs, crypto).join();
        stores.get(3).apply(u4_3);

        ChatUpdate u3_3 = u3_2.state.merge(chatUid, chat4.host, identities.get(2), stores.get(3), ipfs, crypto).join();
        stores.get(2).apply(u3_3);
        ChatUpdate u3_4 = u3_3.state.sendMessage(text("Hey user4, whats up?"), chatIdentities.get(2).chatIdentity, identities.get(2), stores.get(2), ipfs, crypto).join();
        stores.get(2).apply(u3_4);

        ChatUpdate u4_4 = u4_3.state.merge(chatUid, chat3.host, identities.get(3), stores.get(2), ipfs, crypto).join();
        stores.get(3).apply(u4_4);
        ChatUpdate u4_5 = u4_4.state.sendMessage(text("Just saving the world one encrypted chat at a time.."), chatIdentities.get(3).chatIdentity, identities.get(3), stores.get(3), ipfs, crypto).join();
        stores.get(3).apply(u4_5);

        ChatUpdate u3_5 = u3_4.state.merge(chatUid, chat4.host, identities.get(2), stores.get(3), ipfs, crypto).join();
        stores.get(2).apply(u3_5);
        Assert.assertTrue(stores.get(3).messages.containsAll(stores.get(2).messages));
        Assert.assertEquals(stores.get(3).messages.size(), 6);

        // now resolve the partition and merge states
        ChatUpdate u1_6 = u1_5.state.merge(chatUid, chat4.host, identities.get(0), stores.get(3), ipfs, crypto).join();
        stores.get(0).apply(u1_6);
        Assert.assertEquals(stores.get(0).messages.size(), 12);
        ChatUpdate u2_6 = u2_5.state.merge(chatUid, chat1.host, identities.get(1), stores.get(0), ipfs, crypto).join();
        stores.get(1).apply(u2_6);
        Assert.assertTrue(stores.get(1).messages.containsAll(stores.get(0).messages));

        // check ordering
        for (int i=0; i < stores.size(); i++)
            validateMessageLog(stores.get(i).getMessagesFrom(0).join(), genesis);
    }

    private static void validateMessageLog(List<SignedMessage> msgs, TreeClock genesis) {
        TreeClock current = genesis;
        for (int i=0; i < msgs.size(); i++) {
            SignedMessage signed = msgs.get(i);
            MessageEnvelope msg = signed.msg;
            TreeClock t = msg.timestamp;
            if (t.isBeforeOrEqual(current))
                throw new IllegalStateException("Invalid timestamp ordering!");
            current = current.merge(t);
        }
    }

    @Test
    public void clockSize() {
        List<Id> ids = IntStream.range(0, 100).mapToObj(Id::new).collect(Collectors.toList());
        TreeClock clock = TreeClock.init(ids);
        byte[] raw = clock.serialize();
        Assert.assertTrue(raw.length < 400);
    }

    private static class RamMessageStore implements MessageStore {
        public final List<SignedMessage> messages;

        public RamMessageStore() {
            this.messages = new ArrayList<>();
        }

        @Override
        public CompletableFuture<List<SignedMessage>> getMessagesFrom(long index) {
            return Futures.of(messages.subList((int) index, messages.size()));
        }

        @Override
        public CompletableFuture<List<SignedMessage>> getMessages(long fromIndex, long toIndex) {
            return Futures.of(messages.subList((int) fromIndex, (int) toIndex));
        }

        @Override
        public CompletableFuture<Snapshot> addMessages(Snapshot initialVersion, Committer committer, long msgIndex, List<SignedMessage> msgs) {
            if (messages.size() != msgIndex)
                throw new IllegalStateException();
            messages.addAll(msgs);
            return Futures.of(initialVersion);
        }

        @Override
        public CompletableFuture<Snapshot> revokeAccess(Set<String> usernames, Snapshot initialVersion, Committer committer) {
            return Futures.of(new Snapshot(Collections.emptyMap()));
        }

        public void apply(ChatUpdate u) {
            messages.addAll(u.newMessages);
        }

        public void mirror(RamMessageStore other) {
            messages.addAll(other.messages);
        }
    }

    private static List<SigningPrivateKeyAndPublicHash> generateUsers(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> SigningKeyPair.random(crypto.random, crypto.signer))
                .map(p -> new SigningPrivateKeyAndPublicHash(ContentAddressedStorage.hashKey(p.publicSigningKey), p.secretSigningKey))
                .collect(Collectors.toList());
    }

    private static List<PrivateChatState> generateChatIdentities(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> SigningKeyPair.random(crypto.random, crypto.signer))
                .map(p -> new PrivateChatState(new SigningPrivateKeyAndPublicHash(
                        ContentAddressedStorage.hashKey(p.publicSigningKey), p.secretSigningKey),
                        p.publicSigningKey, Collections.emptySet()))
                .collect(Collectors.toList());
    }
}
