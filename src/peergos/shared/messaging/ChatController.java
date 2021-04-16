package peergos.shared.messaging;

import jsinterop.annotations.*;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.util.Pair;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class ChatController {

    public final String chatUuid;
    private final Chat state;
    private final MessageStore store;
    private final PrivateChatState privateChatState;

    public ChatController(String chatUuid, Chat state, MessageStore store, PrivateChatState privateChatState) {
        this.chatUuid = chatUuid;
        this.state = state;
        this.store = store;
        this.privateChatState = privateChatState;
    }

    public Member getMember(String username) {
        return state.getMember(username);
    }

    @JsMethod
    public Set<String> getMemberNames() {
        return state.members.values().stream().map(m -> m.username).collect(Collectors.toSet());
    }

    //Temporary method...
    @JsMethod
    public CompletableFuture<Pair<Integer, List<Message.ChatMessage>>> getFilteredMessages(int from, int to) {
        return getMessages(from, to).thenApply(messages -> {
            List<Message.ChatMessage> filteredMessages = new ArrayList<>();
            for (Message message : messages) {
                try {
                    filteredMessages.add(Message.TextMessage.fromCbor(CborObject.fromByteArray(message.payload)));
                } catch (Exception e) {
                    try {
                        filteredMessages.add(Message.ConversationTitleMessage.fromCbor(CborObject.fromByteArray(message.payload)));
                    } catch (Exception e2) {
                        try {
                            filteredMessages.add(Message.StatusMessage.fromCbor(CborObject.fromByteArray(message.payload)));
                        } catch (Exception e3) {
                        }
                    }
                }
            }
            return new Pair<>(messages.size(), filteredMessages);
        });
    }

    @JsMethod
    public CompletableFuture<List<Message>> getMessages(long from, long to) {
        return store.getMessages(from, to)
                .thenApply(signed -> signed.stream().map(s -> s.msg).collect(Collectors.toList()));
    }

    @JsMethod
    public CompletableFuture<ChatController> sendMessage(byte[] message) {
        return state.addMessage(message, privateChatState.chatIdentity, store)
                .thenApply(x -> this);
    }

    public CompletableFuture<ChatController> join(SigningPrivateKeyAndPublicHash identity,
                                                  Function<Chat, CompletableFuture<Boolean>> committer) {
        OwnerProof chatId = OwnerProof.build(identity, privateChatState.chatIdentity.publicKeyHash);
        return state.join(state.host, chatId, privateChatState.chatIdPublic, identity, store, committer)
                .thenApply(x -> this);
    }

    public CompletableFuture<ChatController> invite(String username,
                                                    PublicKeyHash identity,
                                                    Function<Chat, CompletableFuture<Boolean>> committer) {
        return state.inviteMember(username, identity, privateChatState.chatIdentity, store, committer)
                .thenApply(x -> this);
    }

    public CompletableFuture<ChatController> mergeMessages(String username,
                                                           MessageStore mirrorStore,
                                                           ContentAddressedStorage ipfs,
                                                           Function<Chat, CompletableFuture<Boolean>> committer) {
        Member mirrorHost = state.getMember(username);
        return state.merge(mirrorHost.id, mirrorStore, store, ipfs, committer)
                .thenApply(x -> this);
    }
}
