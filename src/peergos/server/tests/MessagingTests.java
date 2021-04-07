package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.messaging.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.stream.*;

public class MessagingTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final ContentAddressedStorage ipfs = new RAMStorage(crypto.hasher);

    @Test
    public void basicChat() {
        List<SigningPrivateKeyAndPublicHash> identities = generateUsers(2);
        List<SigningPrivateKeyAndPublicHash> chatIdentities = generateUsers(2);
        Chat chat1 = Chat.createNew("user1", identities.get(0).publicKeyHash);
        OwnerProof user1ChatId = OwnerProof.build(identities.get(0), chatIdentities.get(0).publicKeyHash);
        chat1.join(chat1.us, user1ChatId);

        Member user2 = chat1.inviteMember("user2", identities.get(1).publicKeyHash);
        Chat chat2 = chat1.copy(user2);
        OwnerProof user2ChatId = OwnerProof.build(identities.get(1), chatIdentities.get(1).publicKeyHash);
        chat2.join(user2, user2ChatId);

        Message msg1 = chat1.addMessage("Welcome!".getBytes());
        chat2.merge(chat1, ipfs);
        Assert.assertTrue(chat2.messages.get(3).equals(msg1));

        Message msg2 = chat2.addMessage("This is cool!".getBytes());

        chat1.merge(chat2, ipfs);
        Assert.assertTrue(chat1.messages.get(4).equals(msg2));
    }

    @Test
    public void messagePropagation() {
        List<SigningPrivateKeyAndPublicHash> identities = generateUsers(3);
        List<SigningPrivateKeyAndPublicHash> chatIdentities = generateUsers(3);
        Chat chat1 = Chat.createNew("user1", identities.get(0).publicKeyHash);
        OwnerProof user1ChatId = OwnerProof.build(identities.get(0), chatIdentities.get(0).publicKeyHash);
        chat1.join(chat1.us, user1ChatId);

        Member user2 = chat1.inviteMember("user2", identities.get(1).publicKeyHash);
        Chat chat2 = chat1.copy(user2);
        OwnerProof user2ChatId = OwnerProof.build(identities.get(1), chatIdentities.get(1).publicKeyHash);
        chat2.join(user2, user2ChatId);

        Member user3 = chat2.inviteMember("user3", identities.get(2).publicKeyHash);
        Chat chat3 = chat2.copy(user3);
        OwnerProof user3ChatId = OwnerProof.build(identities.get(2), chatIdentities.get(2).publicKeyHash);
        chat3.join(user3, user3ChatId);

        Message msg1 = chat3.addMessage("Hey All!".getBytes());
        chat2.merge(chat3, ipfs);
        Assert.assertTrue(chat2.messages.get(5).equals(msg1));

        chat1.merge(chat2, ipfs);
        Assert.assertTrue(chat1.messages.get(5).equals(msg1));
    }

    @Test
    public void partitionAndJoin() {
        List<SigningPrivateKeyAndPublicHash> identities = generateUsers(4);
        List<SigningPrivateKeyAndPublicHash> chatIdentities = generateUsers(4);
        Chat chat1 = Chat.createNew(
                Arrays.asList("user1", "user2", "user3", "user4"),
                identities.stream().map(p -> p.publicKeyHash).collect(Collectors.toList()));
        OwnerProof user1ChatId = OwnerProof.build(identities.get(0), chatIdentities.get(0).publicKeyHash);
        chat1.join(chat1.us, user1ChatId);

        Collection<Member> members = chat1.getMembers();
        Member user2 = members.stream().filter(m -> m.username.equals("user2")).findFirst().get();
        Chat chat2 = chat1.copy(user2);
        OwnerProof user2ChatId = OwnerProof.build(identities.get(1), chatIdentities.get(1).publicKeyHash);
        chat2.join(user2, user2ChatId);

        Member user3 = members.stream().filter(m -> m.username.equals("user3")).findFirst().get();
        Chat chat3 = chat1.copy(user3);
        OwnerProof user3ChatId = OwnerProof.build(identities.get(2), chatIdentities.get(2).publicKeyHash);
        chat3.join(user3, user3ChatId);

        Member user4 = members.stream().filter(m -> m.username.equals("user4")).findFirst().get();
        Chat chat4 = chat1.copy(user4);
        OwnerProof user4ChatId = OwnerProof.build(identities.get(3), chatIdentities.get(3).publicKeyHash);
        chat4.join(user4, user4ChatId);

        // partition and chat between user1 and user2
        Message msg1 = chat1.addMessage("Hey All, I'm user1!".getBytes());
        chat2.merge(chat1, ipfs);
        Message msg2 = chat2.addMessage("Hey user1! I'm user2.".getBytes());
        chat1.merge(chat2, ipfs);
        Message msg3 = chat1.addMessage("Hey user2, whats up?".getBytes());
        chat2.merge(chat1, ipfs);
        Message msg4 = chat2.addMessage("Just saving the world one decentralized chat at a time..".getBytes());
        chat1.merge(chat2, ipfs);
        Assert.assertTrue(chat2.messages.containsAll(Arrays.asList(msg1, msg2, msg3, msg4)));
        Assert.assertTrue(chat2.messages.containsAll(chat1.messages));
        Assert.assertTrue(chat2.messages.size() == 6);

        // also between user3 and user4
        Message msg5 = chat3.addMessage("Hey All, I'm user3!".getBytes());
        chat4.merge(chat3, ipfs);
        Message msg6 = chat4.addMessage("Hey user3! I'm user4.".getBytes());
        chat3.merge(chat4, ipfs);
        Message msg7 = chat3.addMessage("Hey user4, whats up?".getBytes());
        chat4.merge(chat3, ipfs);
        Message msg8 = chat4.addMessage("Just saving the world one encrypted chat at a time..".getBytes());
        chat3.merge(chat4, ipfs);
        Assert.assertTrue(chat4.messages.containsAll(chat3.messages));
        Assert.assertTrue(chat4.messages.size() == 7);

        // now resolve the partition and merge states
        chat1.merge(chat4, ipfs);
        Assert.assertTrue(chat1.messages.size() == 12);
        chat2.merge(chat1, ipfs);
        Assert.assertTrue(chat2.messages.containsAll(chat1.messages));
    }

    private static List<SigningPrivateKeyAndPublicHash> generateUsers(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> SigningKeyPair.random(crypto.random, crypto.signer))
                .map(p -> new SigningPrivateKeyAndPublicHash(ContentAddressedStorage.hashKey(p.publicSigningKey), p.secretSigningKey))
                .collect(Collectors.toList());
    }
}
