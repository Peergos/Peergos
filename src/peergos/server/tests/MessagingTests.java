package peergos.server.tests;

import org.junit.*;
import peergos.shared.messaging.*;

import java.util.*;

public class MessagingTests {

    @Test
    public void basicChat() {
        Chat chat1 = Chat.createNew("user1");

        Member user2 = chat1.inviteMember("user2");
        Chat chat2 = chat1.createCopy(user2);

        Message msg1 = chat1.addMessage("Welcome!".getBytes());
        chat2.merge(chat1);
        Assert.assertTrue(chat2.messages.get(1).equals(msg1));

        Message msg2 = chat2.addMessage("This is cool!".getBytes());

        chat1.merge(chat2);
        Assert.assertTrue(chat1.messages.get(2).equals(msg2));
    }

    @Test
    public void messagePropagation() {
        Chat chat1 = Chat.createNew("user1");

        Member user2 = chat1.inviteMember("user2");
        Chat chat2 = chat1.createCopy(user2);

        Member user3 = chat2.inviteMember("user3");
        Chat chat3 = chat2.createCopy(user3);

        Message msg1 = chat3.addMessage("Hey All!".getBytes());
        chat2.merge(chat3);
        Assert.assertTrue(chat2.messages.get(2).equals(msg1));

        chat1.merge(chat2);
        Assert.assertTrue(chat1.messages.get(2).equals(msg1));
    }

    @Test
    public void partitionAndJoin() {
        Chat chat1 = Chat.createNew(Arrays.asList("user1", "user2", "user3", "user4"));

        Collection<Member> members = chat1.getMembers();
        Member user2 = members.stream().filter(m -> m.username.equals("user2")).findFirst().get();
        Chat chat2 = chat1.createCopy(user2);

        Member user3 = members.stream().filter(m -> m.username.equals("user3")).findFirst().get();
        Chat chat3 = chat1.createCopy(user3);

        Member user4 = members.stream().filter(m -> m.username.equals("user4")).findFirst().get();
        Chat chat4 = chat1.createCopy(user4);

        // partition and chat between user1 and user2
        Message msg1 = chat1.addMessage("Hey All, I'm user1!".getBytes());
        chat2.merge(chat1);
        Message msg2 = chat2.addMessage("Hey user1! I'm user2.".getBytes());
        chat1.merge(chat2);
        Message msg3 = chat1.addMessage("Hey user2, whats up?".getBytes());
        chat2.merge(chat1);
        Message msg4 = chat2.addMessage("Just saving the world one decentralized chat at a time..".getBytes());
        chat1.merge(chat2);
        Assert.assertTrue(chat2.messages.equals(Arrays.asList(msg1, msg2, msg3, msg4)));
        Assert.assertTrue(chat2.messages.equals(chat1.messages));
        Assert.assertTrue(chat2.messages.size() == 4);

        // also between user3 and user4
        Message msg5 = chat3.addMessage("Hey All, I'm user3!".getBytes());
        chat4.merge(chat3);
        Message msg6 = chat4.addMessage("Hey user3! I'm user4.".getBytes());
        chat3.merge(chat4);
        Message msg7 = chat3.addMessage("Hey user4, whats up?".getBytes());
        chat4.merge(chat3);
        Message msg8 = chat4.addMessage("Just saving the world one encrypted chat at a time..".getBytes());
        chat3.merge(chat4);
        Assert.assertTrue(chat4.messages.equals(chat3.messages));
        Assert.assertTrue(chat4.messages.size() == 4);

        // now resolve the partition and merge states
        chat1.merge(chat4);
        Assert.assertTrue(chat1.messages.size() == 8);
        chat2.merge(chat1);
        Assert.assertTrue(chat2.messages.equals(chat1.messages));
    }
}
