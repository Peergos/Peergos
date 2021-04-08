package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.messaging.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class MessagingTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final ContentAddressedStorage ipfs = new RAMStorage(crypto.hasher);

    @Test
    public void basicChat() {
        List<SigningPrivateKeyAndPublicHash> identities = generateUsers(2);
        List<SigningPrivateKeyAndPublicHash> chatIdentities = generateUsers(2);
        List<RamMessageStore> stores = IntStream.range(0, 2).mapToObj(i -> new RamMessageStore()).collect(Collectors.toList());

        Chat chat1 = Chat.createNew("user1", identities.get(0).publicKeyHash);
        OwnerProof user1ChatId = OwnerProof.build(identities.get(0), chatIdentities.get(0).publicKeyHash);
        chat1.join(chat1.host, user1ChatId, identities.get(0), stores.get(0)).join();

        Member user2 = chat1.inviteMember("user2", identities.get(1).publicKeyHash, chatIdentities.get(0), stores.get(0)).join();
        Chat chat2 = chat1.copy(user2);
        stores.get(1).mirror(stores.get(0));
        OwnerProof user2ChatId = OwnerProof.build(identities.get(1), chatIdentities.get(1).publicKeyHash);
        chat2.join(user2, user2ChatId, identities.get(1), stores.get(1)).join();

        Message msg1 = chat1.addMessage("Welcome!".getBytes(), chatIdentities.get(0), stores.get(0)).join();
        chat2.merge(chat1, stores.get(0), stores.get(1), ipfs).join();
        Assert.assertTrue(stores.get(1).messages.get(3).msg.equals(msg1));

        Message msg2 = chat2.addMessage("This is cool!".getBytes(), chatIdentities.get(1), stores.get(1)).join();

        chat1.merge(chat2, stores.get(1), stores.get(0), ipfs).join();
        Assert.assertTrue(stores.get(0).messages.get(4).msg.equals(msg2));
    }

    @Test
    public void multipleInvites() {
        List<SigningPrivateKeyAndPublicHash> identities = generateUsers(3);
        List<SigningPrivateKeyAndPublicHash> chatIdentities = generateUsers(3);
        List<RamMessageStore> stores = IntStream.range(0, 3).mapToObj(i -> new RamMessageStore()).collect(Collectors.toList());

        Chat chat1 = Chat.createNew("user1", identities.get(0).publicKeyHash);
        OwnerProof user1ChatId = OwnerProof.build(identities.get(0), chatIdentities.get(0).publicKeyHash);
        chat1.join(chat1.host, user1ChatId, identities.get(0), stores.get(0)).join();

        Member user2 = chat1.inviteMember("user2", identities.get(1).publicKeyHash, chatIdentities.get(0), stores.get(0)).join();
        Chat chat2 = chat1.copy(user2);
        stores.get(1).mirror(stores.get(0));
        OwnerProof user2ChatId = OwnerProof.build(identities.get(1), chatIdentities.get(1).publicKeyHash);
        chat2.join(user2, user2ChatId, identities.get(1), stores.get(1)).join();

        Member user3 = chat1.inviteMember("user3", identities.get(2).publicKeyHash, chatIdentities.get(0), stores.get(0)).join();
        Chat chat3 = chat1.copy(user3);
        stores.get(2).mirror(stores.get(0));
        OwnerProof user3ChatId = OwnerProof.build(identities.get(2), chatIdentities.get(2).publicKeyHash);
        chat3.join(user3, user3ChatId, identities.get(2), stores.get(0)).join();

        Assert.assertTrue(! user2.id.equals(user3.id));
    }

    @Test
    public void messagePropagation() {
        List<SigningPrivateKeyAndPublicHash> identities = generateUsers(3);
        List<SigningPrivateKeyAndPublicHash> chatIdentities = generateUsers(3);
        List<RamMessageStore> stores = IntStream.range(0, 3).mapToObj(i -> new RamMessageStore()).collect(Collectors.toList());

        Chat chat1 = Chat.createNew("user1", identities.get(0).publicKeyHash);
        OwnerProof user1ChatId = OwnerProof.build(identities.get(0), chatIdentities.get(0).publicKeyHash);
        chat1.join(chat1.host, user1ChatId, identities.get(0), stores.get(0)).join();

        Member user2 = chat1.inviteMember("user2", identities.get(1).publicKeyHash, chatIdentities.get(0), stores.get(0)).join();
        Chat chat2 = chat1.copy(user2);
        stores.get(1).mirror(stores.get(0));
        OwnerProof user2ChatId = OwnerProof.build(identities.get(1), chatIdentities.get(1).publicKeyHash);
        chat2.join(user2, user2ChatId, identities.get(1), stores.get(1)).join();

        Member user3 = chat2.inviteMember("user3", identities.get(2).publicKeyHash, chatIdentities.get(1), stores.get(1)).join();
        Chat chat3 = chat2.copy(user3);
        stores.get(2).mirror(stores.get(1));
        OwnerProof user3ChatId = OwnerProof.build(identities.get(2), chatIdentities.get(2).publicKeyHash);
        chat3.join(user3, user3ChatId, identities.get(2), stores.get(2)).join();

        Message msg1 = chat3.addMessage("Hey All!".getBytes(), chatIdentities.get(2), stores.get(2)).join();
        chat2.merge(chat3, stores.get(2), stores.get(1), ipfs).join();
        Assert.assertTrue(stores.get(1).messages.get(5).msg.equals(msg1));

        chat1.merge(chat2, stores.get(1), stores.get(0), ipfs).join();
        Assert.assertTrue(stores.get(0).messages.get(5).msg.equals(msg1));
    }

    @Test
    public void partitionAndJoin() {
        List<SigningPrivateKeyAndPublicHash> identities = generateUsers(4);
        List<SigningPrivateKeyAndPublicHash> chatIdentities = generateUsers(4);
        List<RamMessageStore> stores = IntStream.range(0, 4).mapToObj(i -> new RamMessageStore()).collect(Collectors.toList());

        List<Chat> chats = Chat.createNew(
                Arrays.asList("user1", "user2", "user3", "user4"),
                identities.stream().map(p -> p.publicKeyHash).collect(Collectors.toList()));
        Chat chat1 = chats.get(0);
        Chat chat2 = chats.get(1);
        Chat chat3 = chats.get(2);
        Chat chat4 = chats.get(3);
        OwnerProof user1ChatId = OwnerProof.build(identities.get(0), chatIdentities.get(0).publicKeyHash);
        chat1.join(chat1.host, user1ChatId, identities.get(0), stores.get(0)).join();

        OwnerProof user2ChatId = OwnerProof.build(identities.get(1), chatIdentities.get(1).publicKeyHash);
        chat2.join(chat2.host, user2ChatId, identities.get(1), stores.get(1)).join();

        OwnerProof user3ChatId = OwnerProof.build(identities.get(2), chatIdentities.get(2).publicKeyHash);
        chat3.join(chat3.host, user3ChatId, identities.get(2), stores.get(2)).join();

        OwnerProof user4ChatId = OwnerProof.build(identities.get(3), chatIdentities.get(3).publicKeyHash);
        chat4.join(chat4.host, user4ChatId, identities.get(3), stores.get(3)).join();

        // partition and chat between user1 and user2
        Message msg1 = chat1.addMessage("Hey All, I'm user1!".getBytes(), chatIdentities.get(0), stores.get(0)).join();
        chat2.merge(chat1, stores.get(0), stores.get(1), ipfs).join();
        Message msg2 = chat2.addMessage("Hey user1! I'm user2.".getBytes(), chatIdentities.get(1), stores.get(1)).join();
        chat1.merge(chat2, stores.get(1), stores.get(0), ipfs).join();
        Message msg3 = chat1.addMessage("Hey user2, whats up?".getBytes(), chatIdentities.get(0), stores.get(0)).join();
        chat2.merge(chat1, stores.get(0), stores.get(1), ipfs).join();
        Message msg4 = chat2.addMessage("Just saving the world one decentralized chat at a time..".getBytes(), chatIdentities.get(1), stores.get(1)).join();
        chat1.merge(chat2, stores.get(1), stores.get(0), ipfs).join();
        Assert.assertTrue(stores.get(1).messages.containsAll(stores.get(0).messages));
        Assert.assertEquals(stores.get(1).messages.size(), 6);

        // also between user3 and user4
        Message msg5 = chat3.addMessage("Hey All, I'm user3!".getBytes(), chatIdentities.get(2), stores.get(2)).join();
        chat4.merge(chat3, stores.get(2), stores.get(3), ipfs).join();
        Message msg6 = chat4.addMessage("Hey user3! I'm user4.".getBytes(), chatIdentities.get(3), stores.get(3)).join();
        chat3.merge(chat4, stores.get(3), stores.get(2), ipfs).join();
        Message msg7 = chat3.addMessage("Hey user4, whats up?".getBytes(), chatIdentities.get(2), stores.get(2)).join();
        chat4.merge(chat3, stores.get(2), stores.get(3), ipfs).join();
        Message msg8 = chat4.addMessage("Just saving the world one encrypted chat at a time..".getBytes(), chatIdentities.get(3), stores.get(3)).join();
        chat3.merge(chat4, stores.get(3), stores.get(2), ipfs).join();
        Assert.assertTrue(stores.get(3).messages.containsAll(stores.get(2).messages));
        Assert.assertEquals(stores.get(3).messages.size(), 6);

        // now resolve the partition and merge states
        chat1.merge(chat4, stores.get(3), stores.get(0), ipfs).join();
        Assert.assertEquals(stores.get(0).messages.size(), 12);
        chat2.merge(chat1, stores.get(0), stores.get(1), ipfs).join();
        Assert.assertTrue(stores.get(1).messages.containsAll(stores.get(0).messages));
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
        public CompletableFuture<Boolean> addMessage(SignedMessage msg) {
            messages.add(msg);
            return Futures.of(true);
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
}
