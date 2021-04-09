package peergos.shared.messaging;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;

import java.util.concurrent.*;

public class ChatController {

    public final String chatUuid;
    private Chat state;
    private final MessageStore store;
    private final PrivateChatState privateChatState;

    public ChatController(String chatUuid, Chat state, MessageStore store, PrivateChatState privateChatState) {
        this.chatUuid = chatUuid;
        this.state = state;
        this.store = store;
        this.privateChatState = privateChatState;
    }

    public CompletableFuture<ChatController> join(SigningPrivateKeyAndPublicHash identity) {
        OwnerProof chatId = OwnerProof.build(identity, privateChatState.chatIdentity.publicKeyHash);
        return state.join(state.host, chatId, privateChatState.chatIdPublic, identity, store)
                .thenApply(x -> this);
    }

    public CompletableFuture<ChatController> invite(String username, PublicKeyHash identity) {
        return state.inviteMember(username, identity, privateChatState.chatIdentity, store)
                .thenApply(x -> this);
    }

    public CompletableFuture<ChatController> sendMessage(byte[] message) {
        return state.addMessage(message, privateChatState.chatIdentity, store)
                .thenApply(x -> this);
    }
}
